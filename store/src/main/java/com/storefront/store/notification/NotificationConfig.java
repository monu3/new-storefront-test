package com.storefront.store.notification;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the exchange and the email queue.
 *
 * <p>The queue is <b>durable</b>, so it survives a broker restart. Combined with persistent
 * messages on a durable queue, a broker bounce does not lose notifications — which is the failure
 * the outbox was introduced to prevent, and which a non-durable queue would reintroduce silently.
 *
 * <p>{@link ConditionalOnProperty} means the whole topology can be switched off with one flag,
 * so the outbox tests run without a broker and the application still starts when RabbitMQ is
 * down. The relay then accumulates unpublished rows, which is exactly the designed behaviour: no
 * notifications, but orders still work.
 */
@Configuration
@ConditionalOnProperty(name = "store.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationConfig {

	@Bean
	public TopicExchange storeEventsExchange() {
		return new TopicExchange(NotificationTopology.EXCHANGE, true, false);
	}

	@Bean
	public Queue notificationEmailQueue() {
		return new Queue(NotificationTopology.EMAIL_QUEUE, true);
	}

	/** Binds every order event to the email queue. */
	@Bean
	public Binding orderEventsToEmailBinding(Queue notificationEmailQueue,
			TopicExchange storeEventsExchange) {
		return BindingBuilder.bind(notificationEmailQueue).to(storeEventsExchange)
				.with(NotificationTopology.ORDER_PREFIX + ".#");
	}
}
