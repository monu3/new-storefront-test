package com.storefront.store.settings;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Settings lookups.
 *
 * <p>Every method takes a {@code shopId}. There is no "find all settings" query on purpose: this
 * table holds one owner's private configuration, and an unscoped list endpoint is the shape that
 * eventually becomes a cross-tenant leak.
 */
public interface ShopSettingRepository extends JpaRepository<ShopSetting, UUID> {

	Optional<ShopSetting> findByShopId(UUID shopId);
}