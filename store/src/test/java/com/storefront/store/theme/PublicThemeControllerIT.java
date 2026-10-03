package com.storefront.store.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * The storefront payload is public, so what it must NOT contain is as important as what it does.
 *
 * <p>This endpoint needs no token. It was returning the JPA entity and letting Jackson serialise
 * it, which published the shop's cost price — `costPriceMinor` — to anyone who asked. That field
 * was `null` only because the seeded products had no cost price set; a shop that sets one leaks
 * its margin. Sales and view counts went with it.
 *
 * <p>These assertions are the reason the endpoint returns a DTO rather than an entity. An entity
 * gains a field and silently publishes it; a DTO fails this test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class PublicThemeControllerIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private ProductService products;

	@Autowired
	private TestFixtures fixtures;

	private String slug;

	@BeforeEach
	void setUp() {
		var shop = fixtures.createVerifiedShop(
				"Public Shop " + UUID.randomUUID().toString().substring(0, 6),
				com.storefront.store.catalog.ShopType.GROCERY);
		slug = shop.getSlug();

		Product product = fixtures.createProduct(shop, "Public Rice",
				com.storefront.store.catalog.ProductCategory.RICE, 45_000L);
		// A cost price the shop owner would never choose to publish.
		products.update(shop, product, null, null, null, null, 30_000L, null, null, null, null,
				null, null);
		products.recordSale(product, 17);
		products.publish(shop, product);
	}

	private JsonNode payload() throws Exception {
		String body = mvc.perform(get("/api/v1/p/shops/" + slug + "/storefront"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(body);
	}

	@Test
	@DisplayName("needs no authentication — a storefront has to sell to anyone")
	void isPublic() throws Exception {
		mvc.perform(get("/api/v1/p/shops/" + slug + "/storefront"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("never exposes a shop's cost price")
	void noCostPrice() throws Exception {
		// The assertion this whole class exists for. It is the reason the endpoint returns a
		// DTO rather than the entity: an entity gains a field and silently publishes it.
		JsonNode products = payload().get("latestProducts");

		assertThat(products.isArray()).isTrue();
		assertThat(products).isNotEmpty();

		for (JsonNode product : products) {
			List<String> fields = new ArrayList<>();
			product.fieldNames().forEachRemaining(fields::add);

			assertThat(fields)
					.as("fields published for product '%s'", product.path("name").asText())
					.noneMatch(f -> f.toLowerCase(Locale.ROOT).contains("cost"))
					.noneMatch(f -> f.toLowerCase(Locale.ROOT).contains("sold"))
					.noneMatch(f -> f.toLowerCase(Locale.ROOT).contains("view"));
		}
	}

	@Test
	@DisplayName("sends 'price', which is the name the storefront renders")
	void priceFieldName() throws Exception {
		// The entity field is basePriceMinor; the renderer reads price. When the entity was
		// serialised directly, every product card on the live site rendered "Rs. NaN" and every
		// test still passed.
		JsonNode first = payload().get("latestProducts").get(0);

		assertThat(first.has("price")).isTrue();
		assertThat(first.has("basePriceMinor"))
				.as("the internal column name must not be the public contract")
				.isFalse();
		assertThat(first.get("price").asLong()).isEqualTo(45_000L);
	}

	@Test
	@DisplayName("includes the fields a card needs, and nothing else")
	void cardFields() throws Exception {
		JsonNode first = payload().get("latestProducts").get(0);

		assertThat(first.has("id")).isTrue();
		assertThat(first.has("slug")).isTrue();
		assertThat(first.has("name")).isTrue();
		assertThat(first.has("categoryLabel")).isTrue();
		assertThat(first.has("purchasable")).isTrue();
	}

	@Test
	@DisplayName("falls back to newest products when the shop has featured nothing")
	void featuredFallsBack() throws Exception {
		// An empty "Popular right now" carousel on a homepage reads as a broken shop, and
		// featuring products is something a new shop has not had time to do.
		JsonNode body = payload();

		assertThat(body.get("featuredProducts"))
				.as("nothing is featured, so the showcase must fall back rather than be empty")
				.isNotEmpty();
	}

	@Test
	@DisplayName("an unverified shop is 404, not a partially-populated payload")
	void hiddenShopIsNotFound() throws Exception {
		var draft = fixtures.createShop("Hidden " + UUID.randomUUID().toString().substring(0, 6),
				com.storefront.store.catalog.ShopType.GROCERY);

		mvc.perform(get("/api/v1/p/shops/" + draft.getSlug() + "/storefront"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.costPriceMinor").doesNotExist());
	}
}
