package com.storefront.store.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductRepository;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;
import com.storefront.store.wallet.WalletTopUpRequest;
import com.storefront.store.theme.DefaultTemplates;
import com.storefront.store.theme.ThemeTemplate;
import com.storefront.store.theme.ThemeService;
import com.storefront.store.theme.ThemeTemplateRepository;

/**
 * Entitlements enforced where they actually matter (Phase 6).
 *
 * <p>An entitlement engine that nothing consults is documentation. These tests assert the two places
 * a shop would notice: creating a product past the cap, and installing a paid template.
 *
 * <p>Both are asserted in <em>both</em> directions. The free tier being refused is the obvious half;
 * the half that actually breaks in production is the refusal <b>lifting</b> when a shop upgrades, or
 * never being applied because the filter and the gate disagree.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class EntitlementEnforcementIT {

	@Autowired
	private ProductService products;

	@Autowired
	private ProductRepository productRows;

	@Autowired
	private EntitlementService entitlements;

	@Autowired
	private SubscriptionService subscriptions;

	@Autowired
	private ThemeService themes;

	@Autowired
	private ThemeTemplateRepository templates;

	@Autowired
	private SubscriptionPlanRepository plans;

	@Autowired
	private com.storefront.store.subscription.PlanEntitlementRepository planEntitlements;

	@Autowired
	private SubscriptionPaymentService payments;

	@Autowired
	private com.storefront.store.wallet.WalletTopUpService topUpService;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private AppUserRepository users;

	private Shop shop;
	private AppUser admin;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop("Gate " + UUID.randomUUID(), ShopType.GROCERY);
		admin = users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"admin-" + UUID.randomUUID(), "a-" + UUID.randomUUID() + "@example.test", "Admin"));
	}

	// ------------------------------------------------------------------ product cap

	@Test
	@DisplayName("a free shop cannot create a product past the free cap")
	void freeShopHitsTheCap() {
		assertThat(entitlements.limitOf(shop.getId(), FeatureKey.MAX_PRODUCTS)).hasValue(25L);

		// Fill to the cap. Created straight through the service so the cap is not the thing being
		// skipped -- the point is that the 26th is refused, which means the first 25 must succeed.
		for (int i = 0; i < 25; i++) {
			products.createDraft(shop, "Product " + i, ProductCategory.RICE, null, null, null);
		}
		assertThat(productRows.countByShopIdAndDeletedAtIsNull(shop.getId())).isEqualTo(25L);

		var refused = org.assertj.core.api.Assertions.catchThrowableOfType(
				() -> products.createDraft(shop, "One too many", ProductCategory.RICE, null, null, null),
				EntitlementService.EntitlementDeniedException.class);

		// The refusal has to name the numbers. "Limit reached" with no figures is the least actionable
		// thing a shop owner can be told.
		assertThat(refused).isNotNull();
		assertThat(refused.getMessage())
				.contains("25")
				.contains("allows 25");
		assertThat(refused.feature()).isEqualTo(FeatureKey.MAX_PRODUCTS);
		// And never offers the plan they are already on: a free shop told to "upgrade to Free
		// (Rs. 0.00)" has been told nothing, in the one message it will read carefully.
		assertThat(refused.upgradeToCode()).isNotBlank();
		assertThat(refused.upgradeToCode()).isNotEqualTo("FREE");

		// And nothing was written on the way to refusing.
		assertThat(productRows.countByShopIdAndDeletedAtIsNull(shop.getId())).isEqualTo(25L);
	}

	@Test
	@DisplayName("upgrading lifts the product cap")
	void upgradeLiftsTheCap() {
		subscribeTo(planWith(500L));

		for (int i = 0; i < 25; i++) {
			products.createDraft(shop, "Product " + i, ProductCategory.RICE, null, null, null);
		}
		// Would have been refused a moment ago.
		products.createDraft(shop, "Product 25", ProductCategory.RICE, null, null, null);
		products.createDraft(shop, "Product 26", ProductCategory.RICE, null, null, null);

		assertThat(productRows.countByShopIdAndDeletedAtIsNull(shop.getId())).isEqualTo(27L);
	}

	@Test
	@DisplayName("lapsing puts the product cap back")
	void lapseRestoresTheCap() {
		ShopSubscription active = subscribeTo(planWith(500L));

		for (int i = 0; i < 26; i++) {
			products.createDraft(shop, "Product " + i, ProductCategory.RICE, null, null, null);
		}
		assertThat(productRows.countByShopIdAndDeletedAtIsNull(shop.getId())).isEqualTo(26L);

		subscriptions.lapse(active, active.getExpiresAt().plusSeconds(1));

		// Over the cap again, and refused. The shop is not *punished* -- its 26 products stay, and
		// its storefront is unchanged -- but it may not add a 27th until it is under 25.
		assertThatThrownBy(() -> products.createDraft(
				shop, "After lapse", ProductCategory.RICE, null, null, null))
				.isInstanceOf(EntitlementService.EntitlementDeniedException.class)
				.hasMessageContaining("already have 26");

		// And the way back in is deleting something, not upgrading. A shop that lapsed and could not
		// sell because of it would be a shop forced to pay again just to carry on trading.
		//
		// Note the cap is "at most 25": sitting at exactly 25 still refuses a 26th, so it takes two
		// deletions to get back under. Asserted explicitly because "I have 25 and it says I may
		// have 25, why can't I add one?" is the natural reading and the wrong one.
		var two = products.listForShop(shop, null,
				org.springframework.data.domain.Pageable.ofSize(2)).getContent();

		products.delete(shop, two.get(0));
		assertThat(productRows.countByShopIdAndDeletedAtIsNull(shop.getId())).isEqualTo(25L);
		assertThatThrownBy(() -> products.createDraft(
				shop, "Still at the cap", ProductCategory.RICE, null, null, null))
				.isInstanceOf(EntitlementService.EntitlementDeniedException.class);

		products.delete(shop, two.get(1));
		assertThat(productRows.countByShopIdAndDeletedAtIsNull(shop.getId())).isEqualTo(24L);
		products.createDraft(shop, "After deleting two", ProductCategory.RICE, null, null, null);
		assertThat(productRows.countByShopIdAndDeletedAtIsNull(shop.getId())).isEqualTo(25L);
	}

	@Test
	@DisplayName("the cap counts drafts and ignores archived products")
	void whatTheCapCounts() {
		// Drafts count: otherwise a shop on the free tier creates unlimited drafts and the limit
		// never arrives, because only published rows are checked.
		for (int i = 0; i < 25; i++) {
			products.createDraft(shop, "Draft " + i, ProductCategory.RICE, null, null, null);
		}
		assertThatThrownBy(() -> products.createDraft(
				shop, "One too many", ProductCategory.RICE, null, null, null))
				.isInstanceOf(EntitlementService.EntitlementDeniedException.class);

		// Archiving does NOT free the allowance. An archived product is hidden from the storefront but
		// still live, still in order history, and one click from coming back -- so if archiving freed
		// the cap, a shop would archive all 25, create 25 more, un-archive, and hold 50.
		Shop another = fixtures.createVerifiedShop("Archive " + UUID.randomUUID(), ShopType.GROCERY);
		for (int i = 0; i < 25; i++) {
			products.createDraft(another, "P " + i, ProductCategory.RICE, null, null, null);
		}
		var some = products.listForShop(another, null,
				org.springframework.data.domain.Pageable.ofSize(1)).getContent().get(0);
		products.archive(another, some);

		assertThat(productRows.countByShopIdAndDeletedAtIsNull(another.getId())).isEqualTo(25L);
		assertThatThrownBy(() -> products.createDraft(
				another, "Replacement", ProductCategory.RICE, null, null, null))
				.isInstanceOf(EntitlementService.EntitlementDeniedException.class);

		// Deleting does free it -- the only thing that does.
		products.delete(another, some);
		assertThat(productRows.countByShopIdAndDeletedAtIsNull(another.getId())).isEqualTo(24L);
		products.createDraft(another, "Replacement", ProductCategory.RICE, null, null, null);
		assertThat(productRows.countByShopIdAndDeletedAtIsNull(another.getId())).isEqualTo(25L);
	}

	// ------------------------------------------------------------------ paid templates

	@Test
	@DisplayName("a free shop cannot install a paid template, even by crafting the request")
	void paidTemplateIsGated() {
		ThemeTemplate paid = paidTemplate();

		// The marketplace hides it, which is the honest UI...
		assertThat(themes.marketplaceFor(shop, false))
				.extracting(ThemeTemplate::getId)
				.doesNotContain(paid.getId());

		// ...but a hidden link is not a control. The install endpoint takes an id, and that is where
		// the tier has to actually be enforced.
		assertThatThrownBy(() -> themes.installTemplate(shop, paid, admin.getId()))
				.isInstanceOf(EntitlementService.EntitlementDeniedException.class)
				.hasMessageContaining("paid templates");
	}

	@Test
	@DisplayName("a subscribed shop can install a paid template")
	void paidTemplateUnlocks() {
		subscribeTo(planWith(null));

		ThemeTemplate paid = paidTemplate();

		assertThat(themes.marketplaceFor(shop, true))
				.extracting(ThemeTemplate::getId)
				.contains(paid.getId());

		var installed = themes.installTemplate(shop, paid, admin.getId());
		assertThat(installed.getTemplateId()).isEqualTo(paid.getId());
	}

	@Test
	@DisplayName("the tier is re-checked at install time, not read from the listing")
	void gateIsNotTheFilter() {
		// Subscribe, see it in the marketplace, then lapse without the sweep. The listing was
		// filtered on a subscription that has since ended; the install must refuse.
		ShopSubscription active = subscribeTo(planWith(null));
		ThemeTemplate paid = paidTemplate();

		subscriptions.lapse(active, active.getExpiresAt().plusSeconds(1));

		assertThatThrownBy(() -> themes.installTemplate(shop, paid, admin.getId()))
				.isInstanceOf(EntitlementService.EntitlementDeniedException.class);
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * A paid, published template.
	 *
	 * <p>Built through {@link ThemeService} rather than by hand, because a template only appears in
	 * the marketplace once it is published -- and the whole point of these tests is what happens at
	 * install time for a template a shop could actually see. A hand-built row would not be published
	 * and every test here would pass for the wrong reason.
	 */
	private ThemeTemplate paidTemplate() {
		ThemeTemplate created = themes.createTemplate(
				"paid-" + UUID.randomUUID().toString().substring(0, 6),
				"Paid " + UUID.randomUUID().toString().substring(0, 4),
				"Only for subscribers",
				null,
				DefaultTemplates.GENERIC,
				ThemeTemplate.PriceTier.SUBSCRIBED,
				admin.getId());

		return themes.publishTemplate(created.getId(), admin.getId());
	}

	/** A paid plan granting {@code maxProducts} (null for uncapped) plus the commission waiver. */
	private SubscriptionPlan planWith(Long maxProducts) {
		SubscriptionPlan plan = plans.saveAndFlush(new SubscriptionPlan(
				"GATE_" + UUID.randomUUID().toString().substring(0, 6),
				"Gated", SubscriptionPlan.BillingPeriod.MONTHLY, 200_000, 31));

		planEntitlements.deleteByPlanId(plan.getId());
		planEntitlements.flush();
		planEntitlements.saveAndFlush(new PlanEntitlement(
				plan, FeatureKey.MAX_PRODUCTS, true, maxProducts));
		planEntitlements.saveAndFlush(new PlanEntitlement(
				plan, FeatureKey.REMOVE_COMMISSION, true, null));
		planEntitlements.saveAndFlush(new PlanEntitlement(
				plan, FeatureKey.USE_PAID_TEMPLATES, true, null));
		return plan;
	}

	/** The whole D7 flow: request, submit the top-up, approve. */
	private ShopSubscription subscribeTo(SubscriptionPlan plan) {
		ShopSubscription requested = subscriptions.request(shop, plan);
		WalletTopUpRequest topUp = topUpService.requestForSubscription(shop.getId(), requested,
				WalletTopUpRequest.Method.BANK_TRANSFER, "REF", null, null);
		return payments.settle(topUp.getId(), admin.getId(), "paid");
	}
}
