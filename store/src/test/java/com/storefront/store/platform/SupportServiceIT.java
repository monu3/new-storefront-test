package com.storefront.store.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.identity.AppUser;
import com.storefront.store.notification.Notification;
import com.storefront.store.notification.NotificationRepository;
import com.storefront.store.support.TestConfig;

/**
 * Support tooling: notification history and the email log (Phase 7).
 *
 * <p><b>One table, two screens.</b> The email log is {@code channel = 'EMAIL'} over the same rows
 * the notification history shows, not a second record of the same send. Two records of one event is
 * a thing that disagrees — most visibly on a retry, where the table has one row with two attempts
 * and a separate log would have two rows. The tests below assert that the two views agree.
 *
 * <p><b>Failure is the reason this screen exists.</b> "We sent you a reset link" and "the reset link
 * bounced" are both true about the same event, so {@code status}, {@code attempts} and
 * {@code last_error} all have to be visible together, or a support agent cannot answer the person
 * who is asking.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class SupportServiceIT {

	@Autowired
	private SupportService support;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private com.storefront.store.identity.AppUserRepository users;

	// ------------------------------------------------------------------ history

	@Nested
	@DisplayName("the notification history")
	class History {

		@Test
		@DisplayName("answers on an empty log rather than throwing")
		void answersWhenEmpty() {
			var history = support.history(filters());

			assertThat(history.entries()).isEmpty();
			assertThat(history.totalElements()).isZero();
			assertThat(history.totalPages())
					.as("zero results still has one page -- an empty page 0 of 0 reads as a broken "
							+ "pager rather than an empty log")
					.isEqualTo(1);
		}

		@Test
		@DisplayName("carries the summary even when nothing matched, so the tiles still render")
		void carriesTheSummaryWhenEmpty() {
			var history = support.history(filters());

			assertThat(history.summary()).isNotNull();
			assertThat(history.summary().total()).isGreaterThanOrEqualTo(0);
		}

		@Test
		@DisplayName("finds a message it was given")
		void findsAMessage() {
			given("Order delivered", Notification.Channel.EMAIL, Notification.Status.SENT);

			var history = support.history(filters());

			assertThat(history.entries())
					.extracting(SupportService.Entry::title)
					.contains("Order delivered");
		}

		@Test
		@DisplayName("filters by status, which is how support finds the failures")
		void filtersByStatus() {
			given("Delivered", Notification.Channel.EMAIL, Notification.Status.SENT);
			given("Reset link", Notification.Channel.EMAIL, Notification.Status.FAILED);

			var failed = support.history(new SupportService.NotificationFilters(
					30, null, "FAILED", null, null, null, 50, 0));

			assertThat(failed.entries()).isNotEmpty();
			assertThat(failed.entries())
					.allSatisfy(entry -> assertThat(entry.status()).isEqualTo("FAILED"));
		}

		@Test
		@DisplayName("filters by channel, so the email log is the history narrowed")
		void filtersByChannel() {
			given("By email", Notification.Channel.EMAIL, Notification.Status.SENT);
			given("In app", Notification.Channel.IN_APP, Notification.Status.SENT);

			var emails = support.history(new SupportService.NotificationFilters(
					30, "EMAIL", null, null, null, null, 50, 0));

			assertThat(emails.entries())
					.allSatisfy(entry -> assertThat(entry.channel()).isEqualTo("EMAIL"));
			assertThat(emails.entries())
					.extracting(SupportService.Entry::title)
					.contains("By email")
					.doesNotContain("In app");
		}

		@Test
		@DisplayName("searches on the text a person would actually have")
		void searchesOnText() {
			given("Your order has shipped", Notification.Channel.EMAIL, Notification.Status.SENT);

			var found = support.history(new SupportService.NotificationFilters(
					30, null, null, null, null, "shipped", 50, 0));

			assertThat(found.entries()).isNotEmpty();
		}

		@Test
		@DisplayName("a blank search matches everything rather than nothing")
		void blankSearchMatchesEverything() {
			given("One", Notification.Channel.EMAIL, Notification.Status.SENT);

			var blank = support.history(new SupportService.NotificationFilters(
					30, null, null, null, null, "   ", 50, 0));
			var none = support.history(filters());

			assertThat(blank.totalElements()).isEqualTo(none.totalElements());
		}

		@Test
		@DisplayName("refuses a window that is not a window")
		void refusesAnAbsurdWindow() {
			assertThatThrownBy(() -> support.history(
					new SupportService.NotificationFilters(0, null, null, null, null, null, 50, 0)))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> support.history(
					new SupportService.NotificationFilters(10_000, null, null, null, null, null, 50, 0)))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("caps the page size")
		void capsThePageSize() {
			var history = support.history(new SupportService.NotificationFilters(
					30, null, null, null, null, null, 100_000, 0));

			assertThat(history.size()).isLessThanOrEqualTo(200);
		}

		@Test
		@DisplayName("pages without repeating or dropping rows")
		void pages() {
			for (int n = 0; n < 5; n++) {
				given("Message " + n, Notification.Channel.EMAIL, Notification.Status.SENT);
			}

			var firstPage = support.history(new SupportService.NotificationFilters(
					30, "EMAIL", null, null, null, null, 2, 0));
			var secondPage = support.history(new SupportService.NotificationFilters(
					30, "EMAIL", null, null, null, null, 2, 2));

			assertThat(firstPage.entries()).hasSize(2);
			assertThat(secondPage.entries()).hasSizeLessThanOrEqualTo(2);
			assertThat(firstPage.entries())
					.as("page 2 must not repeat page 1")
					.doesNotContainAnyElementsOf(secondPage.entries());
		}
	}

	// ------------------------------------------------------------------ the email log

	@Nested
	@DisplayName("the email log")
	class EmailLog {

		@Test
		@DisplayName("is the same rows the history shows, narrowed to email")
		void isTheHistoryNarrowed() {
			given("By email", Notification.Channel.EMAIL, Notification.Status.SENT);
			given("In app", Notification.Channel.IN_APP, Notification.Status.SENT);

			var log = support.emailLog(30, 50, 0);

			assertThat(log.entries())
					.allSatisfy(entry -> assertThat(entry.channel()).isEqualTo("EMAIL"));
			assertThat(log.entries()).extracting(SupportService.Entry::title)
					.contains("By email")
					.doesNotContain("In app");
		}
	}

	// ------------------------------------------------------------------ the summary

	@Nested
	@DisplayName("the delivery summary")
	class Summary {

		@Test
		@DisplayName("counts the window as a whole, not the current filter")
		void countsTheWindowNotTheFilter() {
			given("Delivered", Notification.Channel.EMAIL, Notification.Status.SENT);
			given("Failed to send", Notification.Channel.EMAIL, Notification.Status.FAILED);

			var everything = support.history(filters());
			var onlySent = support.history(new SupportService.NotificationFilters(
					30, null, "SENT", null, null, null, 50, 0));

			assertThat(everything.summary().sent())
					.as("the summary deliberately ignores the caller's filters -- a summary that "
							+ "moved as the filters changed would describe the current selection, "
							+ "not the platform, and the two read as the same number")
					.isEqualTo(onlySent.summary().sent());
			assertThat(onlySent.summary().failed())
					.as("the failures are in the window whether or not the filter shows them, "
							+ "which is what makes the tile trustworthy")
					.isGreaterThan(0);
		}

		@Test
		@DisplayName("counts only statuses the enum can actually hold")
		void countsRealStatusesOnly() {
			given("Failed to send", Notification.Channel.EMAIL, Notification.Status.FAILED);

			var history = support.history(filters());

			assertThat(history.summary().failed())
					.isGreaterThanOrEqualTo(1);
			assertThat(history.summary().total())
					.isEqualTo(history.summary().sent()
							+ history.summary().failed()
							+ history.summary().pending());
		}

		@Test
		@DisplayName("counts emails, so the tile matches the email log's own filter")
		void countsEmails() {
			given("By email", Notification.Channel.EMAIL, Notification.Status.SENT);
			given("In app", Notification.Channel.IN_APP, Notification.Status.SENT);

			var history = support.history(filters());

			assertThat(history.summary().emails()).isGreaterThanOrEqualTo(1);
			assertThat(history.summary().emails())
					.isLessThanOrEqualTo(history.summary().total());
		}
	}

	// ------------------------------------------------------------------ helpers

	private SupportService.NotificationFilters filters() {
		return new SupportService.NotificationFilters(30, null, null, null, null, null, 50, 0);
	}

	/**
	 * One message in the log, at the delivery status under test.
	 *
	 * <p>Built through the real constructor and the real state transitions, not by writing SQL:
	 * the constructor refuses a notification addressed to nobody, and a fixture that bypassed it
	 * could produce a row the platform itself would never create.
	 */
	private void given(String title, Notification.Channel channel, Notification.Status status) {
		// Flushed first so the notification has an id the recipient row can reference -- the same
		// ordering problem a shop insert has, and the same reason for the explicit flush.
		var recipient = users.saveAndFlush(new AppUser(
				AppUser.Realm.CUSTOMERS,
				"sub-" + UUID.randomUUID(),
				"notify-" + UUID.randomUUID() + "@example.test",
				"Notified Person"));

		var notification = new Notification(Notification.Audience.CUSTOMER, recipient.getId(), null,
				channel, "ORDER_SHIPPED", title, "Body for " + title);

		switch (status) {
			case SENT -> notification.markSent();
			case FAILED -> notification.markFailed("smtp said no");
			case PENDING -> {
				// Already pending on construction -- that is the state every message starts in.
			}
		}
		notifications.saveAndFlush(notification);
	}
}