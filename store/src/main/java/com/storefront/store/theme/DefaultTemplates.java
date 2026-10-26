package com.storefront.store.theme;

import java.util.LinkedHashMap;
import java.util.Map;

import com.storefront.store.catalog.ShopType;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * A starter theme per shop type, and a generic fallback.
 *
 * <p><b>A shop is never given an empty storefront.</b> Sign-up creates a shop with no theme, and
 * the storefront renders whatever the platform default is until the owner arranges their own. That
 * is the decision that makes a new shop immediately presentable rather than a blank page a
 * customer lands on and leaves.
 *
 * <p>The themes are <b>deliberately plain</b>. A pharmacy default leads with "prescriptions
 * accepted" because that is what a pharmacy's customers need to know; a fashion default leads with
 * a hero and a grid. What they do not do is try to be beautiful — that is what the template
 * marketplace and the editor are for. A default's job is to be <em>correct and obvious</em>, so
 * every section is one whose props have sensible defaults in the registry.
 *
 * <p>Stored as JSON strings rather than built with a DSL, so what a theme actually contains is
 * readable in this file — and so the same string is what gets validated and persisted.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DefaultTemplates {

	/** The fallback for a shop whose type has no specific default, and every shop's starting point. */
	public static final String GENERIC = """
			{
			  "schemaVersion": 1,
			  "tokens": {
			    "primary": "#16a34a",
			    "background": "#ffffff",
			    "foreground": "#0f172a",
			    "muted": "#f1f5f9",
			    "accent": "#f97316",
			    "border": "#e2e8f0",
			    "ring": "#16a34a",
			    "fontHeading": "Inter",
			    "fontBody": "Inter",
			    "radius": "0.5rem",
			    "mode": "light"
			  },
			  "sections": [
			    { "id": "announcement", "type": "announcement-bar",
			      "props": { "text": "Welcome — free local delivery over Rs. 2,000", "dismissible": true } },
			    { "id": "hero-1", "type": "hero", "variant": "split-image",
			      "props": { "heading": "Your shop, online",
			                 "subheading": "Browse what we stock and order for delivery.",
			                 "imageUrl": "",
			                 "cta": { "label": "Shop now", "href": "#products" } } },
			    { "id": "categories", "type": "category-grid",
			      "props": { "columns": 4, "source": "shop_categories", "limit": 8 } },
			    { "id": "featured", "type": "product-carousel",
			      "props": { "source": "featured", "limit": 8, "layoutId": "card-soft" } },
			    { "id": "products", "type": "product-grid",
			      "props": { "source": "latest", "limit": 8, "columns": 4 } },
			    { "id": "trust", "type": "trust-badges",
			      "props": { "items": ["Cash on delivery", "Bank transfer", "Secure checkout"] } }
			  ],
			  "nav": { "menu": [ { "label": "Shop", "href": "#products" } ] },
			  "footer": { "assetHandle": "footer-main", "columns": 3, "showPaymentBadges": true }
			}
			""";

	/** Per-type defaults. A shop type absent here gets {@link #GENERIC}. */
	private static final Map<ShopType, String> BY_TYPE = new LinkedHashMap<>();

	static {
		// A pharmacy's first question is whether a prescription is needed, and whether the shop
		// is licensed. Both answer above the fold.
		BY_TYPE.put(ShopType.PHARMACY, """
				{
				  "schemaVersion": 1,
				  "tokens": {
				    "primary": "#0d9488", "background": "#ffffff", "foreground": "#0f172a",
				    "muted": "#f0fdfa", "accent": "#0ea5e9", "border": "#ccfbf1", "ring": "#0d9488",
				    "fontHeading": "Inter", "fontBody": "Inter", "radius": "0.5rem", "mode": "light"
				  },
				  "sections": [
				    { "id": "announcement", "type": "announcement-bar",
				      "props": { "text": "Licensed pharmacy · Prescriptions accepted · Free delivery over Rs. 500",
				                 "dismissible": false } },
				    { "id": "hero-1", "type": "hero", "variant": "split-image",
				      "props": { "heading": "Your neighbourhood pharmacy",
				                 "subheading": "Genuine medicines, dispensed by licensed pharmacists. Upload your prescription and we will confirm before delivery.",
				                 "imageUrl": "",
				                 "cta": { "label": "Browse medicines", "href": "#products" } } },
				    { "id": "categories", "type": "category-grid",
				      "props": { "columns": 4, "source": "shop_categories", "limit": 8 } },
				    { "id": "featured", "type": "product-carousel",
				      "props": { "source": "featured", "limit": 8, "layoutId": "card-soft" } },
				    { "id": "products", "type": "product-grid",
				      "props": { "source": "latest", "limit": 12, "columns": 4 } },
				    { "id": "how-it-works", "type": "feature-list",
				      "props": { "items": [
				        "Send your prescription photo at checkout",
				        "A pharmacist confirms your order",
				        "We deliver, and you pay on arrival"
				      ] } },
				    { "id": "faq", "type": "faq",
				      "props": { "items": [
				        { "q": "Do I need a prescription?", "a": "Yes for prescription-only medicines. Schedule I and II items need one; over-the-counter items do not." },
				        { "q": "Do you deliver outside Kathmandu?", "a": "Yes. Delivery charges and times depend on your area." }
				      ] } },
				    { "id": "trust", "type": "trust-badges",
				      "props": { "items": ["Licensed pharmacy", "Cash on delivery", "Pharmacist reviewed"] } }
				  ],
				  "nav": { "menu": [
				    { "label": "Medicines", "href": "#products" },
				    { "label": "FAQ", "href": "#faq" }
				  ] },
				  "footer": { "assetHandle": "footer-main", "columns": 3, "showPaymentBadges": true }
				}
				""");

		// Clothing sells on imagery and browsing, so the default leads with products, not a
		// feature list.
		BY_TYPE.put(ShopType.FASHION, """
				{
				  "schemaVersion": 1,
				  "tokens": {
				    "primary": "#111827", "background": "#ffffff", "foreground": "#111827",
				    "muted": "#f9fafb", "accent": "#b91c1c", "border": "#e5e7eb", "ring": "#111827",
				    "fontHeading": "Playfair Display", "fontBody": "Inter",
				    "radius": "0rem", "mode": "light"
				  },
				  "sections": [
				    { "id": "announcement", "type": "announcement-bar",
				      "props": { "text": "Free delivery over Rs. 3,000 · Cash on delivery", "dismissible": true } },
				    { "id": "hero-1", "type": "hero", "variant": "centered",
				      "props": { "heading": "New season, now in",
				                 "subheading": "Handmade in Nepal. Delivered across the country.",
				                 "imageUrl": "",
				                 "cta": { "label": "Shop the collection", "href": "#products" } } },
				    { "id": "categories", "type": "category-grid",
				      "props": { "columns": 4, "source": "shop_categories", "limit": 8 } },
				    { "id": "featured", "type": "product-carousel",
				      "props": { "source": "featured", "limit": 8, "layoutId": "card-soft" } },
				    { "id": "products", "type": "product-grid",
				      "props": { "source": "latest", "limit": 8, "columns": 4 } },
				    { "id": "story", "type": "image-with-text",
				      "props": { "imageUrl": "", "heading": "Made by hand",
				                 "body": "Every piece is woven and finished in local workshops.",
				                 "imagePosition": "left" } }
				  ],
				  "nav": { "menu": [ { "label": "Shop", "href": "#products" } ] },
				  "footer": { "assetHandle": "footer-main", "columns": 4, "showPaymentBadges": true }
				}
				""");

		// A grocery's storefront is a search box and a wall of staples. Trust badges matter more
		// than imagery: is the produce fresh, is the shop real.
		BY_TYPE.put(ShopType.GROCERY, """
				{
				  "schemaVersion": 1,
				  "tokens": {
				    "primary": "#15803d", "background": "#ffffff", "foreground": "#14532d",
				    "muted": "#f7fee7", "accent": "#ca8a04", "border": "#dcfce7", "ring": "#15803d",
				    "fontHeading": "Inter", "fontBody": "Inter", "radius": "0.5rem", "mode": "light"
				  },
				  "sections": [
				    { "id": "announcement", "type": "announcement-bar",
				      "props": { "text": "Fresh daily · Free delivery over Rs. 1,500 · Cash on delivery",
				                 "dismissible": true } },
				    { "id": "hero-1", "type": "hero", "variant": "split-image",
				      "props": { "heading": "Groceries, delivered today",
				                 "subheading": "Rice, lentils, oil, spices and snacks from the shop around the corner.",
				                 "imageUrl": "",
				                 "cta": { "label": "Shop groceries", "href": "#products" } } },
				    { "id": "categories", "type": "category-grid",
				      "props": { "columns": 5, "source": "shop_categories", "limit": 10 } },
				    { "id": "featured", "type": "product-carousel",
				      "props": { "source": "featured", "limit": 8, "layoutId": "card-soft" } },
				    { "id": "products", "type": "product-grid",
				      "props": { "source": "latest", "limit": 12, "columns": 4 } },
				    { "id": "trust", "type": "trust-badges",
				      "props": { "items": ["Sourced locally", "Cash on delivery", "Same-day in city"] } }
				  ],
				  "nav": { "menu": [ { "label": "Groceries", "href": "#products" } ] },
				  "footer": { "assetHandle": "footer-main", "columns": 3, "showPaymentBadges": true }
				}
				""");
	}

	/** The default theme for a shop of this type. Never null. */
	public static String forShopType(ShopType type) {
		return type == null ? GENERIC : BY_TYPE.getOrDefault(type, GENERIC);
	}

	/** How many types have a specific default. Asserted by a test so the map is not forgotten. */
	public static int specificDefaults() {
		return BY_TYPE.size();
	}
}
