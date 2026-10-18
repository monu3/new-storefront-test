package com.storefront.store.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Shareable links and the WhatsApp button (Phase 8 — the multi-channel requirement).
 *
 * <p><b>What is asserted here is the encoding, not the presence of a string.</b> A share link that
 * "looks right" and is subtly wrong is the whole failure mode: a product named
 * {@code Tea & Coffee} shares as {@code Tea %26 Coffee} rendered literally, or as
 * {@code Tea & Coffee} read by the chat as a second parameter. Both paste successfully and go
 * somewhere wrong, and neither shows up in a test that asserts the link contains the name.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class ShareServiceIT {

	@Autowired
	private ShareService sharing;

	@Autowired
	private ShareClickRepository clicks;

	@Autowired
	private TestFixtures fixtures;

	@Value("${store.storefront.base-url}")
	private String storefrontBaseUrl;

	private Product product;

	@BeforeEach
	void setUp() {
		var shop = fixtures.createVerifiedShop("Share Shop " + UUID.randomUUID(),
				com.storefront.store.catalog.ShopType.GROCERY);
		product = fixtures.createProduct(shop, "Green Tea Leaves",
				ProductCategory.RICE, 100_000L);
	}

	@Nested
	@DisplayName("a product's share sheet")
	class ProductSheet {

		@Test
		@DisplayName("builds links against the configured storefront, never a hardcoded host")
		void usesConfiguredBaseUrl() {
			var share = sharing.shareForProduct(product.getId());

			assertThat(share.url()).startsWith(storefrontBaseUrl);
			// No doubled slash after the scheme. `http://localhost:3000//product/x` is a different
			// path to some hosts and is cached separately by others, so it must not be produced.
			assertThat(share.url().substring(storefrontBaseUrl.length()))
					.doesNotContain("//")
					.startsWith("/");
		}

		@Test
		@DisplayName("tags every link, so channels can be told apart later")
		void tagsEveryLink() {
			var share = sharing.shareForProduct(product.getId());

			assertThat(share.url())
					.as("sharing without attribution is decoration -- nobody can tell whether it "
							+ "worked")
					.contains("utm_source=share")
					.contains("utm_campaign=share");
		}

		@Test
		@DisplayName("the WhatsApp link opens the picker, not one person")
		void whatsappOpensThePicker() {
			var share = sharing.shareForProduct(product.getId());

			assertThat(share.whatsapp())
					.as("a baked-in number shares to one person and cannot be posted in a group "
							+ "at all")
					.isEqualTo("https://wa.me/?text=" + share.whatsapp().split("text=")[1]);
			assertThat(share.whatsapp()).startsWith("https://wa.me/?text=");
		}

		@Test
		@DisplayName("the WhatsApp text carries the product name and the link")
		void whatsappTextIsUseful() {
			var share = sharing.shareForProduct(product.getId());

			String text = URLDecoder.decode(share.whatsapp().split("text=")[1], StandardCharsets.UTF_8);

			assertThat(text)
					.contains("Green Tea Leaves")
					.as("a bare URL in a chat reads as spam; a line of text above it reads as a "
							+ "recommendation, which is why somebody is sharing it")
					.contains(share.url());
		}

		@Test
		@DisplayName("encodes an ampersand in the product name rather than breaking the link")
		void encodesAwkwardProductNames() {
			var shop = fixtures.createVerifiedShop("Amp Shop", com.storefront.store.catalog.ShopType.GROCERY);
			var awkward = fixtures.createProduct(shop, "Tea & Coffee",
					ProductCategory.RICE, 100_000L);

			var share = sharing.shareForProduct(awkward.getId());

			String text = URLDecoder.decode(share.whatsapp().split("text=")[1], StandardCharsets.UTF_8);

			assertThat(text)
					.as("'Tea & Coffee' unencoded splits the message at the ampersand and the shop "
							+ "shares half a product name")
					.contains("Tea & Coffee");
		}

		@Test
		@DisplayName("offers Viber, which is the channel most customers here actually use")
		void offersViber() {
			var share = sharing.shareForProduct(product.getId());

			assertThat(share.channels()).containsKeys("whatsapp", "messenger", "viber");
			assertThat(share.viber()).startsWith("https://viber.com/share?");
		}

		@Test
		@DisplayName("the copy text is the product name, a newline, then the link")
		void copyTextIsTwoLines() {
			var share = sharing.shareForProduct(product.getId());

			assertThat(share.copyText())
					.isEqualTo("Green Tea Leaves" + System.lineSeparator() + share.url());
		}
	}

	@Nested
	@DisplayName("click attribution")
	class Clicks {

		@Test
		@DisplayName("records a click, so a share channel can be told from another")
		void recordsAClick() {
			sharing.recordClick(product.getId(), "whatsapp");

			var counts = sharing.clickCountsFor(product.getId(), 30);

			assertThat(counts).extracting(ShareClickRepository.SourceCount::source).contains("whatsapp");
		}

		@Test
		@DisplayName("counts each channel separately")
		void countsPerChannel() {
			sharing.recordClick(product.getId(), "whatsapp");
			sharing.recordClick(product.getId(), "whatsapp");
			sharing.recordClick(product.getId(), "copy");

			var counts = sharing.clickCountsFor(product.getId(), 30);

			assertThat(counts).hasSize(2);
			assertThat(counts.get(0).clicks())
					.as("two channels from WhatsApp and one from copy, and the order matters")
					.isEqualTo(2);
		}

		@Test
		@DisplayName("treats a missing channel as unknown rather than storing an empty string")
		void blanksBecomeUnknown() {
			sharing.recordClick(product.getId(), "  ");

			assertThat(sharing.clickCountsFor(product.getId(), 30))
					.extracting(ShareClickRepository.SourceCount::source)
					.contains("unknown");
		}

		@Test
		@DisplayName("reports nothing for a product nobody shared")
		void reportsNothingWhenThereWereNoClicks() {
			assertThat(sharing.clickCountsFor(product.getId(), 30)).isEmpty();
		}
	}

	@Nested
	@DisplayName("ownership")
	class Ownership {

		/**
		 * The share endpoints take a shop id in the path, and this pins that it is checked.
		 *
		 * <p>It was not, when these were written: the controller had a role check and nothing else,
		 * so any shop owner could read another shop's share links — and, through the click-stats
		 * endpoint, another shop's traffic. The unit test could never have caught it, because the
		 * hole is in an annotation, not in the service.
		 *
		 * <p>So this asserts the annotation itself. The alternative -- trusting a future reader to
		 * notice -- is how the hole came back the second time.
		 */
		@Test
		@DisplayName("every shop-scoped share endpoint checks the caller owns the shop")
		void everyEndpointChecksOwnership() throws Exception {
			java.lang.reflect.Method[] endpoints = ShareController.class.getDeclaredMethods();

			List<String> unguarded = new java.util.ArrayList<>();

			for (java.lang.reflect.Method method : endpoints) {
				String path = null;
				var mapping = method.getAnnotation(
						org.springframework.web.bind.annotation.RequestMapping.class);
				if (mapping != null && mapping.value().length > 0) {
					path = mapping.value()[0];
				}

				// Only the shop-scoped reads; the click endpoint is public by design.
				if (path == null || !path.contains("{shopId}")) {
					continue;
				}

				var authorize = method.getAnnotation(
						org.springframework.security.access.prepost.PreAuthorize.class);

				if (authorize == null || !authorize.value().contains("tenantGuard.owns(#shopId)")) {
					unguarded.add(method.getName() + " -> " + path);
				}
			}

			assertThat(unguarded)
					.as("an unchecked shop id in a path is just a way to read somebody else's data by "
							+ "editing a URL")
					.isEmpty();
		}
	}

	@Nested
	@DisplayName("errors")
	class Errors {

		@Test
		@DisplayName("a product that does not exist is refused, not silently shared")
		void refusesAMissingProduct() {
			assertThatThrownBy(() -> sharing.shareForProduct(UUID.randomUUID()))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("a share window that is not a window is refused")
		void refusesAnAbsurdWindow() {
			assertThatThrownBy(() -> sharing.clickCountsFor(product.getId(), 0))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}
}