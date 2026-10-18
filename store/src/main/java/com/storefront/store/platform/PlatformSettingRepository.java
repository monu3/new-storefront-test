package com.storefront.store.platform;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Platform settings storage.
 *
 * <p><b>Top-level, not nested inside {@link PlatformSettings}.</b> Spring Data only scans nested
 * repository interfaces when {@code considerNestedRepositories = true}, which is off by default —
 * so a nested one is not a bean, and the failure is a missing dependency rather than anything that
 * names the nesting.
 */
public interface PlatformSettingRepository
		extends JpaRepository<PlatformSettings.Setting, String> {
}
