package com.storefront.store.support;

import java.util.UUID;


import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;

/**
 * Creates the minimum real rows a test needs.
 *
 * <p>Tests cannot use arbitrary UUIDs for foreign keys: the wallet, orders and products all
 * reference {@code shop(id)}, so a fabricated id fails the constraint before the test logic
 * ever runs. Every fixture therefore goes through the real tables.
 *
 * <p>Lives in {@code src/test/java} and is registered only by
 * {@link TestConfig}, so it never exists in the running application.
 */
public class TestFixtures {

	private final AppUserRepository userRepository;
	private final ShopRepository shopRepository;
	private final com.storefront.store.order.ShopOrderRepository orderRepository;
	private final com.storefront.store.catalog.ProductRepository productRepository;
	private final com.storefront.store.catalog.ProductVariantRepository variantRepository;
	private final com.storefront.store.inventory.InventoryRepository inventoryRepository;


	public TestFixtures(AppUserRepository userRepository, ShopRepository shopRepository,
			com.storefront.store.order.ShopOrderRepository orderRepository,
			com.storefront.store.catalog.ProductRepository productRepository,
			com.storefront.store.catalog.ProductVariantRepository variantRepository,
			com.storefront.store.inventory.InventoryRepository inventoryRepository) {
		this.inventoryRepository = inventoryRepository;
		this.userRepository = userRepository;
		this.shopRepository = shopRepository;
		this.orderRepository = orderRepository;
		this.productRepository = productRepository;
		this.variantRepository = variantRepository;
	}

	/**
	 * The owner shared by every shop this fixture creates.
	 *
	 * <p>Resolved lazily rather than in a {@code @BeforeEach}: that callback only fires for the
	 * test class itself, not for helper methods a test calls — which is exactly how this fixture
	 * is used.
	 */
	/**
	 * A fresh owner for the current test.
	 *
	 * <p>Not cached across calls. Every test in these suites is {@code @Transactional} and rolls
	 * back, so a cached {@link AppUser} would hold an id whose row no longer exists — and the
	 * next shop insert would fail its foreign key with a perfectly valid-looking id. One owner
	 * per test costs a row insert and removes an entire class of confusing failure.
	 */
	private AppUser ownerUser() {
		// saveAndFlush, not save: the shop insert that follows references this row, and a
		// pending insert is not visible to the foreign key check.
		return userRepository.saveAndFlush(new AppUser(
				AppUser.Realm.OPS,
				"test-owner-" + UUID.randomUUID(),
				"owner-" + UUID.randomUUID() + "@example.test",
				"Test Shop Owner"));
	}

	public AppUser owner() {
		return ownerUser();
	}

	/** A DRAFT shop. Verification is a separate step, so most tests do not need it. */
	public Shop createShop(String name, ShopType type) {
		return createShop(name, type, Shop.Status.DRAFT);
	}

	public Shop createShop(String name, ShopType type, Shop.Status status) {
		Shop shop = new Shop(ownerUser(), name, type);
		shop.setStatus(status);
		// Flushed immediately: callers usually go on to insert wallet and order rows that
		// reference this shop, and Hibernate's action queue does not guarantee the parent row
		// is written before the child when both are pending.
		return shopRepository.saveAndFlush(shop);
	}

	/** A shop that has passed verification, ready to publish. */
	public Shop createVerifiedShop(String name, ShopType type) {
		return createShop(name, type, Shop.Status.VERIFIED);
	}

	/** The common case: a verified pharmacy. */
	public Shop createVerifiedPharmacy() {
		return createVerifiedShop("Test Pharmacy " + UUID.randomUUID().toString().substring(0, 8),
				ShopType.PHARMACY);
	}

	/**
	 * A DRAFT product with one priced variant.
	 *
	 * <p>A variant is included because a product with no variant cannot be published and cannot
	 * be ordered, so almost every test that needs "a product" needs one with a price. Written
	 * through the repositories rather than the service so a fixture cannot fail because of a
	 * service rule the test is not about.
	 */
	public com.storefront.store.catalog.Product createProduct(Shop shop, String name,
			com.storefront.store.catalog.ProductCategory category, long priceMinor) {

		com.storefront.store.catalog.Product product =
				productRepository.saveAndFlush(new com.storefront.store.catalog.Product(
						shop.getId(), name,
						com.storefront.store.shop.SlugGenerator.productSlug(null, name,
								java.util.UUID.randomUUID()),
						category));
		product.refreshMinimumPrice(priceMinor);

		variantRepository.saveAndFlush(new com.storefront.store.catalog.ProductVariant(
				product.getId(), "Default", priceMinor));

		return productRepository.saveAndFlush(product);
	}

	/** A product in a grocery shop, for the common "does this category belong" case. */
	public com.storefront.store.catalog.Product createProduct(String name,
			com.storefront.store.catalog.ProductCategory category, long priceMinor) {
		return createProduct(createVerifiedShop("Fixture Shop " + UUID.randomUUID().toString().substring(0, 6),
				ShopType.GROCERY), name, category, priceMinor);
	}

	/** The product created by {@link #createProduct}, so a test can get at its variant. */
	public com.storefront.store.catalog.ProductVariant defaultVariantOf(
			com.storefront.store.catalog.Product product) {
		return variantRepository
				.findByProductIdOrderBySortOrderAscNameAsc(product.getId())
				.get(0);
	}

	/** Puts stock on the shelf for a variant. */
	public void stock(com.storefront.store.catalog.ProductVariant variant, int units) {
		inventoryRepository.saveAndFlush(new com.storefront.store.inventory.Inventory(
				variant.getProductId(), variant.getId()));
		com.storefront.store.inventory.Inventory row = inventoryRepository
				.findByIdProductIdAndIdVariantId(variant.getProductId(), variant.getId())
				.orElseThrow();
		row.adjustOnHand(units);
		inventoryRepository.saveAndFlush(row);
	}

	/**
	 * A verified grocery shop with a published, stocked product.
	 *
	 * <p>The common starting point for order tests: something a customer can actually buy.
	 */
	public com.storefront.store.catalog.Product stockableProduct(long priceMinor, int units) {
		Shop shop = createVerifiedShop(
				"Fixture Shop " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
		com.storefront.store.catalog.Product product =
				createProduct(shop, "Stocked Item", com.storefront.store.catalog.ProductCategory.RICE,
						priceMinor);
		stock(defaultVariantOf(product), units);
		return product;
	}

	/**
	 * A fresh shop id, safe to call from a {@code @BeforeEach}.
	 *
	 * <p>Callers must obtain this per test rather than caching it in a field initialised once:
	 * every {@code @Nested} class gets a new test instance, so a field set only in the outer
	 * class's {@code @BeforeEach} is null inside the nested ones.
	 */
	public UUID newShopId() {
		return createVerifiedPharmacy().getId();
	}

	/**
	 * A minimal PENDING order, for tests whose subject is something else.
	 *
	 * <p>Orders are needed because the wallet ledger's {@code order_id} is a real foreign key, so
	 * a test about commission cannot pass a random UUID and call it an order.
	 */
	public com.storefront.store.order.ShopOrder createPendingOrder(UUID shopId) {
		com.storefront.store.order.ShopOrder order = new com.storefront.store.order.ShopOrder(
				shopId,
				com.storefront.store.catalog.ShopType.PHARMACY,
				"TEST-" + UUID.randomUUID().toString().substring(0, 8),
				com.storefront.store.order.ShopOrder.PaymentMethod.COD,
				100_000, 0, 0, 0,
				"{\"city\":\"Kathmandu\"}");

		// The order_has_contact constraint requires either a customer_id or a guest_email.
		// Guest checkout is the common case for a test order, so the email is set here.
		order.setGuestEmail("buyer-" + UUID.randomUUID() + "@example.test");
		return orderRepository.saveAndFlush(order);
	}
}
