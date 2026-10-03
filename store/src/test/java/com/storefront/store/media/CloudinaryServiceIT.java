package com.storefront.store.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.storefront.store.common.CloudinaryProperties;

/**
 * Cloudinary integration, against the real test account.
 *
 * <p>The live half is gated on {@code CLOUDINARY_CLOUD_NAME} being set, so the suite skips
 * rather than fails where there are no credentials:
 *
 * <pre>
 * CLOUDINARY_CLOUD_NAME=dhkcfcs3e ./mvnw test -Dtest=CloudinaryServiceIT
 * </pre>
 *
 * <p>The folder rules are asserted unconditionally: they are our invariant, not Cloudinary's, and
 * they must hold whether or not a network call succeeds.
 *
 * <p>Every upload lands in a uniquely named folder and is deleted afterwards, so repeated runs
 * do not accumulate files in the account.
 */
@SpringBootTest
@ActiveProfiles("test")
class CloudinaryServiceIT {

	@Autowired
	private CloudinaryService cloudinary;

	@Autowired
	private CloudinaryProperties properties;

	private String folder;

	@BeforeEach
	void newFolder() {
		folder = "shop/test-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** A real 1x1 PNG, so the upload is a genuine image rather than an arbitrary blob. */
	private static byte[] pngBytes() {
		return Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
	}

	@Nested
	@DisplayName("folder convention (D19)")
	class FolderRules {

		@Test
		@DisplayName("uploads must live under shop/")
		void mustBeUnderShop() {
			assertThatThrownBy(() -> cloudinary.signUpload("products/abc"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must go under shop/");
		}

		@Test
		@DisplayName("a folder escaping upwards is rejected")
		void rejectsTraversal() {
			// The folder comes from a shop-scoped path, but a traversal here would let a shop
			// write into another shop's directory or outside the account entirely.
			assertThatThrownBy(() -> cloudinary.signUpload("shop/abc/../../../etc"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must not contain '..'");
		}

		@Test
		@DisplayName("a blank folder is rejected")
		void rejectsBlank() {
			assertThatThrownBy(() -> cloudinary.signUpload("  "))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> cloudinary.signUpload(null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("the public_id encodes the folder, so it needs no stored column")
		void folderDerivedFromPublicId() {
			assertThat(CloudinaryService.folderOf("shop/abc/products/def")).isEqualTo("shop/abc/products");
			assertThat(CloudinaryService.folderOf("nopath")).isNull();
			assertThat(CloudinaryService.folderOf(null)).isNull();
		}
	}

	/**
	 * The live half, skipped unless {@code CLOUDINARY_CLOUD_NAME} is set.
	 *
	 * <p>Uses the environment-variable form of the condition rather than {@code @EnabledIf}:
	 * a method reference resolves against the class that declares it, and a static method on the
	 * outer class is not visible from the nested one. The env-var form has no such scoping
	 * problem and is also how this would be skipped in CI.
	 */
	@Nested
	@DisplayName("against the real Cloudinary account")
	@EnabledIfEnvironmentVariable(named = "CLOUDINARY_CLOUD_NAME", matches = ".+")
	class Live {

		@Test
		@DisplayName("signs a browser upload bound to the shop folder")
		void signsUpload() {
			CloudinaryService.UploadSignature signature = cloudinary.signUpload(folder);

			assertThat(signature.signature()).isNotBlank();
			assertThat(signature.cloudName()).isEqualTo(properties.cloudName());
			assertThat(signature.folder())
					.as("the signed folder is what the browser must send")
					.isEqualTo(folder);
			assertThat(signature.timestamp()).isPositive();
			// The limits travel with the signature so the browser can reject a large file
			// without spending the user's bandwidth on an upload that would be refused.
			assertThat(signature.maxBytes()).isPositive();
			assertThat(signature.maxDimension()).isPositive();
		}

		@Test
		@DisplayName("uploads an image and returns what media stores")
		void uploadsImage() {
			CloudinaryService.UploadResult result = cloudinary.upload(folder, pngBytes(), "probe.png");

			try {
				assertThat(result.publicId())
						.as("public_id is what makes deletion possible (D19)")
						.isNotBlank()
						.startsWith(folder);
				assertThat(result.secureUrl()).contains("res.cloudinary.com");
				assertThat(result.width()).isEqualTo(1);
				assertThat(result.height()).isEqualTo(1);
				assertThat(result.bytes()).isPositive();

				assertThat(cloudinary.listPublicIds(folder))
						.as("a shop's media must be listable in one call")
						.contains(result.publicId());
			} finally {
				cloudinary.delete(result.publicId());
			}
		}

		@Test
		@DisplayName("deletes an asset")
		void deletesAsset() {
			CloudinaryService.UploadResult result = cloudinary.upload(folder, pngBytes(), "doomed.png");
			assertThat(cloudinary.listPublicIds(folder)).contains(result.publicId());

			cloudinary.delete(result.publicId());

			assertThat(cloudinary.listPublicIds(folder))
					.as("after deletion the asset must be gone")
					.doesNotContain(result.publicId());
		}

		@Test
		@DisplayName("deleting something already gone is not an error")
		void deleteIsIdempotent() {
			// The goal is absence, so "not found" is success. A product save that fails because
			// a redundant delete 404s would be worse than the orphan it was avoiding.
			assertThatCode(() -> cloudinary.delete(folder + "/never-existed")).doesNotThrowAnyException();
			assertThatCode(() -> cloudinary.delete(null)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("rejects an empty file")
		void rejectsEmpty() {
			assertThatThrownBy(() -> cloudinary.upload(folder, new byte[0], "empty.png"))
					.isInstanceOf(CloudinaryService.MediaException.class)
					.hasMessageContaining("empty");
		}

		@Test
		@DisplayName("rejects a file over the size limit before uploading anything")
		void rejectsOversized() {
			byte[] tooBig = new byte[(int) properties.maxUploadBytes() + 1];

			assertThatThrownBy(() -> cloudinary.upload(folder, tooBig, "big.png"))
					.isInstanceOf(CloudinaryService.MediaException.class)
					.hasMessageContaining("limit is");
		}

		@Test
		@DisplayName("produces a width-capped delivery URL")
		void deliveryUrl() {
			CloudinaryService.UploadResult result = cloudinary.upload(folder, pngBytes(), "delivery.png");

			try {
				String url = cloudinary.deliveryUrl(result.publicId(), 400);

				assertThat(url)
						.as("a shop's 6000px original must never be served raw")
						.contains("res.cloudinary.com")
						.contains("w_400")
						.contains("q_auto");
			} finally {
				cloudinary.delete(result.publicId());
			}
		}

		@Test
		@DisplayName("a delivery URL for a blank public_id is null, not an exception")
		void deliveryUrlBlank() {
			assertThat(cloudinary.deliveryUrl(null, 400)).isNull();
			assertThat(cloudinary.deliveryUrl("", 400)).isNull();
		}
	}
}
