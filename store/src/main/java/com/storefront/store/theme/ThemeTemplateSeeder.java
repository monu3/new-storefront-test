package com.storefront.store.theme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;

import lombok.RequiredArgsConstructor;

/**
 * Publishes a starter marketplace so a new shop has something to install.
 *
 * <p><b>Runs on every boot and is a no-op when the codes already exist.</b> A marketplace with
 * nothing in it makes the theme engine look broken: an owner opens the browser, sees an empty
 * grid, and concludes the feature does not work.
 *
 * <p>Two templates, deliberately. One tagged {@code PHARMACY} so the type filter (D17) is
 * demonstrably doing something, and one untagged so every shop type has at least one option —
 * a bakery and a hardware shop can share a generic layout.
 *
 * <p>Never overwrites. An admin who has edited a template must not find it reset by a restart.
 *
 * <p><b>Off in tests</b> ({@code store.themes.seed=false} in the test profile). A test asserting
 * "a draft is not offered" cannot also tolerate a seeder that published a template on the way
 * into the context, and the right answer is to switch the seeder off rather than to write tests
 * that assume a fixed set of rows nobody else controls.
 */
@Component
@ConditionalOnProperty(name = "store.themes.seed", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ThemeTemplateSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ThemeTemplateSeeder.class);

	private final ThemeService themes;
	private final ThemeTemplateRepository templates;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		seedIfAbsent("pharmacy-trust", "Pharmacy Trust",
				"Calm, clinical layout that leads with prescription guidance and pharmacist trust.",
				ShopType.PHARMACY, pharmacyTemplate(), 0);

		seedIfAbsent("general-storefront", "General Storefront",
				"A neutral layout that suits any vertical. Hero, categories, products and trust badges.",
				null, generalTemplate(), 100);
	}

	private void seedIfAbsent(String code, String name, String description, ShopType shopType,
			String config, int sortOrder) {

		if (templates.findByCode(code).isPresent()) {
			// Never overwrite: an admin's edits must survive a restart.
			return;
		}
		try {
			ThemeTemplate template = themes.createTemplate(code, name, description, shopType,
					config, ThemeTemplate.PriceTier.FREE, null);
			template.setSortOrder(sortOrder);
			themes.publishTemplate(template.getId(), null);
			log.info("Seeded and published theme template '{}'", code);
		} catch (RuntimeException e) {
			// A bad seed must not stop the application booting. The rest of the platform is
			// perfectly usable without a starter template.
			log.warn("Could not seed theme template '{}': {}", code, e.getMessage());
		}
	}

	/**
	 * The pharmacy starter.
	 *
	 * <p>Leads with the question a pharmacy customer actually has — do I need a prescription,
	 * and is this shop licensed — because that is the same reason the platform's default pharmacy
	 * theme does. Both are the same opinion expressed twice: a storefront has one job, which is
	 * answering the visitor's question before they have to ask it.
	 */
	private String pharmacyTemplate() {
		return """
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
				                 "subheading": "Genuine medicines, dispensed by licensed pharmacists. Send your prescription and a pharmacist will confirm before delivery.",
				                 "imageUrl": "asset:HERO/main",
				                 "cta": { "label": "Browse medicines", "href": "#products" } } },
				    { "id": "categories", "type": "category-grid",
				      "props": { "columns": 4, "source": "shop_categories", "limit": 8 } },
				    { "id": "featured", "type": "product-carousel",
				      "props": { "heading": "In stock now", "source": "featured", "limit": 8 } },
				    { "id": "how-it-works", "type": "feature-list",
				      "props": { "heading": "How ordering works",
				                 "items": [
				                   "Send your prescription photo at checkout",
				                   "A pharmacist reviews and confirms your order",
				                   "We deliver; you pay the courier on arrival"
				                 ] } },
				    { "id": "all-products", "type": "product-grid",
				      "props": { "source": "latest", "limit": 12, "columns": 4 } },
				    { "id": "faq", "type": "faq",
				      "props": { "heading": "Common questions",
				                 "items": [
				                   { "q": "Do I need a prescription?", "a": "Yes for prescription-only medicines. Schedule I and II items require one; over-the-counter items do not." },
				                   { "q": "Do you deliver outside Kathmandu?", "a": "Yes. Delivery charge and time depend on your area." },
				                   { "q": "Can I return a medicine?", "a": "Sealed items only, and only where the law permits. Opened medicines cannot be returned." }
				                 ] } },
				    { "id": "trust", "type": "trust-badges",
				      "props": { "items": ["Licensed pharmacy", "Pharmacist reviewed", "Cash on delivery"] } }
				  ],
				  "nav": { "menu": [
				    { "label": "Medicines", "href": "#products" },
				    { "label": "Questions", "href": "#faq" }
				  ] },
				  "footer": { "assetHandle": "footer-main", "columns": 3, "showPaymentBadges": true }
				}
				""";
	}

	/** The untagged starter every shop type can install. */
	private String generalTemplate() {
		return """
				{
				  "schemaVersion": 1,
				  "tokens": {
				    "primary": "#16a34a", "background": "#ffffff", "foreground": "#0f172a",
				    "muted": "#f1f5f9", "accent": "#f97316", "border": "#e2e8f0", "ring": "#16a34a",
				    "fontHeading": "Inter", "fontBody": "Inter", "radius": "0.5rem", "mode": "light"
				  },
				  "sections": [
				    { "id": "announcement", "type": "announcement-bar",
				      "props": { "text": "Free local delivery over Rs. 2,000", "dismissible": true } },
				    { "id": "hero-1", "type": "hero", "variant": "split-image",
				      "props": { "heading": "Your shop, online",
				                 "subheading": "Browse what we stock and order for delivery across Nepal.",
				                 "imageUrl": "asset:HERO/main",
				                 "cta": { "label": "Shop now", "href": "#products" } } },
				    { "id": "categories", "type": "category-grid",
				      "props": { "columns": 4, "source": "shop_categories", "limit": 8 } },
				    { "id": "featured", "type": "product-carousel",
				      "props": { "heading": "Popular right now", "source": "featured", "limit": 8 } },
				    { "id": "story", "type": "image-with-text",
				      "props": { "heading": "Why shop with us",
				                 "body": "Tell your customers what makes your shop worth choosing. Replace this section from the appearance editor.",
				                 "imageUrl": "", "imagePosition": "left" } },
				    { "id": "all-products", "type": "product-grid",
				      "props": { "source": "latest", "limit": 8, "columns": 4 } },
				    { "id": "trust", "type": "trust-badges",
				      "props": { "items": ["Cash on delivery", "Bank transfer", "Secure checkout"] } }
				  ],
				  "nav": { "menu": [ { "label": "Shop", "href": "#products" } ] },
				  "footer": { "assetHandle": "footer-main", "columns": 3, "showPaymentBadges": true }
				}
				""";
	}
}
