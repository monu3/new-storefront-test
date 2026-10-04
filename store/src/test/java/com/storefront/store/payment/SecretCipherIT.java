package com.storefront.store.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.crypto.AEADBadTagException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.support.TestConfig;

/**
 * Secret encryption, and the rule that a payment method without its credentials cannot be offered.
 *
 * <p>The cipher is tested on its own rather than only through the service. These are the properties
 * that decide whether a leaked database leaks money-routing instructions, and none of them are
 * visible from the API.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class SecretCipherIT {

	@Autowired
	private SecretCipher cipher;

	@Nested
	@DisplayName("with a configured key")
	class Configured {

		@Test
		@DisplayName("round-trips a secret")
		void roundTrip() {
			String plaintext = "ESEWA:SECRET-abc123";
			assertThat(cipher.decrypt(cipher.encrypt(plaintext))).isEqualTo(plaintext);
		}

		@Test
		@DisplayName("never stores the plaintext")
		void notPlaintext() {
			byte[] blob = cipher.encrypt("BANK-ACCOUNT-99887");
			assertThat(new String(blob, StandardCharsets.ISO_8859_1))
					.doesNotContain("BANK-ACCOUNT-99887");
		}

		@Test
		@DisplayName("produces a different ciphertext every time")
		void noncesAreUnique() {
			// The same secret encrypted twice must differ. Identical blobs mean a reused GCM nonce,
			// which leaks the authentication key and allows plaintext recovery -- so this test is
			// not a curiosity, it is the property the nonce exists for.
			byte[] first = cipher.encrypt("same-secret");
			byte[] second = cipher.encrypt("same-secret");
			assertThat(first).isNotEqualTo(second);
			assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second));
		}

		@Test
		@DisplayName("refuses a ciphertext that has been altered")
		void detectsTampering() {
			byte[] blob = cipher.encrypt("ACCOUNT-1");
			blob[blob.length - 1] ^= 0x01;

			// GCM is authenticated, so this throws rather than returning corrupted bytes. A bank
			// account number that decrypts to a different account number sends someone's money
			// somewhere else, with nothing to notice it -- which is why CBC would be wrong here.
			assertThatThrownBy(() -> cipher.decrypt(blob))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("could not be authenticated");
		}

		@Test
		@DisplayName("treats null and empty as 'no secret'")
		void blankMeansAbsent() {
			assertThat(cipher.encrypt(null)).isNull();
			assertThat(cipher.encrypt("")).isNull();
			assertThat(cipher.decrypt(null)).isNull();
		}
	}

	@Nested
	@DisplayName("without a configured key")
	class Unconfigured {

		@Test
		@DisplayName("reports itself unavailable rather than using a default key")
		void notConfigured() {
			// Built directly rather than autowired: the application's own bean is configured, so
			// the unconfigured path needs its own instance.
			SecretCipher blank = new SecretCipher("");

			assertThat(blank.isConfigured()).isFalse();
			// Refusing is the point. Generating a random key would lose every secret on restart;
			// shipping a default in the jar would mean every deployment shares one.
			assertThatThrownBy(() -> blank.encrypt("anything"))
					.isInstanceOf(SecretCipher.SecretStorageUnavailableException.class)
					.hasMessageContaining("STORE_SECRETS_KEY");
		}
	}

	@Test
	@DisplayName("a different key cannot decrypt")
	void wrongKeyFails() {
		byte[] blob = cipher.encrypt("SHOP-MERCHANT-SECRET");
		SecretCipher other = new SecretCipher("a completely different passphrase");

		// Rotating the passphrase without re-encrypting leaves every stored secret unreadable.
		// Better that it says so loudly than that it hands back wrong bytes.
		assertThatThrownBy(() -> other.decrypt(blob))
				.isInstanceOf(IllegalStateException.class);
	}
}