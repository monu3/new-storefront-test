package com.storefront.store.customer;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Push registration for the customer portal (Phase 5).
 *
 * <p><b>Registration is public, deliberately.</b> A service worker can only obtain an FCM token
 * after the page loads, which is frequently before anybody signs in — a first visit to a storefront
 * is exactly when a token arrives. Requiring a token here would push registration onto the account
 * page, which customers never visit, and the push notifications would then only reach people who
 * had already found their way to settings.
 *
 * <p>That is not a privilege escalation. An unattached token belongs to nobody, and the fan-out
 * query is keyed by <em>user</em>, so an unattached token can never be sent anything. What an
 * attacker can do is grow the table, which {@code DeviceService} bounds.
 *
 * <p>Reading and removing a customer's own devices does require a token, because that is a list of
 * their devices.
 */
@RestController
@RequestMapping("/api/v1/customer/devices")
@Tag(name = "Devices", description = "Push notification registration")
@RequiredArgsConstructor
public class DeviceController {

	private final DeviceService devices;
	private final CurrentUser currentUser;
	private final AppUserRepository users;

	/**
	 * Registers this browser for push.
	 *
	 * <p>Attaches to the signed-in account when there is one, and otherwise stores the token
	 * unowned so it can be attached later.
	 */
	@PostMapping
	@Operation(summary = "Register a push token",
			description = "May be called signed out; the token is attached when a customer signs in.")
	public RegisterResponse register(@Valid @RequestBody RegisterRequest body) {
		java.util.UUID userId = currentUser.optionalUserId();
		boolean registered = devices.register(body.token(), body.platform(), userId);

		return new RegisterResponse(registered,
				"Push notifications are sent to this device when you are signed in.");
	}

	/** The signed-in customer's own registered devices. */
	@GetMapping
	@Operation(summary = "This account's registered devices")
	public List<DeviceView> list() {
		java.util.UUID userId = currentUser.require().getId();
		return devices.listFor(userId).stream().map(DeviceView::from).toList();
	}

	/**
	 * Removes one of this customer's devices.
	 *
	 * <p>"Removes" means detaching rather than deleting, so the browser can register again if the
	 * same customer signs back in on it. Deleting outright would leave a signed-in browser silently
	 * without notifications.
	 */
	@DeleteMapping
	@Operation(summary = "Stop sending push to one of this account's devices")
	public void unregister(@RequestBody UnregisterRequest body) {
		java.util.UUID userId = currentUser.require().getId();
		devices.unregister(userId, body.token());
	}

	// ------------------------------------------------------------------ types

	/**
	 * An FCM registration token.
	 *
	 * <p>Bounded at 4 KB, which is comfortably above a real token's few hundred characters and below
	 * anything that looks like somebody posting a file into a text field.
	 */
	public record RegisterRequest(
			@NotBlank @Size(max = 4096) String token,
			@Size(max = 20) String platform) {
	}

	public record UnregisterRequest(@NotBlank @Size(max = 4096) String token) {
	}

	public record RegisterResponse(boolean registered, String message) {
	}

	/** A device, described without its token. */
	public record DeviceView(String platform, boolean active, String lastSeenAt) {

		static DeviceView from(CustomerDevice device) {
			// The token itself is not returned. The console has no use for it, and a response that
			// can echo a credential is a response that eventually gets logged.
			return new DeviceView(device.getPlatform(), device.isActive(),
					device.lastSeenAt() == null ? null : device.lastSeenAt().toString());
		}
	}
}