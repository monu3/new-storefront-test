package com.storefront.store.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.common.Money;
import com.storefront.store.wallet.WalletLedgerEntry.Direction;
import com.storefront.store.wallet.WalletLedgerEntry.EntryType;
import com.storefront.store.wallet.WalletService.InsufficientWalletBalanceException;

/**
 * The wallet ledger against a real database (plan risk R1).
 *
 * <p>Unit tests can prove arithmetic; only a real Postgres can prove the constraints do what the
 * code assumes. These run against {@code store_app_test} and are rolled back, so the ledger is
 * left exactly as it was found.
 *
 * <p>The three properties that matter:
 * <ol>
 *   <li>a debit is impossible without funds,</li>
 *   <li>the same idempotency key can never move money twice,</li>
 *   <li>the cache and the ledger never disagree, because they are written together.</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(com.storefront.store.support.TestConfig.class)
class WalletServiceIT {

	@Autowired
	private WalletService wallet;

	@Autowired
	private WalletLedgerRepository ledger;

	@Autowired
	private WalletBalanceRepository balances;

	@Autowired
	private com.storefront.store.support.TestFixtures fixtures;

	private UUID shopId;

	@BeforeEach
	void setUp() {
		// A real shop row: the wallet's foreign key to shop(id) means a fabricated id would
		// fail the constraint before the test logic ever ran. Recreated per test because every
		// @Nested class gets a fresh instance of the outer class.
		shopId = fixtures.newShopId();
		System.out.println("DEBUG setUp shopId=" + shopId);
	}

	private void fund(long minor) {
		wallet.credit(shopId, EntryType.TOPUP_APPROVED, minor,
				"topup:" + UUID.randomUUID(), "Test top-up", null, null, null);
	}

	private long balance() {
		return wallet.balanceMinorOf(shopId).orElse(0L);
	}

	@Nested
	@DisplayName("opening a wallet")
	class Opening {

		@Test
		@DisplayName("a shop with no movements has no balance row yet")
		void noRowUntilUsed() {
			// A balance row is opened on first movement, not eagerly for every shop. Asserting
			// "exists" here would be asserting an implementation detail, and the meaningful
			// guarantee is simply that a shop with no money has a balance of zero.
			assertThat(balances.findByShopId(shopId)).isEmpty();
			assertThat(wallet.balanceOf(shopId))
					.as("a brand new wallet opens at zero")
					.isZero();
		}

		@Test
		@DisplayName("asking twice does not create two wallets")
		void isIdempotent() {
			wallet.balanceOf(shopId);
			wallet.balanceOf(shopId);
			assertThat(balances.findByShopId(shopId)).isPresent();
		}
	}

	@Nested
	@DisplayName("crediting")
	class Crediting {

		@Test
		@DisplayName("increases the balance")
		void increasesBalance() {
			fund(100_000);
			assertThat(balance()).isEqualTo(100_000);
		}

		@Test
		@DisplayName("accumulates across several top-ups")
		void accumulates() {
			fund(50_000);
			fund(25_000);
			fund(25_000);
			assertThat(balance()).isEqualTo(100_000);
		}

		@Test
		@DisplayName("rejects a non-positive amount")
		void rejectsNonPositive() {
			assertThatThrownBy(() -> wallet.credit(shopId, EntryType.TOPUP_APPROVED, 0,
					"k" + UUID.randomUUID(), "zero", null, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("the same key credits only once")
		void sameKeyCreditsOnce() {
			String key = "topup:fixed-key";
			wallet.credit(shopId, EntryType.TOPUP_APPROVED, 10_000, key, "first", null, null, null);
			wallet.credit(shopId, EntryType.TOPUP_APPROVED, 10_000, key, "second", null, null, null);

			assertThat(balance())
					.as("a retried top-up request must not add money twice")
					.isEqualTo(10_000);
		}
	}

	@Nested
	@DisplayName("debiting")
	class Debiting {

		@Test
		@DisplayName("reduces the balance when funds are available")
		void reducesBalance() {
			fund(100_000);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000,
					"commission:order-1", "Commission", null, null);

			assertThat(balance()).isEqualTo(95_000);
		}

		@Test
		@DisplayName("refuses when the balance is short — the core D2 rule")
		void refusesWhenShort() {
			fund(1_000);

			assertThatThrownBy(() -> wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000,
					"commission:order-1", "Commission", null, null))
					.isInstanceOf(InsufficientWalletBalanceException.class)
					.hasMessageContaining("Rs. 10.00")   // have
					.hasMessageContaining("Rs. 50.00");  // need

			assertThat(balance())
					.as("a refused debit must not move the balance")
					.isEqualTo(1_000);
		}

		@Test
		@DisplayName("refuses when the wallet is empty")
		void refusesWhenEmpty() {
			assertThatThrownBy(() -> wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 1,
					"commission:order-1", "Commission", null, null))
					.isInstanceOf(InsufficientWalletBalanceException.class);
		}

		@Test
		@DisplayName("allows a debit of exactly the balance")
		void allowsExactBalance() {
			fund(5_000);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000,
					"commission:exact", "Commission", null, null);

			assertThat(balance()).isZero();
		}

		@Test
		@DisplayName("the same key debits only once")
		void sameKeyDebitsOnce() {
			fund(100_000);
			String key = "commission:order-42";

			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000, key, "Commission", null, null);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000, key, "Commission", null, null);

			assertThat(balance())
					.as("accepting the same order twice must charge commission once")
					.isEqualTo(95_000);
			assertThat(ledger.findByIdempotencyKey(key)).isPresent();
		}

		@Test
		@DisplayName("a repeated key with a different amount does not apply the new amount")
		void repeatedKeyIgnoresChangedAmount() {
			fund(100_000);
			String key = "commission:order-42";

			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000, key, "first", null, null);
			// A caller retrying with a recomputed, different amount still gets the original.
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 9_000, key, "second", null, null);

			assertThat(balance()).isEqualTo(95_000);
		}
	}

	@Nested
	@DisplayName("reversing a commission debit")
	class Reversing {

		@Test
		@DisplayName("restores the balance after a cancellation")
		void restoresBalance() {
			fund(100_000);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000,
					"commission:order-1", "Commission", null, null);
			assertThat(balance()).isEqualTo(95_000);

			var reversal = wallet.reverseDebit(shopId, "commission:order-1",
					"Refund on cancellation", null, null);

			assertThat(reversal).isPresent();
			assertThat(balance())
					.as("cancelling must return exactly what was taken")
					.isEqualTo(100_000);
		}

		@Test
		@DisplayName("records the reversal as a new entry linked to the original")
		void recordsLinkedEntry() {
			fund(100_000);
			// A real order, because wallet_ledger_entry.order_id is a foreign key. The link
			// between a debit and its reversal is what this test is about, so the entry has to
			// point at something that exists.
			UUID orderId = fixtures.createPendingOrder(shopId).getId();
			WalletLedgerEntry debit = wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000,
					"commission:order-1", "Commission", orderId, null);

			var reversal = wallet.reverseDebit(shopId, "commission:order-1",
					"Refund", orderId, null).orElseThrow();

			assertThat(reversal.getEntryType()).isEqualTo(EntryType.COMMISSION_REVERSAL);
			assertThat(reversal.getDirection()).isEqualTo(Direction.CREDIT);
			assertThat(reversal.getAmountMinor()).isEqualTo(debit.getAmountMinor());
			assertThat(reversal.getReversalOf())
					.as("the reversal must point at the debit it undoes")
					.isEqualTo(debit.getId());
			assertThat(reversal.getOrderId()).isEqualTo(orderId);
		}

		@Test
		@DisplayName("cancelling twice credits only once")
		void secondReversalIsNoOp() {
			fund(100_000);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000,
					"commission:order-1", "Commission", null, null);
			wallet.reverseDebit(shopId, "commission:order-1", "first", null, null);
			wallet.reverseDebit(shopId, "commission:order-1", "second", null, null);

			assertThat(balance())
					.as("a double cancellation must not credit twice")
					.isEqualTo(100_000);
		}

		@Test
		@DisplayName("reversing a debit that never happened is not an error")
		void reversingNothing() {
			// A PENDING order cancelled before acceptance owes no commission.
			var result = wallet.reverseDebit(shopId, "commission:never-charged",
					"No debit to reverse", UUID.randomUUID(), null);

			assertThat(result).isEmpty();
			assertThat(balance()).isZero();
		}

		@Test
		@DisplayName("a reversal can be found from the original entry")
		void findByReversalOf() {
			fund(100_000);
			WalletLedgerEntry debit = wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000,
					"commission:order-1", "Commission", null, null);
			wallet.reverseDebit(shopId, "commission:order-1", "Refund", null, null);

			assertThat(ledger.findFirstByReversalOf(debit.getId())).isPresent();
		}
	}

	@Nested
	@DisplayName("cache and ledger agree")
	class Consistency {

		@Test
		@DisplayName("the cached balance always equals the ledger sum")
		void cacheMatchesLedger() {
			fund(100_000);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000, "commission:a", "a", null, null);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 15_000, "commission:b", "b", null, null);
			wallet.reverseDebit(shopId, "commission:a", "reversal", null, null);

			// 100,000 - 5,000 - 15,000 + 5,000 (reversal) = 85,000. Only debit "a" was
			// reversed, so "b" stays charged.
			assertThat(balance()).isEqualTo(85_000);
			assertThat(balance())
					.as("the cache must always equal the ledger, never drift from it")
					.isEqualTo(ledger.sumSignedMinor(shopId));
		}

		@Test
		@DisplayName("reconciliation reports a healthy wallet")
		void reconcileHealthy() {
			fund(100_000);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000, "commission:a", "a", null, null);

			assertThat(wallet.reconcile(shopId)).isTrue();
		}

		@Test
		@DisplayName("reconciliation detects and repairs drift")
		void reconcileDetectsDrift() {
			fund(100_000);

			// Simulate something writing the cache behind the service's back — a direct
			// repository update, which is exactly what a rogue caller would have. The ledger is
			// untouched, so reconciliation must detect the mismatch and win.
			balances.applyDelta(shopId, -40_000L);

			assertThat(balance())
					.as("precondition: the cache is now wrong")
					.isEqualTo(60_000);

			assertThat(wallet.reconcile(shopId))
					.as("reconciliation must report that it had to repair the cache")
					.isFalse();

			assertThat(balance())
					.as("the ledger is authoritative, so the balance is restored from it")
					.isEqualTo(100_000);
		}

		@Test
		@DisplayName("every entry records the running balance")
		void entriesRecordRunningBalance() {
			fund(100_000);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 30_000, "commission:a", "a", null, null);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 20_000, "commission:b", "b", null, null);

			assertThat(ledger.findByIdempotencyKey("commission:a").orElseThrow().getBalanceAfterMinor())
					.isEqualTo(70_000);
			assertThat(ledger.findByIdempotencyKey("commission:b").orElseThrow().getBalanceAfterMinor())
					.isEqualTo(50_000);
		}

		@Test
		@DisplayName("the database rejects a duplicate key even if the service is bypassed")
		void databaseEnforcesUniqueness() {
			fund(100_000);
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, 5_000, "commission:dupe", "first", null, null);

			// A raw insert with the same key, bypassing WalletService, must still fail. The
			// guarantee lives in the schema, not only in the calling code.
			WalletLedgerEntry rogue = WalletLedgerEntry.builder()
					.shopId(shopId)
					.entryType(EntryType.COMMISSION_DEBIT)
					.direction(Direction.DEBIT)
					.amountMinor(999)
					.balanceAfterMinor(0)
					.idempotencyKey("commission:dupe")
					.description("rogue insert")
					.build();

			assertThatThrownBy(() -> ledger.saveAndFlush(rogue))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Nested
	@DisplayName("a full commission cycle")
	class FullCycle {

		@Test
		@DisplayName("fund, accept, ship, cancel — the money ends where it started")
		void fullCycle() {
			Money orderTotal = Money.ofMinor(100_000);
			var commission = new CommissionCalculator().calculate(orderTotal, 500, false);

			fund(100_000);
			assertThat(balance()).isEqualTo(100_000);

			// Accept: commission debited.
			var order = fixtures.createPendingOrder(shopId);
			String orderKey = "commission:" + order.getId();
			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, commission.amount().minor(),
					orderKey, "Commission on order", order.getId(), null);
			assertThat(balance()).isEqualTo(100_000 - commission.amount().minor());

			// Cancel: commission returned.
			wallet.reverseDebit(shopId, orderKey, "Cancelled", order.getId(), null);

			assertThat(balance())
					.as("a cancelled order must leave the wallet exactly as it was")
					.isEqualTo(100_000);
			assertThat(ledger.countByShopIdAndEntryType(shopId, EntryType.COMMISSION_DEBIT)).isEqualTo(1);
			assertThat(ledger.countByShopIdAndEntryType(shopId, EntryType.COMMISSION_REVERSAL)).isEqualTo(1);
		}

		@Test
		@DisplayName("a delivered order keeps its commission")
		void deliveredKeepsCommission() {
			fund(100_000);
			var commission = new CommissionCalculator().calculate(Money.ofMinor(100_000), 500, false);

			wallet.debit(shopId, EntryType.COMMISSION_DEBIT, commission.amount().minor(),
					"commission:order-" + UUID.randomUUID(), "Commission", null, null);

			assertThat(balance()).isEqualTo(95_000);
		}
	}
}
