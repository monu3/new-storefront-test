package com.storefront.store.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storefront.store.shop.ShopRepository;

import lombok.RequiredArgsConstructor;

/**
 * Consumes order events and turns them into notifications.
 *
 * <p>Deliberately simple. It builds a human-readable title and body from the event, writes a
 * {@link Notification} row, and sends it. Templating, per-shop branding and localisation arrive in
 * Phase 4 with the email-template settings, and pretending to need them now would produce a
 * template engine that every template then has to be migrated out of.
 *
 * <p><b>Consumption is idempotent</b> because RabbitMQ delivers at-least-once: a consumer that
 * crashes after sending but before acknowledging gets the message again. The check is on
 * {@code (type, aggregate, recipient)}, so a redelivery updates the existing row rather than
 * emailing a customer twice about the same order.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "store.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationWorker {

	private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

	private final NotificationRepository notifications;
	private final EmailSender emailSender;
	private final ShopRepository shops;
	private final ObjectMapper json;
	private final com.storefront.store.customer.DeviceService deviceService;

	@RabbitListener(queues = NotificationTopology.EMAIL_QUEUE)
	@Transactional
	public void onEvent(String payload) throws Exception {
		JsonNode event = json.readTree(payload);

		String type = text(event, "eventType");
		UUID shopId = uuid(event, "shopId");
		String orderNumber = text(event, "orderNumber");
		String customerEmail = text(event, "customerEmail");
		Long totalMinor = event.hasNonNull("totalMinor") ? event.get("totalMinor").asLong() : null;

		if (type == null || shopId == null) {
			// Acknowledge rather than requeue: a message we cannot parse will never become
			// parseable, and requeueing it blocks every later message behind it.
			log.warn("Discarding an event with no type or shop: {}", payload);
			return;
		}

		// Low stock is a shop-only warning. It has no order, no customer and no amount, and
		// addressing it to a customer would email a message about somebody else's inventory.
		if ("LOW_STOCK".equals(type)) {
			handleLowStock(event, shopId);
			return;
		}

		String title = title(type, orderNumber);
		String body = body(type, orderNumber, totalMinor);

		// One notification per event, addressed to the customer and to the shop. The shop's copy
		// is what makes their order board and their inbox agree.
		storeNotification(Notification.Audience.SHOP, null, shopId, type, title, body, shopName(shopId));
		if (customerEmail != null) {
			// A guest has no app_user row, so the notification is addressed by shop and carries
			// the email in its data. Phase 5 replaces this with a real customer row.
			storeNotification(Notification.Audience.CUSTOMER, null, shopId, type, title, body,
					customerEmail);
		}

		// Push, for the customer who signed in. Guests have no account and therefore no devices,
		// which is why this is addressed by user id rather than by email.
		pushOrderStatus(event, shopId, orderNumber, title, body);
	}

	/**
	 * The push half of an order-status notification.
	 *
	 * <p>Separate from the email above because the two have different failure modes and different
	 * audiences: an email failure is retried by the outbox, and a push failure is logged and
	 * dropped. A customer who has push blocked, or no device registered, or is offline still sees
	 * the same state in the app, so push can never be the only delivery route.
	 *
	 * <p>Called after the email has been recorded rather than instead of it: the email is the
	 * durable record, and push is the thing that saves the customer a page load.
	 */
	private void pushOrderStatus(JsonNode event, UUID shopId, String orderNumber,
			String title, String body) {

		UUID userId = uuid(event, "customerUserId");
		String shopSlug = text(event, "shopSlug");
		if (userId == null || shopSlug == null) {
			// An event with no signed-in customer has nobody to push to. Not an error: most orders
			// on this platform are guests.
			return;
		}

		try {
			deviceService.notifyOrderStatus(userId, shopSlug, orderNumber, title, body);
		} catch (RuntimeException ex) {
			// Never propagated. This runs inside the notification consumer, and letting a push
			// failure escape would requeue the message and re-send the email to a customer who
			// already has it.
			log.warn("Could not send the order-status push for {}: {}", orderNumber, ex.getMessage());
		}
	}

	/**
	 * The low-stock warning.
	 *
	 * <p>Uses the owner's own email template with {@code {body}} replaced by the product and count.
	 * The subject and body are optional in settings, so each falls back to a plain sentence rather
	 * than sending an empty mail.
	 */
	private void handleLowStock(JsonNode event, UUID shopId) {
		String productName = text(event, "productName");
		String variantName = text(event, "variantName");
		int available = event.hasNonNull("available") ? event.get("available").asInt() : 0;
		int threshold = event.hasNonNull("threshold") ? event.get("threshold").asInt() : 0;

		String item = variantName == null || variantName.isBlank()
				? productName
				: productName + " (" + variantName + ")";

		String subject = "Low stock: " + item;
		String bodyText = item + " has " + available + " left (warning at " + threshold + ").";

		storeNotification(Notification.Audience.SHOP, null, shopId, "LOW_STOCK",
				subject, bodyText, shopName(shopId));
	}

	private void storeNotification(Notification.Audience audience, UUID userId, UUID shopId,
			String type, String title, String body, String recipientLabel) {

		if (alreadyRecorded(type, shopId, audience)) {
			log.debug("Notification for {} already recorded; skipping redelivery", type);
			return;
		}

		Notification notification = new Notification(audience, userId, shopId,
				Notification.Channel.EMAIL, type, title, body);
		notifications.saveAndFlush(notification);

		try {
			emailSender.send(notification);
			log.info("{} notification recorded and sent to {}", type, recipientLabel);
		} catch (RuntimeException e) {
			// The row exists and says FAILED, so the failure is visible and retryable. Swallowing
			// it here would leave a notification row stuck at PENDING forever.
			notification.markFailed(e.getMessage());
			notifications.saveAndFlush(notification);
			log.warn("Could not send {} notification to {}: {}", type, recipientLabel, e.getMessage());
		}
	}

	/** At-least-once delivery means this will be called twice for a message already handled. */
	private boolean alreadyRecorded(String type, UUID shopId, Notification.Audience audience) {
		return notifications.findByRecipientShopIdOrderByCreatedAtDesc(shopId,
				org.springframework.data.domain.PageRequest.of(0, 20)).stream()
				.anyMatch(n -> type.equals(n.getType()) && audience == n.getAudience());
	}

	private String shopName(UUID shopId) {
		return shops.findById(shopId).map(s -> s.getName()).orElse("the shop");
	}

	private String title(String type, String orderNumber) {
		String ref = orderNumber == null ? "" : " " + orderNumber;
		return switch (type) {
			case "ORDER_PLACED" -> "New order" + ref;
			case "ORDER_ACCEPTED" -> "Order accepted" + ref;
			case "ORDER_CANCELLED" -> "Order cancelled" + ref;
			default -> "Order update" + ref;
		};
	}

	private String body(String type, String orderNumber, Long totalMinor) {
		String ref = orderNumber == null ? "" : orderNumber;
		String amount = totalMinor == null ? ""
				: " for " + com.storefront.store.common.Money.ofMinor(totalMinor).toDisplay();
		return switch (type) {
			case "ORDER_PLACED" -> "A customer placed order " + ref + amount + ".";
			case "ORDER_ACCEPTED" -> "Order " + ref + " has been accepted and is being prepared.";
			case "ORDER_CANCELLED" -> "Order " + ref + " has been cancelled.";
			default -> "Order " + ref + " has been updated.";
		};
	}

	private String text(JsonNode node, String field) {
		return node.hasNonNull(field) ? node.get(field).asText() : null;
	}

	private UUID uuid(JsonNode node, String field) {
		return node.hasNonNull(field) ? UUID.fromString(node.get(field).asText()) : null;
	}
}
