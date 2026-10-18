package com.storefront.store.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Support tooling: what the platform tried to tell somebody, and whether it got there (Phase 7).
 *
 * <p><b>Both screens read the one {@code notification} table, and there is no separate email
 * log.</b> The email log would be a second record of the same send, kept alongside the first, and
 * the two would disagree — most visibly on a retry, where the table has one row with two attempts
 * and the log has two. Filtering {@code channel = 'EMAIL'} gives the email history that a support
 * agent actually wants, with the retry history attached.
 *
 * <p><b>Failure is the reason this screen exists.</b> "We sent you a reset link" and "the reset link
 * bounced" are both true statements about the same event, and an operator debugging either needs to
 * see them in one place. {@code last_error} and {@code attempts} are on the row precisely so that a
 * send that failed twice cannot look identical to one that failed once.
 */
@Service
public class SupportService {

	private static final int MAX_DAYS = 365;
	private static final int MAX_PAGE_SIZE = 200;

	private final SupportQueries queries;

	public SupportService(SupportQueries queries) {
		this.queries = queries;
	}

	/**
	 * The notification history, filtered.
	 *
	 * @param channel null for every channel; {@code EMAIL} is the email log
	 * @param status  null for every delivery status. {@code FAILED} is the one support asks for
	 * @param audience null for both audiences. {@code ADMIN} and {@code SHOP} are the platform's
	 *                own traffic; {@code CUSTOMER} is the shop's
	 * @param days    how far back to look, bounded so this cannot become a full-table dump
	 */
	@Transactional(readOnly = true)
	public History history(NotificationFilters filters) {
		int days = window(filters.days());

		var source = new MapSqlParameterSource()
				.addValue("since", java.time.OffsetDateTime.ofInstant(
						Instant.now().minus(days, ChronoUnit.DAYS), java.time.ZoneOffset.UTC),
						java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
				// Blanked rather than passed through: a cleared filter still submits "", and `''`
				// is a real value that matches nothing -- which reads as "the filter is broken"
				// rather than "no filter".
				.addValue("channel", filters.channel())
				.addValue("status", filters.status())
				.addValue("audience", filters.audience())
				.addValue("type", blankToNull(filters.type()))
				.addValue("search", blankToNull(filters.search()))
				.addValue("limit", limit(filters.limit()))
				.addValue("offset", Math.max(0, filters.offset()));

		long total = queries.count(source);
		int size = limit(filters.limit());

		// The summary counts the whole window, so it is built from a source with no filters on it
		// -- see Summary for why it does not follow the caller's filters.
		var windowOnly = new MapSqlParameterSource()
				.addValue("since", java.time.OffsetDateTime.ofInstant(
						Instant.now().minus(days, ChronoUnit.DAYS), java.time.ZoneOffset.UTC),
						java.sql.Types.TIMESTAMP_WITH_TIMEZONE);

		if (total == 0) {
			// Not an error and not an exception: "nothing matched" is the answer, and it needs the
			// same shape as every other page so the UI does not branch on it. One page, not zero --
			// "page 0 of 0" reads as a broken pager rather than an empty log.
			return new History(days, List.of(), 0, 1, size, filters.offset(),
					queries.summary(windowOnly));
		}

		// `size` is the *effective* size, not what was asked for. Echoing back a request for
		// 100,000 that was clamped to 200 would have the UI draw 100,000 page links.
		return new History(days, queries.page(source), total, offsetPage(total, filters),
				size, filters.offset(), queries.summary(windowOnly));
	}

	/** Just the emails, for the support agent who asked about one. */
	@Transactional(readOnly = true)
	public History emailLog(int days, int limit, int offset) {
		return history(new NotificationFilters(days, "EMAIL", null, null, null, null, limit, offset));
	}

	/**
	 * How many pages there are at this page size.
	 *
	 * <p>Uses {@code ceil}, so a total of exactly 25 at size 25 is one page rather than two — an
	 * empty second page reads as "there is more" and invites a pointless click.
	 */
	private static int offsetPage(long total, NotificationFilters filters) {
		int size = limit(filters.limit());
		return (int) Math.max(1, Math.ceil((double) total / size));
	}

	private static int limit(int limit) {
		if (limit <= 0) {
			return 50;
		}
		return Math.min(limit, MAX_PAGE_SIZE);
	}

	private static int window(int days) {
		if (days < 1 || days > MAX_DAYS) {
			throw new IllegalArgumentException(
					"Ask for between 1 and " + MAX_DAYS + " days, not " + days + ".");
		}
		return days;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	// ------------------------------------------------------------------ types

	/**
	 * What to show in the filters.
	 *
	 * <p>A record rather than eight parameters, because a controller with eight near-identical
	 * {@code String} arguments is a controller whose parameters get mixed up at the call site, and
	 * nothing about that mix-up is visible in the code.
	 */
	public record NotificationFilters(
			int days,
			String channel,
			String status,
			String audience,
			String type,
			String search,
			int limit,
			int offset) {
	}

	/** One message the platform tried to deliver. */
	public record Entry(
			UUID id,
			String audience,
			UUID recipientUserId,
			UUID recipientShopId,
			String channel,
			String type,
			String title,
			String body,
			String status,
			int attempts,
			String lastError,
			Instant sentAt,
			Instant createdAt) {
	}

	/**
	 * Delivery counts over the same window.
	 *
	 * <p>Every figure here is a count of rows in the {@code notification} table, not a delivery
	 * confirmation from an email provider. {@code sent} means this platform handed the message to a
	 * sender and the sender did not throw; it does not mean the recipient opened it, and this
	 * platform has no way to know that. Saying so here is why the field is named {@code sent}
	 * rather than {@code delivered}.
	 *
	 * <p>Unfiltered by the caller's own filters on purpose: the tiles say "of everything in the last
	 * N days, this much failed", which is the question a support agent opens the screen with. A
	 * summary that moved as the filters changed would describe the current selection, not the
	 * platform, and the two are read as the same number.
	 */
	public record Summary(
			long total,
			long sent,
			long failed,
			long pending,
			long retried,
			long emails) {
	}

	public record History(
			int days,
			List<Entry> entries,
			long totalElements,
			int totalPages,
			int size,
			int offset,
			Summary summary) {
	}

	// ------------------------------------------------------------------ queries

	/** The notification queries. Separate from the service so the SQL is in one place. */
	@Repository
	public static class SupportQueries {

		private final NamedParameterJdbcTemplate jdbc;

		public SupportQueries(NamedParameterJdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		private static final String SELECT = """
				SELECT id, audience::text AS audience, recipient_user_id, recipient_shop_id,
				       channel::text AS channel, type, title, body, status::text AS status,
				       attempts, last_error, sent_at, created_at
				FROM notification
				WHERE created_at >= :since
				  AND (CAST(:channel AS text) IS NULL OR channel::text = :channel)
				  AND (CAST(:status AS text) IS NULL OR status::text = :status)
				  AND (CAST(:audience AS text) IS NULL OR audience::text = :audience)
				  AND (CAST(:type AS text) IS NULL OR type = :type)
				  AND (CAST(:search AS text) IS NULL
				       OR lower(title) LIKE lower('%' || :search || '%')
				       OR lower(body) LIKE lower('%' || :search || '%'))
				""";

		/**
		 * One page of entries.
		 *
		 * <p><b>Every optional filter is {@code CAST(… AS text)}.</b> Written as
	 * {@code :channel IS NULL}, Postgres has nothing to infer the parameter's type from and fails
	 * with "could not determine data type of parameter $2" — an error that names a placeholder
	 * number rather than the filter that caused it.
	 *
	 * <p>Offset paging, not a keyset cursor. Offset paging degrades on deep pages and a
		 * support agent reading "what did we send this person last month" wants to be able to jump
		 * to a page number; the row counts here are small enough that the degradation has not begun.
		 */
		List<Entry> page(MapSqlParameterSource params) {
			return jdbc.query(SELECT + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
					params, (rs, n) -> entry(rs));
		}

		/**
		 * The unfiltered count for the same predicate.
		 *
		 * <p>Separate from {@link #page} rather than {@code page().size()}: a count taken from the
		 * page tells you how many rows came back, which is the page size for every page after the
		 * first, and the UI then shows "page 4 of 4" no matter how many there are.
		 */
		long count(MapSqlParameterSource params) {
			return jdbc.queryForObject(
					"SELECT count(*) FROM (" + SELECT + ") AS filtered",
					params, Long.class);
		}

		/** Delivery counts across the window, unfiltered by the caller's filters. See {@link Summary}. */
		Summary summary(MapSqlParameterSource windowOnly) {
			return jdbc.queryForObject("""
					SELECT count(*)                                       AS total,
					       count(*) FILTER (WHERE status = 'SENT')          AS sent,
					       count(*) FILTER (WHERE status = 'FAILED')        AS failed,
					       count(*) FILTER (WHERE status = 'PENDING')       AS pending,
					       count(*) FILTER (WHERE attempts > 1)              AS retried,
					       count(*) FILTER (WHERE channel = 'EMAIL')        AS emails
					FROM notification
					WHERE created_at >= :since
					""", windowOnly, (rs, n) -> new Summary(
					rs.getLong("total"), rs.getLong("sent"), rs.getLong("failed"),
					rs.getLong("pending"), rs.getLong("retried"), rs.getLong("emails")));
		}

		/**
		 * A {@code timestamptz} column as an {@link Instant}.
		 *
		 * <p>Through {@code OffsetDateTime}, because the driver refuses
		 * {@code getObject(…, Instant.class)} and says so in a message about types that points at
		 * the schema rather than at the call.
		 */
		private static Instant timestamp(ResultSet rs, String column) throws SQLException {
			java.time.OffsetDateTime value =
					rs.getObject(column, java.time.OffsetDateTime.class);
			return value == null ? null : value.toInstant();
		}

		private static Entry entry(ResultSet rs) throws SQLException {
			return new Entry(
					rs.getObject("id", UUID.class),
					rs.getString("audience"),
					rs.getObject("recipient_user_id", UUID.class),
					rs.getObject("recipient_shop_id", UUID.class),
					rs.getString("channel"),
					rs.getString("type"),
					rs.getString("title"),
					rs.getString("body"),
					rs.getString("status"),
					rs.getInt("attempts"),
					rs.getString("last_error"),
					timestamp(rs, "sent_at"),
					timestamp(rs, "created_at"));
		}
	}
}