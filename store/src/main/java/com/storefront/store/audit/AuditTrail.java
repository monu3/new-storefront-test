package com.storefront.store.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.security.CurrentUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes the audit log (Phase 7).
 *
 * <p>Phase 7's exit criterion is "every privileged action lands in the audit log", so this is the
 * piece everything else calls. It is built before the admin features rather than after, because
 * retrofitting auditing is how you end up with a log that is complete for the features somebody
 * remembered.
 *
 * <p><b>The record and the action commit together, or neither does.</b> {@link #record} joins the
 * caller's transaction ({@link Propagation#REQUIRED}) rather than opening its own.
 *
 * <p>The first version of this was {@code REQUIRES_NEW} with swallowed exceptions, on the reasoning
 * that an audit failure should not take down a suspension. That is the wrong trade, and it was also
 * a deadlock. Two reasons, both learned the hard way:
 *
 * <ol>
 *   <li><b>It deadlocks against the caller's own uncommitted rows.</b> {@code REQUIRES_NEW} suspends
 *       the caller's transaction and takes a <em>second connection</em>. The audit row carries foreign
 *       keys to the shop and the admin being acted on, so inserting it needs a shared lock on rows
 *       the suspended transaction holds exclusively and has not committed. The inner write waits for
 *       the outer commit; the outer commit waits for the test to finish; the test waits for the
 *       inner write. Three minutes of nothing, then a hang. Every test in {@code AuditTrailIT}
 *       froze exactly this way.</li>
 *   <li><b>It makes the log lie.</b> A separate transaction commits the audit row whether or not the
 *       action commits. So a suspension that rolls back leaves a record saying a shop was suspended,
 *       and a suspension that succeeds can lose its record. The log stops being evidence of
 *       anything.</li>
 * </ol>
 *
 * <p>So it joins the caller's transaction and lets a failure propagate. <b>Failing closed is the
 * correct behaviour for an audit log</b>: an action nobody can prove happened is exactly the failure
 * Phase 7 exists to prevent, and "we could not write the log, so we did not do the thing" is a
 * defensible answer while "we did the thing unrecorded" is not.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditTrail {

	// NOT named `log`: @Slf4j generates a static field of exactly that name, and a field here
	// with the same name silently shadows it -- which reads as "my logging calls do nothing"
	// rather than as a compile error.
	private final AuditLogRepository entries;
	private final CurrentUser currentUser;

	/**
	 * Records a privileged action.
	 *
	 * @param action   what was done; the closed vocabulary
	 * @param entityType "Shop", "WalletTopUpRequest", "Plan" — capitalised, because it is a Java
	 *                   type name and the log reads better that way
	 * @param entityId the row, or null for an action not about a row
	 * @param summary  one sentence; derived from the action's verb when null
	 * @param reason   required by the API for actions that take something away
	 * @param before   the fields that changed, before. Null when there is no state to compare
	 * @param after    the fields that changed, after
	 */
	@Transactional
	public void record(AuditAction action, String entityType, UUID entityId, String summary,
			String reason, Map<String, Object> before, Map<String, Object> after) {

		var caller = currentUser.optionalUser();

		entries.save(new AuditLog(
				action,
				caller == null ? null : caller.getId(),
				caller == null ? null : caller.getEmail(),
				caller == null ? null : rolesOf(),
				entityType,
				entityId,
				before,
				after,
				summary,
				reason,
				null,
				null));

		log.info("[audit] {} — {}", action, summary == null ? entityType : summary);
	}

	/** The common case: an action about a row, with no state worth diffing. */
	public void record(AuditAction action, String entityType, UUID entityId, String summary,
			String reason) {
		record(action, entityType, entityId, summary, reason, null, null);
	}

	/** An action about a row, no reason. */
	public void record(AuditAction action, String entityType, UUID entityId, String summary) {
		record(action, entityType, entityId, summary, null, null, null);
	}

	/**
	 * Records an action taken by the platform rather than a person.
	 *
	 * <p>Used by the expiry sweep. The actor is null on purpose: the log must distinguish "an admin
	 * cancelled this" from "the scheduler did", and a subscription can be revoked without anybody
	 * having touched it.
	 */
	@Transactional
	public void recordSystem(AuditAction action, String entityType, UUID entityId, String summary,
			Map<String, Object> after) {

		entries.save(new AuditLog(
				action, null, "system", null,
				entityType, entityId, null, after, summary, null, null, null));

		log.info("[audit] {} — {}", action, summary);
	}

	/**
	 * Builds a before/after pair from one field, which is most single-field transitions.
	 *
	 * <p>Only the changed field, deliberately: storing whole entities would make each row enormous
	 * and each diff a wall of identical values with the one that moved buried in it.
	 */
	public static Map<String, Object> field(String name, Object value) {
		Map<String, Object> single = new LinkedHashMap<>();
		single.put(name, value);
		return single;
	}

	/**
	 * Validates that a reason was given where one is required.
	 *
	 * <p>Checked here rather than at each call site, because "suspend without a reason" is a mistake
	 * made in twenty places rather than one, and the rule is uniform: it belongs to the action, not
	 * to the endpoint.
	 */
	public static void requireReason(AuditAction action, String reason) {
		if (action.requiresReason() && (reason == null || reason.isBlank())) {
			throw new IllegalArgumentException(
					"'" + action + "' needs a reason. The shop is told the reason, and the next "
							+ "operator needs to know what happened.");
		}
	}

	/** The caller's authorities, as a comma-joined string, frozen at write time. */
	private String rolesOf() {
		var session = currentUser.currentJwt();
		if (session == null) {
			return null;
		}
		return session.getClaimAsStringList("realm_access") == null
				? null
				: String.join(",", roles());
	}

	private List<String> roles() {
		var session = currentUser.currentJwt();
		if (session == null || session.getClaimAsStringList("realm_access") == null) {
			return List.of();
		}
		// realm_access is a map of {realm: [roles]}; flattened to a comma-joined string because the
		// column is text and the log only ever displays it.
		Object claim = session.getClaim("realm_access");
		if (claim instanceof Map<?, ?> roles) {
			return roles.values().stream()
					.filter(List.class::isInstance)
					.flatMap(value -> ((List<?>) value).stream().map(String::valueOf))
					.toList();
		}
		return List.of();
	}
}
