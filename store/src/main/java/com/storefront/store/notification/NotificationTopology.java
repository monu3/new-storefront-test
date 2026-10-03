package com.storefront.store.notification;

/**
 * Exchange and queue names, in one place.
 *
 * <p>Constants rather than {@code @RabbitListener(queues = "...")} string literals scattered
 * across consumers, because a typo in a queue name does not fail at compile time — it fails as a
 * consumer that silently receives nothing, which is the hardest kind of notification bug to
 * notice.
 *
 * <p>The exchange is a topic exchange so consumers can bind {@code ORDER.#} when they want
 * everything about orders, or {@code ORDER_ACCEPTED} when they want one event.
 */
public final class NotificationTopology {

	private NotificationTopology() {
	}

	/** Topic exchange. Event types are routing keys. */
	public static final String EXCHANGE = "store.events";

	/** Queue for the email worker. */
	public static final String EMAIL_QUEUE = "store.notifications.email";

	/** Routing key prefix for order events. */
	public static final String ORDER_PREFIX = "ORDER";
}
