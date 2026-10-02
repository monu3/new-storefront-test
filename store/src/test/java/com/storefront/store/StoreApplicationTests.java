package com.storefront.store;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Phase 0 Step 1 smoke test.
 *
 * <p>Verifies the application context boots against the LOCAL infrastructure and, critically,
 * that it is pointed at {@code store_app_test} — never {@code store_app}, and never
 * {@code storefront}, which holds unrelated project data on this server (see plan risk R13).
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreApplicationTests {

	@Autowired
	private DataSource dataSource;

	@Test
	void contextLoads() {
		// A non-null connection pool proves Postgres, Flyway, JPA and the
		// AMQP/Redis auto-configurations all initialised successfully.
		assertThat(dataSource).isNotNull();
	}

	@Test
	void usesTestDatabaseNotStorefront() {
		String url = ((HikariDataSource) dataSource).getJdbcUrl();
		assertThat(url)
			.as("integration tests must never touch the dev or unrelated databases")
			.contains("store_app_test")
			.doesNotContain("storefront");
	}

}
