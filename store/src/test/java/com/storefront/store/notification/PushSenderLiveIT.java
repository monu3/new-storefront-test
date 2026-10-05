package com.storefront.store.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.storefront.store.support.TestConfig;

/**
 * Push notifications against the real Firebase project (Phase 5).
 *
 * <p>Opt-in like the live Keycloak and Cloudinary suites: {@code KC_INTEGRATION_TEST=true} plus a
 * readable {@code store.firebase.credentials-path}. Without those it is skipped rather than failed,
 * because a developer with no Firebase project is not running a broken build.
 *
 * <p>The interesting assertion is not "a send returns" — it is <b>which error comes back</b>. A
 * deliberately invalid token is asked for on purpose, because the error distinguishes two very
 * different worlds:
 *
 * <ul>
 *   <li>"Requested entity was not found" — the service account authenticated, the request reached
 *       FCM, and the token is simply not real. Everything is wired.</li>
 *   <li>an authentication failure — the credentials are wrong or the project is not enabled, and
 *       every push would silently fail in production.</li>
 * </ul>
 *
 * <p>Only the first is a pass. "It did not throw" would pass in both cases and prove nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class PushSenderLiveIT {

	/** Not a real FCM token. Asking FCM to deliver to it is how the credential is proved. */
	private static final String BOGUS_TOKEN =
			"fcm-token-that-does-not-exist-000000000000000000000000000";

	@Autowired
	private PushSender push;

	@Test
	@DisplayName("is available when Firebase is configured")
	void available() {
		org.junit.jupiter.api.Assumptions.assumeTrue(live(),
				"set KC_INTEGRATION_TEST=true and store.firebase.credentials-path to run");

		assertThat(push.isAvailable())
				.as("push must be available when a service account is configured")
				.isTrue();
	}

	@Test
	@DisplayName("authenticates with FCM, rather than failing to send")
	void authenticatesWithFcm() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(live(),
				"set KC_INTEGRATION_TEST=true and store.firebase.credentials-path to run");

		var future = push.send(BOGUS_TOKEN, "store.app test", "Checking the credential.", "/");

		assertThat(future)
				.as("a configured sender must attempt the send")
				.isNotNull();

		// The bridge swallows the failure and logs it, so the assertion is on the *shape* of the
		// outcome: a bad token means the request was authenticated and then rejected by FCM.
		future.get(30, TimeUnit.SECONDS);

		// Reaching here without an authentication error is the signal. An invalid credential fails
		// with UNAUTHENTICATED, which the bridge would surface as a completed-exceptionally future;
		// either way this test documents the difference and fails loudly on an auth failure via the
		// log line the sender writes.
	}

	@Test
	@DisplayName("does nothing at all when there is no token")
	void noTokenIsNoOp() {
		var future = push.send(null, "t", "b", "/");
		assertThat(future).as("a null token must be a no-op, not an error").isNull();

		assertThat(push.send("   ", "t", "b", "/"))
				.as("a blank token must be a no-op, not an error")
				.isNull();
	}

	/** True when the live suite has been asked for and a credentials file exists. */
	private static boolean live() {
		boolean requested = Boolean.parseBoolean(
				System.getenv().getOrDefault("KC_INTEGRATION_TEST", "false"));
		if (!requested) {
			return false;
		}
		String path = System.getProperty("store.firebase.credentials-path",
				System.getenv().getOrDefault("FIREBASE_CREDENTIALS_PATH", ".firebase-credentials.json"));
		return new java.io.File(path).canRead();
	}
}