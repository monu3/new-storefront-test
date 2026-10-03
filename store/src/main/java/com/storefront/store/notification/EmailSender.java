package com.storefront.store.notification;

/**
 * Sends one email.
 *
 * <p>An interface so the notification pipeline can be tested without an SMTP server, and so
 * Phase 4 can swap in a provider (SES, Mailgun) without touching the worker.
 *
 * <p>{@link LoggingEmailSender} is the default in development. It records the notification as sent
 * and logs the address, so the flow is visibly working end to end without anyone having to run a
 * mail server — and it never pretends to deliver to a real address, which would mean the
 * development database can email strangers.
 */
public interface EmailSender {

	/**
	 * Sends the notification.
	 *
	 * @throws RuntimeException on failure. The worker records the failure on the notification row
	 *                          rather than losing it, and the queue's dead-letter policy decides
	 *                          what happens next.
	 */
	void send(Notification notification);
}
