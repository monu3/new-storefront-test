package com.storefront.store.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.common.Money;
import com.storefront.store.shop.ShopRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Customer-facing search and browsing (Phase 5).
 *
 * <p>Public, like the rest of the storefront: a customer who cannot search without signing in
 * cannot shop, and the largest source of lost sales in a marketplace is a wall in front of the
 * catalogue.
 *
 * <p><b>Three questions, three endpoints.</b> A customer searching "paracetamol" wants to know
 * <em>which pharmacies</em> sell it, not to scroll forty listings from one shop. So the shop-level
 * result is the primary one, with the products as the detail behind it.
 *
 * <p><b>Only ACTIVE products in VERIFIED shops.</b> Enforced in SQL rather than in Java, so a
 * retired product cannot appear by way of a filter the service forgot to apply.
 */
@RestController
@RequestMapping("/api/v1/p/search")
@Tag(name = "Public search", description = "Find products and the shops that sell them")
@RequiredArgsConstructor
@Slf4j
public class PublicSearchController {

	/** Enough for a suggestion dropdown; not so much that it becomes a catalogue dump. */
	private static final int MAX_SHOPS = 30;

	private final ProductRepository products;
	private final ShopRepository shops;
	private final com.storefront.store.media.MediaService media;

	@GetMapping
	@Operation(summary = "Search every shop's catalogue",
			description = "Full-text plus typo-tolerant matching. Ranked, and paged.")
	public SearchResults search(
			@RequestParam(name = "q", required = false) String q,
			@RequestParam(required = false) ShopType type,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "24") @Min(1) @Max(100) int size) {

		String term = q == null ? "" : q.trim();
		if (term.length() < 2) {
			// One character cannot use a trigram index at all, and a full-text query for a single
			// letter matches most of the catalogue. Saying so beats returning the whole shop.
			return SearchResults.empty(hintForShortQuery(term));
		}

		Page<Product> found = type == null
				? products.search(term, PageRequest.of(page, size))
				: products.searchInType(term, type.name(), PageRequest.of(page, size));

		return new SearchResults(
				term,
				type == null ? null : type.name(),
				found.getContent().stream().map(this::summary).toList(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages(),
				products.shopsMatching(term, MAX_SHOPS).stream().map(this::shop).toList(),
				null);
	}

	/**
	 * The shops that sell whatever was searched for.
	 *
	 * <p>The D17 payoff, and the endpoint the gallery links to: "show me every pharmacy with
	 * paracetamol" is one request, and the customer never has to know that a pharmacy is a thing
	 * they could have filtered for.
	 */
	@GetMapping("/shops")
	@Operation(summary = "Which shops have a matching product")
	public ShopResults shops(@RequestParam(name = "q") String q) {
		String term = q == null ? "" : q.trim();
		if (term.length() < 2) {
			return new ShopResults(term, List.of());
		}
		return new ShopResults(term,
				products.shopsMatching(term, MAX_SHOPS).stream().map(this::shop).toList());
	}

	// ------------------------------------------------------------------ mapping

	private ProductSummary summary(Product product) {
		var shop = shops.findById(product.getShopId()).orElse(null);

		// The image is resolved through the media service rather than read off the product, because
		// the product has no image column: images are media rows, and a card with a null image
		// renders a placeholder rather than a broken picture.
		//
		// The gallery is already in gallery order, so the first entry is the primary image. Null on
		// any failure, because a search result must never fail to render over a missing picture.
		String imageUrl = null;
		try {
			imageUrl = media.listForProduct(product.getShopId(), product.getId()).stream()
					.findFirst()
					.map(asset -> asset.displayUrl())
					.orElse(null);
		} catch (RuntimeException ex) {
			log.debug("No image for product {}: {}", product.getId(), ex.getMessage());
		}

		// basePriceMinor, not a variant price: a search result is a card, and the card shows the
		// product's price from. The variant price is resolved when the customer opens it.
		return new ProductSummary(
				product.getId().toString(),
				product.getName(),
				product.getSlug(),
				product.getProductCategory().name(),
				product.getProductCategory().label(),
				product.getBasePriceMinor(),
				Money.ofMinor(product.getBasePriceMinor()).toDisplay(),
				product.getShortDescription(),
				imageUrl,
				shop == null ? null : shop.getSlug(),
				shop == null ? null : shop.getName());
	}

	/** The interface projection, flattened onto the record the API actually returns. */
	private ShopSummary shop(PublicSearchRepository.ShopMatchView match) {
		return new ShopSummary(
				match.getShopId().toString(),
				match.getSlug(),
				match.getName(),
				match.getShopType(),
				match.getMatches());
	}

	/** What to tell someone whose search was too short to be useful. */
	private static String hintForShortQuery(String term) {
		return term.isEmpty()
				? "Type at least two characters to search."
				: "Search for at least two characters.";
	}

	// ------------------------------------------------------------------ types

	/**
	 * A search result: the shops, and the products.
	 *
	 * <p>Both in one response because the customer needs both at once — "three pharmacies have this"
	 * and "here are the ones in stock" — and two round trips to draw one screen is how a search box
	 * feels slow.
	 */
	public record SearchResults(
			String query,
			String shopType,
			List<ProductSummary> products,
			int page,
			int size,
			long totalElements,
			int totalPages,
			List<ShopSummary> shops,
			/** Why the result is empty, when it is. Never a bare "no results". */
			String hint) {

		static SearchResults empty(String hint) {
			return new SearchResults("", null, List.of(), 0, 0, 0, 0, List.of(), hint);
		}
	}

	public record ShopResults(String query, List<ShopSummary> shops) {
	}

	public record ShopSummary(String id, String slug, String name, String shopType, long matches) {
	}

	/** A product, with the shop that sells it. */
	public record ProductSummary(
			String id,
			String name,
			String slug,
			String category,
			String categoryLabel,
			long priceMinor,
			String priceDisplay,
			String shortDescription,
			String imageUrl,
			String shopSlug,
			String shopName) {
	}
}