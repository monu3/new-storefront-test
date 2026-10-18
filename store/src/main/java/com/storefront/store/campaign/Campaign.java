package com.storefront.store.campaign;

import java.time.Instant;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A scheduled or promotional campaign (Phase 8).
 *
 * <p><b>No stored status.</b> Live-ness is derived from {@link #isLiveAt(Instant)} against the clock,
 * so there is no sweeper to fall behind and no state in which the storefront shows a campaign the
 * database believes is scheduled.
 */
@Entity
@Table(name = "campaign")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Campaign extends BaseEntity {

	/** What a campaign does. Three kinds, because three is what the storefront can actually render. */
	public enum Kind {
		/** A percentage off the goods value. */
		DISCOUNT,
		/** Shipping becomes zero. */
		FREE_DELIVERY,
		/** An announcement. No money attached. */
		BANNER
	}

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** Null for a platform-wide campaign. Shops running their own is the ordinary case. */
	@Column(name = "shop_id", updatable = false)
	private UUID shopId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "description", columnDefinition = "text")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false)
	private Kind kind;

	/**
	 * Percentage in basis points: 500 is 5%.
	 *
	 * <p>Basis points rather than a {@code BigDecimal} percentage because commission is stored the
	 * same way (D31), and two representations of "a rate" in one codebase is how a 5% campaign
	 * quietly becomes 0.05%.
	 */
	@Column(name = "discount_bp")
	private Integer discountBp;

	@Column(name = "headline")
	private String headline;

	@Column(name = "body", columnDefinition = "text")
	private String body;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	/** Null means it runs until somebody stops it. */
	@Column(name = "ends_at")
	private Instant endsAt;

	// ------------------------------------------------------------------ derived state

	/**
	 * Whether the campaign is running at {@code at}.
	 *
	 * <p><b>Half-open: {@code startsAt <= at < endsAt}.</b> At the exact moment a campaign ends it is
	 * over. A closed interval would have it running for one instant too long, and an instant is
	 * enough for an order placed at midnight on the last day.
	 */
	public boolean isLiveAt(Instant at) {
		if (at.isBefore(startsAt)) {
			return false;
		}
		return endsAt == null || at.isBefore(endsAt);
	}

	public boolean isLiveNow() {
		return isLiveAt(Instant.now());
	}

	// ------------------------------------------------------------------ construction

	/**
	 * A campaign for one shop.
	 *
	 * <p>The window is validated here rather than by the database check alone, so a shop gets
	 * "the end date must be after the start date" instead of a constraint name.
	 */
	public static Campaign forShop(UUID shopId, String name, Kind kind, Integer discountBp,
			Instant startsAt, Instant endsAt, String headline, String body, String description) {

		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A campaign needs a name. An operator with six live "
					+ "campaigns and no names manages none of them.");
		}
		if (startsAt == null) {
			throw new IllegalArgumentException("A campaign needs a start. A promotion with no start "
					+ "date is one somebody has to remember to switch on.");
		}
		if (endsAt != null && !endsAt.isAfter(startsAt)) {
			throw new IllegalArgumentException(
					"A campaign must end after it starts, not at " + endsAt + " after " + startsAt
							+ ". An empty window is a campaign that never runs.");
		}
		if (kind == Kind.DISCOUNT && (discountBp == null || discountBp <= 0 || discountBp > 10_000)) {
			throw new IllegalArgumentException(
					"A discount campaign needs a rate between 1 and 10000 basis points, not "
							+ discountBp + ". Over 10000 is money the shop has not got.");
		}

		Campaign campaign = new Campaign();
		campaign.id = UUID.randomUUID();
		campaign.shopId = shopId;
		campaign.name = name.trim();
		campaign.kind = kind;
		campaign.discountBp = discountBp;
		campaign.startsAt = startsAt;
		campaign.endsAt = endsAt;
		campaign.headline = headline;
		campaign.body = body;
		campaign.description = description;
		return campaign;
	}

	/** Changes the window. Refused once a campaign has started, so nobody rewrites history. */
	public void reschedule(Instant startsAt, Instant endsAt) {
		if (Instant.now().isAfter(startsAt)) {
			throw new IllegalStateException(
					"This campaign has already started. Ending or extending it is a separate "
							+ "decision; changing the window now would rewrite what shoppers were "
							+ "shown.");
		}
		if (endsAt != null && !endsAt.isAfter(startsAt)) {
			throw new IllegalArgumentException("A campaign must end after it starts.");
		}

		this.startsAt = startsAt;
		this.endsAt = endsAt;
	}
}