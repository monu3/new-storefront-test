package com.storefront.store.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Payment-method lookups.
 *
 * <p>Every method takes a {@code shopId}: a payment method carries merchant credentials and bank
 * details, so an unscoped query here would be a cross-tenant read of the most sensitive
 * configuration in the system.
 */
public interface ShopPaymentMethodRepository extends JpaRepository<ShopPaymentMethod, UUID> {

	Optional<ShopPaymentMethod> findByShopIdAndMethod(UUID shopId,
			PaymentGateway.ShopOrderPaymentMethod method);

	/** All three methods, ordered the way the owner arranged them for the customer. */
	List<ShopPaymentMethod> findByShopIdOrderBySortOrderAscMethodAsc(UUID shopId);

	List<ShopPaymentMethod> findByShopIdAndEnabledTrue(UUID shopId);
}