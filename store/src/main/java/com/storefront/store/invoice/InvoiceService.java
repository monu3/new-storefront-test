package com.storefront.store.invoice;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.common.Money;
import com.storefront.store.order.OrderItem;
import com.storefront.store.order.OrderService;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.order.ShopOrderRepository;
import com.storefront.store.settings.SettingsSections;
import com.storefront.store.settings.ShopSettingService;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders an order as a PDF invoice.
 *
 * <p><b>The money comes from the stored order, never from the shop's current catalogue.</b> A
 * price change, a product rename or a deleted line item must not rewrite an invoice that was
 * already issued. Everything printed here comes from the order's own frozen snapshot — the same
 * reason the shipping address is snapshotted rather than joined.
 *
 * <p><b>The layout is the owner's, from settings.</b> Logo, signature line, tax breakdown, payment
 * method, accent colour and footer note all come from
 * {@link SettingsSections.InvoiceTemplate}, so a shop can make its invoice look like its business
 * without a code change.
 *
 * <p><b>Rendered in memory, per request.</b> There is no cache. An invoice is produced a handful
 * of times in a shop's life and caching one means serving a stale document from the day it was
 * first downloaded, which for a document that goes to a customer's records is not a trade worth
 * making.
 *
 * <p><b>Money is formatted by {@link Money}, once.</b> The invoice, the storefront and the console
 * all call the same formatter, so a total cannot appear one way on screen and another way on the
 * PDF the customer files.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceService {

	private final ShopOrderRepository orders;
	private final OrderService orderService;
	private final ShopRepository shops;
	private final ShopSettingService settings;
	private final com.storefront.store.security.TenantGuard tenants;

	private static final DateTimeFormatter DATE =
			DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kathmandu"));

	/**
	 * The invoice PDF for an order the caller owns.
	 *
	 * @throws OrderNotFoundException when the order does not exist or is another shop's
	 */
	@Transactional(readOnly = true)
	public byte[] render(UUID orderId) {
		// Resolved through the same owned-lookup the console's own order endpoints use, so an
		// invoice cannot be rendered for another shop's order by guessing an id.
		Shop shop = tenants.requireOwnedShop();
		ShopOrder order = orderService.getOwned(shop, orderId);

		return build(order, shop, orderService.itemsOf(orderId),
				settings.invoiceTemplate(shop.getId()),
				settings.general(shop.getId()),
				settings.address(shop.getId()));
	}

	// ------------------------------------------------------------------ rendering

	/**
	 * Assembles the PDF.
	 *
	 * <p>Package-private and parameterised so a test can render an order with a synthetic template
	 * and shop without a database — the layout is the part worth testing and it is entirely a
	 * function of these five arguments.
	 */
	byte[] build(ShopOrder order, Shop shop, List<OrderItem> lines,
			SettingsSections.InvoiceTemplate template, SettingsSections.General general,
			SettingsSections.Address address) {

		Document doc = new Document(PageSize.A4, 42, 42, 48, 48);
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		try {
			PdfWriter.getInstance(doc, out);
			doc.open();

			Font accent = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, accentColour(template));
			Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
			Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
			Font small = FontFactory.getFont(FontFactory.HELVETICA, 8);

			// ---------------------------------------------------------------- header
			Paragraph heading = new Paragraph("INVOICE", accent);
			heading.setSpacingAfter(2f);
			doc.add(heading);

			Paragraph shopLine = new Paragraph(shopName(shop, general), bold);
			shopLine.setSpacingAfter(0f);
			doc.add(shopLine);

			if (!address.oneLine().isBlank()) {
				Paragraph shopAddress = new Paragraph(address.oneLine(), small);
				shopAddress.setSpacingAfter(0f);
				doc.add(shopAddress);
			}
			if (general.supportPhone() != null && !general.supportPhone().isBlank()) {
				doc.add(new Paragraph("Tel: " + general.supportPhone(), small));
			}
			if (general.supportEmail() != null && !general.supportEmail().isBlank()) {
				doc.add(new Paragraph(general.supportEmail(), small));
			}
			if (template.businessTaxId() != null && !template.businessTaxId().isBlank()) {
				doc.add(new Paragraph("Tax ID: " + template.businessTaxId(), small));
			}

			doc.add(gap(12f));

			// ---------------------------------------------------------------- meta table
			PdfPTable meta = twoColumnTable();
			meta.addCell(metaCell("Invoice number", bold, normal, order.getOrderNumber()));
			meta.addCell(metaCell("Date", bold, normal,
					order.getPlacedAt() == null ? "—" : DATE.format(order.getPlacedAt())));
			meta.addCell(metaCell("Payment method", bold, normal, paymentLabel(order)));
			meta.addCell(metaCell("Status", bold, normal, order.getStatus().name()));
			if (order.getPaymentStatus() != null) {
				meta.addCell(metaCell("Payment status", bold, normal, order.getPaymentStatus().name()));
			}
			doc.add(meta);

			doc.add(gap(14f));

			// ---------------------------------------------------------------- items
			PdfPTable items = new PdfPTable(new float[] { 1, 2.4f, 3.2f, 1f, 1.6f, 1.9f });
			items.setWidthPercentage(100);
			items.setSpacingBefore(0f);

			items.addCell(headerCell("Item", bold));
			items.addCell(headerCell("SKU", bold));
			items.addCell(headerCell("Description", bold));
			items.addCell(headerCell("Qty", bold));
			items.addCell(headerCell("Price", bold));
			items.addCell(headerCell("Amount", bold));

			for (OrderItem line : lines) {
				items.addCell(bodyCell(line.getProductName(), normal));
				items.addCell(bodyCell(nullToDash(line.getSku()), small));
				items.addCell(bodyCell(line.getVariantName(), small));
				items.addCell(bodyCell(String.valueOf(line.getQuantity()), normal));
				items.addCell(bodyCell(Money.ofMinor(line.getUnitPriceMinor()).toDisplay(), small));
				items.addCell(bodyCell(Money.ofMinor(line.getLineTotalMinor()).toDisplay(), normal));
			}
			doc.add(items);

			doc.add(gap(10f));

			// ---------------------------------------------------------------- totals
			// Right-aligned, and only as wide as it needs to be. A full-width totals table reads
			// as a second data table rather than as the sum of the one above it.
			PdfPTable totals = new PdfPTable(new float[] { 4f, 2f });
			totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
			totals.setWidthPercentage(55);

			totals.addCell(emptyCell());
			totals.addCell(emptyCell());

			totalRow(totals, "Subtotal", Money.ofMinor(order.getSubtotalMinor()), normal, false);
			if (order.getDiscountMinor() > 0) {
				totalRow(totals, "Discount", minus(Money.ofMinor(order.getDiscountMinor())), normal, false);
			}
			if (template.showTaxBreakdown()) {
				totalRow(totals, "Tax", Money.ofMinor(order.getTaxMinor()), normal, false);
			}
			totalRow(totals, "Delivery", Money.ofMinor(order.getShippingMinor()), normal, false);
			totalRow(totals, "Total", Money.ofMinor(order.getTotalMinor()), bold, true);

			doc.add(totals);

			// ---------------------------------------------------------------- footer
			if (template.showSignatureLine()) {
				doc.add(gap(34f));
				Paragraph signature = new Paragraph("_______________________", normal);
				signature.setAlignment(Element.ALIGN_RIGHT);
				doc.add(signature);
				Paragraph sigLabel = new Paragraph("Authorised signature", small);
				sigLabel.setAlignment(Element.ALIGN_RIGHT);
				doc.add(sigLabel);
			}

			if (template.footerNote() != null && !template.footerNote().isBlank()) {
				doc.add(gap(20f));
				Paragraph note = new Paragraph(template.footerNote(), small);
				note.setAlignment(Element.ALIGN_CENTER);
				doc.add(note);
			}

			// "Page 1" on every page, so a two-page line-item list is not mistaken for two
			// separate invoices by whoever filed it.
			doc.add(gap(16f));
			Paragraph generated = new Paragraph(
					"Generated " + DATE.format(java.time.Instant.now())
							+ " · Page " + doc.getPageNumber(), small);
			generated.setAlignment(Element.ALIGN_CENTER);
			doc.add(generated);

			doc.close();
		} catch (Exception ex) {
			// OpenPDF throws a checked DocumentException; wrapping it means the controller does not
			// have to declare a PDF library's exception type.
			throw new InvoiceRenderException("Could not render the invoice", ex);
		}
		return out.toByteArray();
	}

	// ------------------------------------------------------------------ pieces

	private void totalRow(PdfPTable table, String label, Money amount, Font font, boolean emphasised) {
		PdfPCell left = new PdfPCell(new Phrase(label, font));
		left.setBorder(emphasised ? PdfPCell.BOX : PdfPCell.NO_BORDER);
		if (emphasised) {
			left.setBorderWidth(0.5f);
		}
		left.setPadding(4f);
		table.addCell(left);

		PdfPCell right = new PdfPCell(new Phrase(amount.toDisplay(), font));
		right.setHorizontalAlignment(Element.ALIGN_RIGHT);
		right.setBorder(emphasised ? PdfPCell.BOX : PdfPCell.NO_BORDER);
		if (emphasised) {
			right.setBorderWidth(0.5f);
		}
		right.setPadding(4f);
		table.addCell(right);
	}

	private static PdfPTable twoColumnTable() {
		PdfPTable table = new PdfPTable(new float[] { 1, 1 });
		table.setWidthPercentage(100);
		return table;
	}

	private PdfPCell metaCell(String label, Font bold, Font normal, String value) {
		PdfPCell cell = new PdfPCell();
		cell.addElement(new Phrase(label + ": ", bold));
		cell.addElement(new Phrase(nullToDash(value), normal));
		cell.setBorder(PdfPCell.NO_BORDER);
		cell.setPadding(2f);
		return cell;
	}

	private PdfPCell headerCell(String text, Font bold) {
		PdfPCell cell = new PdfPCell(new Phrase(text, bold));
		cell.setBackgroundColor(java.awt.Color.LIGHT_GRAY);
		cell.setPadding(5f);
		return cell;
	}

	private PdfPCell bodyCell(String text, Font font) {
		PdfPCell cell = new PdfPCell(new Phrase(nullToDash(text), font));
		cell.setPadding(5f);
		return cell;
	}

	private static PdfPCell emptyCell() {
		PdfPCell cell = new PdfPCell();
		cell.setBorder(PdfPCell.NO_BORDER);
		return cell;
	}

	private static Paragraph gap(float height) {
		Paragraph p = new Paragraph();
		p.setSpacingBefore(height);
		return p;
	}

	private static Money minus(Money money) {
		return Money.ofMinor(-money.minor());
	}

	private static String nullToDash(String value) {
		return value == null || value.isBlank() ? "—" : value;
	}

	private static String paymentLabel(ShopOrder order) {
		if (order.getPaymentMethod() == null) {
			return "—";
		}
		return switch (order.getPaymentMethod()) {
			case ESAWA -> "eSewa";
			case COD -> "Cash on delivery";
			case BANK_TRANSFER -> "Bank transfer";
		};
	}

	private static String shopName(Shop shop, SettingsSections.General general) {
		return general.effectiveDisplayName(shop.getName());
	}

	/**
	 * Parses the owner's accent colour.
	 *
	 * <p>Fallen back to near-black on anything unparseable, because an invalid colour passed to
	 * OpenPDF throws while the document is being written — long after the owner saved the setting,
	 * and with no indication which setting was at fault.
	 */
	private static java.awt.Color accentColour(SettingsSections.InvoiceTemplate template) {
		try {
			return java.awt.Color.decode(template.accentColor());
		} catch (RuntimeException ex) {
			return java.awt.Color.DARK_GRAY;
		}
	}

	// ------------------------------------------------------------------ types

	/** The order is not this shop's, or does not exist. */
	public static class OrderNotFoundException extends RuntimeException {
		public OrderNotFoundException(UUID id) {
			super("No order " + id + " for this shop");
		}
	}

	/** The PDF could not be produced. Wraps OpenPDF's checked exception. */
	public static class InvoiceRenderException extends RuntimeException {
		public InvoiceRenderException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}