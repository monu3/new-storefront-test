package com.storefront.store.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The audit log's queries.
 *
 * <p><b>No update and no delete method.</b> That is the whole design, and it is enforced by the
 * absence of a method rather than by a comment: an operator who wanted to tidy the log would find
 * {@code deleteAll} on some other repository and use it, but here there is nothing to reach for.
 *
 * <p>Every list query is newest-first, because the question an operator opens this screen to ask is
 * "what did they just do?", and the answer is almost never three months ago.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

	Page<AuditLog> findByActionOrderByCreatedAtDesc(AuditAction action, Pageable pageable);

	Page<AuditLog> findByActorUserIdOrderByCreatedAtDesc(UUID actorUserId, Pageable pageable);

	/**
	 * Everything that happened to one row, newest first.
	 *
	 * <p><b>An explicit query rather than a derived name.</b> There is also a paged finder over the
	 * same columns, and two derived methods differing only by a trailing {@code Pageable} are
	 * ambiguous to Spring Data's parser — which fails with a message naming neither of them. This one
	 * returns a plain list, so it gets a name of its own.
	 *
	 * <p>Bounded by the caller: "everything that happened to this shop" is small, because an admin
	 * performs a handful of actions on a shop over its life.
	 */
	@Query("""
			select a from AuditLog a
			where a.entityType = :entityType and a.entityId = :entityId
			order by a.createdAt desc
			""")
	List<AuditLog> findForEntity(@Param("entityType") String entityType,
			@Param("entityId") UUID entityId);

	/**
	 * The filtered log.
	 *
	 * <p>Every filter nullable, all applied in one query rather than composed in memory — an audit
	 * log is exactly the table that grows without bound, and loading it to filter it is the one
	 * approach that stops working first.
	 *
	 * <p>The group filter arrives as a resolved list of member actions rather than a case
	 * expression, so adding a group to {@link AuditAction} cannot leave a query silently returning
	 * the wrong subset.
	 *
	 * <p><b>{@code action}, {@code group} and {@code groupActions} are names, not enums.</b> Bound
	 * as enums against a {@code CAST(? AS text)}, Postgres fails with "could not determine data type
	 * of parameter $n" — again on a parameter used only in an {@code IS NULL} test, so again on
	 * every request rather than only the filtered ones.
	 *
	 * <p><b>{@code IN (:groupActions)}, not {@code = ANY(...)}.</b> Spring Data expands a
	 * collection into a comma-separated bind list, and {@code ANY(?)} needs a single array -- so
	 * {@code = ANY} renders as {@code ANY(?,?,?)} and Postgres rejects it with a syntax error that
	 * points at the comma. An <em>empty</em> list expands to {@code IN (null)}, which is valid SQL
	 * matching nothing, and the {@code group IS NULL} branch is what actually skips the clause.
	 *
	 * @param action   null, or an {@link AuditAction} name
	 * @param group    null, or an {@link AuditAction.Group} name
	 * @param actorId  null for every actor, including system actions
	 * @param entityId null for actions not about a row
	 * @param since    null for the whole history
	 * @param search   null or blank for no text filter; matched against the summary and the actor
	 */
	//
	// NATIVE, and not for style. As JPQL this query compiles to SQL Postgres rejects:
	//
	//   ERROR: function lower(bytea) does not exist
	//
	// <p>Hibernate renders `lower(concat('%', ?,'%'))` with an untyped bind, Postgres resolves the
	// concatenation against the parameter rather than against the `text` column beside it, and the
	// whole expression ends up typed `bytea`. Nothing in that message mentions the search term --
	// it names a function, and the failure happens on *every* request, including the ones with no
	// search at all, because the predicate is always in the query even when the bind is null.
	//
	// <p>Written natively with explicit `CAST(:x AS text)`, which is the same reasoning as the
	// admin list queries: a nullable bind needs its type stated or Postgres cannot infer it.
	//
	@Query(value = """
			SELECT a.* FROM audit_log a
			WHERE (CAST(:action AS text) IS NULL OR a.action = :action)
			  AND (CAST(:actorId AS uuid) IS NULL OR a.actor_user_id = :actorId)
			  AND (CAST(:entityId AS uuid) IS NULL OR a.entity_id = :entityId)
			  AND (CAST(:since AS timestamptz) IS NULL OR a.created_at >= :since)
			  AND (CAST(:group AS text) IS NULL OR a.action IN (:groupActions))
			  AND (CAST(:search AS text) IS NULL
			       OR lower(a.summary) LIKE lower('%' || :search || '%')
			       OR lower(coalesce(a.actor_email, '')) LIKE lower('%' || :search || '%'))
			ORDER BY a.created_at DESC
			""", nativeQuery = true)
	Page<AuditLog> search(@Param("action") String action,
			@Param("group") String group,
			@Param("groupActions") List<String> groupActions,
			@Param("actorId") UUID actorId,
			@Param("entityId") UUID entityId,
			@Param("since") Instant since,
			@Param("search") String search,
			Pageable pageable);

	/**
	 * How many of each action, for the admin's summary tiles.
	 *
	 * <p>Over the whole log rather than the current page: a tile that says "suspensions: 1" because
	 * one appears on page 3 is a lie that looks like a number.
	 */
	@Query("""
			select a.action, count(a) from AuditLog a
			where a.createdAt >= :since
			group by a.action
			""")
	List<Object[]> countByActionSince(@Param("since") Instant since);

	/** Everything one actor has done, for a review. */
	List<AuditLog> findByActorUserIdOrderByCreatedAtDesc(UUID actorUserId);

	/** Distinct actors present in the log, for the filter dropdown. */
	@Query("""
			select distinct a.actorEmail from AuditLog a
			where a.actorEmail is not null
			order by a.actorEmail
			""")
	List<String> distinctActorEmails();
}
