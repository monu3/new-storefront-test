package com.storefront.store.campaign;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;



/**
 * Scheduled and promotional campaigns (Phase 8).
 *
 * <p><b>A window, not a cron expression.</b> The plan needs "next Tuesday to Sunday", and a window
 * is something an operator can read back and an admin can show a shop. A cron string in a database
 * column is a thing you cannot display and can only change by guessing.
 *
 * <p><b>Live-ness is derived from the clock, never stored.</b> A stored {@code status} column would
 * need a sweeper to keep it honest, and between the window opening and the sweeper running, a
 * campaign would be live and marked scheduled at the same time. Derived means the storefront asking
 * "what is running now" is always right without anybody having done anything.
 *
 * <p><b>Shop-owned by default.</b> Running your own promotion is the ordinary case; a platform-wide
 * campaign is the exception, and that is why {@code shopId} is nullable rather than required.
 */
@Service
public class CampaignService {

	private static final int MAX_DAYS = 365;

	private final CampaignRepository campaigns;

	public CampaignService(CampaignRepository campaigns) {
		this.campaigns = campaigns;
	}

	// ------------------------------------------------------------------ reads

	/** What a storefront should show right now: live campaigns, platform-wide first. */
	@Transactional(readOnly = true)
	public List<CampaignView> live(UUID shopId) {
		Instant now = Instant.now();

		return campaigns.liveFor(shopId, now).stream()
				.map(CampaignView::from)
				.toList();
	}

	/** A shop's own campaigns, newest first. */
	@Transactional(readOnly = true)
	public Page<CampaignView> forShop(UUID shopId, Pageable pageable) {
		return campaigns.findByShopIdOrderByStartsAtDesc(shopId, pageable).map(CampaignView::from);
	}

	/**
	 * Schedules a campaign for a shop.
	 *
	 * <p>Validation is the entity's, not this method's — an empty window is the same mistake whoever
	 * asks for it, and two places checking the same rule is one of them being wrong.
	 */
	@Transactional
	public Campaign create(UUID shopId, String name, Campaign.Kind kind, Integer discountBp,
			Instant startsAt, Instant endsAt, String headline, String body, String description) {

		return campaigns.save(Campaign.forShop(shopId, name, kind, discountBp, startsAt, endsAt,
				headline, body, description));
	}

	// ------------------------------------------------------------------ the one piece of arithmetic

	/**
	 * What a live campaign does to a quote.
	 *
	 * <p><b>Returned as a suggestion, applied by the quote service.</b> This method does not touch an
	 * order and does not touch the price — it says what a campaign is worth, and the caller decides.
	 * A promotion that recalculated totals in two places is a promotion whose totals disagree.
	 *
	 * <p><b>Only live campaigns count.</b> A discount that applies to a window that has closed is not
	 * a small bug: it is a shop quietly owing more money than it expected.
	 *
	 * @param subtotalMinor the goods value, before shipping and tax — the same base commission uses
	 *                      (D31), so a promotion and a commission never disagree about what was bought
	 * @return the effect, with {@code discountMinor} already floored to the subtotal so a 50%-off
	 *         campaign on a Rs. 10 cart cannot produce a negative charge
	 */
	@Transactional(readOnly = true)
	public CampaignEffect effectOf(Campaign campaign, long subtotalMinor) {
		if (!campaign.isLiveAt(Instant.now())) {
			return CampaignEffect.NONE;
		}

		return switch (campaign.getKind()) {
			case DISCOUNT -> {
				if (subtotalMinor <= 0) {
					yield CampaignEffect.NONE;
				}

				long discount = Math.floorDiv(subtotalMinor * campaign.getDiscountBp(), 10_000L);

				yield new CampaignEffect(campaign.getId(), discount, 0,
						// Floored to the subtotal: a 50%-off campaign on a Rs. 10 cart is a Rs. 10
						// discount at most, and negative money is not a discount.
						Math.min(discount, subtotalMinor));
			}
			case FREE_DELIVERY -> new CampaignEffect(campaign.getId(), 0, subtotalMinor, 0);
			case BANNER -> CampaignEffect.NONE;
		};
	}

	// ------------------------------------------------------------------ types

	/** What a campaign does to one quote. */
	public record CampaignEffect(
			UUID campaignId,
			long discountMinor,
			long freeShippingMinor,
			long appliedDiscountMinor) {

		static final CampaignEffect NONE = new CampaignEffect(null, 0, 0, 0);

		public boolean applies() {
			return appliedDiscountMinor > 0 || freeShippingMinor > 0;
		}
	}

	/** A campaign, for a screen. */
	public record CampaignView(
			UUID id,
			UUID shopId,
			String name,
			String description,
			String kind,
			Integer discountBp,
			String headline,
			String body,
			Instant startsAt,
			Instant endsAt,
			boolean liveNow,
			String display) {

		public static CampaignView from(Campaign campaign) {
			boolean live = campaign.isLiveAt(Instant.now());

			return new CampaignView(
					campaign.getId(), campaign.getShopId(), campaign.getName(),
					campaign.getDescription(), campaign.getKind().name(),
					campaign.getDiscountBp(), campaign.getHeadline(), campaign.getBody(),
					campaign.getStartsAt(), campaign.getEndsAt(), live,
					campaign.isLiveAt(Instant.now())
							? "Live now"
							: describeWindow(campaign));
		}

		/**
		 * "Live now", "Starts in 3 days", "Ended 2 weeks ago".
		 *
		 * <p>Relative rather than two bare timestamps, because the question an operator has about a
		 * campaign is "is it running", not "what were the dates".
		 */
		private static String describeWindow(Campaign campaign) {
			Instant now = Instant.now();

			if (now.isBefore(campaign.getStartsAt())) {
				return "Starts " + relative(campaign.getStartsAt(), now);
			}
			return "Ended " + relative(campaign.getEndsAt(), now);
		}

		private static String relative(Instant then, Instant now) {
			if (then == null) {
				return "never";
			}

			Duration gap = Duration.between(now, then).abs();

			if (gap.toDays() >= 1) {
				long days = gap.toDays();
				return days == 1 ? "tomorrow" : days + " days";
			}
			long hours = Math.max(1, gap.toHours());
			return hours == 1 ? "in an hour" : "in " + hours + " hours";
		}
	}
}