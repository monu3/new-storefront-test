package com.storefront.store.social;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Share-link clicks (Phase 8).
 *
 * <p><b>Append-only rows in their own table, not a counter on the product.</b> "How many people came
 * from WhatsApp" needs a date and a channel per click, and a counter on a product cannot answer "how
 * many last month" without a second table that duplicates the same problem one level down.
 *
 * <p><b>A plain JDBC repository, matching {@code KpiRepository}.</b> This is an aggregate/insert-only
 * store with no entity to manage — there is nothing to update and nothing to delete — so an entity
 * would be a class whose only method is a wrapper around {@code INSERT}.
 *
 * <p><b>Unauthenticated writes are bounded.</b> A visitor arriving from a link has no token, so this
 * endpoint has to be open; {@code max_rows_per_source_per_day} is the limit that stops it being a
 * way to write a million rows.
 */
@Repository
public class ShareClickRepository {

	/**
	 * A source may contribute at most this many rows a day before new ones are dropped.
	 *
	 * <p>Counted in SQL on an indexed column, and the insert is skipped rather than rejected: a
	 * visitor being rate-limited should still reach the product page. The alternative — returning
	 * 429 — turns someone else's link spam into a broken shop.
	 */
	private static final int MAX_ROWS_PER_SOURCE_PER_DAY = 5_000;

	private final NamedParameterJdbcTemplate jdbc;

	public ShareClickRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Records one click, or quietly does nothing if this source is over its daily budget.
	 *
	 * @return whether a row was written, so a caller can tell silence from a dropped row
	 */
	public boolean record(UUID productId, String source) {
		Instant since = Instant.now().minusSeconds(24 * 60 * 60);

		Integer already = jdbc.queryForObject("""
				SELECT count(*) FROM share_click
				WHERE product_id = :productId AND source = :source AND clicked_at >= :since
				""", new MapSqlParameterSource()
				.addValue("productId", productId)
				.addValue("source", source)
				.addValue("since", java.time.OffsetDateTime.ofInstant(since,
						java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE),
				Integer.class);

		if (already != null && already >= MAX_ROWS_PER_SOURCE_PER_DAY) {
			return false;
		}

		jdbc.update("""
				INSERT INTO share_click (id, product_id, source, clicked_at, created_at)
				VALUES (:id, :productId, :source, :clickedAt, :clickedAt)
				""", new MapSqlParameterSource()
				.addValue("id", UUID.randomUUID())
				.addValue("productId", productId)
				.addValue("source", source)
				.addValue("clickedAt", java.time.OffsetDateTime.ofInstant(Instant.now(),
						java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE));

		return true;
	}

	/** Clicks per channel for one product, for the shop's "is sharing working?" panel. */
	public List<SourceCount> countsFor(UUID productId, Instant since) {
		return jdbc.query("""
				SELECT source, count(*) AS clicks
				FROM share_click
				WHERE product_id = :productId AND clicked_at >= :since
				GROUP BY source
				ORDER BY clicks DESC
				""", new MapSqlParameterSource()
				.addValue("productId", productId)
				.addValue("since", java.time.OffsetDateTime.ofInstant(since,
						java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE),
				(rs, n) -> new SourceCount(rs.getString("source"), rs.getLong("clicks")));
	}

	/** One channel's clicks. */
	public record SourceCount(String source, long clicks) {
	}
}