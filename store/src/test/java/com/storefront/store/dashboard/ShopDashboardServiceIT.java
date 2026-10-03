package com.storefront.store.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * The dashboard must report what the shop actually earned, and nothing else.
 *
 * <p>Every test is about a number a shop owner would act on. A dashboard that flatters a shop
 * into making a bad pricing decision is worse than one that says "you have made no sales".
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ShopDashboardServiceIT {

	@Autowired
	private ShopDashboardService dashboard;

	@Autowired
	private ProductService products;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Dash Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
	}

	@Test
	@DisplayName("a shop with no orders reports zeroes, not invented numbers")
	void emptyShopIsZero() {
		ShopDashboardService.DashboardSnapshot d = dashboard.forShop(shop);

		assertThat(d.totalRevenue().minor()).isZero();
		assertThat(d.sales()).isZero();
		assertThat(d.unitsSold()).isZero();
		assertThat(d.revenueChangePct())
				.as("no previous period means no comparison, not 'no change'")
				.isNull();
	}

	@Test
	@DisplayName("the month chart always has six points, even with no sales")
	void chartHasSixMonths() {
		// A chart that shrinks to nothing when a shop is new looks broken. Six zero bars reads as
		// "nothing sold yet", which is the truth.
		List<ShopDashboardService.MonthRevenue> revenue = dashboard.forShop(shop).revenueByMonth();

		assertThat(revenue).hasSize(6);
		assertThat(revenue).extracting(ShopDashboardService.MonthRevenue::amountMinor).containsOnly(0L);
		assertThat(revenue).extracting(ShopDashboardService.MonthRevenue::month)
				.hasSize(6);
	}

	@Test
	@DisplayName("counts only this shop's catalogue")
	void scopedToShop() {
		Shop other = fixtures.createVerifiedShop(
				"Other " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
		fixtures.createProduct(shop, "Mine", ProductCategory.RICE, 10_000L);
		fixtures.createProduct(shop, "Also Mine", ProductCategory.LENTIL, 10_000L);
		fixtures.createProduct(other, "Theirs", ProductCategory.RICE, 10_000L);

		assertThat(dashboard.forShop(shop).products())
				.as("another shop's product must not appear in my catalogue count")
				.isEqualTo(2);
		assertThat(dashboard.forShop(other).products()).isEqualTo(1);
	}

	@Test
	@DisplayName("splits published from draft products")
	void countsDraftsSeparately() {
		Product live = fixtures.createProduct(shop, "Live", ProductCategory.RICE, 10_000L);
		products.addVariant(shop, live, "Default", 10_000L, null,
				ProductService.VariantOptions.empty(), null, null);
		products.publish(shop, live);
		fixtures.createProduct(shop, "Draft", ProductCategory.LENTIL, 10_000L);

		ShopDashboardService.DashboardSnapshot d = dashboard.forShop(shop);

		assertThat(d.products()).isEqualTo(2);
		assertThat(d.publishedProducts()).isEqualTo(1);
		assertThat(d.draftProducts()).isEqualTo(1);
	}

	@Test
	@DisplayName("low stock counts variants at or below their own threshold")
	void lowStock() {
		Product product = fixtures.createProduct(shop, "Stocked", ProductCategory.RICE, 10_000L);
		fixtures.stock(fixtures.defaultVariantOf(product), 3);

		assertThat(dashboard.forShop(shop).lowStockVariants())
				.as("3 units against the default threshold of 5 is low")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a shop with no orders has nothing open")
	void openOrders() {
		assertThat(dashboard.forShop(shop).openOrders()).isZero();
	}

	@Test
	@DisplayName("sends money pre-formatted and as minor units")
	void moneyIsFormattedAndRaw() {
		// The card shows the formatted string; a chart would need the raw number. Shipping both
		// means the client never re-implements the formatting and gets it subtly wrong.
		ShopDashboardService.DashboardView view = dashboard.forShop(shop).toView();

		assertThat(view.totalRevenueDisplay()).contains("0");
		assertThat(view.totalRevenueMinor()).isZero();
	}

	@Test
	@DisplayName("traffic sources are absent rather than fabricated")
	void noFabricatedTrafficData() {
		// Nothing in the platform records where a visitor came from. A donut chart of plausible
		// percentages would be a lie an owner could make a marketing decision on.
		assertThat(ShopDashboardService.DashboardView.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("traffic"));
	}
}
