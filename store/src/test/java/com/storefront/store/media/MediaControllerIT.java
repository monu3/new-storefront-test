package com.storefront.store.media;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.security.KeycloakJwtAuthenticationConverter;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * The media HTTP surface.
 *
 * <p>The property that matters: the signing endpoint derives the folder from the caller's own
 * shop, so there is no request that can direct an upload into another shop's directory. The
 * recording endpoint then re-checks the folder against what Cloudinary reports, which closes the
 * loop on a client that lies about a {@code public_id}.
 *
 * <p>Signing is a pure local computation, so these run without credentials. Nothing here performs
 * an upload — that is the browser's job, and deliberately so.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class MediaControllerIT {

	private static final String OPS_ISSUER = "https://auth-master.yenyasoft.com/realms/ops";
	private static final String CUSTOMER_ISSUER = "https://auth-master.yenyasoft.com/realms/customers";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private MediaService media;

	@Autowired
	private MediaAssetRepository mediaRepository;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop("Media Shop " + UUID.randomUUID().toString().substring(0, 6),
				ShopType.GROCERY);
	}

	private String subOf(UUID userId) {
		return users.findById(userId).orElseThrow().getKeycloakSub();
	}

	private RequestPostProcessor asShopOwner() {
		return tokenWithRole("SHOP", subOf(shop.getOwnerUserId()));
	}

	private RequestPostProcessor asOtherShopOwner() {
		Shop other = fixtures.createVerifiedShop("Other " + UUID.randomUUID().toString().substring(0, 6),
				ShopType.GROCERY);
		return tokenWithRole("SHOP", subOf(other.getOwnerUserId()));
	}

	private RequestPostProcessor asCustomer() {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		AppUser customer = users.saveAndFlush(new AppUser(AppUser.Realm.CUSTOMERS,
				"cust-" + UUID.randomUUID(),
				"cust-" + UUID.randomUUID() + "@example.test", "Customer"));
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", CUSTOMER_ISSUER)
						.claim("sub", customer.getKeycloakSub())
						.claim("email", customer.getEmail())
						.claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	private RequestPostProcessor tokenWithRole(String role, String subject) {
		var converter = new KeycloakJwtAuthenticationConverter(OPS_ISSUER, CUSTOMER_ISSUER);
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(jwt -> jwt
						.claim("iss", OPS_ISSUER)
						.claim("sub", subject)
						.claim("email", role.toLowerCase(java.util.Locale.ROOT) + "@example.test")
						.claim("realm_access", Map.of("roles", List.of(role))))
				.authorities(jwt -> converter.convert(jwt).getAuthorities());
	}

	private String body(Object value) throws Exception {
		return json.writeValueAsString(value);
	}

	/** A row inserted directly, for read and tenancy tests that do not need a real upload. */
	private MediaAsset seed(Shop owner, String subPath, MediaAsset.Usage usage) {
		String folder = media.folderFor(owner, subPath);
		return mediaRepository.saveAndFlush(new MediaAsset(owner.getId(), usage, folder,
				folder + "/" + UUID.randomUUID().toString().substring(0, 8),
				"https://res.cloudinary.com/dhkcfcs3e/image/upload/w_1600/a.jpg",
				"https://res.cloudinary.com/dhkcfcs3e/image/upload/w_1600/a.jpg",
				800, 600, 40_000, "jpg", owner.getOwnerUserId()));
	}

	// ------------------------------------------------------------------ signing

	@Nested
	@DisplayName("signing an upload")
	class Signing {

		@Test
		@DisplayName("signs into the caller's own shop folder")
		void signsIntoOwnFolder() throws Exception {
			mvc.perform(post("/api/v1/shop/media/signature").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("path", "products"))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.signature").isNotEmpty())
					.andExpect(jsonPath("$.folder").value("shop/" + shop.getId() + "/products"))
					.andExpect(jsonPath("$.timestamp").isNumber())
					.andExpect(jsonPath("$.cloudName").isNotEmpty());
		}

		@Test
		@DisplayName("carries the limits, so the browser can refuse a huge file for free")
		void carriesLimits() throws Exception {
			// The browser can reject an oversized file before spending the user's bandwidth on
			// an upload this server would refuse anyway.
			mvc.perform(post("/api/v1/shop/media/signature").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of())))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.maxBytes").isNumber())
					.andExpect(jsonPath("$.maxDimension").isNumber())
					.andExpect(jsonPath("$.folder").value("shop/" + shop.getId()));
		}

		@Test
		@DisplayName("a traversal in the requested path is refused")
		void refusesTraversal() throws Exception {
			// Defence in depth. The shop id is added by the server, so this string is the only
			// part a caller controls — and it must not be able to point somewhere else.
			mvc.perform(post("/api/v1/shop/media/signature").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("path", "../../../someone-else"))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.detail")
							.value(org.hamcrest.Matchers.containsString("'..'")));
		}

		@Test
		@DisplayName("an anonymous caller cannot obtain a signature")
		void anonymousCannotSign() throws Exception {
			mvc.perform(post("/api/v1/shop/media/signature")
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of())))
					.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("a customer cannot obtain a signature")
		void customerCannotSign() throws Exception {
			mvc.perform(post("/api/v1/shop/media/signature").with(asCustomer())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of())))
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("two shops never receive a signature for the same folder")
		void twoShopsGetDifferentFolders() throws Exception {
			// The property that makes uploads attributable and removable: each shop's media is
			// reachable by one folder call, and no shop can write into another's.
			Shop other = fixtures.createVerifiedShop("Other " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.GROCERY);

			String myFolder = mvc.perform(post("/api/v1/shop/media/signature").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("path", "products"))))
					.andReturn().getResponse().getContentAsString();
			String theirFolder = mvc.perform(post("/api/v1/shop/media/signature")
							.with(SecurityMockMvcRequestPostProcessors.jwt()
									.jwt(jwt -> jwt
											.claim("iss", OPS_ISSUER)
											.claim("sub", subOf(other.getOwnerUserId()))
											.claim("realm_access", Map.of("roles", List.of("SHOP"))))
									.authorities(jwt -> new KeycloakJwtAuthenticationConverter(
											OPS_ISSUER, CUSTOMER_ISSUER).convert(jwt).getAuthorities()))
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("path", "products"))))
					.andReturn().getResponse().getContentAsString();

			assertFoldersDiffer(myFolder, theirFolder, shop, other);
		}
	}

	// ------------------------------------------------------------------ recording

	@Nested
	@DisplayName("recording a claim")
	class Recording {

		@Test
		@DisplayName("refuses a claim about an image in a folder we did not sign")
		void refusesForeignFolder() throws Exception {
			// The whole point of the folder check. A client can claim any public_id it likes;
			// without this, one shop could reference another shop's image and render it in their
			// own storefront.
			Shop theirs = fixtures.createVerifiedShop("Theirs " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.GROCERY);
			String theirFolder = media.folderFor(theirs, "logo");

			mvc.perform(post("/api/v1/shop/media/record").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of(
									"publicId", theirFolder + "/their-logo",
									"expectedFolder", media.folderFor(shop, "logo"),
									"usage", "SHOP_LOGO"))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("MEDIA_REJECTED"))
					.andExpect(jsonPath("$.detail")
							.value(org.hamcrest.Matchers.containsString("somewhere else")));
		}

		@Test
		@DisplayName("refuses a claim with no public id")
		void refusesBlankPublicId() throws Exception {
			mvc.perform(post("/api/v1/shop/media/record").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of(
									"publicId", "  ",
									"expectedFolder", media.folderFor(shop, "logo"),
									"usage", "SHOP_LOGO"))))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("refuses an asset with no purpose")
		void refusesMissingUsage() throws Exception {
			mvc.perform(post("/api/v1/shop/media/record").with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of(
									"publicId", media.folderFor(shop, "logo") + "/x",
									"expectedFolder", media.folderFor(shop, "logo")))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.detail")
							.value(org.hamcrest.Matchers.containsString("purpose")));
		}
	}

	// ------------------------------------------------------------------ library

	@Nested
	@DisplayName("the media library")
	class Library {

		@Test
		@DisplayName("returns only the caller's own images")
		void returnsOwnImages() throws Exception {
			seed(shop, "products", MediaAsset.Usage.PRODUCT_IMAGE);
			Shop theirs = fixtures.createVerifiedShop("Theirs " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.GROCERY);
			seed(theirs, "products", MediaAsset.Usage.PRODUCT_IMAGE);

			mvc.perform(get("/api/v1/shop/media").with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].url")
							.value(org.hamcrest.Matchers.containsString("res.cloudinary.com")));
		}

		@Test
		@DisplayName("never exposes the public id or the folder")
		void hidesInternalIdentifiers() throws Exception {
			seed(shop, "products", MediaAsset.Usage.PRODUCT_IMAGE);

			// public_id is what makes deletion possible (D19); it is server-side bookkeeping and
			// exposing it would let a client call Cloudinary's admin API surface assumptions
			// about our account's layout.
			mvc.perform(get("/api/v1/shop/media").with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].publicId").doesNotExist())
					.andExpect(jsonPath("$[0].cloudinaryFolder").doesNotExist());
		}

		@Test
		@DisplayName("another shop's image reads as not found, not forbidden")
		void crossShopIsNotFound() throws Exception {
			MediaAsset theirs = seed(shop, "products", MediaAsset.Usage.PRODUCT_IMAGE);

			// 403 would confirm the id exists, turning this into an existence oracle.
			mvc.perform(patch("/api/v1/shop/media/" + theirs.getId())
							.with(asOtherShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("altText", "hijacked"))))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
		}

		@Test
		@DisplayName("another shop cannot delete my image")
		void crossShopDeleteRefused() throws Exception {
			MediaAsset mine = seed(shop, "products", MediaAsset.Usage.PRODUCT_IMAGE);

			mvc.perform(delete("/api/v1/shop/media/" + mine.getId()).with(asOtherShopOwner()))
					.andExpect(status().isNotFound());

			mvc.perform(get("/api/v1/shop/media").with(asShopOwner()))
					.andExpect(jsonPath("$.length()").value(1));
		}

		@Test
		@DisplayName("sets alt text, and blank clears it")
		void setsAltText() throws Exception {
			MediaAsset mine = seed(shop, "products", MediaAsset.Usage.PRODUCT_IMAGE);

			mvc.perform(patch("/api/v1/shop/media/" + mine.getId()).with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("altText", "A packet of noodles"))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.altText").value("A packet of noodles"));

			mvc.perform(patch("/api/v1/shop/media/" + mine.getId()).with(asShopOwner())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body(Map.of("altText", "   "))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.altText").doesNotExist());
		}

		@Test
		@DisplayName("filters by usage so the logo picker does not show product photos")
		void filtersByUsage() throws Exception {
			seed(shop, "products", MediaAsset.Usage.PRODUCT_IMAGE);
			seed(shop, "logo", MediaAsset.Usage.SHOP_LOGO);

			mvc.perform(get("/api/v1/shop/media").param("usage", "SHOP_LOGO").with(asShopOwner()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].usage").value("SHOP_LOGO"));
		}

		@Test
		@DisplayName("a customer cannot reach the library")
		void customerCannotList() throws Exception {
			mvc.perform(get("/api/v1/shop/media").with(asCustomer()))
					.andExpect(status().isForbidden());
		}
	}

	/** Asserts the two signature responses named different, shop-scoped folders. */
	private void assertFoldersDiffer(String myJson, String theirJson, Shop mine, Shop theirs)
			throws Exception {
		String myFolder = json.readTree(myJson).get("folder").asText();
		String theirFolder = json.readTree(theirJson).get("folder").asText();

		org.assertj.core.api.Assertions.assertThat(myFolder)
				.isEqualTo("shop/" + mine.getId() + "/products");
		org.assertj.core.api.Assertions.assertThat(theirFolder)
				.isEqualTo("shop/" + theirs.getId() + "/products")
				.isNotEqualTo(myFolder);
	}
}
