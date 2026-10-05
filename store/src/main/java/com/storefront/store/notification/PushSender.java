package com.storefront.store.notification;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushNotification;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Order-status push, over Firebase Cloud Messaging (Phase 5).
 *
 * <p><b>Refuses rather than pretends.</b> With no credentials configured this reports every send as
 * unavailable and sends nothing. The alternative — logging an error and continuing — means the
 * notification system looks alive, the owner's order-status notifications quietly never arrive,
 * and nobody finds out until a customer complains.
 *
 * <p><b>Push is an optimisation, never the source of truth.</b> A customer who has notifications
 * blocked, has no device registered, or is offline still sees their order's state in the app. Push
 * exists to save them a page load; a failed push must cost a page load and nothing else.
 *
 * <p><b>Credentials come from a file, not from configuration.</b> A service-account private key is
 * a multi-line PEM; putting one inside YAML or an environment variable means escaping it, and one
 * wrong backslash produces a key that parses to the wrong thing and fails at the first send rather
 * than at startup.
 */
@Component
@Slf4j
public class PushSender {

	private final FirebaseProperties properties;
	private final FirebaseMessaging messaging;
	private final boolean available;

	public PushSender(FirebaseProperties properties,
			@Value("${store.firebase.enabled:false}") boolean enabled) {

		this.properties = properties;
		this.messaging = enabled ? initialise(properties) : null;
		this.available = messaging != null;

		if (!available) {
			log.info("Push notifications are disabled: Firebase is not configured. "
					+ "Order statuses are still delivered in-app.");
		}
	}

	/** Whether push can actually be sent. Checked by the caller before doing any work. */
	public boolean isAvailable() {
		return available;
	}

	/**
	 * Builds the Firebase app, or returns null when it cannot be.
	 *
	 * <p>Null rather than an exception: push being misconfigured must not stop the application
	 * booting, or a missing environment variable takes the whole storefront down.
	 */
	private static FirebaseMessaging initialise(FirebaseProperties properties) {
		try {
			if (properties.credentialsPath() == null || properties.credentialsPath().isBlank()) {
				return null;
			}

			Path path = Path.of(properties.credentialsPath());
			if (!Files.isReadable(path)) {
				log.warn("Firebase credentials not readable at {}. Push is disabled.", path);
				return null;
			}

			FirebaseOptions.Builder options = FirebaseOptions.builder()
					.setCredentials(GoogleCredentials.fromStream(credentialStream(path)));
			if (properties.projectId() != null && !properties.projectId().isBlank()) {
				options.setProjectId(properties.projectId());
			}

			// getApps() first: the SDK throws on a second initialise of the same project, and a
			// context reload in a test would otherwise fail every time.
			FirebaseApp app = FirebaseApp.getApps().isEmpty()
					? FirebaseApp.initializeApp(options.build())
					: FirebaseApp.getInstance();

			return FirebaseMessaging.getInstance(app);

		} catch (Exception ex) {
			log.warn("Could not initialise Firebase ({}). Push is disabled; order statuses are "
					+ "still delivered in-app.", ex.getMessage());
			return null;
		}
	}

	/**
	 * Opens the service-account JSON for the SDK to read.
	 *
	 * <p>Closed on a shutdown hook rather than handed over. The SDK reads the stream during
	 * {@code initialiseApp} and keeps no reference, but an unclosed handle on the credentials file
	 * breaks a developer who edits it while the server is running.
	 */
	private static InputStream credentialStream(Path path) throws java.io.IOException {
		InputStream in = Files.newInputStream(path);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				in.close();
			} catch (java.io.IOException ignored) {
				// Nothing useful to do at shutdown.
			}
		}));
		return in;
	}

	// ------------------------------------------------------------------ send

	/**
	 * Sends one notification to one device.
	 *
	 * <p>Asynchronous, and deliberately not awaited by the caller. Push is a courtesy; blocking an
	 * order-status update on a round trip to Google's servers would add a third-party timeout to
	 * the path that marks an order shipped.
	 *
	 * @return a future completing when the send settles; null when push is unavailable
	 */
	public CompletableFuture<Void> send(String token, String title, String body, String path) {

		if (!available || token == null || token.isBlank()) {
			return null;
		}

		try {
			Message message = Message.builder()
					.setToken(token)
					.setNotification(Notification.builder()
							.setTitle(title)
							.setBody(body)
							.build())
					// Both the webpush and the android blocks are set because one message is served
					// to every platform at once; FCM ignores the blocks a device does not use.
					.setWebpushConfig(WebpushConfig.builder()
							.setNotification(WebpushNotification.builder()
									.setTitle(title)
									.setBody(body)
									.build())
							// Where the notification links to. Relative, so it stays correct across
							// every storefront deployment without being configured per environment.
							.putData("path", path == null ? "/" : path)
							.build())
					.setAndroidConfig(AndroidConfig.builder()
							.setNotification(AndroidNotification.builder()
									.setTitle(title)
									.setBody(body)
									.setChannelId("orders")
									.build())
							.build())
					.build();

			// `sendAsync`, not `send`: in firebase-admin 9.x `send` is blocking and returns a String,
			// so using it would put a round trip to Google's servers on the path that marks an order
			// shipped. The returned Guava future is bridged so callers get a CompletableFuture.
			com.google.api.core.ApiFuture<String> sent = messaging.sendAsync(message);
			CompletableFuture<String> done = new CompletableFuture<>();
			sent.addListener(() -> {
				try {
					done.complete(sent.get());
				} catch (Exception ex) {
					done.completeExceptionally(ex);
				}
			}, Runnable::run);

			return done.thenAccept(id -> log.debug("Push {} sent to {}", id, mask(token)))
					.exceptionally(ex -> {
						// Logged, not rethrown. A failed push must not fail the order transition that
						// triggered it; the customer sees the same state in the app regardless.
						log.warn("Push to {} failed: {}", mask(token), ex.getMessage());
						return null;
					});

		} catch (RuntimeException ex) {
			// Building the message can still fail -- a malformed token, an over-long title. Caught
			// broadly because the point is that no push failure may propagate to the caller, who is
			// an order transition and not a notification handler.
			log.warn("Could not build the push message: {}", ex.getMessage());
			return null;
		}
	}

	/** The first few characters of a token, enough to correlate, not enough to be a credential. */
	private static String mask(String token) {
		return token.length() <= 12 ? "***" : token.substring(0, 12) + "…";
	}

	@PreDestroy
	void shutdown() {
		if (available && !FirebaseApp.getApps().isEmpty()) {
			try {
				FirebaseApp.getInstance().delete();
			} catch (Exception ex) {
				log.debug("Firebase shutdown: {}", ex.getMessage());
			}
		}
	}

	/** Where the credentials live. Bound from {@code store.firebase.*}. */
	@ConfigurationProperties(prefix = "store.firebase")
	public record FirebaseProperties(
			boolean enabled,
			String projectId,
			/** Path to the service-account JSON. A path, not inline JSON — see the class note. */
			String credentialsPath) {
	}
}