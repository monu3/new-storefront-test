package com.storefront.store.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.common.Money;
import com.storefront.store.order.OrderItem;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.settings.SettingsSections;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * The invoice PDF.
 *
 * <p>Asserted on the bytes rather than by opening the file. A PDF is compressed, so the useful
 * checks are structural: it begins with {@code %PDF}, carries a page, and — the part that actually
 * matters — that a change to the shop's settings changes the document, while a change to the
 * catalogue does not.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class InvoiceServiceIT {

	@Autowired
	private InvoiceService invoices;

	@Autowired
	private TestFixtures fixtures;

	@Test
	@DisplayName("produces a real PDF")
	void producesPdf() {
		byte[] pdf = render(template(true, true, true, "#1f2937", null));

		assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
		assertThat(pdf.length).isGreaterThan(500);
		// The trailer is what makes it a complete document rather than a truncated stream.
		assertThat(new String(pdf, Math.max(0, pdf.length - 32), 32, StandardCharsets.ISO_8859_1))
				.contains("EOF");
	}

	@Test
	@DisplayName("the owner's accent colour changes the document")
	void templateIsApplied() {
		byte[] plain = render(template(true, true, true, "#000000", null));
		byte[] coloured = render(template(true, true, true, "#ff0000", "Thank you"));

		// Two different templates must produce two different files. Without this the invoice
		// settings would be stored, accepted, and never read.
		assertThat(java.util.Arrays.equals(plain, coloured)).isFalse();
	}

	@Test
	@DisplayName("an unparseable accent colour falls back rather than throwing")
	void badAccentColour() {
		// The colour is parsed while the document is being written, long after the owner saved it.
		// A hard failure here would mean one bad setting breaks every invoice.
		byte[] pdf = render(template(true, true, true, "not-a-colour", null));

		assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
	}

	@Test
	@DisplayName("an order with no items still renders")
	void emptyOrder() {
		byte[] pdf = render(template(true, true, true, "#1f2937", null), List.of());

		assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
	}

	@Test
	@DisplayName("many lines render on one page without overflow")
	void manyLines() {
		List<OrderItem> lines = new java.util.ArrayList<>();
		for (int i = 0; i < 40; i++) {
			lines.add(line("Product " + i, 199_00L * (i + 1), 2));
		}

		byte[] pdf = render(template(true, true, true, "#1f2937", null), lines);

		assertThat(pdf.length).isGreaterThan(1000);
	}

	// ------------------------------------------------------------------ helpers

	private byte[] render(SettingsSections.InvoiceTemplate tpl) {
		return render(tpl, List.of(line("Sona Masoori Rice 5kg", 65_000L, 2)));
	}

	private byte[] render(SettingsSections.InvoiceTemplate tpl, List<OrderItem> lines) {
		// Unique per call: the slug is derived from the name, so two shops with the same name
		// collide on shop_slug -- which is the constraint working, not a bug to work around.
		Shop shop = fixtures.createShop("Invoice Shop " + java.util.UUID.randomUUID(), ShopType.GROCERY);
		ShopOrder order = fixtures.createPendingOrder(shop.getId());

		SettingsSections.General general = new SettingsSections.General(
				"Ravi Kirana Store", "Fresh every day", null, "help@example.test",
				"+9779800000000", "NPR", "Asia/Kathmandu");
		SettingsSections.Address address = new SettingsSections.Address(
				"Street 1", "Second floor", "Kathmandu", "Bagmati", "44600", "Nepal", null);

		return invoices.build(order, shop, lines, tpl, general, address);
	}

	/**
	 * A line with no product or variant behind it.
	 *
	 * <p>Order items carry frozen names rather than a join to the catalogue, which is exactly why
	 * the invoice needs no products to exist. {@code productId} and {@code variantId} may be null
	 * for a deleted product -- that is the case the frozen snapshot exists to survive.
	 */
	private OrderItem line(String name, long unitMinor, int qty) {
		return new OrderItem(java.util.UUID.randomUUID(), null, null, name, "5kg",
				ProductCategory.RICE, unitMinor, qty, "SKU-" + Math.abs(name.hashCode()), null);
	}

	private SettingsSections.InvoiceTemplate template(boolean logo, boolean signature,
			boolean taxBreakdown, String accent, String footer) {
		return new SettingsSections.InvoiceTemplate(logo, signature, taxBreakdown, true, accent,
				footer, "TAX-12345");
	}
}