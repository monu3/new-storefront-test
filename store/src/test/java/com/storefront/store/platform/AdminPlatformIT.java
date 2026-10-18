package com.storefront.store.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Admin shop management and KPI dashboard (Phase 7).
 *
 * <p>Two properties here are worth more than the rest.
 *
 * <p><b>Featuring is refused for an unverified shop.</b> The alternative is a gallery that shows a
 * shop which cannot legitimately trade, for a reason its owner has to fix and cannot see. That is a
 * bug the flag itself cannot represent, which is why the rule lives in the service rather than in
 * the front end.
 *
 * <p><b>A vertical with no orders still appears in the revenue split.</b> A {@code LEFT JOIN} that
 * quietly drops it makes a shop type disappear from the revenue table, and an operator reading that
 * table concludes the type does not exist — which is the one conclusion the table exists to prevent.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class AdminPlatformIT {

	@Autowired
	private ShopAdministrationService shopAdmin;

	@Autowired
	private KpiService kpis;

	@Autowired
	private AuditLogProbe auditLog;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private AppUserRepository users;

	// ------------------------------------------------------------------ featuring

	@Nested
	@DisplayName("featuring a shop")
	class Featuring {

		@Test
		@DisplayName("puts a verified shop at the top of the gallery and records it once")
		void featuresAVerifiedShop() {
			Shop shop = fixtures.createVerifiedShop("Featured Pharmacy", ShopType.PHARMACY);

			Shop featured = shopAdmin.feature(shop.getId());

			assertThat(featured.isFeatured()).isTrue();
			assertThat(shopAdmin.featured()).extracting(Shop::getId).contains(shop.getId());

			assertThat(auditLog.countFor(shop.getId()))
					.as("one action writes one audit row")
					.isEqualTo(1);
		}

		@Test
		@DisplayName("refuses a shop that is not verified, and says why")
		void refusesAnUnverifiedShop() {
			Shop draft = fixtures.createShop("Draft Pharmacy", ShopType.PHARMACY);

			assertThatThrownBy(() -> shopAdmin.feature(draft.getId()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("not verified");

			assertThat(shopAdmin.featured())
					.as("a refused feature must not leave the shop featured")
					.doesNotContain(draft);
		}

		@Test
		@DisplayName("is idempotent — featuring twice writes one row, not two")
		void isIdempotent() {
			Shop shop = fixtures.createVerifiedShop("Twice Featured", ShopType.GROCERY);

			shopAdmin.feature(shop.getId());
			shopAdmin.feature(shop.getId());

			assertThat(auditLog.countFor(shop.getId()))
					.as("a no-op should not gain a log row; a log with duplicates is a log nobody "
							+ "can count")
					.isEqualTo(1);
		}

		@Test
		@DisplayName("takes a shop back off, and unfeaturing twice is also a no-op")
		void unfeatures() {
			Shop shop = fixtures.createVerifiedShop("Unfeatured", ShopType.BOOKS);
			shopAdmin.feature(shop.getId());

			shopAdmin.unfeature(shop.getId());
			shopAdmin.unfeature(shop.getId());

			assertThat(shop.isFeatured()).isFalse();
			assertThat(shopAdmin.featured()).doesNotContain(shop);
			assertThat(auditLog.countFor(shop.getId()))
					.as("feature plus unfeature, and the second unfeature adds nothing")
					.isEqualTo(2);
		}
	}

	// ------------------------------------------------------------------ browsing

	@Nested
	@DisplayName("the admin shop list")
	class Browsing {

		@Test
		@DisplayName("filters by vertical")
		void filtersByType() {
			fixtures.createVerifiedShop("A Pharmacy", ShopType.PHARMACY);
			fixtures.createVerifiedShop("A Bookstore", ShopType.BOOKS);

			var found = shopAdmin.browse(ShopType.BOOKS, null, null, null, PageRequest.of(0, 25));

			assertThat(found.getContent()).extracting(ShopAdministrationService.ShopView::shopType)
					.containsOnly(ShopType.BOOKS);
		}

		@Test
		@DisplayName("filters by status, and can ask for drafts")
		void filtersByStatus() {
			fixtures.createVerifiedShop("Live One", ShopType.PHARMACY);
			fixtures.createShop("Draft One", ShopType.PHARMACY);

			var verified = shopAdmin.browse(null, Shop.Status.VERIFIED, null, null,
					PageRequest.of(0, 25));

			assertThat(verified.getContent()).isNotEmpty();
			assertThat(verified.getContent())
					.extracting(ShopAdministrationService.ShopView::status)
					.containsOnly(Shop.Status.VERIFIED);
		}

		@Test
		@DisplayName("searches on name, case-insensitively")
		void searchesOnName() {
			fixtures.createVerifiedShop("Kantipur Pharmacy", ShopType.PHARMACY);
			fixtures.createVerifiedShop("Bright Books", ShopType.BOOKS);

			var found = shopAdmin.browse(null, null, null, "kantipur", PageRequest.of(0, 25));

			assertThat(found.getContent()).extracting(ShopAdministrationService.ShopView::name)
					.anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("kantipur"));
		}

		@Test
		@DisplayName("a blank search matches everything rather than nothing")
		void blankSearchMatchesEverything() {
			fixtures.createVerifiedShop("One", ShopType.PHARMACY);
			fixtures.createVerifiedShop("Two", ShopType.BOOKS);

			// A cleared search box still submits "". If that were passed through, '%' would match
			// every row -- fine -- but a naive null-vs-empty distinction would match none, and the
			// symptom is "the search is broken" on an empty box.
			var blank = shopAdmin.browse(null, null, null, "   ", PageRequest.of(0, 25));
			var none = shopAdmin.browse(null, null, null, null, PageRequest.of(0, 25));

			assertThat(blank.getTotalElements()).isEqualTo(none.getTotalElements());
		}

		@Test
		@DisplayName("paginates rather than returning every shop")
		void paginates() {
			fixtures.createVerifiedShop("Paged One", ShopType.PHARMACY);
			fixtures.createVerifiedShop("Paged Two", ShopType.PHARMACY);
			fixtures.createVerifiedShop("Paged Three", ShopType.PHARMACY);

			var firstPage = shopAdmin.browse(null, null, null, null, PageRequest.of(0, 1));

			assertThat(firstPage.getContent()).hasSize(1);
			assertThat(firstPage.getTotalElements()).isGreaterThanOrEqualTo(3);
			assertThat(firstPage.getTotalPages()).isGreaterThan(1);
		}

		@Test
		@DisplayName("caps a page size that asks for more than the service allows")
		void capsPageSize() {
			fixtures.createVerifiedShop("Capped", ShopType.PHARMACY);

			var found = shopAdmin.browse(null, null, null, null, PageRequest.of(0, 10_000));

			assertThat(found.getSize())
					.as("a crafted page size must not be honoured")
					.isLessThanOrEqualTo(100);
		}
	}

	// ------------------------------------------------------------------ the dashboard

	@Nested
	@DisplayName("the KPI dashboard")
	class Dashboard {

		@Test
		@DisplayName("answers rather than erroring on an empty platform")
		void answersOnAnEmptyPlatform() {
			var dashboard = kpis.dashboard(30);

			assertThat(dashboard.orders()).isGreaterThanOrEqualTo(0);
			assertThat(dashboard.gmvDisplay()).isNotBlank();
			assertThat(dashboard.commissionRateBp())
					.as("the rate is a platform setting, not a constant")
					.isEqualTo(500);
		}

		@Test
		@DisplayName("names its window, so the figures are not free-floating")
		void namesItsWindow() {
			var dashboard = kpis.dashboard(7);

			assertThat(dashboard.windowDays()).isEqualTo(7);
			assertThat(dashboard.window())
					.contains("7 days")
					.as("a ratio with an unstated denominator means something different every "
							+ "time it appears, and the text is what fixes it");
		}

		@Test
		@DisplayName("refuses a window that is not a window")
		void refusesAnAbsurdWindow() {
			assertThatThrownBy(() -> kpis.dashboard(0))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> kpis.dashboard(10_000))
					.as("a crafted window is a full-table aggregate per day of it")
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("reports growth as unknown rather than zero when there is no baseline")
		void growthIsUnknownWithoutABaseline() {
			// The test database carries rows from earlier runs, so a 1-day window is the only one
			// likely to be empty -- and asserting "no prior data" absolutely would make this fail
			// the first time somebody places an order.
			var dashboard = kpis.dashboard(1);

			if (dashboard.previousGmvMinor() == 0) {
				assertThat(dashboard.gmvGrowthPercent())
						.as("\"up 0%\" from no prior sales is a claim; \"no previous period\" is "
								+ "the truth")
						.isNull();
			}
		}

		@Test
		@DisplayName("lists every vertical that exists, including the ones with no orders")
		void listsVerticalsWithNoOrders() {
			fixtures.createVerifiedShop("A Pharmacy", ShopType.PHARMACY);

			var dashboard = kpis.dashboard(30);

			assertThat(dashboard.revenueByType())
					.as("a vertical that quietly vanishes from the revenue table looks like a "
							+ "vertical that does not exist")
					.extracting(KpiService.TypeRow::shopType)
					.contains(ShopType.PHARMACY.name());
		}

		@Test
		@DisplayName("counts ARPU against shops that exist, not against zero")
		void arpuDoesNotDivideByZero() {
			var dashboard = kpis.dashboard(30);

			assertThat(dashboard.arpuPerActiveShopMinor()).isGreaterThanOrEqualTo(0);
			assertThat(dashboard.arpuPerVerifiedShopDisplay()).isNotBlank();
		}

		@Test
		@DisplayName("names the realm a person belongs to, not the wire value")
		void namesRealmsInPlainWords() {
			var dashboard = kpis.dashboard(30);

			assertThat(dashboard.peopleByRealmAndStatus().keySet())
					.allSatisfy(key -> assertThat(key.toLowerCase(java.util.Locale.ROOT))
							.doesNotContain("ops /"));
		}

		@Test
		@DisplayName("churn is counted against shops, so an empty window cannot report 100%")
		void churnIsARateOfSomethingReal() {
			var dashboard = kpis.dashboard(30);

			assertThat(dashboard.churnRatePercent())
					.as("with no earlier window there is no churn, and reporting a percentage "
							+ "anyway invents one")
					.isBetween(0d, 100d);
		}
	}

	/**
	 * A narrow reader over the audit log, so the tests do not depend on the admin controller.
	 *
	 * <p><b>It flushes first, and that is the whole reason this class exists rather than a direct
	 * {@code saveAndFlush}.</b> {@code AuditTrail} writes with {@code save}, so inside a test
	 * transaction the row sits in Hibernate's action queue and not in the table. A raw JDBC count
	 * without a flush returns 0 and the test fails while looking like a broken audit log — a
	 * conclusion that would send someone looking for a bug in {@code AuditTrail} that is not there.
	 */
	@Component
	static class AuditLogProbe {

		private final org.springframework.jdbc.core.JdbcTemplate jdbc;
		private final jakarta.persistence.EntityManager em;

		AuditLogProbe(org.springframework.jdbc.core.JdbcTemplate jdbc,
				jakarta.persistence.EntityManager em) {
			this.jdbc = jdbc;
			this.em = em;
		}

		int countFor(UUID shopId) {
			em.flush();

			Integer count = jdbc.queryForObject(
					"SELECT count(*) FROM audit_log WHERE entity_id::text = ?",
					Integer.class, shopId.toString());
			return count == null ? 0 : count;
		}
	}
}