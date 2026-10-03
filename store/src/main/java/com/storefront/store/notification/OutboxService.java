package com.storefront.store.notification;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes events to the outbox.
 *
 * <p><b>{@link #record} must run inside the caller's transaction</b>, which
 * {@link Propagation#MANDATORY} enforces rather than assumes. That is the entire point of the
 * pattern: if the state change commits, the event commits with it, and if it rolls back the event
 * disappears with it. An event that survives a rolled-back order is a notification about an order
 * that never existed.
 *
 * <p>{@link Propagation#MUST_NOT} on nothing here. The naive implementation — commit the order,
 * then publish — is exactly the gap R5 describes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxService {

	private final OutboxEventRepository outbox;
	private final ObjectMapper json;

	/**
	 * Records an event in the caller's transaction.
	 *
	 * @param aggregateType {@link OutboxEvent#ORDER} or {@link OutboxEvent#SHOP}
	 * @param eventType     e.g. {@code ORDER_PLACED}, {@code ORDER_ACCEPTED}
	 * @param payload       serialised with the shared ObjectMapper, so the JSON matches what a
	 *                      consumer expects rather than whatever a hand-built string happened to
	 *                      contain
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public OutboxEvent record(String aggregateType, UUID aggregateId, String eventType,
			Object payload) {

		String body;
		try {
			body = json.writeValueAsString(payload);
		} catch (Exception e) {
			// A payload that cannot be serialised is a programming error. Throwing here rolls the
			// whole state change back, which is drastic but correct: shipping an order whose
			// notification is silently empty is worse than refusing the order.
			throw new IllegalArgumentException(
					"Could not serialise the payload for " + eventType, e);
		}

		OutboxEvent event = outbox.saveAndFlush(new OutboxEvent(aggregateType, aggregateId, eventType, body));
		log.debug("Outbox event {} recorded for {} {}", eventType, aggregateType, aggregateId);
		return event;
	}
}
