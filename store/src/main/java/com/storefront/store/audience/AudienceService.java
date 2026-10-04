package com.storefront.store.audience;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.security.TenantGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The owner's audience: who buys from this shop, how much, and what the owner has noted about them.
 *
 * <p><b>Shop-private throughout.</b> Every query is keyed by the shop resolved from the token, and
 * every method that takes a customer id routes through {@link #owned(UUID)}. The notes on these
 * rows are the owner's own annotations about real people; the only way they leave this class is
 * {@link #view(Customer)}, which is the owner's own view, and there is no customer-facing consumer
 * of it anywhere in the codebase.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AudienceService {

	/** How the owner wants their audience listed. */
	public enum Sort {
		/** Lifetime spend, highest first. The default: "who are my best customers". */
		SPENT,
		/** Most recent order first. "Who have I not heard from in a while". */
		RECENT,
		/** First seen first. */
		FIRST_SEEN
	}

	private final CustomerRepository customers;
	private final TenantGuard tenants;

	// ------------------------------------------------------------------ read

	@Transactional(readOnly = true)
	public Page<Customer> list(Sort sort, int page, int size) {
		UUID shopId = tenants.requireOwnedShop().getId();
		Pageable pageable = PageRequest.of(page, size);
		return switch (sort) {
			case SPENT -> customers.findByShopIdOrderByTotalSpentMinorDesc(shopId, pageable);
			case RECENT -> customers.findMostRecentFirst(shopId, pageable);
			case FIRST_SEEN -> customers.findByShopIdOrderByCreatedAtDesc(shopId, pageable);
		};
	}

	@Transactional(readOnly = true)
	public Customer get(UUID customerId) {
		return owned(customerId);
	}

	/** Fetches a customer and proves they belong to the caller. 404 rather than 403 for both cases. */
	Customer owned(UUID customerId) {
		UUID shopId = tenants.requireOwnedShop().getId();
		return customers.findById(customerId)
				.filter(c -> c.getShopId().equals(shopId))
				.orElseThrow(() -> new CustomerNotFoundException(customerId));
	}

	/**
	 * The audience header's figures.
	 *
	 * <p>Lifetime spend and order count come from a database {@code SUM} over <em>all</em> the
	 * shop's customers, not from the visible page. Adding up twenty rows in Java and calling it
	 * the shop's revenue would be wrong in the way that matters — the owner's most valuable
	 * customers are often not on the first page of a spend-descending list.
	 */
	@Transactional(readOnly = true)
	public Summary summary() {
		UUID shopId = tenants.requireOwnedShop().getId();
		var totals = customers.totalsFor(shopId);
		long buyers = customers.countByShopIdAndTotalOrdersGreaterThan(shopId, 0);
		long repeats = customers.repeatBuyers(shopId);

		return new Summary(
				totals.customers(),
				buyers,
				totals.orders(),
				totals.spentMinor(),
				// Average order value over everyone who has bought, not over every row: a customer
				// with no orders would otherwise drag it down for no reason.
				buyers == 0 ? 0 : totals.spentMinor() / buyers,
				buyers == 0 ? 0.0 : Math.round(1000.0 * repeats / buyers) / 10.0);
	}

	// ------------------------------------------------------------------ write

	/**
	 * Replaces a customer's private note.
	 *
	 * <p>The note is stamped with the author and the time, so an owner can tell an old note from a
	 * new one. With one owner per shop (D10) that is currently always the same person, and it stays
	 * correct the moment a staff role exists.
	 */
	@Transactional
	public Customer annotate(UUID customerId, String notes) {
		Customer customer = owned(customerId);
		UUID author = currentOwnerId();
		customer.annotate(notes, author);
		return customers.save(customer);
	}

	/** The caller's own user id, for stamping a note. */
	private UUID currentOwnerId() {
		return tenants.requireOwnedShop().getOwnerUserId();
	}

	// ------------------------------------------------------------------ types

	/** What the audience header shows. */
	public record Summary(
			long customers,
			long buyers,
			long orders,
			long spentMinor,
			long averageOrderMinor,
			/** Percentage of buyers with two or more orders. */
			double repeatRate) {
	}

	/** No such customer for this shop. */
	public static class CustomerNotFoundException extends RuntimeException {
		public CustomerNotFoundException(UUID id) {
			super("No customer " + id + " for this shop");
		}
	}
}