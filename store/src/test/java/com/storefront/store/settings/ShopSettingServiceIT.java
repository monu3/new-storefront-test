package com.storefront.store.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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

import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Settings: the shape, the defaults, and the two ways it can go wrong.
 *
 * <p>The uninteresting half is that a value round-trips. The interesting half is everything below
 * the save, because every one of those failures is silent:
 *
 * <ul>
 *   <li>a shop with no settings row must be indistinguishable from one full of defaults,</li>
 *   <li>JSON written by a newer console must survive a round trip through this backend,</li>
 *   <li>unreadable JSON must degrade to the default instead of failing a customer's checkout,</li>
 *   <li>the order-number counter must not be rewritable from a form.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ShopSettingServiceIT {

	@Autowired
	private ShopSettingService settings;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createShop("Settings Shop " + UUID.randomUUID(), ShopType.GROCERY);
	}

	// ------------------------------------------------------------------ defaults

	@Nested
	@DisplayName("a shop that has never opened its settings")
	class Defaults {

		@Test
		@DisplayName("sees defaults, not a missing row")
		void defaultsNotNull() {
			assertThat(settings.general(shop.getId())).isNotNull();
			assertThat(settings.address(shop.getId())).isNotNull();
			assertThat(settings.social(shop.getId())).isNotNull();
			assertThat(settings.checkout(shop.getId())).isNotNull();
			assertThat(settings.notifications(shop.getId())).isNotNull();
			assertThat(settings.orderNumber(shop.getId())).isNotNull();
			assertThat(settings.invoiceTemplate(shop.getId())).isNotNull();
			assertThat(settings.emailTemplates(shop.getId())).isNotNull();
		}

		@Test
		@DisplayName("accepts orders until told otherwise")
		void checkoutOpenByDefault() {
			var checkout = settings.checkout(shop.getId());
			assertThat(checkout.acceptOrders()).isTrue();
			assertThat(checkout.minOrderMinor()).isZero();
		}

		@Test
		@DisplayName("wants to hear about orders and low stock")
		void notificationsOnByDefault() {
			var notifications = settings.notifications(shop.getId());
			assertThat(notifications.newOrder()).isTrue();
			assertThat(notifications.lowStock()).isTrue();
			assertThat(notifications.lowStockThreshold()).isEqualTo(5);
		}

		@Test
		@DisplayName("numbers orders ORD-0001")
		void defaultOrderNumberFormat() {
			var format = settings.orderNumber(shop.getId());
			assertThat(format.render(1, Instant.parse("2026-01-01T00:00:00Z")))
					.isEqualTo("ORD-0001");
			assertThat(format.render(42, Instant.parse("2026-01-01T00:00:00Z")))
					.isEqualTo("ORD-0042");
		}
	}

	// ------------------------------------------------------------------ round trips

	@Nested
	@DisplayName("saving a tab")
	class RoundTrip {

		@Test
		@DisplayName("keeps the general tab")
		void general() {
			settings.writeSection(shop.getId(), SettingsSections.General.class,
					new SettingsSections.General("Ravi Kirana", "Fresh every day", "Since 2011",
							"help@example.test", "+9779800000000", "NPR", "Asia/Kathmandu"),
					"general");

			var read = settings.general(shop.getId());
			assertThat(read.displayName()).isEqualTo("Ravi Kirana");
			assertThat(read.tagline()).isEqualTo("Fresh every day");
			assertThat(read.timezone()).isEqualTo("Asia/Kathmandu");
		}

		@Test
		@DisplayName("keeps the address tab, including blank lines")
		void address() {
			settings.writeSection(shop.getId(), SettingsSections.Address.class,
					new SettingsSections.Address("Street 1", null, "Kathmandu", "Bagmati", "44600",
							"Nepal", "Ring road, second floor"),
					"address");

			var read = settings.address(shop.getId());
			assertThat(read.oneLine()).isEqualTo("Street 1, Kathmandu, Bagmati, 44600, Nepal");
			assertThat(read.isComplete()).isTrue();
		}

		@Test
		@DisplayName("closes the shop when the owner stops accepting orders")
		void checkout() {
			settings.writeSection(shop.getId(), SettingsSections.Checkout.class,
					new SettingsSections.Checkout(false, 50000L, false, false, false, "Call ahead"),
					"checkout");

			var read = settings.checkout(shop.getId());
			assertThat(read.acceptOrders()).isFalse();
			assertThat(read.minOrderMinor()).isEqualTo(50000L);
		}
	}

	@Test
	@DisplayName("writing one tab leaves the others alone")
	void tabsAreIndependent() {
		settings.writeSection(shop.getId(), SettingsSections.Social.class,
				new SettingsSections.Social("https://facebook.com/x", null, null, null, null, null),
				"social");

		// The classic lost-update bug: a form that loaded the document before the Social tab was
		// saved must not be able to blank it out by writing a different tab.
		settings.writeSection(shop.getId(), SettingsSections.Checkout.class,
				new SettingsSections.Checkout(true, 100L, true, true, true, null),
				"checkout");

		assertThat(settings.social(shop.getId()).facebookUrl()).isEqualTo("https://facebook.com/x");
		assertThat(settings.checkout(shop.getId()).minOrderMinor()).isEqualTo(100L);
	}

	@Test
	@DisplayName("an unknown section name is rejected rather than silently dropped")
	void unknownSection() {
		assertThatThrownBy(() -> settings.writeSection(shop.getId(), SettingsSections.Social.class,
				SettingsSections.Social.defaults(), "appearanceExtra"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown settings section");
	}

	// ------------------------------------------------------------------ order numbers

	@Nested
	@DisplayName("the order-number format")
	class OrderNumberFormat {

		@Test
		@DisplayName("renders a date-stamped number")
		void withDate() {
			var format = new SettingsSections.OrderNumber("ORD-{YYYY}-{####}", 1L);
			assertThat(format.render(7, Instant.parse("2026-03-04T00:00:00Z")))
					.isEqualTo("ORD-2026-0007");
		}

		@Test
		@DisplayName("pads to the width the format asks for")
		void paddingWidth() {
			var narrow = new SettingsSections.OrderNumber("ORD-{##}", 1L);
			assertThat(narrow.render(7, Instant.parse("2026-03-04T00:00:00Z"))).isEqualTo("ORD-07");

			var wide = new SettingsSections.OrderNumber("ORD-{#####}", 1L);
			assertThat(wide.render(7, Instant.parse("2026-03-04T00:00:00Z"))).isEqualTo("ORD-00007");
		}

		@Test
		@DisplayName("accepts more than one token")
		void multipleTokens() {
			// The pattern this replaces allowed exactly one token, which rejected the most natural
			// format of all. A test naming the failure keeps it fixed.
			var format = new SettingsSections.OrderNumber("ORD-{YYYY}-{####}", 1L);
			assertThat(format.render(7, Instant.parse("2026-03-04T00:00:00Z")))
					.isEqualTo("ORD-2026-0007");
		}

		@Test
		@DisplayName("renders month and day")
		void monthAndDay() {
			var format = new SettingsSections.OrderNumber("ORD-{YY}{MM}{DD}-{#}", 1L);
			assertThat(format.render(3, Instant.parse("2026-12-25T00:00:00Z")))
					.isEqualTo("ORD-261225-3");
		}
	}

	// ------------------------------------------------------------------ resilience

	@Test
	@DisplayName("JSON this backend does not recognise degrades to the default")
	void unknownFieldsAreTolerated() {
		// A newer console writes a field this backend has never heard of. It must not 500 on read.
		settings.writeRawForTest(shop.getId(), "general",
				"{\"displayName\":\"Future Shop\",\"somethingRemovedInAnOlderVersion\":{\"a\":1}}");

		var read = settings.general(shop.getId());
		assertThat(read.displayName()).isEqualTo("Future Shop");
	}

	/**
	 * JSON that is syntactically valid but the wrong shape.
	 *
	 * <p>Note what this is <em>not</em>: it is not a corrupt string. Postgres rejects those at the
	 * column, because {@code address jsonb} genuinely cannot hold {@code "this is not json"}.
	 * So the "unreadable JSON" case is only reachable for JSON that parses — a field of the wrong
	 * type, or an array where an object belongs. Asserting on a string Postgres would never store
	 * would test a database constraint, not this read path.
	 */
	@Test
	@DisplayName("well-formed JSON of the wrong shape degrades to the default")
	void wrongShapeDegrades() {
		settings.writeRawForTest(shop.getId(), "address", "[1, 2, 3]");

		// The customer's basket must still price. An owner whose settings the backend cannot read
		// should lose their address on the invoice, not their shop.
		assertThat(settings.address(shop.getId()).oneLine()).isEmpty();
	}

	/**
	 * A JSON object whose fields are of the wrong type — here a number where a string belongs.
	 *
	 * <p>This is the case a future or buggy console actually produces, and it is the one the
	 * lenient mapper has to absorb without a 500.
	 */
	@Test
	@DisplayName("a field of the wrong type degrades to the default")
	void wrongFieldTypeDegrades() {
		settings.writeRawForTest(shop.getId(), "checkout", "{\"minOrderMinor\":\"not a number\"}");

		assertThat(settings.checkout(shop.getId()).acceptOrders()).isTrue();
	}

	@Test
	@DisplayName("saving another tab does not break the order number")
	void savingAnotherTabKeepsOrderNumberUsable() {
		// The regression this exists for. A settings row is created lazily with
		// order_number = '{}', so saving the *General* tab left the order number reading back as a
		// null format -- and every subsequent checkout threw a NullPointerException while
		// rendering the next order number. The shop was left unable to sell because someone edited
		// their support email.
		settings.writeSection(shop.getId(), SettingsSections.General.class,
				new SettingsSections.General("Support Fix", null, null, null, null, "NPR", "Asia/Kathmandu"),
				"general");

		var format = settings.orderNumber(shop.getId());
		assertThat(format.format()).isEqualTo("ORD-{####}");
		assertThat(format.next()).isEqualTo(1L);
		assertThat(format.render(7, Instant.parse("2026-01-01T00:00:00Z"))).isEqualTo("ORD-0007");
	}

	@Test
	@DisplayName("an empty order-number section is indistinguishable from an absent one")
	void emptyOrderNumberSection() {
		settings.writeRawForTest(shop.getId(), "orderNumber", "{}");

		assertThat(settings.orderNumber(shop.getId()).render(1, Instant.parse("2026-01-01T00:00:00Z")))
				.isEqualTo("ORD-0001");
	}

	@Test
	@DisplayName("a missing JSON key takes the record's default, not zero")
	void missingKeysUseDefaults() {
		// Only the threshold is present: acceptOrders must still default to true.
		settings.writeRawForTest(shop.getId(), "checkout", "{\"autoAcceptOrders\":true}");

		assertThat(settings.checkout(shop.getId()).autoAcceptOrders()).isTrue();
	}
}