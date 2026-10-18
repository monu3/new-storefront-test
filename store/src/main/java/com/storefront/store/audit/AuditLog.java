package com.storefront.store.audit;

import java.util.Map;
import java.util.UUID;

import com.storefront.store.common.BaseEntity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One privileged action (Phase 7).
 *
 * <p><b>Append-only.</b> There is no update path, no delete path, and no repository method that
 * would support one. An audit trail that can be edited is not an audit trail, and the only defence
 * is for the code to make editing impossible rather than for everyone to remember not to.
 *
 * <p><b>The actor's email is copied, not joined.</b> A record that disappears when the account is
 * deleted is not a record, and deleting an admin's account is exactly when somebody would want to
 * read what they did.
 *
 * <p><b>{@code beforeState} and {@code afterState} hold only what changed.</b> Whole entities would
 * make each row enormous and each diff a wall of identical fields with the three that moved buried in
 * it. The reader came to see the change.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter(AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog extends BaseEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** Null for a scheduled job. "The scheduler did this" is a different claim from "an admin did". */
	@Column(name = "actor_user_id", updatable = false)
	private UUID actorUserId;

	/** Copied at write time, so the record outlives the account. */
	@Column(name = "actor_email", updatable = false)
	private String actorEmail;

	/** The authorities in force at the time, so a later role change does not rewrite history. */
	@Column(name = "actor_roles", updatable = false)
	private String actorRoles;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, updatable = false)
	private AuditAction action;

	@Column(name = "entity_type", updatable = false)
	private String entityType;

	@Column(name = "entity_id", updatable = false)
	private UUID entityId;

	/**
	 * The shop this action was about, when there is one.
	 *
	 * <p>Kept from the Phase 1 declaration. It is redundant with {@code entityType = "Shop"} and
	 * {@code entityId}, and it is still its own column because "everything that happened to this
	 * shop" is then a one-column index scan rather than a string match -- and that is the query a
	 * shop owner most wants when they ask why they were suspended.
	 */
	@Column(name = "shop_id", updatable = false)
	private UUID shopId;

	/**
	 * Only the fields that changed. Null when the action has no state to compare.
	 *
	 * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} is required, not decorative: Hibernate cannot infer a
	 * JDBC type for {@code Map<String, Object>} and fails at startup with "Could not determine
	 * recommended JdbcType" — a message that names neither the column nor the missing annotation.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "before_state", updatable = false)
	private Map<String, Object> beforeState;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "after_state", updatable = false)
	private Map<String, Object> afterState;

	/** One sentence, readable in a list without opening the row. */
	@Column(name = "summary", nullable = false, updatable = false)
	private String summary;

	/** Required by the API for actions where {@link AuditAction#requiresReason()}. */
	@Column(name = "reason", updatable = false)
	private String reason;

	@Column(name = "ip_address", updatable = false)
	private String ipAddress;

	@Column(name = "user_agent", updatable = false)
	private String userAgent;

	public AuditLog(AuditAction action, UUID actorUserId, String actorEmail, String actorRoles,
			String entityType, UUID entityId, Map<String, Object> before,
			Map<String, Object> after, String summary, String reason,
			String ipAddress, String userAgent) {
		// Set from the entity type rather than passed separately, because the only caller that
		// would set it differently would be one that is about a shop without saying so.
		this.shopId = "Shop".equals(entityType) ? entityId : null;

		this.id = UUID.randomUUID();
		this.action = action;
		this.actorUserId = actorUserId;
		this.actorEmail = actorEmail;
		this.actorRoles = actorRoles;
		this.entityType = entityType;
		this.entityId = entityId;
		this.beforeState = before;
		this.afterState = after;
		this.summary = summary == null || summary.isBlank()
				? defaultSummary(action, entityType)
				: summary;
		this.reason = reason;
		this.ipAddress = ipAddress;
		this.userAgent = userAgent;
	}

	/**
	 * A summary for an action whose caller did not supply one.
	 *
	 * <p>The column is NOT NULL because a list of actions with a blank description is a list nobody
	 * can scan, so a missing summary falls back to something rather than failing the write — losing
	 * the record entirely would be worse than a terse one.
	 */
	private static String defaultSummary(AuditAction action, String entityType) {
		return action.verb() + " " + (entityType == null ? "something" : entityType.toLowerCase());
	}

	/** True when this action changed a state, so the diff view has something to draw. */
	public boolean hasDiff() {
		return beforeState != null || afterState != null;
	}

	/** The keys that differ between before and after, for the diff view's summary row. */
	public java.util.List<String> changedFields() {
		java.util.Set<String> keys = new java.util.LinkedHashSet<>();
		if (beforeState != null) {
			keys.addAll(beforeState.keySet());
		}
		if (afterState != null) {
			keys.addAll(afterState.keySet());
		}

		java.util.List<String> changed = new java.util.ArrayList<>();
		for (String key : keys) {
			Object before = beforeState == null ? null : beforeState.get(key);
			Object after = afterState == null ? null : afterState.get(key);
			if (!java.util.Objects.equals(before, after)) {
				changed.add(key);
			}
		}
		return changed;
	}
}
