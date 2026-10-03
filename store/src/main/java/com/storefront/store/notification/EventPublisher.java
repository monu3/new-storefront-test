package com.storefront.store.notification;

/**
 * Publishes one outbox event.
 *
 * <p>An interface with one implementation so {@link OutboxRelay} can be tested without a broker.
 * The relay's behaviour worth testing is "a failure on one event does not block the rest, and the
 * failure is recorded on the row" — and that is untestable if every test needs RabbitMQ running.
 */
public interface EventPublisher {

	/**
	 * Publishes the event.
	 *
	 * @throws RuntimeException to signal failure. The relay records the message on the row and
	 *                          moves on; it is not expected to distinguish kinds of failure,
	 *                          because there is nothing different it would do about them.
	 */
	void publish(OutboxEvent event);
}
