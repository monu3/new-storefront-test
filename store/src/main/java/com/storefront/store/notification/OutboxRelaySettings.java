package com.storefront.store.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox relay tuning.
 *
 * <p>A typed record rather than {@code @Value} fields on the relay, so the two settings are
 * constructor-injected and a test can build a relay with explicit values instead of poking
 * private fields by string name.
 *
 * @param enabled      false switches the relay off entirely, which is how the application starts
 *                     and the suite runs without RabbitMQ. Events then accumulate unpublished,
 *                     which is the designed behaviour rather than a failure.
 * @param batchSize    events per pass. Bounded so a large backlog cannot hold a transaction open
 *                     for an unbounded time.
 * @param pollIntervalMs how often the relay runs. Two seconds is frequent enough that an order
 *                       confirmation feels immediate and slow enough to be invisible.
 */
@ConfigurationProperties(prefix = "store.outbox")
public record OutboxRelaySettings(boolean enabled, int batchSize, long pollIntervalMs) {

	public OutboxRelaySettings {
		if (batchSize <= 0) {
			batchSize = 50;
		}
		if (pollIntervalMs <= 0) {
			pollIntervalMs = 2000;
		}
	}
}
