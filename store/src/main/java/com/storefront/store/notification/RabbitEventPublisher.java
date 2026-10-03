package com.storefront.store.notification;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes outbox events to RabbitMQ.
 *
 * <p>The routing key is the event type, so consumers bind to what they care about
 * ({@code ORDER_PLACED.#}) rather than receiving everything and filtering. That matters as the
 * event count grows: a queue per concern means a slow email consumer cannot delay a push
 * notification.
 *
 * <p><b>Delivery is persistent</b> ({@link MessageDeliveryMode#PERSISTENT}). A message that
 * survives a broker restart is the whole point of writing it to the outbox; a transient message
 * would reintroduce the loss the outbox was added to prevent, just at a different moment.
 *
 * <p>The publisher confirms are not enabled here. A confirm would let the relay record a publish
 * as successful only once the broker has taken it — which is stronger — but Spring's confirm
 * requires the channel to be in confirm mode, and misconfiguring that silently publishes
 * nothing. {@code store.outbox.enabled=false} plus the pending-count health check is the
 * honest way to notice a broker that is not actually receiving.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RabbitEventPublisher implements EventPublisher {

	private final RabbitTemplate rabbit;

	@Override
	public void publish(OutboxEvent event) {
		rabbit.convertAndSend(
				NotificationTopology.EXCHANGE,
				event.getEventType(),
				event.getPayload(),
				message -> {
					message.getMessageProperties().setMessageId(event.getId().toString());
					message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
					// The aggregate type is a header rather than part of the body, so a consumer can
					// filter without parsing JSON, and the body stays exactly what the publisher
					// wrote.
					message.getMessageProperties().setHeader("aggregateType", event.getAggregateType());
					message.getMessageProperties().setHeader("aggregateId",
							event.getAggregateId().toString());
					return message;
				});

		log.debug("Published {} for {} {}", event.getEventType(), event.getAggregateType(),
				event.getAggregateId());
	}
}
