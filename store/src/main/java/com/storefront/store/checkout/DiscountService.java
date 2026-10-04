package com.storefront.store.checkout;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.security.TenantGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The owner's side of discounts: creating them, and reading them back for the console.
 *
 * <p><b>The arithmetic is not here.</b> {@link Discount#amountOff(long)} and
 * {@link Discount#isUsableNow()} already own whether a discount applies and how much it takes off,
 * because {@link QuoteService} calls them at checkout and this class must not be able to disagree
 * with what the customer was charged. Everything here is presentation: can this shop list, create,
 * amend and pause its own discounts, and can one shop touch another's code.
 *
 * <p><b>Usage is consumed at quote time, never here.</b> A discount's {@code usageUsed} counter is
 * advanced by the order that redeemed it, inside the order's transaction — so a failed checkout
 * does not burn an owner's limited-use campaign. That is why there is no {@code markUsed} method
 * on this service: the path that could be called twice is the one that should not exist.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DiscountService {

	private final DiscountRepository discounts;
	private final TenantGuard tenants;

	// ------------------------------------------------------------------ read

	/** Every discount for the caller's own shop, newest first. Always scoped by shop id. */
	@Transactional(readOnly = true)
	public Page<Discount> list(Pageable pageable) {
		UUID shopId = tenants.requireOwnedShop().getId();
		return discounts.findAllByShopIdOrderByIdAsc(shopId, pageable);
	}

	/**
	 * One discount, or 404.
	 *
	 * <p>Looked up through {@link #owned(UUID)} rather than by raw id, so a guessed id belonging to
	 * another shop produces the same 404 as one that does not exist.
	 */
	@Transactional(readOnly = true)
	public Discount get(UUID discountId) {
		return owned(discountId);
	}

	/** Fetches a discount and proves it belongs to the caller. */
	Discount owned(UUID discountId) {
		UUID shopId = tenants.requireOwnedShop().getId();
		return discounts.findById(discountId)
				.filter(d -> d.getShopId().equals(shopId))
				.orElseThrow(() -> new DiscountNotFoundException(discountId));
	}

	// ------------------------------------------------------------------ write

	/** Creates a discount for the caller's shop. */
	@Transactional
	public Discount create(Draft draft) {
		UUID shopId = tenants.requireOwnedShop().getId();
		validate(draft, shopId, null);
		return discounts.save(new Discount(shopId, normalise(draft.code()), draft.name(),
				draft.type(), draft.value()));
	}

	/**
	 * Amends a discount.
	 *
	 * <p>The code is deliberately <em>not</em> editable. A code is on printed baskets, in support
	 * messages and in customers' memories; changing it silently strands everyone holding the old
	 * one. An owner who needs a different code pauses this discount and creates another.
	 */
	@Transactional
	public Discount update(UUID discountId, Draft draft) {
		Discount existing = owned(discountId);
		validate(draft, existing.getShopId(), existing.getId());

		existing.setName(draft.name().trim());
		existing.setType(draft.type());
		existing.setValue(draft.value());
		existing.setMinOrderMinor(draft.minOrderMinor());
		existing.setMaxDiscountMinor(draft.maxDiscountMinor());
		existing.setUsageLimit(draft.usageLimit());
		existing.setPerCustomerLimit(draft.perCustomerLimit());
		existing.setStartsAt(draft.startsAt());
		existing.setEndsAt(draft.endsAt());
		existing.setActive(draft.active());
		return discounts.save(existing);
	}

	/**
	 * Pauses or resumes a discount.
	 *
	 * <p>A pause rather than a delete: a campaign that ran for a fortnight and is redeployed next
	 * season must keep the usage it accumulated, or its per-customer limit resets and customers
	 * who already used it can use it again.
	 */
	@Transactional
	public Discount setActive(UUID discountId, boolean active) {
		Discount existing = owned(discountId);
		existing.setActive(active);
		return discounts.save(existing);
	}

	// ------------------------------------------------------------------ validation

	/**
	 * Checks a draft before it is stored.
	 *
	 * <p>Each rule here corresponds to a way the discount would otherwise exist but be
	 * un-redeemable, which is the worst outcome for an owner: the campaign is on their storefront
	 * and no customer can use it.
	 */
	private void validate(Draft draft, UUID shopId, UUID selfId) {
		if (draft.name() == null || draft.name().isBlank()) {
			throw new IllegalArgumentException("Discount name is required");
		}
		if (draft.value() < 0) {
			throw new IllegalArgumentException("Discount value cannot be negative");
		}

		// A percentage above 100 would produce a negative price. 10,000 basis points is 100%.
		if (draft.type() == Discount.Type.PERCENT && draft.value() > 10_000) {
			throw new IllegalArgumentException(
					"A percentage discount cannot exceed 100% (10000 basis points)");
		}

		// A free-shipping discount with a value is meaningless: its value is always the whole
		// shipping charge. Storing one suggests it does something it does not.
		if (draft.type() == Discount.Type.FREE_SHIPPING && draft.value() != 0) {
			throw new IllegalArgumentException("A free-shipping discount takes no value");
		}

		if (draft.minOrderMinor() != null && draft.minOrderMinor() < 0) {
			throw new IllegalArgumentException("Minimum order cannot be negative");
		}

		// A cap below the minimum order makes the discount un-redeemable in every case: the order
		// must first reach the minimum, at which point the cap has already been exceeded.
		if (draft.minOrderMinor() != null && draft.maxDiscountMinor() != null
				&& draft.maxDiscountMinor() > 0
				&& draft.maxDiscountMinor() >= draft.minOrderMinor()) {
			throw new IllegalArgumentException(
					"The maximum discount must be less than the minimum order, or no order qualifies");
		}

		if (draft.usageLimit() != null && draft.usageLimit() <= 0) {
			throw new IllegalArgumentException("Usage limit must be at least 1, or leave it unset");
		}
		if (draft.perCustomerLimit() != null && draft.perCustomerLimit() <= 0) {
			throw new IllegalArgumentException("Per-customer limit must be at least 1, or leave it unset");
		}

		if (draft.startsAt() != null && draft.endsAt() != null && !draft.endsAt().isAfter(draft.startsAt())) {
			throw new IllegalArgumentException("The end date must be after the start date");
		}

		String code = normalise(draft.code());
		if (code != null) {
			// Code uniqueness is per shop, and case-insensitive by virtue of the citext column. The
			// self-exclusion is what lets an owner rename a discount without tripping its own code.
			discounts.findByShopIdAndCodeIgnoreCase(shopId, code)
					.filter(existing -> !existing.getId().equals(selfId))
					.ifPresent(existing -> {
						throw new IllegalArgumentException(
								"This shop already has a discount with the code " + code);
					});
		}
	}

	/** Trimmed and upper-cased, or null. A blank code means "automatic", not "empty string". */
	private static String normalise(String code) {
		if (code == null) {
			return null;
		}
		String trimmed = code.trim();
		return trimmed.isEmpty() ? null : trimmed.toUpperCase(java.util.Locale.ROOT);
	}

	// ------------------------------------------------------------------ types

	/** What the console sends. Mirrors {@link Discount}'s columns without exposing the entity. */
	public record Draft(
			String code,
			String name,
			Discount.Type type,
			long value,
			Long minOrderMinor,
			Long maxDiscountMinor,
			Integer usageLimit,
			Integer perCustomerLimit,
			Instant startsAt,
			Instant endsAt,
			boolean active) {
	}

	/** A discount that does not exist, or belongs to another shop. */
	public static class DiscountNotFoundException extends RuntimeException {
		public DiscountNotFoundException(UUID id) {
			super("No discount " + id + " for this shop");
		}
	}
}