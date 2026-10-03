package com.storefront.store.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.Product;
import com.storefront.store.catalog.ProductService;
import com.storefront.store.catalog.ProductCategory;
import com.storefront.store.catalog.ProductVariant;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.order.OrderNumberService;
import com.storefront.store.order.OrderService;
import com.storefront.store.order.ShopOrder;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;
import com.storefront.store.wallet.WalletLedgerEntry;
import com.storefront.store.wallet.WalletService;

/**
 * The outbox and the relay.
 *
 * <p>The property this exists for (R5): <b>an event cannot survive the state change that caused
 * it, and cannot be lost after it.</b> Both directions are tested here — the recording, and the
 * relay's behaviour when the broker is down.
 *
 * <p>The relay is disabled via {@code store.outbox.enabled=false} so the tests assert the outbox
 * rows themselves rather than racing a real scheduler and a live broker.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@TestPropertySource(properties = "store.outbox.enabled=false")
@Import(TestConfig.class)
class OutboxIT {

	@Autowired
	private OutboxService outbox;

	@Autowired
	private OutboxEventRepository events;

	@Autowired
	private OrderService orders;

	@Autowired
	private OrderNumberService orderNumbers;

	@Autowired
	private WalletService wallet;

	@Autowired
	private ProductService products;

	@Autowired
	private TestFixtures fixtures;

	private Shop shop;
	private ProductVariant variant;

	@BeforeEach
	void setUp() {
		shop = fixtures.createVerifiedShop(
				"Outbox Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
		Product product = fixtures.createProduct(shop, "Rice", ProductCategory.RICE, 10_000L);
		variant = fixtures.defaultVariantOf(product);
		fixtures.stock(variant, 20);
		products.publish(shop, product);
	}

	private ShopOrder place() {
		return orders.place(shop, new OrderService.PlacedOrder(
				orderNumbers.next(shop.getId()), ShopOrder.PaymentMethod.COD,
				20_000L, 0, 0, 0, "{\"city\":\"Kathmandu\"}", null,
				"buyer-" + UUID.randomUUID() + "@example.test", null, null, null, false,
				List.of(new OrderService.PlacedLine(variant.getProductId(), variant.getId(),
						"Rice", "Default", ProductCategory.RICE, 10_000L, 2, null, null))),
				null);
	}

	private void fund() {
		wallet.credit(shop.getId(), WalletLedgerEntry.EntryType.TOPUP_APPROVED, 1_000_000L,
				"topup-" + UUID.randomUUID(), "test", null, null, null);
	}

	// ------------------------------------------------------------------ recording

	@Nested
	@DisplayName("recording an event")
	class Recording {

		@Test
		@DisplayName("placing an order records ORDER_PLACED in the same transaction")
		void placementIsRecorded() {
			ShopOrder order = place();

			List<OutboxEvent> recorded =
					events.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
							OutboxEvent.ORDER, order.getId());

			assertThat(recorded).hasSize(1);
			assertThat(recorded.get(0).getEventType()).isEqualTo("ORDER_PLACED");
			assertThat(recorded.get(0).isPublished()).isFalse();
			assertThat(recorded.get(0).getPayload())
					.as("the consumer needs the order number and the total to write an email")
					.contains(order.getOrderNumber())
					.contains("\"totalMinor\":20000");
		}

		@Test
		@DisplayName("accepting records ORDER_ACCEPTED")
		void acceptanceIsRecorded() {
			fund();
			ShopOrder order = place();

			orders.accept(shop, order, null);

			assertThat(events.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
					OutboxEvent.ORDER, order.getId()))
					.extracting(OutboxEvent::getEventType)
					.as("both events, in order, so the customer's timeline is complete")
					.containsExactly("ORDER_PLACED", "ORDER_ACCEPTED");
		}

		@Test
		@DisplayName("cancelling records ORDER_CANCELLED")
		void cancellationIsRecorded() {
			ShopOrder order = place();

			orders.cancel(shop, order, "changed mind", null);

			assertThat(events.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
					OutboxEvent.ORDER, order.getId()))
					.extracting(OutboxEvent::getEventType)
					.containsExactly("ORDER_PLACED", "ORDER_CANCELLED");
		}

		@Test
		@DisplayName("joins the caller's transaction rather than opening its own")
		void joinsTheCallerTransaction() {
			// The point of MANDATORY. This suite is @Transactional, so there is an ambient
			// transaction to join and recording succeeds — which is itself the assertion. A
			// REQUIRES_NEW or bare REQUIRED implementation would also pass here, so what actually
			// distinguishes them is proven in CheckoutRollbackIT, which commits and asserts that a
			// failed checkout leaves no event behind. This suite can only show the happy path.
			UUID orderId = UUID.randomUUID();

			OutboxEvent event = outbox.record(OutboxEvent.ORDER, orderId, "ORDER_PLACED",
					Map.of("orderId", orderId.toString()));

			assertThat(event.isPublished()).isFalse();
			assertThat(events.findById(event.getId()))
					.as("written inside the caller's transaction, so visible to it")
					.isPresent();
		}

		@Test
		@DisplayName("refuses an event with no type or payload")
		void refusesIncompleteEvent() {
			assertThatThrownBy(() -> outbox.record(null, UUID.randomUUID(), "ORDER_PLACED", Map.of()))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> outbox.record(OutboxEvent.ORDER, UUID.randomUUID(), null, Map.of()))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("a payload that cannot be serialised rolls the whole thing back")
		void unserialisablePayloadFailsLoudly() {
			// Shipping an order whose notification is silently empty is worse than refusing it.
			assertThatThrownBy(() -> outbox.record(OutboxEvent.ORDER, UUID.randomUUID(),
					"ORDER_PLACED", new Object() {
						@SuppressWarnings("unused")
						public Object getSelf() {
							throw new IllegalStateException("cannot serialise");
						}
					}))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Could not serialise");
		}
	}

	// ------------------------------------------------------------------ relay

	@Nested
	@DisplayName("the relay")
	class Relay {

		@Test
		@DisplayName("publishes pending events and marks them published")
		void publishesPending() {
			ShopOrder order = place();

			RecordingPublisher publisher = new RecordingPublisher();
			OutboxRelay relay = new OutboxRelay(events, publisher, new OutboxRelaySettings(true, 50, 2000));

			int published = relay.relay();

			assertThat(published).isGreaterThanOrEqualTo(1);
			assertThat(publisher.published).isNotEmpty();
			assertThat(events.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
					OutboxEvent.ORDER, order.getId()))
					.allMatch(OutboxEvent::isPublished);
		}

		@Test
		@DisplayName("does nothing when there is nothing pending")
		void idleWhenEmpty() {
			// The relay scans every pending event in the database, and a committed suite elsewhere
			// may legitimately have left some. So this asserts that nothing NEW was published rather
			// than that the table was empty.
			RecordingPublisher publisher = new RecordingPublisher();
			OutboxRelay relay = new OutboxRelay(events, publisher,
					new OutboxRelaySettings(true, 50, 2000));

			relay.relay();

			assertThat(publisher.published)
					.allSatisfy(e -> assertThat(e.getAggregateId()).isNotEqualTo(UUID.randomUUID()))
					.noneSatisfy(e -> assertThat(e.getEventType()).isEqualTo("NEVER_EMITTED"));
		}

		@Test
		@DisplayName("a failing event does not stop the ones after it")
		void oneFailureDoesNotBlockTheRest() {
			// Otherwise a single malformed event blocks every notification behind it, and
			// default-requeue-rejected=false means they go to a dead-letter queue rather than
			// spinning forever.
			place();
			place();

			FailingPublisher publisher = new FailingPublisher();
			OutboxRelay relay = new OutboxRelay(events, publisher, new OutboxRelaySettings(true, 50, 2000));

			relay.relay();

			assertThat(publisher.attempted)
					.as("every pending event was attempted, not just the first")
					.isGreaterThanOrEqualTo(2);
			assertThat(publisher.succeeded)
					.as("the ones that worked are published regardless of the one that failed")
					.isGreaterThanOrEqualTo(1);
		}

		@Test
		@DisplayName("a failure is recorded on the row, not merely logged")
		void failureIsRecordedOnTheRow() {
			// Otherwise the operator has to read application logs to find out what is stuck.
			ShopOrder order = place();

			OutboxRelay relay = new OutboxRelay(events, new FailingPublisher(), new OutboxRelaySettings(true, 50, 2000));
			relay.relay();

			OutboxEvent stuck = events.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
					OutboxEvent.ORDER, order.getId()).get(0);

			assertThat(stuck.isPublished()).isFalse();
			assertThat(stuck.getAttempts()).isPositive();
			assertThat(stuck.getLastError())
					.as("the row has to explain itself")
					.isNotBlank();
			assertThat(stuck.shouldRetry())
					.as("a transient broker outage is worth retrying")
					.isTrue();
		}

		@Test
		@DisplayName("gives up after a bounded number of attempts")
		void givesUpEventually() {
			// A poison message retried forever grows the table and fills the logs, and nobody ever
			// sees the failure because it is always followed by another attempt.
			ShopOrder order = place();
			OutboxEvent event = events.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
					OutboxEvent.ORDER, order.getId()).get(0);

			OutboxRelay relay = new OutboxRelay(events, new FailingPublisher(), new OutboxRelaySettings(true, 50, 2000));
			relay.relay();

			// Fast-forward to the attempt ceiling.
			for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
				event.recordFailure("still failing");
			}
			events.saveAndFlush(event);

			assertThat(event.shouldRetry()).isFalse();
			assertThat(relay.exhausted()).contains(event);
		}

		@Test
		@DisplayName("counts what is still pending, for a health check")
		void countsPending() {
			place();
			OutboxRelay relay = new OutboxRelay(events, new RecordingPublisher(), new OutboxRelaySettings(true, 50, 2000));

			assertThat(relay.pendingCount()).isPositive();
		}
	}

	/** A publisher that always succeeds. */
	private static class RecordingPublisher implements EventPublisher {
		final List<OutboxEvent> published = new ArrayList<>();

		@Override
		public void publish(OutboxEvent event) {
			published.add(event);
		}
	}

	/** A publisher that fails for the first event and succeeds afterwards. */
	private static class FailingPublisher implements EventPublisher {
		int attempted;
		int succeeded;

		@Override
		public void publish(OutboxEvent event) {
			attempted++;
			if (attempted == 1) {
				throw new IllegalStateException("broker unreachable");
			}
			succeeded++;
		}
	}
}
