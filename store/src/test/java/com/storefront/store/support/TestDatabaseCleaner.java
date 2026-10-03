package com.storefront.store.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Removes rows a committing test suite left behind.
 *
 * <p>{@link CheckoutRollbackIT} is the reason this exists. It commits, because a rolled-back
 * transaction cannot prove that a failure left nothing behind. Its {@code @AfterEach} cleans up
 * after itself, but when that cleanup itself fails — a constraint violation, say — the committed
 * rows stay, and every later suite that counts rows in the database sees them.
 *
 * <p>Used only by the committing suites, never by a {@code @Transactional} one: truncating a table
 * a rolled-back test is still using would destroy the very rollback it depends on.
 *
 * <p>Only ever pointed at {@code store_app_test}. The method refuses to run against a database
 * whose name does not contain {@code test}, because a mistyped JDBC URL here would otherwise empty
 * the development database.
 */
@Component
public class TestDatabaseCleaner {

	private static final Logger log = LoggerFactory.getLogger(TestDatabaseCleaner.class);

	private final JdbcTemplate jdbc;

	public TestDatabaseCleaner(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Deletes every order, catalogue and stock row.
	 *
	 * <p>Plain DELETE statements in dependency order rather than a {@code TRUNCATE ... CASCADE}:
	 * a cascade would silently drop rows this class does not know about, and an explicit list
	 * documents exactly what a committing test suite can leave behind. The order matters —
	 * {@code wallet_ledger_entry} and {@code payment_transaction} both reference an order with
	 * {@code ON DELETE RESTRICT}, so they go first.
	 */
	public void clearOrdersAndCatalog() {
		requireTestDatabase();

		for (String table : List.of(
				"outbox_event",
				"notification",
				"wallet_ledger_entry",
				"payment_transaction",
				"order_status_history",
				"order_item",
				"shop_order",
				"inventory_movement",
				"inventory",
				"product_variant",
				"product")) {
			jdbc.update("DELETE FROM " + table);
		}
		log.warn("Cleared order, catalogue and stock tables in the test database");
	}

	/** Deletes one shop and everything that cascades from it. */
	public void deleteShop(java.util.UUID shopId) {
		requireTestDatabase();
		// Outbox events hang off an aggregate id, not a shop_id column, so they are not covered by
		// the shop's cascade and have to be removed explicitly. Found by OutboxIT's "publishes
		// pending events" test seeing rows left by a committed checkout.
		jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'SHOP' AND aggregate_id = ?",
				shopId);
		jdbc.update("""
				DELETE FROM outbox_event
				WHERE aggregate_type = 'ORDER'
				  AND aggregate_id IN (SELECT id FROM shop_order WHERE shop_id = ?)
				""", shopId);
		jdbc.update("DELETE FROM notification WHERE recipient_shop_id = ?", shopId);
		jdbc.update("DELETE FROM wallet_topup_request WHERE shop_id = ?", shopId);
		jdbc.update("DELETE FROM wallet_ledger_entry WHERE shop_id = ?", shopId);
		jdbc.update("DELETE FROM shop_order WHERE shop_id = ?", shopId);
		jdbc.update("DELETE FROM inventory WHERE product_id IN "
				+ "(SELECT id FROM product WHERE shop_id = ?)", shopId);
		jdbc.update("DELETE FROM product_variant WHERE product_id IN "
				+ "(SELECT id FROM product WHERE shop_id = ?)", shopId);
		jdbc.update("DELETE FROM media WHERE shop_id = ?", shopId);
		jdbc.update("DELETE FROM product WHERE shop_id = ?", shopId);
		jdbc.update("DELETE FROM order_number_sequence WHERE shop_id = ?", shopId);
		jdbc.update("DELETE FROM shop_wallet_balance WHERE shop_id = ?", shopId);
		jdbc.update("DELETE FROM shop_follower WHERE shop_id = ?", shopId);
		jdbc.update("DELETE FROM shop WHERE id = ?", shopId);
	}

	private void requireTestDatabase() {
		// The connection's catalog, not the DataSource: that is the database actually being written
		// to, and it is what makes this guard meaningful for a mistyped JDBC URL.
		String database = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
				connection.getCatalog());
		if (database == null || !database.toLowerCase(java.util.Locale.ROOT).contains("test")) {
			throw new IllegalStateException(
					"Refusing to clear '" + database + "': this cleaner only runs against a test database");
		}
	}
}
