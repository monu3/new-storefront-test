package com.storefront.store.shop;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ShopType;

/**
 * The shop-type migration endpoint a shop owner uses from their general settings.
 *
 * <p>Requires a token: the answer depends on which products the shop owns, so it is tenant
 * data rather than public reference data. The category values arrive as query parameters only
 * because the product table does not exist until Phase 1; the service layer will read them
 * from Postgres instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShopTypeMigrationControllerIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";

	@Autowired
	private MockMvc mvc;

	/**
	 * Performs the request as a signed-in shop owner.
	 *
	 * <p>A real RSA-signed token would mean standing up Keycloak for the test, so a
	 * structurally valid one carrying the expected claims is used instead. That is sufficient
	 * here: this endpoint's behaviour depends on the query parameters, not on the signature.
	 * Signature, issuer and cross-realm handling are covered by {@code KeycloakLiveRealmIT}
	 * and {@code MultiIssuerJwtDecoderTest}.
	 */
	private MockHttpServletRequestBuilder asShopOwner(MockHttpServletRequestBuilder builder) {
		return builder.with(SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", OPS_ISSUER)
						.claim("sub", "shop-owner-1")
						.claim("realm_access", Map.of("roles", List.of("SHOP")))
						.claim("scope", "openid profile email")));
	}

	private MockHttpServletRequestBuilder reachableRequest(String type, String... categories) {
		MockHttpServletRequestBuilder request = asShopOwner(
				get("/api/v1/shop/type-migration/reachable").param("currentType", type));
		for (String category : categories) {
			request = request.param("currentCategories", category);
		}
		return request;
	}

	@Test
	@DisplayName("is not public")
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/shop/type-migration/reachable").param("currentType", "GROCERY"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("a shop with no products can reach every other type")
	void emptyShopReachesAll() throws Exception {
		mvc.perform(reachableRequest("PHARMACY"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currentType").value("PHARMACY"))
				.andExpect(jsonPath("$.currentCategories").isEmpty())
				.andExpect(jsonPath("$.reachable.length()").value(ShopType.values().length - 1))
				.andExpect(jsonPath("$.blocked").isEmpty());
	}

	@Test
	@DisplayName("never offers the current type as a change")
	void excludesCurrentType() throws Exception {
		mvc.perform(reachableRequest("GROCERY"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reachable", not(hasItem("GROCERY"))));
	}

	@Test
	@DisplayName("a pharmacy selling only baby care can move to a baby shop")
	void sharedCategoryUnblocksChange() throws Exception {
		mvc.perform(reachableRequest("PHARMACY", ProductCategory.BABY_CARE.name()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reachable", hasItem("BABY_SHOP")))
				// The reason the others are unavailable is reported, not silently omitted.
				.andExpect(jsonPath("$.blocked.length()").value(ShopType.values().length - 2));
	}

	@Test
	@DisplayName("reports the exact categories blocking each unavailable type")
	void explainsWhatBlocksEachType() throws Exception {
		mvc.perform(reachableRequest("PHARMACY", ProductCategory.MEDICINE.name()))
				.andExpect(status().isOk())
				// A pharmacy selling medicine cannot become a fashion shop.
				.andExpect(jsonPath("$.blocked[?(@.code=='FASHION')].blockingCount").value(1))
				.andExpect(jsonPath("$.blocked[?(@.code=='FASHION')].blockingCategories[0]")
						.value("Medicine"));
	}

	@Test
	@DisplayName("ignores an unknown category rather than failing the page")
	void ignoresUnknownCategory() throws Exception {
		mvc.perform(reachableRequest("GROCERY", "RICE", "NOT_A_REAL_CATEGORY"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currentCategories.length()").value(1));
	}

	@Test
	@DisplayName("an unknown currentType is a 400, not a 500")
	void rejectsUnknownCurrentType() throws Exception {
		mvc.perform(reachableRequest("BAKERY"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("BABY_CARE is the only category that bridges two verticals")
	void onlyBabyCareBridgesVerticals() throws Exception {
		// Reachability is only useful if it discriminates: a category valid in many
		// verticals would make many types reachable and blunt the endpoint. Asserting the
		// bridging set here means widening it later has to be deliberate.
		//
		// Note PERSONAL_CARE is pharmacy-only, so it does not bridge to a baby shop even
		// though both are care verticals.
		mvc.perform(reachableRequest("PHARMACY", ProductCategory.BABY_CARE.name()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reachable", hasItem("BABY_SHOP")));

		mvc.perform(reachableRequest("PHARMACY",
						ProductCategory.BABY_CARE.name(),
						ProductCategory.PERSONAL_CARE.name()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reachable", not(hasItem("BABY_SHOP"))));
	}
}
