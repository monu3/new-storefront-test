package com.storefront.store.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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

import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;
import com.storefront.store.wallet.WalletLedgerEntry;
import com.storefront.store.wallet.WalletService;

/**
 * Pickup orders (Phase 8).
 *
 * <p><b>The property worth protecting is that shipping becomes zero.</b> A pickup order that keeps
 * its delivery charge is a charge for a journey nobody makes, and it is invisible until somebody
 * notices the total.
 *
 * <p><b>And that the switch is refused after acceptance.</b> Once the shop has taken the commission
 * and reserved the stock, "actually this is a pickup" is a different decision from cancelling.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class PickupOrderIT {

	@Autowired
	private OrderService orders;

	@Autowired
	private WalletService wallet;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private OrderNumberService orderNumbers;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;
	private com.storefront.store.catalog.Product product;
	private com.storefront.store.catalog.ProductVariant variant;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop("Pickup Shop " + UUID.randomUUID(), ShopType.GROCERY);
		product = fixtures.createProduct(shop, "Collectable", ProductCategory.RICE, 10_000L);
		variant = fixtures.defaultVariantOf(product);
		fixtures.stock(variant, 20);
	}

	@Nested
	@DisplayName("switching an order to collection")
	class Switching {

		@Test
		@DisplayName("drops the shipping charge and recomputes the total")
		void dropsShipping() {
			ShopOrder order = place(2, 3_000L);
			assertThat(order.getTotalMinor()).isEqualTo(20_000L + 3_000L);

			ShopOrder pickup = orders.switchToPickup(shop, order, "Collect from the shop, 9–6",
					null);

			assertThat(pickup.isPickup()).isTrue();
			assertThat(pickup.getShippingMinor())
					.as("a pickup order that keeps its delivery charge is a charge for a journey "
							+ "nobody makes")
					.isZero();
			assertThat(pickup.getTotalMinor()).isEqualTo(20_000L);
			assertThat(pickup.getPickupNote()).isEqualTo("Collect from the shop, 9–6");
		}

		@Test
		@DisplayName("defaults to delivery, because that is what almost every order is")
		void defaultsToDelivery() {
			assertThat(place(1, 0).getFulfilmentMethod())
					.isEqualTo(ShopOrder.FulfilmentMethod.DELIVERY);
		}

		@Test
		@DisplayName("needs a note saying where or when to collect")
		void needsANote() {
			ShopOrder order = place(1, 0);

			assertThatThrownBy(() -> orders.switchToPickup(shop, order, "  ", null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("where or when to collect");
		}

		@Test
		@DisplayName("is refused once the shop has accepted — commission and stock are already taken")
		void refusedAfterAcceptance() {
			ShopOrder order = place(1, 0);
			wallet.credit(shop.getId(), WalletLedgerEntry.EntryType.TOPUP_APPROVED, 100_000L,
					"topup-" + UUID.randomUUID(), "fund", null, null, null);
			orders.accept(shop, order, null);

			assertThatThrownBy(() -> orders.switchToPickup(shop, order, "Come and get it", null))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("not PENDING");
		}
	}

	// ------------------------------------------------------------------ helpers

	/** A PENDING order for {@code units} of the stocked variant, with {@code shippingMinor} charged. */
	private ShopOrder place(int units, long shippingMinor) {
		AppUser customer = users.saveAndFlush(new AppUser(AppUser.Realm.CUSTOMERS,
				"sub-" + UUID.randomUUID(),
				"pickup-" + UUID.randomUUID() + "@example.test",
				"Pickup Buyer"));

		return orders.place(shop, new OrderService.PlacedOrder(
				orderNumbers.next(shop.getId()),
				ShopOrder.PaymentMethod.COD,
				10_000L * units, 0, 0, shippingMinor,
				"{\"city\":\"Kathmandu\"}",
				null,
				customer.getEmail(),
				"+9779800000000",
				customer.getId(),
				null, false,
				List.of(new OrderService.PlacedLine(product.getId(), variant.getId(),
						product.getName(), variant.getName(), ProductCategory.RICE,
						10_000L, units, null, null))),
				null);
	}
}
