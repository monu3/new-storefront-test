package com.storefront.store.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Authenticated encryption for merchant secrets.
 *
 * <p>eSewa merchant secrets and bank account numbers are credentials, not configuration. The
 * baseline schema already reserves {@code bytea} columns for them, and this is what writes them.
 *
 * <p><b>AES-256-GCM, not AES-CBC.</b> GCM is authenticated: a ciphertext that has been altered
 * fails to decrypt rather than decrypting to garbage. That matters because this key encrypts
 * money-routing instructions — a bank account number that decrypts to a different account number
 * would send a customer's payment somewhere else, with nothing to notice it. CBC alone would
 * happily return plausible bytes for a modified ciphertext.
 *
 * <p><b>The 12-byte nonce is random per encryption and stored in front of the ciphertext.</b>
 * GCM nonce reuse under one key is catastrophic — it leaks the authentication key and allows
 * plaintext recovery — so a random 96-bit nonce is generated per call and never derived from the
 * plaintext. The version byte in front of it means the stored blob can be recognised and
 * migrated if the scheme ever changes.
 *
 * <p><b>The key is derived from a passphrase with PBKDF2.</b> This is not a substitute for a real
 * KMS, and it is honest about that: an operator who sets {@code STORE_SECRET_KEY} to a real secret
 * gets a real key, and one who sets it to a word gets a weak one. The alternative — shipping a
 * default key in the jar — would mean every deployment shares one.
 *
 * <p><b>What happens with no passphrase configured is the important part.</b> Rather than
 * generating a random key and losing every secret on restart, or shipping a well-known default,
 * this refuses to encrypt and says so. A shop owner who saved a merchant secret and is told "the
 * server is not configured for secrets" can fix it in a minute; one whose secret silently fails
 * to decrypt later has a live payment problem they will discover from a customer.
 */
@Component
@Slf4j
public class SecretCipher {

	/** Marks the stored format, so an old blob can be told from a new one. */
	private static final byte VERSION = 1;

	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;

	public SecretCipher(@Value("${store.secrets.key:}") String passphrase) {
		this.key = passphrase == null || passphrase.isBlank() ? null : deriveKey(passphrase);
	}

	/** True when this build can store secrets at all. Checked before the owner is asked for one. */
	public boolean isConfigured() {
		return key != null;
	}

	/**
	 * Encrypts a plaintext secret.
	 *
	 * @throws SecretStorageUnavailableException when no passphrase is configured
	 */
	public byte[] encrypt(String plaintext) {
		if (key == null) {
			throw new SecretStorageUnavailableException();
		}
		if (plaintext == null || plaintext.isEmpty()) {
			// A null blob and an empty blob must mean the same thing, or "clear this field" and
			// "save nothing" produce different rows.
			return null;
		}
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			new SecureRandom().nextBytes(nonce);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			byte[] out = new byte[1 + NONCE_BYTES + ciphertext.length];
			out[0] = VERSION;
			System.arraycopy(nonce, 0, out, 1, NONCE_BYTES);
			System.arraycopy(ciphertext, 0, out, 1 + NONCE_BYTES, ciphertext.length);
			return out;
		} catch (java.security.GeneralSecurityException ex) {
			// Deliberately does not include the plaintext or the exception's cause message, which
			// can contain the key material's context.
			throw new IllegalStateException("Could not encrypt the secret");
		}
	}

	/**
	 * Decrypts a stored secret.
	 *
	 * @return the plaintext, or null when the blob is null — "never set" is not "failed"
	 * @throws IllegalStateException when the blob has been altered or the key has changed, because
	 *         both mean the caller must not be handed wrong bytes as if they were correct
	 */
	public String decrypt(byte[] blob) {
		if (blob == null || blob.length == 0) {
			return null;
		}
		if (key == null) {
			throw new SecretStorageUnavailableException();
		}
		if (blob[0] != VERSION) {
			throw new IllegalStateException("Stored secret uses an unknown format version");
		}
		try {
			byte[] nonce = Arrays.copyOfRange(blob, 1, 1 + NONCE_BYTES);
			byte[] ciphertext = Arrays.copyOfRange(blob, 1 + NONCE_BYTES, blob.length);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		} catch (javax.crypto.AEADBadTagException ex) {
			// Authentication failed: altered ciphertext, or the key was rotated without the blobs
			// being re-encrypted. Either way the caller must not proceed with wrong bytes.
			throw new IllegalStateException(
					"Stored secret could not be authenticated; it may have been altered or the key changed");
		} catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException("Could not decrypt the secret");
		}
	}

	/**
	 * PBKDF2 with a per-deployment salt derived from the passphrase.
	 *
	 * <p>The salt is not random per encryption — it has to be reproducible, because the key must be
	 * the same on every boot or nothing decrypts. A fixed context string keeps it distinct from
	 * any other use of the same passphrase.
	 */
	private static SecretKeySpec deriveKey(String passphrase) {
		try {
			byte[] salt = MessageDigest.getInstance("SHA-256")
					.digest("store-app/merchant-secret/v1".getBytes(StandardCharsets.UTF_8));

			javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory
					.getInstance("PBKDF2WithHmacSHA256");
			javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
					passphrase.toCharArray(), salt, 210_000, 256);
			return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
		} catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException("Could not derive the secret key");
		}
	}

	/** Secrets cannot be stored because no passphrase is configured. */
	public static class SecretStorageUnavailableException extends RuntimeException {
		public SecretStorageUnavailableException() {
			super("This server is not configured to store payment secrets. "
					+ "Set STORE_SECRETS_KEY to enable them.");
		}
	}
}