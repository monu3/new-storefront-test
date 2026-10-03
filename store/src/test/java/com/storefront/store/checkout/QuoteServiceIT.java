package com.storefront.store.checkout;

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

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * The quote engine.
 *
 * <p>The property that matters most: <b>the line totals sum to the order total exactly</b>. A
 * customer who adds up the lines and finds a one-paisa difference concludes the shop's
 * arithmetic is broken, and they are right to. That is asserted directly rather than inferred.
 *
 * <p>The second property: the client sends no prices. Every amount here comes from the database.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class QuoteServiceIT {

	@Autowired
	private QuoteService quotes;

	@Autowired
	private ProductService products;

	@Autowired
	private ShippingZoneRepository zones;

	@Autowired
	private TaxRuleRepository taxRules;

	@Autowired
	private DiscountRepository discounts;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;
	private Product rice;
	private ProductVariant riceSmall;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Quote Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);

		rice = fixtures.createProduct(shop, "Rice", ProductCategory.RICE, 10_000L);
		riceSmall = fixtures.defaultVariantOf(rice);
		fixtures.stock(riceSmall, 100);
	}

	private List<QuoteService.RequestedLine> oneLine(int quantity) {
		return List.of(new QuoteService.RequestedLine(riceSmall.getId(), quantity));
	}

	/**
	 * A published, stocked product at an awkward price.
	 *
	 * <p>The name carries a counter as well as the price, because the slug is unique per shop and
	 * two products priced alike would collide — which the database correctly refuses.
	 */
	private int oddCounter = 0;

	private ProductVariant oddPriced(long priceMinor, int stock) {
		Product odd = fixtures.createProduct(shop,
				"Odd " + priceMinor + " no " + (++oddCounter), ProductCategory.LENTIL, priceMinor);
		ProductVariant v = fixtures.defaultVariantOf(odd);
		fixtures.stock(v, stock);
		products.publish(shop, odd);
		return v;
	}

	// ------------------------------------------------------------------ basics

	@Nested
	@DisplayName("pricing a basket")
	class Basics {

		@Test
		@DisplayName("multiplies the database price by the quantity")
		void usesTheDatabasePrice() {
			publishRice();

			Quote quote = quotes.quote(shop, oneLine(3), null, null, null);

			assertThat(quote.subtotalMinor()).isEqualTo(30_000L);
			assertThat(quote.totalMinor()).isEqualTo(30_000L);
			assertThat(quote.lines()).hasSize(1);
			assertThat(quote.lines().get(0).unitPriceMinor()).isEqualTo(10_000L);
		}

		@Test
		@DisplayName("refuses an empty basket")
		void refusesEmpty() {
			publishRice();
			assertThatThrownBy(() -> quotes.quote(shop, List.of(), null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("cannot be empty");
		}

		@Test
		@DisplayName("refuses a quantity below one")
		void refusesZeroQuantity() {
			// The classic cart bug, and the one that produces an order total matching nothing the
			// customer saw.
			assertThatThrownBy(() -> quotes.quote(shop, oneLine(0), null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("at least 1");
		}

		@Test
		@DisplayName("refuses a basket of more than 50 lines")
		void refusesHugeBasket() {
			List<QuoteService.RequestedLine> huge = java.util.stream.IntStream.range(0, 51)
					.mapToObj(i -> new QuoteService.RequestedLine(riceSmall.getId(), 1)).toList();

			assertThatThrownBy(() -> quotes.quote(shop, huge, null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("more than 50");
		}

		@Test
		@DisplayName("refuses a variant from another shop")
		void refusesCrossShopVariant() {
			publishRice();
			Shop other = fixtures.createVerifiedShop(
					"Other " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
			Product theirs = fixtures.createProduct(other, "Theirs", ProductCategory.RICE, 5_000L);

			assertThatThrownBy(() -> quotes.quote(shop,
					List.of(new QuoteService.RequestedLine(
							fixtures.defaultVariantOf(theirs).getId(), 1)), null, null, null))
					.as("a basket spanning two shops is refused, not silently split")
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("not from this shop");
		}

		@Test
		@DisplayName("refuses an unpublished product")
		void refusesDraft() {
			// A draft must be invisible to a customer even by direct link.
			assertThatThrownBy(() -> quotes.quote(shop, oneLine(1), null, null, null))
					.as("the fixture product is a draft, so quoting it must fail")
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("a published product is quotable")
		void acceptsPublished() {
			products.addVariant(shop, rice, "Default", riceSmall.getPriceMinor(), null,
					ProductService.VariantOptions.empty(), null, null);
			products.publish(shop, rice);

			Quote quote = quotes.quote(shop, oneLine(2), null, null, null);
			assertThat(quote.subtotalMinor()).isEqualTo(20_000L);
		}

		@Test
		@DisplayName("flags a line the shop cannot fulfil, without refusing the quote")
		void flagsUnavailable() {
			publishRice();
			ProductVariant scarce = oddPriced(9_000L, 1);

			Quote quote = quotes.quote(shop, List.of(
					new QuoteService.RequestedLine(riceSmall.getId(), 1),
					new QuoteService.RequestedLine(scarce.getId(), 5)), null, null, null);

			assertThat(quote.lines()).hasSize(2);
			assertThat(quote.lines().stream().filter(l -> !l.purchasable()).count())
					.as("the customer is told what is unavailable; the order is refused later")
					.isEqualTo(1);
		}
	}

	private void publishRice() {
		products.publish(shop, rice);
	}

	// ------------------------------------------------------------------ arithmetic

	@Nested
	@DisplayName("the arithmetic")
	class Arithmetic {

		@Test
		@DisplayName("line totals sum to subtotal plus tax plus shipping, exactly")
		void linesSumExactly() {
			// The assertion the whole engine exists to make. Not "approximately": exactly.
			publishRice();
			ProductVariant a = oddPriced(3_333L, 100);
			ProductVariant b = oddPriced(7_777L, 100);
			ProductVariant c = oddPriced(1_001L, 100);
			zones.saveAndFlush(new ShippingZone(shop.getId(), "Kathmandu Valley", 15_000L));
			taxRules.saveAndFlush(new TaxRule(shop.getId(), "VAT", 1_300, false));

			Quote quote = quotes.quote(shop, List.of(
					new QuoteService.RequestedLine(a.getId(), 3),
					new QuoteService.RequestedLine(b.getId(), 2),
					new QuoteService.RequestedLine(c.getId(), 7),
					new QuoteService.RequestedLine(riceSmall.getId(), 1)),
					"Kathmandu", null, null);

			long lineSum = quote.lines().stream().mapToLong(Quote.QuoteLine::lineTotalMinor).sum();

			assertThat(lineSum)
					.as("line totals plus shipping must equal the order total with no stray paisa")
					.isEqualTo(quote.totalMinor() - quote.shippingMinor());
			assertThat(quote.subtotalMinor()).isEqualTo(3_333L * 3 + 7_777L * 2 + 1_001L * 7 + 10_000L);
			assertThat(quote.totalMinor())
					.isEqualTo(quote.subtotalMinor() - quote.discountMinor()
							+ quote.taxMinor() + quote.shippingMinor());
		}

		@Test
		@DisplayName("an undivisible discount is spread so the parts still add up")
		void discountDistributesExactly() {
			// 1000 across three equal lines is 333.33 each. Money.distribute hands the leftover
			// paisa to the earliest lines, so the sum is exact by construction rather than by luck.
			publishRice();
			ProductVariant b = oddPriced(10_000L, 100);
			ProductVariant c = oddPriced(10_000L, 100);
			discounts.saveAndFlush(new Discount(shop.getId(), "TENOFF", "Ten off", Discount.Type.FIXED, 1_000L));

			Quote quote = quotes.quote(shop, List.of(
					new QuoteService.RequestedLine(riceSmall.getId(), 1),
					new QuoteService.RequestedLine(b.getId(), 1),
					new QuoteService.RequestedLine(c.getId(), 1)),
					null, "TENOFF", null);

			assertThat(quote.discountMinor()).isEqualTo(1_000L);
			assertThat(quote.lines().stream().mapToLong(Quote.QuoteLine::discountMinor).sum())
					.as("the per-line shares must add back to the order discount")
					.isEqualTo(1_000L);
			assertThat(quote.lines().stream().mapToLong(Quote.QuoteLine::lineTotalMinor).sum())
					.isEqualTo(quote.totalMinor() - quote.shippingMinor());
		}

		@Test
		@DisplayName("tax is charged on the post-discount amount")
		void taxOnPostDiscountAmount() {
			// Taxing the pre-discount amount would overcharge the customer and the tax authority.
			publishRice();
			taxRules.saveAndFlush(new TaxRule(shop.getId(), "VAT", 1_300, false));
			discounts.saveAndFlush(new Discount(shop.getId(), "HALF", "Half off",
					Discount.Type.PERCENT, 5_000L));

			Quote quote = quotes.quote(shop, oneLine(10), null, "HALF", null);

			assertThat(quote.subtotalMinor()).isEqualTo(100_000L);
			assertThat(quote.discountMinor()).isEqualTo(50_000L);
			assertThat(quote.taxMinor())
					.as("13% of 50_000, not of 100_000")
					.isEqualTo(6_500L);
		}

		@Test
		@DisplayName("an inclusive tax is extracted from the price, not added to it")
		void inclusiveTaxIsExtracted() {
			// Getting this backwards overcharges every customer by the tax amount.
			publishRice();
			taxRules.saveAndFlush(new TaxRule(shop.getId(), "VAT", 1_300, true));

			Quote quote = quotes.quote(shop, oneLine(1), null, null, null);

			assertThat(quote.subtotalMinor()).isEqualTo(10_000L);
			assertThat(quote.totalMinor())
					.as("an inclusive tax is already inside the price, so the total does not rise")
					.isEqualTo(10_000L);
			assertThat(quote.taxMinor())
					.as("nothing was added on top, so the order-level tax is zero")
					.isZero();
			assertThat(quote.lines().get(0).taxMinor())
					.as("but the line still reports what part of the price was tax, "
							+ "so the shop can file a return and the customer sees a breakdown")
					.isEqualTo(1_150L);
			assertThat(quote.lines().get(0).lineTotalMinor())
					.as("the two figures are different numbers on purpose; the total uses the first")
					.isEqualTo(10_000L);
		}

		@Test
		@DisplayName("a free-shipping discount zeroes delivery rather than offsetting it")
		void freeShippingZeroesDelivery() {
			// Otherwise the customer sees a delivery line minus an offset that looks like an
			// arithmetic mistake.
			publishRice();
			zones.saveAndFlush(new ShippingZone(shop.getId(), "Default", 20_000L));
			discounts.saveAndFlush(new Discount(shop.getId(), "FREESHIP", "Free delivery",
					Discount.Type.FREE_SHIPPING, 0));

			Quote quote = quotes.quote(shop, oneLine(1), null, "FREESHIP", null);

			assertThat(quote.shippingMinor()).isZero();
			assertThat(quote.appliedDiscount().getName()).isEqualTo("Free delivery");
		}

		@Test
		@DisplayName("a percentage discount is capped by max_discount")
		void percentageIsCapped() {
			// 50% off a big order is a lot of money; the cap is what stops a mistyped code from
			// becoming a liability.
			publishRice();
			Discount big = new Discount(shop.getId(), "HALF", "Half off", Discount.Type.PERCENT, 5_000L);
			big.setMaxDiscountMinor(2_000L);
			discounts.saveAndFlush(big);

			Quote quote = quotes.quote(shop, oneLine(10), null, "HALF", null);

			assertThat(quote.subtotalMinor()).isEqualTo(100_000L);
			assertThat(quote.discountMinor()).isEqualTo(2_000L);
		}

		@Test
		@DisplayName("a discount never exceeds the subtotal")
		void discountNeverExceedsSubtotal() {
			publishRice();
			discounts.saveAndFlush(new Discount(shop.getId(), "HUGE", "Huge", Discount.Type.FIXED,
					999_999L));

			Quote quote = quotes.quote(shop, oneLine(1), null, "HUGE", null);

			assertThat(quote.discountMinor())
					.as("a fixed discount larger than the order would make a negative total")
					.isEqualTo(10_000L);
			assertThat(quote.totalMinor()).isZero();
		}
	}

	// ------------------------------------------------------------------ discounts

	@Nested
	@DisplayName("discount codes")
	class Codes {

		@Test
		@DisplayName("is case-insensitive, because it is typed by hand on a phone")
		void caseInsensitive() {
			publishRice();
			discounts.saveAndFlush(new Discount(shop.getId(), "SAVE10", "Ten off", Discount.Type.FIXED,
					1_000L));

			Quote quote = quotes.quote(shop, oneLine(1), null, "save10", null);
			assertThat(quote.discountMinor()).isEqualTo(1_000L);
		}

		@Test
		@DisplayName("an unknown code charges full price and explains itself")
		void unknownCodeDoesNotFailCheckout() {
			// A customer with a typo in their code should still be able to buy what is in their
			// basket. Being shown an error for a coupon they cannot control is worse than quietly
			// not applying it.
			publishRice();

			Quote quote = quotes.quote(shop, oneLine(1), null, "NOPE", null);

			assertThat(quote.discountMinor()).isZero();
			assertThat(quote.totalMinor()).isEqualTo(10_000L);
			assertThat(quote.discountMessage()).isEqualTo("That code is not valid");
		}

		@Test
		@DisplayName("a code below its minimum order explains the minimum")
		void minimumOrderExplained() {
			publishRice();
			Discount min = new Discount(shop.getId(), "BIG", "Big spend", Discount.Type.FIXED, 5_000L);
			min.setMinOrderMinor(50_000L);
			discounts.saveAndFlush(min);

			Quote quote = quotes.quote(shop, oneLine(1), null, "BIG", null);

			assertThat(quote.discountMinor()).isZero();
			assertThat(quote.discountMessage())
					.as("the message has to say what the customer would have to spend")
					.contains("Rs. 500");
		}

		@Test
		@DisplayName("one shop's code does not work at another")
		void codesAreShopScoped() {
			// An obvious coupon-system hole, closed structurally by the repository lookup.
			publishRice();
			Shop other = fixtures.createVerifiedShop(
					"Other " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
			Product theirProduct = fixtures.createProduct(other, "Theirs", ProductCategory.RICE, 1_000L);
			// Published on its own shop; publishing it on `shop` would (correctly) fail the
			// ownership check.
			products.publish(other, theirProduct);
			discounts.saveAndFlush(new Discount(other.getId(), "ONLYHERE", "Theirs",
					Discount.Type.FIXED, 100L));

			// Quote from the other shop: the code works there.
			assertThat(quotes.quote(other, List.of(new QuoteService.RequestedLine(
					fixtures.defaultVariantOf(theirProduct).getId(), 1)), null, "ONLYHERE", null)
					.discountMinor())
					.isEqualTo(100L);

			// And from this one, whose basket cannot even contain their product, it does not.
			assertThat(quotes.quote(shop, oneLine(1), null, "ONLYHERE", null).discountMinor())
					.as("one shop's code must not be redeemable at another")
					.isZero();
		}

		@Test
		@DisplayName("an automatic discount applies with no code")
		void automaticDiscount() {
			publishRice();
			discounts.saveAndFlush(new Discount(shop.getId(), null, "Welcome 5%",
					Discount.Type.PERCENT, 500L));

			Quote quote = quotes.quote(shop, oneLine(2), null, null, null);

			assertThat(quote.discountMinor()).isEqualTo(1_000L);
			assertThat(quote.appliedDiscount().getName()).isEqualTo("Welcome 5%");
		}
	}

	// ------------------------------------------------------------------ shipping

	@Nested
	@DisplayName("shipping")
	class Shipping {

		@Test
		@DisplayName("charges the zone that covers the delivery city")
		void chargesTheCityZone() {
			publishRice();
			ShippingZone valley = new ShippingZone(shop.getId(), "Valley", 10_000L);
			valley.setCities(List.of("Kathmandu", "Lalitpur"));
			zones.saveAndFlush(valley);
			zones.saveAndFlush(new ShippingZone(shop.getId(), "Default", 25_000L));

			assertThat(quotes.quote(shop, oneLine(1), "Kathmandu", null, null).shippingMinor())
					.isEqualTo(10_000L);
			assertThat(quotes.quote(shop, oneLine(1), "Pokhara", null, null).shippingMinor())
					.as("an unmatched city falls through to the shop's default zone")
					.isEqualTo(25_000L);
		}

		@Test
		@DisplayName("city matching ignores case and surrounding spaces")
		void cityMatchIsForgiving() {
			publishRice();
			ShippingZone valley = new ShippingZone(shop.getId(), "Valley", 10_000L);
			valley.setCities(List.of("Kathmandu"));
			zones.saveAndFlush(valley);
			zones.saveAndFlush(new ShippingZone(shop.getId(), "Default", 25_000L));

			assertThat(quotes.quote(shop, oneLine(1), "  kATHmandu ", null, null).shippingMinor())
					.isEqualTo(10_000L);
		}

		@Test
		@DisplayName("a shop with no zones charges nothing")
		void noZonesMeansFree() {
			publishRice();
			assertThat(quotes.quote(shop, oneLine(1), "Kathmandu", null, null).shippingMinor()).isZero();
		}

		@Test
		@DisplayName("free delivery applies at or above the threshold")
		void freeOverThreshold() {
			publishRice();
			ShippingZone valley = new ShippingZone(shop.getId(), "Valley", 10_000L);
			valley.setFreeOverMinor(50_000L);
			zones.saveAndFlush(valley);

			assertThat(quotes.quote(shop, oneLine(4), null, null, null).shippingMinor())
					.as("40_000 is below the 50_000 threshold")
					.isEqualTo(10_000L);
			assertThat(quotes.quote(shop, oneLine(5), null, null, null).shippingMinor())
					.as("'free from Rs. 500' means free AT 500, not above it")
					.isZero();
		}
	}
}
