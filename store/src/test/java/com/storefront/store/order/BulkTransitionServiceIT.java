package com.storefront.store.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;
import com.storefront.store.wallet.WalletLedgerEntry;
import com.storefront.store.wallet.WalletLedgerRepository;
import com.storefront.store.wallet.WalletService;

/**
 * Bulk order transitions.
 *
 * <p>The behaviour worth protecting is <b>partial success</b>. An owner ticks four orders and
 * presses Accept; one was cancelled by the customer a moment earlier. The right outcome is that
 * three move and the owner is told which one did not. If one refusal could fail the batch, the
 * owner would learn nothing and would have to re-derive the safe subset by hand.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> The batch suspends any surrounding
 * transaction so that each order commits on its own; a test-level transaction would both hide the
 * fixture data from it and defeat the behaviour under test. Setup therefore commits on its own and
 * {@link #tearDown()} removes what was created, in foreign-key order.
 *
 * <p>ACCEPTED is the target throughout because it is one of only two statuses a PENDING order may
 * move to. Accepting debits commission from the prepaid wallet (D2), so the wallet is funded —
 * without that these tests would be measuring the wallet rather than the batch.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class BulkTransitionServiceIT {

	@Autowired
	private BulkTransitionService bulk;

	@Autowired
	private OrderService orders;

	@Autowired
	private ShopOrderRepository orderRepository;

	@Autowired
	private WalletService wallet;

	@Autowired
	private WalletLedgerRepository ledger;

	@Autowired
	private com.storefront.store.shop.ShopRepository shops;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PlatformTransactionManager tx;

	private final List<Shop> created = new java.util.ArrayList<>();

	@BeforeEach
	void setUp() {
		Shop shop = commit(() -> fixtures.createVerifiedShop("Bulk " + UUID.randomUUID(),
				ShopType.GROCERY));
		created.add(shop);
		signIn(shop);
		fund(shop);
	}

	/**
	 * Removes everything this test made, in foreign-key order.
	 *
	 * <p>The order matters and is not arbitrary: {@code wallet_ledger_entry} references both the
	 * shop and the order, {@code shop_order} references the shop, and only the order's own children
	 * (items, status history) cascade. Shops are financial records, so none of these cascade by
	 * design — which is why the deletion is explicit.
	 */
	@AfterEach
	void tearDown() {
		commit(() -> {
			for (Shop shop : created) {
				if (!shops.existsById(shop.getId())) {
					continue;
				}
				// One page is enough: a single test creates a handful of entries, and a loop
				// would be a quiet lie if that ever changed.
				ledger.deleteAll(ledger
						.findByShopIdOrderByCreatedAtDesc(shop.getId(),
								org.springframework.data.domain.PageRequest.of(0, 100))
						.getContent());
				orderRepository.deleteAll(orderRepository.findByShopId(shop.getId()));
				shops.deleteById(shop.getId());
			}
			return null;
		});
		created.clear();
	}

	// ------------------------------------------------------------------ plumbing

	/**
	 * Runs work in its own committed transaction.
	 *
	 * <p>Everything this class creates must be visible to the per-order transactions the batch
	 * opens, and must survive to be asserted on after they commit.
	 */
	private <T> T commit(java.util.function.Supplier<T> work) {
		TransactionTemplate template = new TransactionTemplate(tx);
		template.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
		return template.execute(status -> work.get());
	}

	private void signIn(Shop asShop) {
		// `sub` must be the owner's Keycloak subject, not their database id. IdentitySyncService
		// resolves by subject and creates a brand-new user when nothing matches, so a UUID here
		// produces a second user who owns nothing and TenantGuard answers "does not own a shop"
		// for every call -- a symptom that points at the shop rather than at the token.
		String subject = users.findById(asShop.getOwnerUserId())
				.map(AppUser::getKeycloakSub)
				.orElseThrow(() -> new IllegalStateException("owner row missing"));

		Jwt jwt = new Jwt("t-" + UUID.randomUUID(), java.time.Instant.now().minusSeconds(60),
				java.time.Instant.now().plusSeconds(600), java.util.Map.of("alg", "none"),
				java.util.Map.of("sub", subject,
						"iss", "https://auth-master.yenyasoft.com/realms/ops",
						"email", "owner@example.test"));

		TestSecurityContextHolder.getContext().setAuthentication(
				new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
						jwt, null, java.util.List.of()));
	}

	private void fund(Shop shop) {
		commit(() -> wallet.credit(shop.getId(), WalletLedgerEntry.EntryType.TOPUP_APPROVED,
				10_000_000L, "topup-" + UUID.randomUUID(), "test funding", null, null, null));
	}

	private ShopOrder pending() {
		return commit(() -> fixtures.createPendingOrder(currentShop().getId()));
	}

	/**
	 * Cancels an order the way a customer's cancellation does.
	 *
	 * <p>Through {@link OrderService}, not by writing the field: the entity's own
	 * {@code cancel(reason)} only records the <em>reason</em>, and the status belongs to the state
	 * machine. Setting the column directly produced an order that was still PENDING and therefore
	 * perfectly acceptable — a test that named a cancellation and never created one.
	 */
	private ShopOrder cancelledByCustomer() {
		return commit(() -> orders.cancel(currentShop(),
				orders.getOwned(currentShop(), fixtures.createPendingOrder(currentShop().getId()).getId()),
				"Cancelled by the customer", null));
	}

	private Shop currentShop() {
		return created.get(created.size() - 1);
	}

	// ------------------------------------------------------------------ the batch

	@Nested
	@DisplayName("moving several orders")
	class Batch {

		@Test
		@DisplayName("moves every order when they are all legal")
		void allSucceed() {
			ShopOrder a = pending();
			ShopOrder b = pending();
			ShopOrder c = pending();

			var result = bulk.transition(
					List.of(a.getId(), b.getId(), c.getId()), ShopOrder.Status.ACCEPTED, null);

			assertThat(result.moved()).hasSize(3);
			assertThat(result.refused()).isEmpty();
			assertThat(result.requested()).isEqualTo(3);
			assertThat(result.allSucceeded()).isTrue();
		}

		@Test
		@DisplayName("keeps the ones that worked when one is refused")
		void partialSuccess() {
			// The scenario this class exists for: one order was cancelled by the customer a moment
			// ago, and the owner had already ticked it.
			ShopOrder first = pending();
			ShopOrder second = pending();
			ShopOrder third = pending();
			ShopOrder gone = cancelledByCustomer();

			var result = bulk.transition(
					List.of(first.getId(), second.getId(), third.getId(), gone.getId()),
					ShopOrder.Status.ACCEPTED, null);

			assertThat(result.moved()).containsExactlyInAnyOrder(
					first.getId(), second.getId(), third.getId());
			assertThat(result.refused()).containsOnlyKeys(gone.getId());
			assertThat(result.allSucceeded()).isFalse();
			// The refusal names the order and says why. "3 of 4 succeeded" would leave the owner
			// unable to act, and the one left behind is exactly the one that needs them.
			assertThat(result.refused().get(gone.getId())).containsIgnoringCase("cancel");
		}

		@Test
		@DisplayName("ignores a duplicate id rather than reporting a spurious failure")
		void duplicates() {
			// A client that sends the same id twice must not see a refusal for the second attempt —
			// that would show the owner one of their own orders as broken.
			ShopOrder only = pending();

			var result = bulk.transition(List.of(only.getId(), only.getId()),
					ShopOrder.Status.ACCEPTED, null);

			assertThat(result.requested()).isEqualTo(1);
			assertThat(result.allSucceeded()).isTrue();
		}

		@Test
		@DisplayName("an empty batch does nothing")
		void empty() {
			var result = bulk.transition(List.of(), ShopOrder.Status.ACCEPTED, null);

			// Vacuously true, which is the honest answer for a batch of zero rather than an error
			// the owner needs to see.
			assertThat(result.moved()).isEmpty();
			assertThat(result.requested()).isZero();
			assertThat(result.allSucceeded()).isTrue();
		}

		@Test
		@DisplayName("refuses PENDING up front, not once per order")
		void pendingIsRefused() {
			// An order is created PENDING; there is no transition back. Refusing up front means one
			// clear message instead of a hundred identical refusals burying the real ones.
			assertThatThrownBy(() -> bulk.transition(
					List.of(pending().getId(), pending().getId()),
					ShopOrder.Status.PENDING, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("cannot be moved back to PENDING");
		}
	}

	@Test
	@DisplayName("another shop's order is refused, not moved")
	void crossTenant() {
		Shop other = commit(() -> fixtures.createShop("Other " + UUID.randomUUID(), ShopType.GROCERY));
		created.add(other);
		ShopOrder theirs = commit(() -> fixtures.createPendingOrder(other.getId()));

		var result = bulk.transition(List.of(theirs.getId()), ShopOrder.Status.ACCEPTED, null);

		// Still signed in as the first shop. A guessed id must not move somebody else's order.
		assertThat(result.moved()).isEmpty();
		assertThat(result.refused()).containsKey(theirs.getId());
	}

	@Test
	@DisplayName("a batch is capped")
	void capped() {
		// A 100-id cap stops an accidental double-submit of the whole board.
		List<UUID> many = java.util.stream.IntStream.range(0, BulkTransitionService.MAX_BATCH + 50)
				.mapToObj(i -> UUID.randomUUID())
				.toList();

		var result = bulk.transition(many, ShopOrder.Status.ACCEPTED, null);

		assertThat(result.requested()).isEqualTo(BulkTransitionService.MAX_BATCH);
	}
}