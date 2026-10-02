package com.storefront.store.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ShopType;

/**
 * Verifies the meta endpoint is reachable <em>without</em> a token.
 *
 * <p>This matters: the shop-type picker is shown on the signup screen, before a user has any
 * relationship with the platform, and the product-category dropdown is needed by the storefront.
 * A regression that made these endpoints authenticated would lock a user out of signup with a
 * 401 that is hard to diagnose from the browser.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetaControllerIT {

	@Autowired
	private MockMvc mvc;

	@Test
	@DisplayName("GET /api/v1/meta/shop-types is public and returns every shop type")
	void listIsPublic() throws Exception {
		mvc.perform(get("/api/v1/meta/shop-types"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(ShopType.values().length))
				// First entry is PHARMACY: enum declaration order is the UI order.
				.andExpect(jsonPath("$[0].code").value("PHARMACY"))
				.andExpect(jsonPath("$[0].label").value("Pharmacy"))
				.andExpect(jsonPath("$[0].categories[0].code").value("MEDICINE"));
	}

	@Test
	@DisplayName("each shop type ships its categories with code, label and slug")
	void everyTypeCarriesCategories() throws Exception {
		String json = mvc.perform(get("/api/v1/meta/shop-types"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		for (ShopType type : ShopType.values()) {
			assertThat(json).contains("\"code\":\"" + type.name() + "\"");
			for (ProductCategory category : type.categories()) {
				assertThat(json).contains("\"code\":\"" + category.name() + "\"");
				assertThat(json).contains("\"slug\":\"" + category.slug() + "\"");
			}
		}
	}

	@Test
	@DisplayName("GET a single shop type by code")
	void getOne() throws Exception {
		mvc.perform(get("/api/v1/meta/shop-types/FASHION"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("FASHION"))
				.andExpect(jsonPath("$.categories").isArray());

		// Case-insensitive, matching fromCode().
		mvc.perform(get("/api/v1/meta/shop-types/fashion"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("FASHION"));
	}

	@Test
	@DisplayName("an unknown shop type returns 404 with a machine-readable code")
	void unknownTypeIs404() throws Exception {
		mvc.perform(get("/api/v1/meta/shop-types/BAKERY"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("UNKNOWN_SHOP_TYPE"))
				.andExpect(jsonPath("$.title").value("Unknown shop type"));
	}

	@Test
	@DisplayName("the response is cached by the client, so the payload is stable")
	void payloadIsStable() throws Exception {
		String first = mvc.perform(get("/api/v1/meta/shop-types")).andReturn()
				.getResponse().getContentAsString();
		String second = mvc.perform(get("/api/v1/meta/shop-types")).andReturn()
				.getResponse().getContentAsString();

		assertThat(first).isEqualTo(second);
	}
}
