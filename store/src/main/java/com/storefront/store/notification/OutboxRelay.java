package com.storefront.store.notification;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Moves events from the outbox to RabbitMQ.
 *
 * <p>A relay rather than publishing inline, because publishing inside the business transaction
 * would reintroduce the gap the outbox exists to close: if the broker is briefly unavailable the
 * order transaction rolls back, and a customer cannot place an order because an email server is
 * down. With a relay, the order commits regardless and the notification is retried.
 *
 * <p><b>One event's failure does not stop the others.</b> Each is published in its own transaction
 * and its own try/catch, so a single malformed event cannot block the queue behind it. That
 * matters most because {@code default-requeue-rejected: false} means a requeued message goes to a
 * dead-letter queue rather than spinning forever.
 *
 * <p>{@link OutboxEvent#shouldRetry()} gives up after a bounded number of attempts. A poison
 * message retried without limit grows the table and fills the logs, and nobody ever sees the
 * failure because it is always followed by another attempt.
 */
@Component
@RequiredArgsConstructor
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxEventRepository outbox;
	private final EventPublisher publisher;
	private final OutboxRelaySettings settings;

	/**
	 * One pass.
	 *
	 * <p>Runs outside any ambient transaction ({@link Propagation#NOT_SUPPORTED}) so each event's
	 * success or failure is committed on its own. A single transaction around the whole batch
	 * would mean one failure rolls back the successes and they are published again next pass.
	 */
	@Scheduled(fixedDelayString = "${store.outbox.poll-interval-ms:2000}")
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public int relay() {
		if (!settings.enabled()) {
			return 0;
		}

		List<OutboxEvent> batch = outbox.findUnpublished(PageRequest.of(0, settings.batchSize()));
		if (batch.isEmpty()) {
			return 0;
		}

		int published = 0;
		for (OutboxEvent event : batch) {
			if (publishOne(event)) {
				published++;
			}
		}

		if (published > 0) {
			log.info("Outbox relay published {} of {} events", published, batch.size());
		}
		return published;
	}

	/**
	 * Publishes one event in its own transaction.
	 *
	 * @return true when it went out
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean publishOne(OutboxEvent event) {
		try {
			publisher.publish(event);
			event.markPublished();
			outbox.saveAndFlush(event);
			return true;
		} catch (Exception e) {
			// The failure is recorded on the row rather than merely logged, so an operator can see
			// which events are stuck and why without reading application logs.
			event.recordFailure(e.getMessage());
			outbox.saveAndFlush(event);
			log.warn("Outbox event {} failed on attempt {}: {}",
					event.getId(), event.getAttempts(), e.getMessage());
			return false;
		}
	}

	/** Events that have given up, for an operator screen. */
	@Transactional(readOnly = true)
	public List<OutboxEvent> exhausted() {
		return outbox.findExhausted(OutboxEvent.MAX_ATTEMPTS);
	}

	@Transactional(readOnly = true)
	public long pendingCount() {
		return outbox.countByPublishedAtIsNull();
	}
}
