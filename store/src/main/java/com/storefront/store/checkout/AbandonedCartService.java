package com.storefront.store.checkout;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Abandoned-cart reminders (Phase 8).
 *
 * <p><b>Abandoned is measured from the last change, not from when the cart was created.</b> A cart
 * someone keeps adding to is not abandoned; measuring from creation would email them about a cart
 * they are actively filling in, which is the fastest way to get a shop's reminder switched off.
 *
 * <p><b>At most one reminder per cart.</b> {@code reminded_at} is set in the same statement that
 * selects the row, so a sweep that overlaps with itself — or two application instances — cannot send
 * the same email twice. That is the failure mode that makes people unsubscribe, and it is
 * indistinguishable from spam once it happens.
 *
 * <p><b>The reminder is a normal notification row.</b> It goes through the same pipeline as every
 * other message, so it appears in the support screen's history and can be retried by the same
 * mechanism. A reminder sent by a bespoke code path would be the one message about a customer that
 * support cannot answer questions about.
 *
 * <p><b>One reminder, then silence.</b> A second email to the same abandoned cart is the point where
 * a reminder becomes harassment, and the store's domain stops mattering.
 */
@Service
public class AbandonedCartService {

	private static final Logger log = LoggerFactory.getLogger(AbandonedCartService.class);

	/**
	 * How long a cart must be untouched before it counts as abandoned.
	 *
	 * <p>Two hours. Long enough that somebody who stepped away from a checkout comes back and finds
	 * their cart intact; short enough that the cart is still on their mind when the email arrives.
	 * A day is the usual default and by then the order is usually forgotten rather than delayed.
	 */
	private static final int ABANDONED_AFTER_HOURS = 2;

	private final NamedParameterJdbcTemplate jdbc;
	private final com.storefront.store.notification.OutboxService outbox;

	public AbandonedCartService(NamedParameterJdbcTemplate jdbc,
			com.storefront.store.notification.OutboxService outbox) {
		this.jdbc = jdbc;
		this.outbox = outbox;
	}

	// ------------------------------------------------------------------ recording

	/**
	 * Records that a cart is still open.
	 *
	 * <p><b>Upsert on the customer, not append.</b> A cart that survives a page refresh is the same
	 * cart; one row per cart means "has this person been reminded" is a column read rather than a
	 * count over related tables, and a sweep can answer it without deciding anything.
	 *
	 * @param anonymousKey SHA-256 of a browser/session id, never the id itself — it is a bearer
	 *                     token for somebody else's cart if it lands in a log. Null for a signed-in
	 *                     customer, whose cart is found by account instead.
	 */
	@Transactional
	public void touch(UUID customerId, String anonymousKey, long totalMinor, int itemCount) {
		if ((customerId == null) == (anonymousKey == null || anonymousKey.isBlank())) {
			throw new IllegalArgumentException(
					"A cart belongs to a customer or to an anonymous browser key, not both and not "
							+ "neither.");
		}
		if (totalMinor <= 0 || itemCount <= 0) {
			// An empty cart is not abandoned, it is finished. Storing one produces a reminder about
			// nothing, which is worse than no reminder at all.
			return;
		}

		Instant now = Instant.now();

		jdbc.update("""
				INSERT INTO abandoned_cart (id, customer_user_id, anonymous_key, total_minor,
				                           item_count, last_active_at, created_at, updated_at)
				VALUES (:id, :customerId, :key, :total, :count, :now, :now, :now)
				ON CONFLICT (customer_user_id) WHERE customer_user_id IS NOT NULL
				                  AND recovered_at IS NULL
				DO UPDATE SET total_minor = EXCLUDED.total_minor,
				              item_count  = EXCLUDED.item_count,
				              last_active_at = EXCLUDED.last_active_at,
				              updated_at  = EXCLUDED.updated_at
				""", new MapSqlParameterSource()
				.addValue("id", UUID.randomUUID())
				.addValue("customerId", customerId)
				.addValue("key", anonymousKey)
				.addValue("total", totalMinor)
				.addValue("count", itemCount)
				.addValue("now", utc(now)));
	}

	/**
	 * Marks a cart as recovered, so it is never reminded about.
	 *
	 * <p>Called when the order goes through. The cart that became an order is the one case where a
	 * reminder would be actively wrong — emailing somebody "you left something behind" about an
	 * order they have already placed is the sort of thing that gets a shop's domain marked as spam.
	 */
	@Transactional
	public void markRecovered(UUID customerId) {
		if (customerId == null) {
			return;
		}

		jdbc.update("""
				UPDATE abandoned_cart
				SET recovered_at = :now, updated_at = :now
				WHERE customer_user_id = :customerId AND recovered_at IS NULL
				""", new MapSqlParameterSource()
				.addValue("customerId", customerId)
				.addValue("now", utc(Instant.now())));
	}

	// ------------------------------------------------------------------ the sweep

	/**
	 * Reminds people about carts they left.
	 *
	 * <p><b>Cron with six fields.</b> Spring's cron is seconds-first, and a five-field expression is
	 * either rejected at startup or silently misread — so this is {@code 0} seconds, then the five
	 * fields a person expects.
	 *
	 * <p><b>Claimed in the same statement that selects them.</b> {@code reminded_at IS NULL} is in
	 * the {@code WHERE} of the update, not just the read, so two instances running this at the same
	 * moment cannot both send. That is the difference between a reminder and spam.
	 *
	 * <p>Never throws. A sweep that fails halfway has already sent some reminders, and aborting
	 * means the rest wait for the next run — but throwing out of a scheduler kills the job for good
	 * in some configurations, which is a much worse failure than a partial sweep.
	 */
	@Scheduled(cron = "0 */30 * * * *")
	public void sweep() {
		try {
			sweepOnce();
		} catch (RuntimeException e) {
			// Logged and swallowed on purpose; see the method note.
			log.error("Abandoned-cart sweep failed; it will run again in 30 minutes", e);
		}
	}

	/** The sweep's body, separated so a test can drive it without waiting for the scheduler. */
	@Transactional
	public int sweepOnce() {
		Instant cutoff = Instant.now().minus(ABANDONED_AFTER_HOURS, ChronoUnit.HOURS);

		List<Candidate> candidates = jdbc.query("""
				UPDATE abandoned_cart
				SET reminded_at = :now, updated_at = :now
				WHERE id IN (
					SELECT id FROM abandoned_cart
					WHERE reminded_at IS NULL
					  AND recovered_at IS NULL
					  AND last_active_at <= :cutoff
					ORDER BY last_active_at ASC
					LIMIT 500
				)
				RETURNING customer_user_id, total_minor, item_count
				""", new MapSqlParameterSource()
				.addValue("cutoff", utc(cutoff))
				.addValue("now", utc(Instant.now())),
				(rs, n) -> new Candidate(rs.getObject("customer_user_id", UUID.class),
						rs.getLong("total_minor"), rs.getInt("item_count")));

		int sent = 0;

		for (Candidate candidate : candidates) {
			// A guest cart has no account to email. Skipping it is the whole reason the sweep does
			// not claim those rows -- claiming them would mark them reminded and they would never
			// be contacted by anybody.
			if (candidate.customerUserId() == null) {
				continue;
			}

			try {
				// Through the outbox, like every other message. A bespoke send path here would be
				// the one message about a customer that support could not answer questions about,
				// and the one that would not be retried when a provider was down.
				outbox.record(com.storefront.store.notification.OutboxEvent.CART, candidate.customerUserId(),
						"CART_ABANDONED", java.util.Map.of(
								"customerUserId", candidate.customerUserId().toString(),
								"totalMinor", candidate.totalMinor(),
								"itemCount", candidate.itemCount(),
								"totalDisplay",
								com.storefront.store.common.Money.ofMinor(candidate.totalMinor()).toDisplay()));

				sent++;
			} catch (RuntimeException e) {
				// One bad recipient must not stop the rest. The row is already marked reminded, so
				// it will not be retried -- which is the right trade: a reminder that keeps failing
				// is worse than a missed one, and the support screen shows the failure.
				log.warn("Abandoned-cart reminder failed for customer {}", candidate.customerUserId(), e);
			}
		}

		if (sent > 0) {
			log.info("Sent {} abandoned-cart reminder(s)", sent);
		}

		return sent;
	}

	/** Carts somebody is currently looking at, for a shop's own panel. */
	@Transactional(readOnly = true)
	public Page<AbandonedCartView> forCustomer(UUID customerId, Pageable pageable) {
		List<AbandonedCartView> rows = jdbc.query("""
				SELECT id, total_minor, item_count, last_active_at, reminded_at, recovered_at
				FROM abandoned_cart
				WHERE customer_user_id = :customerId
				ORDER BY last_active_at DESC
				LIMIT :limit OFFSET :offset
				""", new MapSqlParameterSource()
				.addValue("customerId", customerId)
				.addValue("limit", Math.min(pageable.getPageSize(), 100))
				.addValue("offset", pageable.getOffset()),
				(rs, n) -> new AbandonedCartView(
						rs.getObject("id", UUID.class),
						rs.getLong("total_minor"),
						rs.getLong("total_minor") <= 0 ? "Rs. 0.00"
								: com.storefront.store.common.Money.ofMinor(rs.getLong("total_minor")).toDisplay(),
						rs.getInt("item_count"),
						rs.getObject("last_active_at", java.time.OffsetDateTime.class).toInstant(),
						rs.getObject("reminded_at", java.time.OffsetDateTime.class) == null
								? null
								: rs.getObject("reminded_at", java.time.OffsetDateTime.class).toInstant(),
						rs.getObject("recovered_at", java.time.OffsetDateTime.class) != null));

		return new org.springframework.data.domain.PageImpl<>(rows);
	}

	private static java.time.OffsetDateTime utc(Instant instant) {
		return java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
	}

	// ------------------------------------------------------------------ types

	private record Candidate(UUID customerUserId, long totalMinor, int itemCount) {
	}

	/** One abandoned cart, for a screen. */
	public record AbandonedCartView(
			UUID id,
			long totalMinor,
			String totalDisplay,
			int itemCount,
			java.time.Instant lastActiveAt,
			java.time.Instant remindedAt,
			boolean recovered) {
	}
}