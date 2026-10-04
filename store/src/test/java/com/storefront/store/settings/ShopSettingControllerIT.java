package com.storefront.store.settings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.security.KeycloakJwtAuthenticationConverter;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.support.TestConfig;

/**
 * The settings HTTP surface.
 *
 * <p>The point of these is the tab endpoints and who may reach them. That a {@code PUT} returns
 * what it was sent is covered by the service tests; what the HTTP layer adds is the
 * authorisation, the validation response, and the promise that a body is never stored field by
 * field from an unvalidated request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ShopSettingControllerIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUSTOMER_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	@Autowired
	private org.springframework.test.web.servlet.MockMvc mvc;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private ObjectMapper json;

	// ------------------------------------------------------------------ auth

	/** A token carrying a realm role, mapped by the production converter. */
	private RequestPostProcessor asRole(String role) {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", OPS_ISSUER)
						.claim("sub", "sub-" + UUID.randomUUID())
						.claim("email", role.toLowerCase(java.util.Locale.ROOT) + "@example.test")
						.claim("realm_access", Map.of("roles", List.of(role))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	private RequestPostProcessor asShopOwner() {
		AppUser owner = users.saveAndFlush(new AppUser(AppUser.Realm.OPS,
				"owner-" + UUID.randomUUID(),
				"owner-" + UUID.randomUUID() + "@example.test", "Owner"));
		shops.saveAndFlush(new com.storefront.store.shop.Shop(owner, "Settings HTTP Shop",
				ShopType.GROCERY));
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", OPS_ISSUER)
						.claim("sub", owner.getId().toString())
						.claim("email", owner.getEmail())
						.claim("realm_access", Map.of("roles", List.of("SHOP"))))
				.authorities(jwt -> new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER)
						.convert(jwt).getAuthorities());
	}

	// ------------------------------------------------------------------ read

	@Test
	@DisplayName("an owner gets every tab with defaults filled in")
	void readsAllTabs() throws Exception {
		mvc.perform(get("/api/v1/shop/settings").with(asShopOwner()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.general.currency").value("NPR"))
				.andExpect(jsonPath("$.checkout.acceptOrders").value(true))
				.andExpect(jsonPath("$.notifications.lowStock").value(true))
				.andExpect(jsonPath("$.orderNumber.format").value("ORD-{####}"));
	}

	@Test
	@DisplayName("a customer cannot read a shop's settings")
	void customerRefused() throws Exception {
		mvc.perform(get("/api/v1/shop/settings").with(asRole("CUSTOMER")))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("an anonymous caller is refused")
	void anonymousRefused() throws Exception {
		mvc.perform(get("/api/v1/shop/settings"))
				.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ write

	@Test
	@DisplayName("saving a tab returns it and persists it")
	void savesTab() throws Exception {
		RequestPostProcessor owner = asShopOwner();

		String body = json.writeValueAsString(Map.of(
				"displayName", "Ravi Kirana Store",
				"tagline", "Fresh every day",
				"supportEmail", "help@example.test",
				"supportPhone", "+9779800000000",
				"currency", "NPR",
				"timezone", "Asia/Kathmandu"));

		mvc.perform(put("/api/v1/shop/settings/general").with(owner)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayName").value("Ravi Kirana Store"));

		mvc.perform(get("/api/v1/shop/settings").with(owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.general.displayName").value("Ravi Kirana Store"));
	}

	@Test
	@DisplayName("closing the shop is a checkout setting, not a shop profile one")
	void savesCheckout() throws Exception {
		RequestPostProcessor owner = asShopOwner();

		mvc.perform(put("/api/v1/shop/settings/checkout").with(owner)
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of(
								"acceptOrders", false,
								"minOrderMinor", 50000,
								"autoAcceptOrders", false,
								"showDeliveryEstimate", true,
								"guestCheckoutAllowed", true))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.acceptOrders").value(false));

		mvc.perform(get("/api/v1/shop/settings").with(owner))
				.andExpect(jsonPath("$.checkout.acceptOrders").value(false))
				.andExpect(jsonPath("$.checkout.minOrderMinor").value(50000));
	}

	@Test
	@DisplayName("an invalid order-number format is rejected with the reason")
	void rejectsBadOrderNumberFormat() throws Exception {
		mvc.perform(put("/api/v1/shop/settings/order-number").with(asShopOwner())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("format", "ORD-[####]", "next", 1))))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a non-hex accent colour is rejected")
	void rejectsBadAccentColour() throws Exception {
		mvc.perform(put("/api/v1/shop/settings/invoice").with(asShopOwner())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of(
								"showLogo", true,
								"showSignatureLine", true,
								"showTaxBreakdown", true,
								"showPaymentMethod", true,
								"accentColor", "dark blue"))))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a low-stock threshold of -1 is rejected")
	void rejectsNegativeThreshold() throws Exception {
		mvc.perform(put("/api/v1/shop/settings/notifications").with(asShopOwner())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of(
								"newOrder", true,
								"lowStock", true,
								"lowStockThreshold", -1,
								"emailDigest", false))))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("the order-number counter is not client-writable")
	void counterNotWritable() throws Exception {
		RequestPostProcessor owner = asShopOwner();

		mvc.perform(put("/api/v1/shop/settings/order-number").with(owner)
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of(
								"format", "ORD-{YYYY}-{####}", "next", 5000))))
				.andExpect(status().isOk())
				// The response reports the stored counter, never the posted one.
				.andExpect(jsonPath("$.next").value(1));
	}
}