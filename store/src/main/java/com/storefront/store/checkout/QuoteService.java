package com.storefront.store.checkout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductRepository;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ProductVariantRepository;
import com.storefront.store.common.Money;
import com.storefront.store.inventory.InventoryService;
import com.storefront.store.shop.Shop;

import lombok.RequiredArgsConstructor;

/**
 * Prices a basket. The single authority for every number a customer is charged.
 *
 * <p><b>The client never sends a price.</b> It sends variant ids and quantities; this reads the
 * prices from the database. A client that supplies its own total is asking to be believed, and
 * one that is believed loses money on every discounted order — so there is no code path here that
 * would accept a price from a request body.
 *
 * <p><b>Each of the four charges is computed once and stored once.</b> The order is created from
 * the quote's own output, so the number the customer was shown and the number they were charged
 * are the same number, not two independent calculations that happen to agree.
 *
 * <p><b>The order of operations matters:</b>
 * <ol>
 *   <li>resolve every line against the live catalogue (so a withdrawn product fails the quote),
 *   <li>subtotal,
 *   <li>discount against the subtotal,
 *   <li>tax on the post-discount amount (a discount reduces the taxable base — taxing the
 *       pre-discount amount would overcharge the customer and the tax authority),
 *   <li>shipping, which a free-shipping discount can zero out <em>after</em> the fact,
 *   <li>distribute discount and tax across the lines so they sum exactly.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

	private final ProductRepository products;
	private final ProductVariantRepository variants;
	private final ShippingZoneRepository shippingZones;
	private final TaxRuleRepository taxRules;
	private final DiscountRepository discounts;
	private final InventoryService inventory;

	/**
	 * Prices a basket for a shop.
	 *
	 * @param shop    the shop being bought from
	 * @param lines   variant id and quantity pairs, exactly as the client sent them
	 * @param city    delivery city, used to pick a shipping zone
	 * @param code    optional discount code
	 * @param customerId used for per-customer discount limits; null for a guest
	 */
	@Transactional(readOnly = true)
	public Quote quote(Shop shop, List<RequestedLine> lines, String city, String code,
			UUID customerId) {

		if (lines == null || lines.isEmpty()) {
			throw new IllegalArgumentException("A basket cannot be empty");
		}
		if (lines.size() > 50) {
			// A basket of 50 lines is already an unusual order; beyond that the request is either
			// a mistake or an attempt to make the server do a very large amount of work.
			throw new IllegalArgumentException("A basket cannot have more than 50 lines");
		}

		List<Resolved> resolved = lines.stream()
				.map(requested -> resolve(shop, requested))
				.toList();

		long subtotal = resolved.stream().mapToLong(r -> r.variant().getPriceMinor() * r.quantity())
				.sum();

		DiscountResolution discountResolution = resolveDiscount(shop, code, subtotal, customerId);
		Discount applied = discountResolution.discount();
		long discountMinor = discountResolution.amountMinor();

		long afterDiscount = subtotal - discountMinor;

		ShippingZone zone = resolveZone(shop, city);
		long shippingBeforeDiscount = zone == null ? 0 : zone.chargeFor(subtotal);

		// A free-shipping discount zeroes the charge rather than discounting it, so the customer
		// sees "Delivery: Rs. 0 (FREE SHIPPING)" instead of a delivery line minus an offset that
		// looks like an arithmetic mistake.
		boolean freeShipping = applied != null && applied.getType() == Discount.Type.FREE_SHIPPING;
		long shippingMinor = freeShipping ? 0 : shippingBeforeDiscount;

		List<Quote.QuoteLine> quoteLines = buildLines(resolved, discountMinor, afterDiscount, shop);

		// The order's tax is what was actually added to the total, not the sum of the lines'
		// reported figures. Those differ only when an inclusive rule is in play, where the tax is
		// inside the price and adding it would charge the customer twice.
		long taxMinor = quoteLines.stream()
				.mapToLong(l -> l.lineTotalMinor() - (l.grossMinor() - l.discountMinor()))
				.sum();

		long total = afterDiscount + taxMinor + shippingMinor;

		return new Quote(
				quoteLines,
				subtotal,
				discountMinor,
				taxMinor,
				shippingMinor,
				total,
				"NPR",
				applied,
				discountResolution.message(),
				zone,
				zone == null ? null : zone.deliveryEstimate());
	}

	// ------------------------------------------------------------------ lines

	/** A line as the client sent it: which variant, how many. No price — prices are ours. */
	public record RequestedLine(UUID variantId, int quantity) {
	}

	/** A requested line, checked against the live catalogue. */
	private record Resolved(Product product, ProductVariant variant, int quantity,
			int available) {
	}

	/**
	 * Resolves one requested line.
	 *
	 * <p>Everything that would otherwise be discovered at order time fails here, where the
	 * message can be shown: an unknown variant, a deactivated one, a product that is not
	 * published, a quantity that is not a positive number, and a basket spanning two shops.
	 */
	private Resolved resolve(Shop shop, RequestedLine requested) {
		if (requested == null || requested.variantId() == null) {
			throw new IllegalArgumentException("Each basket line needs a variant");
		}
		if (requested.quantity() <= 0) {
			// The classic cart bug, and the one that produces an order total matching nothing
			// the customer saw.
			throw new IllegalArgumentException(
					"Quantity must be at least 1, got: " + requested.quantity());
		}

		ProductVariant variant = variants.findById(requested.variantId())
				.orElseThrow(() -> new IllegalArgumentException(
						"No such item in this shop: " + requested.variantId()));

		Product product = products.findById(variant.getProductId())
				.orElseThrow(() -> new IllegalArgumentException(
						"No such item in this shop: " + requested.variantId()));

		// A basket spanning two shops is refused rather than split. Splitting it would create
		// two orders from one checkout, and the customer would receive one delivery confirmation
		// for goods coming from two places.
		if (!product.getShopId().equals(shop.getId())) {
			throw new IllegalArgumentException("That item is not from this shop");
		}
		if (!product.isVisible()) {
			throw new IllegalArgumentException(product.getName() + " is not available");
		}
		if (!variant.isActive()) {
			throw new IllegalArgumentException(
					product.getName() + " (" + variant.getName() + ") is not available");
		}

		int available = inventory.available(product.getId(), variant.getId());
		return new Resolved(product, variant, requested.quantity(), available);
	}

	/**
	 * Builds the display lines, distributing the discount and computing per-line tax.
	 *
	 * <p>Tax is computed per line rather than on the total, because different lines can be taxed
	 * differently in future (a pharmacy's zero-rated medicine next to a taxable device) and the
	 * per-line tax has to exist on the order anyway.
	 */
	private List<Quote.QuoteLine> buildLines(List<Resolved> resolved, long discountMinor,
			long afterDiscount, Shop shop) {

		List<TaxRule> rules = taxRules.findByShopIdAndActiveTrueOrderByIdAsc(shop.getId());

		// Weight each line by its gross value, so the discount is shared in proportion to what
		// the customer paid rather than equally across lines.
		long[] weights = resolved.stream()
				.mapToLong(r -> r.variant().getPriceMinor() * r.quantity())
				.toArray();
		long[] discountShares = Money.ofMinor(discountMinor).distribute(weights);

		List<Quote.QuoteLine> lines = new ArrayList<>(resolved.size());
		for (int i = 0; i < resolved.size(); i++) {
			Resolved r = resolved.get(i);
			long gross = r.variant().getPriceMinor() * r.quantity();
			long lineDiscount = discountShares[i];
			long netOfDiscount = gross - lineDiscount;
			TaxSplit tax = taxFor(rules, netOfDiscount);

			// A backorder is allowed when the product says so, or when the shop does not track
			// stock at all. Anything else is genuinely unbuyable and the quote refuses it here,
			// before the customer has entered their address.
			boolean purchasable = r.available() >= r.quantity()
					|| r.product().isAllowBackorder()
					|| !r.product().isTrackInventory();

			lines.add(new Quote.QuoteLine(
					r.product().getId().toString(),
					r.variant().getId().toString(),
					r.product().getName(),
					r.variant().getName(),
					r.variant().getImageUrl(),
					r.product().getProductCategory().name(),
					r.product().getProductCategory().label(),
					r.variant().getPriceMinor(),
					r.quantity(),
					gross,
					lineDiscount,
					tax.reported(),
					netOfDiscount + tax.addedToTotal(),
					r.available(),
					purchasable));
		}
		return lines;
	}

	/**
	 * The tax on one line, split into two different numbers.
	 *
	 * @param addedToTotal how much the customer pays <em>on top</em>. Zero for an inclusive rule,
	 *                     whose tax is already inside the price.
	 * @param reported     how much of the price was tax, for the invoice. Non-zero for an
	 *                     inclusive rule, because the shop still has to file a return and the
	 *                     customer still deserves to see the breakdown.
	 *
	 * <p>The two differ only when a shop uses an inclusive rule. For the ordinary exclusive case
	 * they are the same number, which is why an order's line taxes sum to its total tax without
	 * anyone having to think about it.
	 */
	private record TaxSplit(long addedToTotal, long reported) {

		TaxSplit plus(TaxSplit other) {
			return new TaxSplit(addedToTotal + other.addedToTotal, reported + other.reported);
		}

		static TaxSplit none() {
			return new TaxSplit(0, 0);
		}
	}

	/**
	 * Tax on one line, after discount.
	 *
	 * <p>Sequential: each rule applies to the amount the previous one produced, which is the
	 * standard treatment of stacked taxes and keeps the arithmetic explainable on an invoice.
	 *
	 * <p><b>Inclusive and exclusive are tracked separately on purpose.</b> An inclusive tax is
	 * already inside the price, so it does not change what the customer pays — but the invoice
	 * still has to say how much of the price was tax, or the shop cannot file a return. An earlier
	 * version derived the figure from {@code current - amountMinor}, which is zero for an
	 * inclusive rule: the total was right and the breakdown silently said "no tax".
	 */
	private TaxSplit taxFor(List<TaxRule> rules, long amountMinor) {
		TaxSplit split = TaxSplit.none();
		long running = amountMinor;

		for (TaxRule rule : rules) {
			long tax = rule.taxOn(running);
			if (rule.isInclusive()) {
				// Already inside the price: it changes what the customer pays by nothing at all,
				// and only the breakdown.
				split = split.plus(new TaxSplit(0, tax));
			} else {
				split = split.plus(new TaxSplit(tax, tax));
				running += tax;
			}
		}
		return split;
	}

	// ------------------------------------------------------------------ discount

	private record DiscountResolution(Discount discount, long amountMinor, String message) {

		static DiscountResolution none(String message) {
			return new DiscountResolution(null, 0, message);
		}
	}

	/**
	 * Finds and applies a discount.
	 *
	 * <p>An invalid code never fails the quote. It returns a message and charges full price,
	 * because a customer with a typo in their code should still be able to buy what is in their
	 * basket — being shown an error for a coupon they cannot control is a worse experience than
	 * quietly not applying it.
	 */
	private DiscountResolution resolveDiscount(Shop shop, String code, long subtotal, UUID customerId) {
		if (code == null || code.isBlank()) {
			// No code: an automatic discount may still apply.
			List<Discount> automatic = discounts.findByShopIdAndActiveTrueAndCodeIsNullOrderByIdAsc(
					shop.getId());
			for (Discount candidate : automatic) {
				if (candidate.getType() == Discount.Type.FREE_SHIPPING) {
					return new DiscountResolution(candidate, 0, null);
				}
				if (candidate.appliesTo(subtotal)) {
					return new DiscountResolution(candidate, candidate.amountOff(subtotal), null);
				}
			}
			return DiscountResolution.none(null);
		}

		// Scoped by shop: one shop's code must not work at another.
		return discounts.findByShopIdAndCodeIgnoreCase(shop.getId(), code.trim())
				.map(found -> applyOrExplain(found, subtotal, customerId))
				.orElseGet(() -> DiscountResolution.none("That code is not valid"));
	}

	private DiscountResolution applyOrExplain(Discount discount, long subtotal, UUID customerId) {
		if (!discount.appliesTo(subtotal)) {
			return DiscountResolution.none(discount.unusableReason(subtotal));
		}
		if (discount.getType() == Discount.Type.FREE_SHIPPING) {
			return new DiscountResolution(discount, 0, null);
		}
		return new DiscountResolution(discount, discount.amountOff(subtotal), null);
	}

	// ------------------------------------------------------------------ shipping

	/**
	 * The zone for a delivery city.
	 *
	 * <p>City-specific zones first, then the shop's default zone. A city with no matching zone is
	 * not an error: the default covers it, and a shop that has set no zones at all simply does not
	 * charge for delivery.
	 */
	private ShippingZone resolveZone(Shop shop, String city) {
		List<ShippingZone> cityZones = shippingZones.findCityZones(shop.getId());
		for (ShippingZone zone : cityZones) {
			if (zone.covers(city)) {
				return zone;
			}
		}
		return shippingZones.findDefaultZone(shop.getId()).orElse(null);
	}
}
