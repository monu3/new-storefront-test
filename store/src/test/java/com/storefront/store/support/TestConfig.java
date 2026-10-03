package com.storefront.store.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link TestFixtures} for integration tests.
 *
 * <p>{@code @TestComponent} alone is not enough: it only takes effect when nested inside a
 * {@code @TestConfiguration}, and a class in {@code src/test/java} is not automatically a test
 * configuration. Importing this class with {@code @Import(TestConfig.class)} is what puts the
 * fixture bean in the context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

	@Bean
	public TestFixtures testFixtures(
			com.storefront.store.identity.AppUserRepository userRepository,
			com.storefront.store.shop.ShopRepository shopRepository,
			com.storefront.store.order.ShopOrderRepository orderRepository,
			com.storefront.store.catalog.ProductRepository productRepository,
			com.storefront.store.catalog.ProductVariantRepository productVariantRepository,
			com.storefront.store.inventory.InventoryRepository inventoryRepository,
			com.storefront.store.catalog.ProductService productService) {
		return new TestFixtures(userRepository, shopRepository, orderRepository,
				productRepository, productVariantRepository, inventoryRepository, productService);
	}

	/**
	 * Only for the suites that commit.
	 *
	 * <p>Registered here rather than component-scanned so a {@code @Transactional} test cannot
	 * accidentally autowire it: truncating a table inside a rolled-back test would destroy the
	 * very rollback that test depends on. Committing suites import it explicitly.
	 */
	@Bean
	public TestDatabaseCleaner testDatabaseCleaner(org.springframework.jdbc.core.JdbcTemplate jdbc) {
		return new TestDatabaseCleaner(jdbc);
	}
}
