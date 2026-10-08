package com.storefront.store.subscription;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.security.CurrentUser;
import com.storefront.store.wallet.WalletTopUpRequest;
import com.storefront.store.wallet.WalletTopUpService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Subscription approval and the revenue dashboard (Phase 6).
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')}") (D36). Everything here moves money or
 * reports on money, so the gate is on the class rather than repeated per method.
 *
 * <p><b>The approval endpoint is the only place a subscription starts.</b> A shop can request a plan
 * and submit proof of payment; neither grants anything. An admin checking a bank statement against
 * a reference is what turns a request into a subscription, which is D7's manual model and the reason
 * a shop's balance and its entitlements can never both be wrong.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin revenue", description = "Approve subscription payments and read revenue figures")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminRevenueController {

	private final SubscriptionPaymentService payments;
	private final WalletTopUpService topUps;
	private final RevenueService revenue;
	private final SubscriptionService subscriptions;
	private final SubscriptionExpiryJob expiryJob;
	private final CurrentUser currentUser;

	// ------------------------------------------------------------------ approval

	/**
	 * Approves a subscription purchase.
	 *
	 * <p>Credits the shop's wallet for the transfer, debits the subscription price, activates the
	 * plan, grants its entitlements and drops the shop to zero commission — in one transaction. Any
	 * failure leaves all five undone.
	 */
	@PostMapping("/subscription-payments/{topupRequestId}/approve")
	@Operation(summary = "Approve a subscription payment",
			description = "Credits, charges and activates in one transaction.")
	public ApprovalView approve(@PathVariable UUID topupRequestId,
			@RequestParam(required = false) String note) {

		ShopSubscription active = payments.settle(topupRequestId, currentUser.require().getId(), note);

		log.info("Subscription {} activated for shop {} by {}",
				active.getId(), active.getShopId(), currentUser.require().getId());

		return new ApprovalView(
				active.getId(),
				active.getShopId(),
				active.getPlan().getCode(),
				active.getStatus().name(),
				active.getStartedAt(),
				active.getExpiresAt(),
				active.getPlan().getPriceMinor(),
				"The shop is now subscribed and pays no commission.");
	}

	/**
	 * Rejects a subscription purchase.
	 *
	 * <p>Rejects the top-up and abandons the queued request. Nothing was granted, so there is nothing
	 * to undo — and saying so matters, because an admin rejecting this is deciding the shop does not
	 * get the plan, not that it loses something it had.
	 */
	@PostMapping("/subscription-payments/{topupRequestId}/reject")
	@Operation(summary = "Reject a subscription payment",
			description = "Requires a reason. Nothing was ever granted, so nothing is reversed.")
	public RejectionView reject(@PathVariable UUID topupRequestId,
			@RequestParam String reason) {

		WalletTopUpRequest request = topUps.require(topupRequestId);
		topUps.reject(topupRequestId, currentUser.require().getId(), reason);
		if (request.getSubscriptionId() != null) {
			subscriptions.abandonPending(request.getSubscriptionId(), request.getShopId());
		}
		return new RejectionView(request.getId(), request.getShopId(), reason,
				"The request was rejected and the plan was not activated.");
	}

	// ------------------------------------------------------------------ dashboard

	/**
	 * The revenue dashboard.
	 *
	 * <p>Every figure carries its window and denominator; see {@link RevenueService}. The
	 * {@code months} parameter is for the MRR trend only — the panel figures are "this month", and
	 * accepting a range here would invite a caller to believe they had asked for one.
	 */
	@GetMapping("/revenue")
	@Operation(summary = "MRR, commission, top-ups and per-shop revenue")
	public RevenueView revenue(
			@RequestParam(defaultValue = "6") @Min(1) int months) {

		RevenueService.RevenueSummary summary = revenue.summary(Instant.now());

		return new RevenueView(
				summary.asOf(),
				"since the start of the current month, except MRR which is a live figure",
				summary.mrrDisplay(), summary.mrrMinor(),
				summary.paidSubscribers(),
				summary.commissionFreeShops(),
				summary.totalShops(),
				summary.tradingShops(),
				summary.commissionRealisedDisplay(), summary.commissionRealisedMinor(),
				summary.commissionWaivedDisplay(), summary.commissionWaivedMinor(),
				summary.commissionReversedDisplay(), summary.commissionReversedMinor(),
				summary.ordersAccepted(),
				summary.topUpsDisplay(), summary.topUpsMinor(),
				summary.subscriptionChargesDisplay(), summary.subscriptionChargesMinor(),
				summary.subscriptionArpuDisplay(), summary.subscriptionArpuMinor(),
				summary.platformPerTradingShopDisplay(), summary.platformPerTradingShopMinor(),
				summary.commissionPerSubscriberDisplay(), summary.commissionPerSubscriberMinor(),
				summary.commissionFromFreeShopsDisplay(),
				summary.waiverCoverage(),
				revenue.mrrTrend(months, Instant.now()).stream()
						.map(month -> new MonthView(
								month.month(), month.mrrMinor(), month.mrrDisplay(),
								month.subscribers()))
						.toList());
	}

	/**
	 * Runs the expiry sweep now.
	 *
	 * <p>Exposed because an admin seeing a shop still commission-free after its period ended needs
	 * a way to fix it now rather than wait for the cron. Deliberately idempotent — running it twice
	 * changes nothing the second time, which is what makes it safe to expose at all.
	 */
	@PostMapping("/subscriptions/expire")
	@Operation(summary = "Close lapsed subscriptions now",
			description = "Idempotent. Normally runs hourly; this does not wait for it.")
	public ExpiryView expireNow() {
		int closed = expiryJob.expireNow();
		return new ExpiryView(closed,
				closed == 0 ? "Nothing had lapsed." : closed + " subscription(s) closed.");
	}

	// ------------------------------------------------------------------ types

	public record ApprovalView(
			UUID subscriptionId,
			UUID shopId,
			String planCode,
			String status,
			Instant startedAt,
			Instant expiresAt,
			long chargedMinor,
			String message) {
	}

	public record RejectionView(UUID topupRequestId, UUID shopId, String reason, String message) {
	}

	public record MonthView(String month, long mrrMinor, String mrrDisplay, int subscribers) {
	}

	public record RevenueView(
			Instant asOf,
			String window,
			String mrrDisplay, long mrrMinor,
			int paidSubscribers,
			long commissionFreeShops,
			long totalShops,
			long tradingShops,
			String commissionRealisedDisplay, long commissionRealisedMinor,
			String commissionWaivedDisplay, long commissionWaivedMinor,
			String commissionReversedDisplay, long commissionReversedMinor,
			long ordersAccepted,
			String topUpsDisplay, long topUpsMinor,
			String subscriptionChargesDisplay, long subscriptionChargesMinor,
			String subscriptionArpuDisplay, long subscriptionArpuMinor,
			String platformPerTradingShopDisplay, long platformPerTradingShopMinor,
			String commissionPerSubscriberDisplay, long commissionPerSubscriberMinor,
			String commissionFromFreeShopsDisplay,
			/** Subscriptions earned per rupee of commission waived. Null when none was waived. */
			BigDecimal waiverCoverage,
			List<MonthView> trend) {
	}

	public record ExpiryView(int closed, String message) {
	}
}
