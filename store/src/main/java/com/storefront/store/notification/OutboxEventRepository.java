package com.storefront.store.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Outbox queries.
 *
 * <p>{@link #findUnpublished} is the relay's only read, and it is paged rather than
 * {@code findAll()}: the table is unbounded and mostly published, so an unbounded read would grow
 * without limit while returning rows the relay does not want.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	/**
	 * Unpublished events, oldest first.
	 *
	 * <p>Ordered by {@code created_at} so events are delivered in the order they were written. A
	 * customer told their order shipped before they were told it was placed reads as broken.
	 */
	@Query("""
			SELECT e FROM OutboxEvent e
			WHERE e.publishedAt IS NULL
			ORDER BY e.createdAt ASC
			""")
	List<OutboxEvent> findUnpublished(Pageable pageable);

	long countByPublishedAtIsNull();

	/** Events that have given up, for an operator to look at. */
	@Query("""
			SELECT e FROM OutboxEvent e
			WHERE e.publishedAt IS NULL AND e.attempts >= :maxAttempts
			ORDER BY e.createdAt ASC
			""")
	List<OutboxEvent> findExhausted(@Param("maxAttempts") int maxAttempts);

	/** Everything that happened to one aggregate, for debugging a single order. */
	List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
			String aggregateType, UUID aggregateId);

	long countByAggregateTypeAndAggregateId(String aggregateType, UUID aggregateId);

	/** Oldest unpublished event, for a health indicator on stalled delivery. */
	@Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.publishedAt IS NULL")
	Instant oldestUnpublishedAt();
}
