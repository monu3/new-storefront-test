package com.storefront.store.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.notification.OutboxEvent;
import com.storefront.store.notification.OutboxEventRepository;
import com.storefront.store.settings.SettingsSections;
import com.storefront.store.settings.ShopSettingService;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Low-stock warnings.
 *
 * <p>The behaviour worth protecting is <b>warn once, on the crossing</b>. A variant resting at zero
 * would otherwise re-notify on every sale, and a shop with a permanently out-of-stock item would
 * generate an unbounded stream of identical emails. The other half is that turning notifications
 * off means silence rather than a preference that is stored and then ignored.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class LowStockNotifierIT {

	@Autowired
	private LowStockNotifier notifier;

	@Autowired
	private OutboxEventRepository outbox;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private ProductService products;

	@Autowired
	private ShopSettingService settings;

	private Shop shop;
	private Product product;
	private ProductVariant variant;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop("Low Stock " + UUID.randomUUID(), ShopType.GROCERY);
		product = fixtures.createProduct(shop, "Rice", ProductCategory.RICE, 10_000L);
		variant = fixtures.defaultVariantOf(product);
		fixtures.stock(variant, 100);
		products.publish(shop, product);
	}

	private long lowStockEvents() {
		return outbox.findAll().stream()
				.filter(e -> "LOW_STOCK".equals(e.getEventType()))
				.count();
	}

	/** The caller's product and variant, with names the message can actually use. */
	private String productName() {
		return "Sona Masoori Rice 5kg";
	}

	@Nested
	@DisplayName("crossing into low")
	class Crossing {

		@Test
		@DisplayName("emits one event naming the product")
		void emits() {
			notifier.check(shop.getId(), product.getId(), variant.getId(), productName(), "5kg",
					3, 5, false);

			assertThat(lowStockEvents()).isEqualTo(1);
			var event = outbox.findAll().stream()
					.filter(e -> "LOW_STOCK".equals(e.getEventType()))
					.findFirst()
					.orElseThrow();
			// A notification reading "Product 3f2a..." tells a shop owner nothing.
			assertThat(event.getPayload()).contains(productName()).contains("\"available\":3");
		}

		@Test
		@DisplayName("says nothing when the change did not cross the line")
		void noCrossing() {
			notifier.check(shop.getId(), product.getId(), variant.getId(), productName(), "5kg",
					40, 5, false);

			assertThat(lowStockEvents()).isZero();
		}

		@Test
		@DisplayName("stays quiet while it is already low")
		void doesNotRepeat() {
			// The case that matters most in practice: a shop out of its most popular item would
			// otherwise be emailed on every single sale of it.
			notifier.check(shop.getId(), product.getId(), variant.getId(), productName(), "5kg",
					0, 5, true);

			assertThat(lowStockEvents()).isZero();
		}
	}

	@Nested
	@DisplayName("the owner's preference")
	class Preference {

		@Test
		@DisplayName("turning low stock off means silence")
		void optOut() {
			settings.writeSection(shop.getId(), SettingsSections.Notifications.class,
					new SettingsSections.Notifications(true, false, 5, false), "notifications");

			notifier.check(shop.getId(), product.getId(), variant.getId(), productName(), "5kg",
					3, 5, false);

			// A preference that is stored and then ignored is worse than none: the owner believes
			// they have silenced it.
			assertThat(lowStockEvents()).isZero();
		}

		@Test
		@DisplayName("leaving it on by default warns")
		void optInByDefault() {
			assertThat(settings.notifications(shop.getId()).lowStock()).isTrue();

			notifier.check(shop.getId(), product.getId(), variant.getId(), productName(), "5kg",
					1, 5, false);

			assertThat(lowStockEvents()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("the event is addressed to the shop, not a customer")
	void addressedToTheShop() {
		notifier.check(shop.getId(), product.getId(), variant.getId(), productName(), "5kg", 2, 5, false);

		var event = outbox.findAll().stream()
				.filter(e -> "LOW_STOCK".equals(e.getEventType()))
				.findFirst()
				.orElseThrow();

		// Aggregate SHOP, not ORDER: a stock warning has no order behind it, and the notification
		// worker reads the aggregate type to decide where it goes.
		assertThat(event.getAggregateType()).isEqualTo(OutboxEvent.SHOP);
	}
}