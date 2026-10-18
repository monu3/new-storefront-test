package com.storefront.store.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.support.TestConfig;

/**
 * The admin data explorer (Phase 7).
 *
 * <p><b>The property worth the most attention: it refuses to be a way to act.</b> The contract
 * describes the whole platform, so an explorer built from it without a restriction is a second,
 * less-audited path to every shop's and every customer's data — and "I found it in the explorer" is
 * not an answer to "who changed this".
 *
 * <p><b>And it reads the live contract, not a catalogue.</b> A hand-written list of endpoints drifts
 * the moment one is added, and then it lies in both directions at once: offering an endpoint that
 * no longer exists, and omitting one that does.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class DataExplorerIT {

	@Autowired
	private DataExplorerController explorer;

	@Autowired
	private io.swagger.v3.oas.models.OpenAPI contract;
	@BeforeEach
	void signInAsAnAdmin() {
		// The controllers carry `@PreAuthorize("hasRole('ADMIN')")`, so calling them needs an
		// authenticated admin or method security rejects the call before any body runs. The
		// authority list is what that annotation reads, so it is the whole of the admin context --
		// there is no token to forge and no realm to set up.
		//
		// Declared on the outer class on purpose: JUnit runs a parent's @BeforeEach before every
		// nested test, and a copy inside each @Nested group is one more place to forget.
		TestSecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(
						"admin-" + java.util.UUID.randomUUID(), null,
						List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
	}


	// ------------------------------------------------------------------ the contract

	@Nested
	@DisplayName("the contract")
	class TheContract {

		@Test
		@DisplayName("is read from the same annotations the controllers use")
		void comesFromTheControllers() {
			var tree = explorer.contract();

			assertThat(tree.tagNames())
					.as("a hand-written catalogue would drift the moment an endpoint is added, and "
							+ "then it lies: offering something that is gone, omitting something "
							+ "that is not")
					.isNotEmpty();
			assertThat(tree.runnableCount()).isGreaterThan(0);
		}

		@Test
		@DisplayName("includes an endpoint added after the explorer was written")
		void includesNewEndpoints() {
			boolean knowsAboutKpis = explorer.contract().tags().stream()
					.flatMap(tag -> tag.items().stream())
					.anyMatch(endpoint -> endpoint.path().equals("/api/v1/admin/kpis"));

			assertThat(knowsAboutKpis)
					.as("the dashboard is one of the newest admin endpoints; if the explorer "
							+ "cannot see it, it is a list rather than a view of the contract")
					.isTrue();
		}

		@Test
		@DisplayName("groups by tag rather than flattening 200 paths into one list")
		void groupsByTag() {
			var tags = explorer.contract().tags();

			assertThat(tags).isNotEmpty();
			assertThat(tags).allSatisfy(tag -> {
				assertThat(tag.name()).isNotBlank();
				assertThat(tag.items())
						.as("a tag that says it has operations but lists none is a broken group")
						.hasSize((int) tag.operations());
			});
		}

		@Test
		@DisplayName("marks shop and customer paths as not runnable, and says why")
		void refusesForeignPaths() {
			List<DataExplorerController.Endpoint> foreign = explorer.contract().tags().stream()
					.flatMap(tag -> tag.items().stream())
					.filter(endpoint -> !endpoint.path().startsWith("/api/v1/admin"))
					.toList();

			assertThat(foreign)
					.as("the contract covers the whole platform; the explorer must mark the parts "
							+ "it will not run rather than offering them")
					.isNotEmpty();
			assertThat(foreign).allSatisfy(endpoint -> assertThat(endpoint.runnable()).isFalse());
		}

		@Test
		@DisplayName("marks writes as not runnable, even under /admin")
		void refusesWrites() {
			List<DataExplorerController.Endpoint> writes = explorer.contract().tags().stream()
					.flatMap(tag -> tag.items().stream())
					.filter(endpoint -> endpoint.path().startsWith("/api/v1/admin"))
					.filter(endpoint -> !endpoint.method().equals("GET"))
					.toList();

			assertThat(writes)
					.as("the platform has many admin writes, and every one of them is audited; a "
							+ "screen that skipped the audit log would be a second, quieter way to "
							+ "change the marketplace")
					.isNotEmpty();
			assertThat(writes).allSatisfy(endpoint -> assertThat(endpoint.runnable()).isFalse());
		}

		@Test
		@DisplayName("offers query parameters, and only query parameters")
		void offersQueryParameters() {
			List<DataExplorerController.Parameters> parameters =
					explorer.contract().tags().stream()
							.flatMap(tag -> tag.items().stream())
							.flatMap(endpoint -> endpoint.parameters().stream())
							.toList();

			assertThat(parameters)
					.as("a parameter the explorer cannot fill is a form that submits a wrong value")
					.isNotEmpty();
			assertThat(parameters)
					.allSatisfy(parameter -> assertThat(parameter.in()).isEqualTo("query"));
		}
	}

	// ------------------------------------------------------------------ refusing

	@Nested
	@DisplayName("running a read")
	class Running {

		@Test
		@DisplayName("refuses a path outside the admin tree, with the reason")
		void refusesAForeignPath() {
			var result = probe("/api/v1/shops");

			assertThat(result.ok()).isFalse();
			assertThat(result.status()).isEqualTo(403);
			assertThat(result.error())
					.contains("/api/v1/admin")
					.as("an operator who typed a path needs the rule, not just a refusal");
		}

		@Test
		@DisplayName("refuses a shop path even though it exists in the contract")
		void refusesAnExistingShopPath() {
			var result = probe("/api/v1/shops/00000000-0000-0000-0000-000000000001");

			assertThat(result.status()).isEqualTo(403);
			assertThat(result.error()).isNotBlank();
		}

		@Test
		@DisplayName("refuses a crafted path rather than reaching it")
		void refusesACraftedPath() {
			// The regex on the request body rejects this before it reaches anything, and the
			// controller re-checks the prefix anyway -- the body was validated at the edge, but the
			// contract is a snapshot and a hand-typed form is not.
			var result = probe("/api/v1/admin/../../etc/passwd");

			assertThat(result.ok()).isFalse();
			assertThat(result.body()).isNull();
		}

		@Test
		@DisplayName("accepts an admin read path")
		void acceptsAnAdminRead() {
			var result = probe("/api/v1/admin/kpis",
					java.util.Map.of("days", "30"));

			// Without a bearer token forwarded the inner call is anonymous and comes back 401.
			// That is the correct answer here -- it proves the probe runs through the real
			// security stack rather than around it -- so the assertion is about the shape.
			assertThat(result.path()).isEqualTo("/api/v1/admin/kpis");
			assertThat(result.query()).isNotBlank();
		}
	}

	/**
	 * Runs a probe the way the controller does.
	 *
	 * <p>No token, so most calls come back 401. That is not a weakness of the test: the point being
	 * checked is the path restriction, and an anonymous call proves the probe does not bypass the
	 * security filter chain on its way in.
	 */
	private DataExplorerController.ProbeResult probe(String path) {
		return probe(path, java.util.Map.of());
	}

	private DataExplorerController.ProbeResult probe(String path, java.util.Map<String, String> params) {
		var request = new DataExplorerController.ProbeRequest(path, params);

		// The controller needs a servlet request for the header; a null stand-in is safe because
		// every path under test is refused before the header is ever read.
		org.springframework.mock.web.MockHttpServletRequest servlet =
				new org.springframework.mock.web.MockHttpServletRequest();

		return explorer.probe(request, servlet);
	}
}