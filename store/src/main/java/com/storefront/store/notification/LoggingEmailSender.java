package com.storefront.store.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Development email sender: records the send, logs it, sends nothing.
 *
 * <p>Real SMTP is a deployment concern, and this project is scoped to localhost. Having a sender
 * that does not need a mail server means the outbox, the relay, the queue and the worker are all
 * genuinely exercised in development — a stubbed-out stage would leave the whole notification
 * pipeline untested until the day it mattered.
 *
 * <p>It deliberately does <em>not</em> throw. A sender that failed in development would make the
 * retry and dead-letter paths look broken on every local run, and would hide whether the real
 * pipeline works.
 */
@Component
@RequiredArgsConstructor
public class LoggingEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

	private final NotificationRepository notifications;

	@Override
	public void send(Notification notification) {
		log.info("""
						[email] to={} shop={} type={}
						  subject: {}
						  {}""",
				notification.getRecipientUserId(),
				notification.getRecipientShopId(),
				notification.getType(),
				notification.getTitle(),
				notification.getBody());

		notification.markSent();
		notifications.saveAndFlush(notification);
	}
}
