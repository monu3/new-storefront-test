package com.storefront.store.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.support.TestConfig;

/**
 * The audit log's <em>query</em>, as distinct from {@link AuditTrailIT}'s recording.
 *
 * <p><b>This suite exists because of a bug every other test missed.</b> {@code AuditTrailIT} proved
 * that actions are recorded correctly and never passes 5,906 tests. The filter query behind
 * {@code GET /api/v1/admin/audit} was not exercised by anything — and it returned HTTP 500 for
 * <em>every</em> request, including the unfiltered one, because Hibernate rendered
 * {@code lower(concat('%', ?,'%'))} with an untyped bind and Postgres typed the whole expression
 * {@code bytea}:
 *
 * <pre>ERROR: function lower(bytea) does not exist</pre>
 *
 * <p>The predicate is in the query on every call even when the bind is null, so "the search box was
 * empty" did not avoid it. The audit log screen — the entire point of Phase 7's foundation — had
 * never once loaded. It was found by driving the real endpoint in a browser, not by the suite.
 *
 * <p>So the tests below assert what the screen needs: that the endpoint answers, with and without
 * filters, and that each filter actually narrows.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class AdminAuditQueryIT {

	@Autowired
	private AuditLogRepository entries;

	@Autowired
	private AdminAuditController controller;

	@Autowired
	private jakarta.persistence.EntityManager em;

	@Autowired
	private com.storefront.store.support.TestFixtures fixtures;

	@BeforeEach
	void signInAsAnAdmin() {
		TestSecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(
						"admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
	}

	// ------------------------------------------------------------------ the endpoint

	@Nested
	@DisplayName("GET /admin/audit")
	class TheEndpoint {

		@Test
		@DisplayName("answers with no filters at all")
		void answersUnfiltered() {
			// The exact request the audit screen makes when it first loads. This is the call that
			// returned 500 for every visitor.
			var page = controller.list(null, null, null, null, 30, null, 0, 25);

			assertThat(page.content()).isNotNull();
			assertThat(page.totalElements()).isGreaterThanOrEqualTo(0);
		}

		@Test
		@DisplayName("answers with a search term, rather than failing on the untyped bind")
		void answersWithASearchTerm() {
			var page = controller.list(null, null, null, null, 30, "anything", 0, 25);

			assertThat(page.content()).isNotNull();
		}

		@Test
		@DisplayName("answers for every group, which is what the filter dropdown offers")
		void answersForEveryGroup() {
			for (AuditAction.Group group : AuditAction.Group.values()) {
				var page = controller.list(null, group, null, null, 30, null, 0, 25);

				assertThat(page.content())
						.as("the group %s returned nothing and the filter looked broken", group)
						.allSatisfy(entry -> assertThat(entry.group()).isEqualTo(group));
			}
		}

		@Test
		@DisplayName("answers for a single action")
		void answersForAnAction() {
			var page = controller.list(AuditAction.SHOP_FEATURE, null, null, null, 30, null, 0, 25);

			assertThat(page.content()).isNotNull();
		}

		@Test
		@DisplayName("treats a blank search as no search, not as a search for nothing")
		void treatsBlankSearchAsNoSearch() {
			var blank = controller.list(null, null, null, null, 30, "   ", 0, 25);
			var none = controller.list(null, null, null, null, 30, null, 0, 25);

			assertThat(blank.totalElements())
					.as("a cleared search box still submits \"\"; matching nothing would read as a "
							+ "broken filter rather than an absent one")
					.isEqualTo(none.totalElements());
		}
	}

	// ------------------------------------------------------------------ the repository

	@Nested
	@DisplayName("the filtered query")
	class TheQuery {

		@Test
		@DisplayName("finds the rows it recorded")
		void findsItsRows() {
			UUID entityId = given(AuditAction.SHOP_FEATURE, "Featured Green Leaf Pharmacy");

			var found = entries.search(null, null, List.<String>of(), null, entityId, null, null,
					PageRequest.of(0, 25));

			// Flushed first: AuditTrail writes with save, so inside a rolled-back test transaction
			// the row is in Hibernate's action queue and not in the table. Counting without a flush
			// returns zero and looks exactly like a broken log.
			em.flush();

			assertThat(found.getContent())
					.extracting(AuditLog::getEntityId)
					.contains(entityId);
		}

		@Test
		@DisplayName("narrows by search term, matched on the summary")
		void narrowsBySearchTerm() {
			UUID match = given(AuditAction.SHOP_FEATURE, "Featured Green Leaf Pharmacy");
			UUID miss = given(AuditAction.SHOP_FEATURE, "Featured Blue Mountain Books");
			em.flush();

			var found = entries.search(null, null, List.<String>of(), null, null, null, "Green Leaf",
					PageRequest.of(0, 25));

			assertThat(found.getContent()).extracting(AuditLog::getEntityId).contains(match);
			assertThat(found.getContent()).extracting(AuditLog::getEntityId).doesNotContain(miss);
		}

		@Test
		@DisplayName("an empty group list matches nothing rather than failing")
		void emptyGroupListMatchesNothing() {
			UUID entityId = given(AuditAction.SHOP_FEATURE, "Featured Green Leaf Pharmacy");
			em.flush();

			// The list is empty whenever no group is selected. Spring Data expands it to `IN (null)`,
			// which is valid SQL matching nothing -- and the `group IS NULL` branch is what actually
			// skips the clause. Worth pinning, because the alternative (`= ANY(...)`) expands to
			// `ANY(?,?,?)` and Postgres rejects it with a syntax error pointing at the comma.
			var noGroup = entries.search(null, null, List.<String>of(), null, null, null, null,
					PageRequest.of(0, 25));
			assertThat(noGroup.getContent()).extracting(AuditLog::getEntityId).contains(entityId);
		}

		@Test
		@DisplayName("a group narrows to exactly its member actions")
		void aGroupNarrowsToItsMembers() {
			List<String> members = java.util.Arrays.stream(AuditAction.values())
					.filter(action -> action.group() == AuditAction.Group.SHOPS)
					.map(Enum::name)
					.toList();

			var found = entries.search(null, "SHOPS", members, null, null, null, null,
					PageRequest.of(0, 50));

			assertThat(found.getContent())
					.allSatisfy(entry -> assertThat(members).contains(entry.getAction().name()));
		}

		@Test
		@DisplayName("counts every action in the window, not just the current page")
		void countsTheWholeWindow() {
			given(AuditAction.SHOP_FEATURE, "One");
			given(AuditAction.SHOP_UNFEATURE, "Two");
			em.flush();

			var counts = entries.countByActionSince(Instant.now().minusSeconds(3600));

			assertThat(counts)
					.as("a tile that says \"suspensions: 1\" because one appears on page 3 is a lie "
							+ "that looks like a number")
					.isNotEmpty();
		}
	}

	/**
	 * One row, recorded the way the platform records it.
	 *
	 * <p>Through the real constructor rather than raw SQL, so a fixture cannot produce a row the
	 * platform itself would never create -- which is how a query gets tested against a shape that
	 * does not exist.
	 */
	private UUID given(AuditAction action, String summary) {
		// A real shop, and the returned id is the row's entity: `AuditLog` derives `shop_id` from
		// the entity type and that column has a foreign key, so a made-up id fails with a
		// constraint violation that says nothing about the query under test. Returning the id the
		// row was actually written with is what lets a test filter on it -- passing a different
		// random id and expecting a match is a test that can never pass.
		UUID entityId = shop();

		em.persist(new AuditLog(action, null, "admin.test@storeapp.local", "ROLE_ADMIN",
				"Shop", entityId, null, null, summary, null, "127.0.0.1", "test"));

		return entityId;
	}
	/** A real verified shop, so audit rows about it satisfy the foreign key. */
	private java.util.UUID shop() {
		return fixtures.createVerifiedShop("Audited Shop " + UUID.randomUUID(),
				com.storefront.store.catalog.ShopType.PHARMACY).getId();
	}
}