package com.storefront.store.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Movement-log queries.
 *
 * <p>Paged everywhere, because this table grows without bound — every restock, every sale, every
 * cancellation — and a dispute about a quantity is settled by paging back through it.
 */
public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {

	Page<InventoryMovement> findByProductIdAndVariantIdOrderByCreatedAtDesc(
			UUID productId, UUID variantId, Pageable pageable);

	List<InventoryMovement> findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
			String referenceType, UUID referenceId);

	long countByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);
}
