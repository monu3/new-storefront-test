package com.storefront.store.notification;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Notification queries.
 *
 * <p>Every listing is paged: the table grows with every order event for every recipient, and an
 * inbox that loads in one query is an inbox that eventually times out.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

	Page<Notification> findByRecipientShopIdOrderByCreatedAtDesc(UUID shopId, Pageable pageable);

	List<Notification> findByStatusOrderByCreatedAtAsc(Notification.Status status);

	long countByRecipientUserIdAndStatus(UUID userId, Notification.Status status);

	long countByStatus(Notification.Status status);

	long countByRecipientShopId(UUID shopId);
}
