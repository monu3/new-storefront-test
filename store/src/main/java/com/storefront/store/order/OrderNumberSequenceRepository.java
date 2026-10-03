package com.storefront.store.order;

import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The per-shop order-number counter.
 *
 * <p>{@link #lock(UUID)} is the mechanism the whole class exists for. It takes a pessimistic
 * write lock on the shop's counter row, so two checkouts at the same shop serialise on it while
 * checkouts at <em>different</em> shops never contend. That is the difference between "order
 * numbers are unique" and "order numbers are unique and one slow shop does not stall the
 * platform".
 *
 * <p>{@code MAX(order_number) + 1} is the obvious alternative and is wrong: two concurrent
 * transactions both read the same maximum and both write the same number, and the unique
 * constraint then fails one customer's checkout outright.
 */
public interface OrderNumberSequenceRepository extends JpaRepository<OrderNumberSequence, UUID> {

	/**
	 * The shop's counter row, locked for update, created on first use.
	 *
	 * <p>The row is inserted and re-locked inside the caller's transaction, so a brand-new shop
	 * gets a counter without a separate "create counter on signup" step that could be forgotten.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM OrderNumberSequence s WHERE s.shopId = :shopId")
	java.util.Optional<OrderNumberSequence> lock(@Param("shopId") UUID shopId);

	/**
	 * Increments under the lock, returning the row count.
	 *
	 * <p>The timestamp is a parameter rather than {@code now()} because JPQL bulk updates cannot
	 * call database functions — {@code now()} has no JPQL spelling, and passing the value keeps
	 * the statement portable and the value consistent with the rest of the transaction.
	 *
	 * <p>Kept for callers that update without holding the entity. {@code OrderNumberService} uses
	 * the locked entity path instead, which returns the new value directly.
	 */
	@org.springframework.data.jpa.repository.Modifying(clearAutomatically = true,
			flushAutomatically = true)
	@Query("""
			UPDATE OrderNumberSequence s
			SET s.lastNumber = s.lastNumber + 1, s.updatedAt = :now
			WHERE s.shopId = :shopId
			""")
	int increment(@Param("shopId") UUID shopId, @Param("now") java.time.Instant now);

	/**
	 * Raises the counter to at least {@code value}.
	 *
	 * <p>Never lowers it. Seeding historical orders must not make the platform reissue a number
	 * that already appears on a printed invoice.
	 */
	@org.springframework.data.jpa.repository.Modifying(clearAutomatically = true,
			flushAutomatically = true)
	@Query("""
			UPDATE OrderNumberSequence s
			SET s.lastNumber = :value, s.updatedAt = :now
			WHERE s.shopId = :shopId AND s.lastNumber < :value
			""")
	int raiseTo(@Param("shopId") UUID shopId, @Param("value") long value,
			@Param("now") java.time.Instant now);
}
