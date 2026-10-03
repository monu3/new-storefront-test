package com.storefront.store.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Media recording, folder derivation and tenancy.
 *
 * <p>The folder rules and the recording checks run against the database and need no network, so
 * they are asserted unconditionally — they are our invariants, not Cloudinary's.
 *
 * <p>The half that needs the real account is gated on {@code CLOUDINARY_CLOUD_NAME}:
 *
 * <pre>
 * CLOUDINARY_CLOUD_NAME=dhkcfcs3e ./mvnw test -Dtest=MediaServiceIT
 * </pre>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class MediaServiceIT {

	@Autowired
	private MediaService media;

	@Autowired
	private MediaAssetRepository mediaRepository;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop("Media Shop " + UUID.randomUUID().toString().substring(0, 6),
				ShopType.GROCERY);
	}

	/**
	 * The shop owner's real user id.
	 *
	 * <p>{@code created_by} is a genuine foreign key to {@code app_user}, so a fabricated UUID
	 * fails the constraint before the test logic runs. The shop's owner is exactly that user.
	 */
	private UUID actor() {
		return shop.getOwnerUserId();
	}

	/** A real 1x1 PNG, so an upload is a genuine image rather than an arbitrary blob. */
	private static byte[] pngBytes() {
		return Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
	}

	// ------------------------------------------------------------------ folders

	@Nested
	@DisplayName("folder convention (D19)")
	class Folders {

		@Test
		@DisplayName("every folder hangs off the shop's own id")
		void foldersAreShopScoped() {
			String folder = media.folderFor(shop, "products");

			assertThat(folder)
					.as("shop/{shopId}/... is what makes a shop's media listable and removable in one call")
					.isEqualTo("shop/" + shop.getId() + "/products");
		}

		@Test
		@DisplayName("an absent sub-path yields the shop root")
		void rootFolder() {
			assertThat(media.folderFor(shop, null)).isEqualTo("shop/" + shop.getId());
			assertThat(media.folderFor(shop, "   ")).isEqualTo("shop/" + shop.getId());
			assertThat(media.folderFor(shop, "/")).isEqualTo("shop/" + shop.getId());
		}

		@Test
		@DisplayName("leading and trailing slashes are trimmed off the sub-path")
		void trimsSlashes() {
			assertThat(media.folderFor(shop, "/products/"))
					.isEqualTo("shop/" + shop.getId() + "/products");
		}

		@Test
		@DisplayName("a traversal in the sub-path is refused")
		void refusesTraversal() {
			// Defence in depth. CloudinaryService also rejects this, but here the string comes
			// straight from a request body, so the check belongs here too.
			assertThatThrownBy(() -> media.folderFor(shop, "../../other-shop"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("'..'");
		}

		@Test
		@DisplayName("a product's folder nests under the product")
		void productFolder() {
			// A real product: product_id is a genuine foreign key, so a fabricated id aborts
			// the transaction before the assertion is reached.
			UUID productId = fixtures.createProduct(shop, "Attached Product",
					com.storefront.store.catalog.ProductCategory.RICE, 40_000L).getId();

			assertThat(media.productFolder(shop, productId))
					.isEqualTo("shop/" + shop.getId() + "/products/" + productId);
		}
	}

	// ------------------------------------------------------------------ recording

	@Nested
	@DisplayName("recording a claimed upload")
	class Recording {

		@Test
		@DisplayName("refuses a claim about an image in a folder we did not sign")
		void refusesForeignFolder() {
			// The core guard. A client can claim any public_id it likes; without this check a shop
			// could reference another shop's image and render it in their own storefront.
			String theirFolder = "shop/" + UUID.randomUUID() + "/products";

			assertThatThrownBy(() -> media.record(shop, theirFolder + "/their-logo",
					media.folderFor(shop, "logo"), MediaAsset.Usage.SHOP_LOGO,
					null, null, null, actor()))
					.isInstanceOf(CloudinaryService.MediaException.class)
					.hasMessageContaining("uploaded somewhere else");
		}

		@Test
		@DisplayName("refuses a claim about a path with no folder at all")
		void refusesFolderlessPublicId() {
			assertThatThrownBy(() -> media.record(shop, "bare-image", media.folderFor(shop, null),
					MediaAsset.Usage.SHOP_LOGO, null, null, null, actor()))
					.isInstanceOf(CloudinaryService.MediaException.class);
		}

		@Test
		@DisplayName("refuses an empty public id")
		void refusesBlankPublicId() {
			assertThatThrownBy(() -> media.record(shop, "  ", media.folderFor(shop, null),
					MediaAsset.Usage.SHOP_LOGO, null, null, null, actor()))
					.isInstanceOf(CloudinaryService.MediaException.class);
		}

		@Test
		@DisplayName("refuses an asset with no purpose")
		void refusesMissingUsage() {
			// A row with no usage cannot be rendered by any picker, so it would be invisible
			// dead weight that still costs storage.
			assertThatThrownBy(() -> media.record(shop, "shop/" + shop.getId() + "/logo/x",
					media.folderFor(shop, "logo"), null, null, null, null, UUID.randomUUID()))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("purpose");
		}
	}

	// ------------------------------------------------------------------ tenancy

	@Nested
	@DisplayName("tenant isolation")
	class Tenancy {

		@Test
		@DisplayName("another shop's image reads as not found")
		void crossShopReadIsIndistinguishable() {
			Shop theirs = fixtures.createVerifiedShop("Theirs " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.GROCERY);
			String folder = media.folderFor(theirs, "logo");

			MediaAsset theirAsset = mediaRepository.saveAndFlush(new MediaAsset(theirs.getId(),
					MediaAsset.Usage.SHOP_LOGO, folder, folder + "/logo-1",
					"https://res.cloudinary.com/dhkcfcs3e/image/upload/v1/x.jpg",
					"https://res.cloudinary.com/dhkcfcs3e/image/upload/v1/x.jpg",
					10, 10, 100, "jpg", actor()));

			// 404 rather than 403: a 403 would confirm the id exists, turning this into an
			// existence oracle for another shop's media.
			assertThatThrownBy(() -> media.getOwned(shop, theirAsset.getId()))
					.isInstanceOf(MediaService.MediaNotFoundException.class);
		}

		@Test
		@DisplayName("a listing only ever returns the caller's own images")
		void listingIsScoped() {
			Shop theirs = fixtures.createVerifiedShop("Theirs " + UUID.randomUUID().toString().substring(0, 6),
					ShopType.GROCERY);

			String myFolder = media.folderFor(shop, "logo");
			mediaRepository.saveAndFlush(new MediaAsset(shop.getId(), MediaAsset.Usage.SHOP_LOGO,
					myFolder, myFolder + "/mine",
					"https://res.cloudinary.com/dhkcfcs3e/image/upload/v1/a.jpg",
					"https://res.cloudinary.com/dhkcfcs3e/image/upload/v1/a.jpg",
					10, 10, 100, "jpg", actor()));

			String theirFolder = media.folderFor(theirs, "logo");
			mediaRepository.saveAndFlush(new MediaAsset(theirs.getId(), MediaAsset.Usage.SHOP_LOGO,
					theirFolder, theirFolder + "/theirs",
					"https://res.cloudinary.com/dhkcfcs3e/image/upload/v1/b.jpg",
					"https://res.cloudinary.com/dhkcfcs3e/image/upload/v1/b.jpg",
					10, 10, 100, "jpg", actor()));

			List<MediaAsset> mine = media.listForShop(shop, null);

			assertThat(mine).extracting(MediaAsset::getPublicId).containsExactly(myFolder + "/mine");
		}
	}

	// ------------------------------------------------------------------ against Cloudinary

	@Nested
	@DisplayName("against the real Cloudinary account")
	@EnabledIfEnvironmentVariable(named = "CLOUDINARY_CLOUD_NAME", matches = ".+")
	class Live {

		@Test
		@DisplayName("signs an upload into the shop's own folder")
		void signsForShop() {
			CloudinaryService.UploadSignature signature = media.signForShop(shop, "products");

			assertThat(signature.signature()).isNotBlank();
			assertThat(signature.folder()).isEqualTo("shop/" + shop.getId() + "/products");
			assertThat(signature.cloudName()).isNotBlank();
		}

		@Test
		@DisplayName("uploads, records, and lists an image in one flow")
		void uploadRecordList() {
			MediaAsset asset = media.uploadAndRecord(shop, "products", pngBytes(), "photo.png",
					MediaAsset.Usage.PRODUCT_IMAGE, actor());

			try {
				assertThat(asset.getPublicId()).startsWith("shop/" + shop.getId() + "/products");
				assertThat(asset.getCloudinaryFolder()).isEqualTo("shop/" + shop.getId() + "/products");
				assertThat(asset.displayUrl()).contains("res.cloudinary.com");
				// The delivery URL is width-capped, so a shop's 6000px original is never served raw.
				assertThat(asset.displayUrl()).contains("w_1600");

				assertThat(media.listForShop(shop, MediaAsset.Usage.PRODUCT_IMAGE))
						.extracting(MediaAsset::getId)
						.contains(asset.getId());
			} finally {
				media.delete(shop, asset.getId());
			}
		}

		@Test
		@DisplayName("deletes both the row and the cloud asset")
		void deletesBoth() {
			MediaAsset asset = media.uploadAndRecord(shop, "logo", pngBytes(), "logo.png",
					MediaAsset.Usage.SHOP_LOGO, actor());
			String publicId = asset.getPublicId();

			media.delete(shop, asset.getId());

			assertThat(mediaRepository.findById(asset.getId())).isEmpty();
			assertThat(mediaRepository.findByPublicId(publicId))
					.as("the row is what our database knows about; the cloud asset is gone too")
					.isEmpty();
		}

		@Test
		@DisplayName("reordering applies the whole sequence in one go")
		void reorders() {
			MediaAsset first = media.uploadAndRecord(shop, "products", pngBytes(), "first.png",
					MediaAsset.Usage.PRODUCT_IMAGE, actor());
			MediaAsset second = media.uploadAndRecord(shop, "products", pngBytes(), "second.png",
					MediaAsset.Usage.PRODUCT_IMAGE, actor());
			MediaAsset third = media.uploadAndRecord(shop, "products", pngBytes(), "third.png",
					MediaAsset.Usage.PRODUCT_IMAGE, actor());

			try {
				// The storefront shows every thumbnail and a drag sends the whole sequence, so
				// the endpoint takes the complete order rather than a single move.
				List<MediaAsset> reordered = media.reorder(shop,
						List.of(third.getId(), first.getId(), second.getId()));

				assertThat(reordered).extracting(MediaAsset::getSortOrder).containsExactly(0, 1, 2);
				assertThat(reordered.get(0).getId()).isEqualTo(third.getId());
			} finally {
				media.delete(shop, first.getId());
				media.delete(shop, second.getId());
				media.delete(shop, third.getId());
			}
		}

		@Test
		@DisplayName("alt text can be set after upload, and blank clears it")
		void setsAltText() {
			MediaAsset asset = media.uploadAndRecord(shop, "products", pngBytes(), "alt.png",
					MediaAsset.Usage.PRODUCT_IMAGE, actor());

			try {
				assertThat(media.setAltText(shop, asset.getId(), "A packet of noodles").getAltText())
						.isEqualTo("A packet of noodles");
				assertThat(media.setAltText(shop, asset.getId(), "  ").getAltText())
						.as("blank alt text is stored as null, not as an empty string")
						.isNull();
			} finally {
				media.delete(shop, asset.getId());
			}
		}

		@Test
		@DisplayName("an image uploaded before its product exists can be attached afterwards")
		void attachesToProductLater() {
			MediaAsset asset = media.uploadAndRecord(shop, "products", pngBytes(), "orphan.png",
					MediaAsset.Usage.PRODUCT_IMAGE, actor());
			// A real product: product_id is a genuine foreign key, so a fabricated id aborts
			// the transaction before the assertion is reached.
			UUID productId = fixtures.createProduct(shop, "Attached Product",
					com.storefront.store.catalog.ProductCategory.RICE, 40_000L).getId();

			try {
				MediaAsset attached = media.attachToProduct(shop, asset.getId(), productId);

				assertThat(attached.getProductId()).isEqualTo(productId);
				assertThat(media.listForProduct(shop.getId(), productId))
						.extracting(MediaAsset::getId).contains(asset.getId());
			} finally {
				media.delete(shop, asset.getId());
			}
		}

		@Test
		@DisplayName("uploading the same logical name twice overwrites rather than accumulating")
		void overwritesRatherThanAccumulates() {
			MediaAsset first = media.uploadAndRecord(shop, "products", pngBytes(), "same.png",
					MediaAsset.Usage.PRODUCT_IMAGE, actor());
			MediaAsset second = media.uploadAndRecord(shop, "products", pngBytes(), "same.png",
					MediaAsset.Usage.PRODUCT_IMAGE, actor());

			try {
				assertThat(second.getPublicId())
						.as("a replaced image should not leave the old one paying for storage")
						.isEqualTo(first.getPublicId());
			} finally {
				media.delete(shop, second.getId());
			}
		}
	}
}
