package com.storefront.store.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Campaigns (Phase 8).
 *
 * <p><b>The property worth protecting is that a closed window applies nothing.</b> A discount that
 * still applies after its campaign ended is not a display bug — it is a shop quietly owing more
 * money than it priced for, discovered at reconciliation rather than at the till.
 *
 * <p><b>Also pinned: a discount can never exceed the subtotal.</b> A 50%-off campaign on a Rs. 10
 * cart is a Rs. 10 discount at most, and negative money is not a discount.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class CampaignServiceIT {

	@Autowired
	private CampaignService campaigns;

	@Autowired
	private TestFixtures fixtures;

	private UUID shopId;

	@BeforeEach
	void setUp() {
		shopId = fixtures.createVerifiedShop("Campaign Shop " + UUID.randomUUID(), ShopType.GROCERY)
				.getId();
	}

	// ------------------------------------------------------------------ windows

	@Nested
	@DisplayName("when a campaign runs")
	class Windows {

		@Test
		@DisplayName("a campaign whose window has passed is not live")
		void endedCampaignIsNotLive() {
			Campaign over = campaigns.create(shopId, "Over", Campaign.Kind.DISCOUNT, 500,
					Instant.now().minus(10, ChronoUnit.DAYS),
					Instant.now().minus(2, ChronoUnit.DAYS), null, null, null);

			assertThat(over.isLiveNow()).isFalse();
			assertThat(campaigns.effectOf(over, 10_000).applies())
					.as("a discount that still applies after its campaign ended is a shop quietly "
							+ "owing more than it priced for")
					.isFalse();
		}

		@Test
		@DisplayName("a campaign whose window has not opened is not live")
		void futureCampaignIsNotLive() {
			Campaign future = campaigns.create(shopId, "Soon", Campaign.Kind.DISCOUNT, 500,
					Instant.now().plus(2, ChronoUnit.DAYS), null, null, null, null);

			assertThat(future.isLiveNow()).isFalse();
			assertThat(campaigns.effectOf(future, 10_000).applies()).isFalse();
		}

		@Test
		@DisplayName("a campaign with no end date runs until somebody stops it")
		void openEndedCampaignIsLive() {
			Campaign forever = campaigns.create(shopId, "Always", Campaign.Kind.BANNER, null,
					Instant.now().minus(1, ChronoUnit.DAYS), null, null, null, null);

			assertThat(forever.isLiveNow()).isTrue();
		}

		@Test
		@DisplayName("the end is exclusive — a campaign is over the instant it ends")
		void theEndIsExclusive() {
			Instant endsAt = Instant.now().minusSeconds(1);
			Campaign justEnded = campaigns.create(shopId, "Just", Campaign.Kind.DISCOUNT, 500,
					Instant.now().minus(1, ChronoUnit.HOURS), endsAt, null, null, null);

			assertThat(justEnded.isLiveAt(endsAt))
					.as("a closed interval leaves it running for one instant too long, and an "
							+ "instant is enough for an order placed at midnight on the last day")
					.isFalse();
		}

		@Test
		@DisplayName("refuses a window that ends before it starts")
		void refusesAnEmptyWindow() {
			Instant now = Instant.now();

			assertThatThrownBy(() -> campaigns.create(shopId, "Empty", Campaign.Kind.DISCOUNT, 500,
					now, now, null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must end after it starts");
		}
	}

	// ------------------------------------------------------------------ the arithmetic

	@Nested
	@DisplayName("what a campaign does to a price")
	class Effect {

		@Test
		@DisplayName("takes 5% off a live discount campaign's goods value")
		void takesTheRate() {
			Campaign live = campaigns.create(shopId, "Five off", Campaign.Kind.DISCOUNT, 500,
					Instant.now().minus(1, ChronoUnit.HOURS), null, null, null, null);

			var effect = campaigns.effectOf(live, 20_000);

			assertThat(effect.appliedDiscountMinor()).isEqualTo(1_000);
		}

		@Test
		@DisplayName("never discounts more than the goods are worth")
		void neverOverDiscounts() {
			// 100% off is the edge: the whole subtotal, and not a paisa more. The clamp is the thing
			// under test, so the rate is the largest one the enum allows rather than a middling one
			// that would never have reached it.
			Campaign live = campaigns.create(shopId, "Everything free", Campaign.Kind.DISCOUNT, 10_000,
					Instant.now().minus(1, ChronoUnit.HOURS), null, null, null, null);

			var effect = campaigns.effectOf(live, 10);

			assertThat(effect.appliedDiscountMinor())
					.as("a discount larger than the goods would be a negative charge, and negative "
							+ "money is not a discount")
					.isEqualTo(10);
		}

		@Test
		@DisplayName("rounds down, so a campaign never costs the shop a paisa more than intended")
		void roundsDown() {
			// 7% of 999 is 69.93. Rounded to 70 the shop gives away a paisa it did not intend to.
			Campaign live = campaigns.create(shopId, "Seven", Campaign.Kind.DISCOUNT, 700,
					Instant.now().minus(1, ChronoUnit.HOURS), null, null, null, null);

			assertThat(campaigns.effectOf(live, 999).appliedDiscountMinor()).isEqualTo(69);
		}

		@Test
		@DisplayName("free delivery gives back the shipping, not the goods")
		void freeDelivery() {
			Campaign live = campaigns.create(shopId, "Free ship", Campaign.Kind.FREE_DELIVERY, null,
					Instant.now().minus(1, ChronoUnit.HOURS), null, null, null, null);

			var effect = campaigns.effectOf(live, 20_000);

			assertThat(effect.freeShippingMinor()).isEqualTo(20_000);
			assertThat(effect.appliedDiscountMinor())
					.as("free delivery must not also discount the goods")
					.isZero();
		}

		@Test
		@DisplayName("a banner moves no money at all")
		void bannerMovesNoMoney() {
			Campaign banner = campaigns.create(shopId, "Sale on", Campaign.Kind.BANNER, null,
					Instant.now().minus(1, ChronoUnit.HOURS), null, null, null, null);

			assertThat(campaigns.effectOf(banner, 20_000).applies()).isFalse();
		}

		@Test
		@DisplayName("a discount on an empty basket is nothing, not a negative charge")
		void emptyBasket() {
			Campaign live = campaigns.create(shopId, "Five off", Campaign.Kind.DISCOUNT, 500,
					Instant.now().minus(1, ChronoUnit.HOURS), null, null, null, null);

			assertThat(campaigns.effectOf(live, 0).applies()).isFalse();
		}
	}

	// ------------------------------------------------------------------ validation

	@Nested
	@DisplayName("a campaign has to be valid to exist")
	class Validation {

		@Test
		@DisplayName("refuses a discount with no rate")
		void refusesNoRate() {
			assertThatThrownBy(() -> campaigns.create(shopId, "Free", Campaign.Kind.DISCOUNT, null,
					Instant.now(), null, null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("basis points");
		}

		@Test
		@DisplayName("refuses a discount over 100%")
		void refusesOverOneHundredPercent() {
			assertThatThrownBy(() -> campaigns.create(shopId, "Too much", Campaign.Kind.DISCOUNT,
					10_001, Instant.now(), null, null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("money the shop has not got");
		}

		@Test
		@DisplayName("refuses a campaign with no name — an operator with six live campaigns and no "
				+ "names manages none of them")
		void refusesNoName() {
			assertThatThrownBy(() -> campaigns.create(shopId, "  ", Campaign.Kind.BANNER, null,
					Instant.now(), null, null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("name");
		}
	}

	// ------------------------------------------------------------------ listing

	@Nested
	@DisplayName("what a storefront is shown")
	class Listing {

		@Test
		@DisplayName("only live campaigns, never the finished ones")
		void showsOnlyLive() {
			campaigns.create(shopId, "Live one", Campaign.Kind.BANNER, null,
					Instant.now().minus(1, ChronoUnit.HOURS), null, null, null, null);
			campaigns.create(shopId, "Finished one", Campaign.Kind.BANNER, null,
					Instant.now().minus(10, ChronoUnit.DAYS),
					Instant.now().minus(5, ChronoUnit.DAYS), null, null, null);

			var live = campaigns.live(shopId);

			assertThat(live).extracting(CampaignService.CampaignView::name)
					.containsExactly("Live one");
		}

		@Test
		@DisplayName("a shop's campaigns page lists everything, live and finished")
		void shopPageListsEverything() {
			campaigns.create(shopId, "Live one", Campaign.Kind.BANNER, null,
					Instant.now().minus(1, ChronoUnit.HOURS), null, null, null, null);
			campaigns.create(shopId, "Finished one", Campaign.Kind.BANNER, null,
					Instant.now().minus(10, ChronoUnit.DAYS),
					Instant.now().minus(5, ChronoUnit.DAYS), null, null, null);

			var page = campaigns.forShop(shopId, PageRequest.of(0, 25));

			assertThat(page.getTotalElements()).isEqualTo(2);
			assertThat(page.getContent()).extracting(CampaignService.CampaignView::liveNow)
					.as("a finished campaign must be listed, marked as finished -- it is history, "
							+ "and an operator needs to see what ran")
					.containsExactlyInAnyOrder(true, false);
		}

		@Test
		@DisplayName("says whether a campaign is live rather than showing two bare dates")
		void describesTheWindow() {
			campaigns.create(shopId, "Live one", Campaign.Kind.BANNER, null,
					Instant.now().minus(1, ChronoUnit.HOURS), null, null, null, null);

			assertThat(campaigns.live(shopId).get(0).display())
					.as("the question an operator has about a campaign is 'is it running', not "
							+ "'what were the dates'")
					.isEqualTo("Live now");
		}
	}
}