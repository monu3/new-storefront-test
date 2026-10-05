package com.storefront.store.customer;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.notification.PushSender;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Registers browsers for push and sends order-status notifications to them (Phase 5).
 *
 * <p><b>Registration is deliberately unauthenticated.</b> A service worker can only obtain an FCM
 * token after the page loads, which may be before the customer has signed in. Requiring a token to
 * call this endpoint would mean registering the push subscription on the account page instead,
 * which customers never visit. The token is therefore stored unowned and attached to an account
 * when one signs in.
 *
 * <p>That is not a privilege escalation: an unattached token can receive nothing, because
 * {@link #notifyOrderStatus} fans out by <em>user</em>, and an unowned token has no user. The worst
 * an attacker can do is fill the table, which is bounded by the token rate limit below.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceService {

	/**
	 * Refuses a flood of registrations from one client.
	 *
	 * <p>A token table with no bound is a write amplifier: anyone can post junk and grow it without
	 * limit. The cap is generous for real browsers — a customer re-registering on every visit — and
	 * still finite.
	 */
	private static final int MAX_DEVICES_PER_USER = 10;

	private final CustomerDeviceRepository devices;
	private final PushSender push;

	// ------------------------------------------------------------------ register

	/**
	 * Registers a token, attaching it to the caller when there is one.
	 *
	 * <p>Re-registering the same token updates rather than duplicates, which is what a service
	 * worker does on every page load — two rows for one token would send every push twice.
	 *
	 * @return whether the token is now registered
	 */
	@Transactional
	public boolean register(String token, String platform, UUID userId) {

		if (token == null || token.isBlank()) {
			return false;
		}
		String trimmed = token.trim();

		CustomerDevice device = devices.findByToken(trimmed).orElse(null);

		if (device == null) {
			if (userId != null && devices.countByUserIdAndActiveTrue(userId) >= MAX_DEVICES_PER_USER) {
				// Refused rather than silently dropping the oldest: quietly unregistering a device
				// a customer is still using is worse than telling them it was refused.
				log.warn("Refusing a push registration for {}: already at {} devices",
						userId, MAX_DEVICES_PER_USER);
				return false;
			}
			device = new CustomerDevice(trimmed, platform);
			if (userId != null) {
				device.attachTo(userId);
			}
			devices.save(device);
			return true;
		}

		// Already known. A token that was marked dead and has come back is alive again, which is
		// what happens when a service worker re-registers after a reinstall.
		device.setActive(true);
		if (userId != null) {
			device.attachTo(userId);
		}
		devices.save(device);
		return true;
	}

	/** Attaches every unowned token from this browser to the account that just signed in. */
	@Transactional
	public int attachUnownedTokens(UUID userId, String platform) {
		List<CustomerDevice> unowned = devices.findByUserIdAndActiveTrue(userId);
		if (unowned.isEmpty()) {
			return 0;
		}
		unowned.forEach(d -> d.attachTo(userId));
		devices.saveAll(unowned);
		return unowned.size();
	}

	/** Detaches on sign-out. The token stays registered for whoever signs in here next. */
	@Transactional
	public void signOut(UUID userId) {
		devices.detachAllFor(userId);
	}

	/** This account's own devices, for the notifications settings screen. */
	@Transactional(readOnly = true)
	public List<CustomerDevice> listFor(UUID userId) {
		return devices.findByUserIdAndActiveTrue(userId);
	}

	/**
	 * Stops sending to one of this account's devices.
	 *
	 * <p>Detaches rather than deletes. A browser that is signed out but still open would
	 * otherwise re-register the same token and, with no row, quietly become somebody else's to
	 * inherit when the next person signs in on that machine.
	 */
	@Transactional
	public boolean unregister(UUID userId, String token) {
		if (token == null || token.isBlank()) {
			return false;
		}
		return devices.findByToken(token.trim())
				.filter(device -> device.belongsTo(userId))
				.map(device -> {
					device.attachTo(null);
					devices.save(device);
					return true;
				})
				.orElse(false);
	}

	// ------------------------------------------------------------------ notify

	/**
	 * Sends an order-status push to every device the customer has.
	 *
	 * <p>Best-effort by construction. The future is not awaited and a failure is logged by the
	 * sender: a customer who has push blocked, no device registered, or is offline still sees the
	 * same state in the app, so a push failure must never fail the order transition that caused it.
	 *
	 * @return how many devices were attempted, for the log line and the tests
	 */
	@Transactional(readOnly = true)
	public int notifyOrderStatus(UUID userId, String shopSlug, String orderNumber,
			String title, String body) {

		if (!push.isAvailable() || userId == null) {
			return 0;
		}

		List<CustomerDevice> targets = devices.findByUserIdAndActiveTrue(userId);
		if (targets.isEmpty()) {
			return 0;
		}

		// A relative path, so the link works on every storefront deployment without being
		// configured per environment.
		String path = "/s/" + shopSlug + "/orders/" + orderNumber;

		for (CustomerDevice device : targets) {
			push.send(device.getToken(), title, body, path);
		}

		log.debug("Order-status push for {} attempted on {} device(s)", orderNumber, targets.size());
		return targets.size();
	}
}