package com.storefront.store.wallet;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.security.TenantGuard;
import com.storefront.store.shop.Shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * The shop's own wallet: balance, ledger and top-up requests.
 *
 * <p>No shop id anywhere — the shop is the caller's own (D35).
 *
 * <p>The ledger is shown alongside the balance deliberately. A shop owner who sees "Rs. 4,900"
 * and has no way to see why will not trust it, and an untrusted balance is a support ticket. The
 * balance is a cache; the ledger is the truth, and showing both makes that legible.
 */
@RestController
@RequestMapping("/api/v1/shop/wallet")
@Tag(name = "Wallet", description = "Balance, ledger history and top-up requests")
@PreAuthorize("hasRole('SHOP')")
@RequiredArgsConstructor
public class WalletController {

	private static final int MAX_PAGE_SIZE = 100;

	private final WalletService wallet;
	private final WalletTopUpService topUps;
	private final TenantGuard tenantGuard;

	/**
	 * The shop's balance, plus whether it can afford to accept an order.
	 *
	 * <p>{@code canAcceptOrders} is the number that matters day to day: a shop that has run its
	 * wallet down cannot take orders at all, and finding that out by having an accept fail is a
	 * lost sale.
	 */
	@GetMapping
	@Operation(summary = "The shop's wallet balance")
	public WalletSummary summary() {
		Shop shop = tenantGuard.requireOwnedShop();
		long balance = wallet.balanceOf(shop.getId());

		return new WalletSummary(
				shop.getId(),
				balance,
				com.storefront.store.common.Money.ofMinor(balance).toDisplay(),
				// Arbitrary but non-zero: the exact threshold is Phase 6's, when plans exist.
				// For now the honest statement is "can you take an order at all".
				balance > 0,
				shop.paysCommission(),
				shop.getType().name());
	}

	/**
	 * Ledger history.
	 *
	 * <p>Reverse chronological, paged. Append-only: there is no endpoint here that edits or
	 * deletes an entry, and there never will be.
	 */
	@GetMapping("/ledger")
	@Operation(summary = "The shop's ledger, newest first")
	public LedgerPage ledger(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) int size) {

		Shop shop = tenantGuard.requireOwnedShop();
		Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));

		var result = wallet.ledgerFor(shop.getId(), pageable);
		return new LedgerPage(
				result.getContent().stream().map(LedgerEntryView::from).toList(),
				page, result.getSize(), result.getTotalElements(), result.getTotalPages(),
				wallet.balanceOf(shop.getId()));
	}

	/**
	 * Requests a top-up.
	 *
	 * <p>201 with the request: it exists, but it is not money yet. The response says so, so the
	 * shop owner's screen does not imply their balance has changed.
	 */
	@PostMapping("/topups")
	@Operation(summary = "Request a wallet top-up",
			description = "Manual by design (D7). The balance changes only when an admin approves.")
	public ResponseEntity<WalletTopUpRequest.TopUpView> requestTopUp(
			@Valid @RequestBody TopUpRequest request) {

		Shop shop = tenantGuard.requireOwnedShop();
		WalletTopUpRequest created = topUps.request(shop.getId(), request.amountMinor(),
				request.method(), request.reference(), request.proofMediaId(), request.note());

		return ResponseEntity.status(201).body(WalletTopUpRequest.TopUpView.from(created));
	}

	@GetMapping("/topups")
	@Operation(summary = "The shop's top-up requests")
	public List<WalletTopUpRequest.TopUpView> listTopUps(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) int size) {

		Shop shop = tenantGuard.requireOwnedShop();
		return topUps.forShop(shop.getId(),
						PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)))
				.getContent().stream()
				.map(WalletTopUpRequest.TopUpView::from)
				.toList();
	}

	// ------------------------------------------------------------------ requests

	public record TopUpRequest(
			@NotNull @Min(1) Long amountMinor,
			@NotNull WalletTopUpRequest.Method method,
			/** A transaction id or deposit slip number. What the admin matches the bank against. */
			@Size(max = 200) String reference,
			/** A media id: a photo of the transfer receipt. */
			UUID proofMediaId,
			@Size(max = 500) String note) {
	}

	// ------------------------------------------------------------------ responses

	public record WalletSummary(
			UUID shopId,
			long balance,
			String balanceDisplay,
			boolean canAcceptOrders,
			boolean paysCommission,
			String shopType) {
	}

	/** One ledger entry, with the running balance it produced. */
	public record LedgerEntryView(
			UUID id,
			WalletLedgerEntry.EntryType type,
			WalletLedgerEntry.Direction direction,
			long amount,
			long balanceAfter,
			String description,
			UUID orderId,
			UUID reversalOf,
			java.time.Instant createdAt) {

		static LedgerEntryView from(WalletLedgerEntry e) {
			return new LedgerEntryView(
					e.getId(), e.getEntryType(), e.getDirection(),
					e.getAmountMinor(), e.getBalanceAfterMinor(), e.getDescription(),
					e.getOrderId(), e.getReversalOf(), e.getCreatedAt());
		}
	}

	/** A page of entries, plus the current balance so the header need not fetch separately. */
	public record LedgerPage(
			List<LedgerEntryView> entries,
			int page,
			int size,
			long totalElements,
			int totalPages,
			long currentBalance) {
	}
}
