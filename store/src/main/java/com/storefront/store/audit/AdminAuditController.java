package com.storefront.store.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Reading the audit log (Phase 7).
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')}") (D36). The log contains who suspended
 * which shop and why, which is about as sensitive as a marketplace holds — and it is also the record
 * an admin uses to justify their own decisions, so a shop owner cannot read it either.
 *
 * <p><b>Read-only, and there is deliberately no delete.</b> The repository has no delete method, so
 * an operator who wants to tidy the log has nothing to reach for. If a log is worth keeping it is
 * worth keeping whole.
 *
 * <p><b>Admin writes to the log, and the admin reads the log.</b> That is not a conflict here: the
 * log records <em>what changed and why</em>, never credentials, and every write is itself an action a
 * shop can be shown. A separate read-only auditor role would be the right call at a scale where one
 * person both breaks the till and signs off the paperwork.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "Admin audit", description = "Every privileged action, with what changed and why")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAuditController {

	private static final int MAX_PAGE_SIZE = 100;

	private final AuditLogRepository entries;

	@GetMapping
	@Operation(summary = "The audit log, newest first",
			description = "All filters are optional. Paginated, because a log with no page size is a log that gets dumped.")
	public PageResponse<AuditEntry> list(
			@RequestParam(required = false) AuditAction action,
			@RequestParam(required = false) AuditAction.Group group,
			@RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) UUID entityId,
			@RequestParam(required = false) Integer days,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

		Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));

		// The group filter is resolved to its member actions here rather than as a case expression in
		// SQL, so adding a group to the enum cannot leave a query silently returning the wrong subset.
		// Names, not enums: see AuditLogRepository.search. Passing the enum leaves Postgres with an
		// untyped bind and it gives up with a message about a parameter number.
		List<String> groupActions = group == null
				? List.of()
				: java.util.Arrays.stream(AuditAction.values())
						.filter(action1 -> action1.group() == group)
						.map(Enum::name)
						.toList();

		Instant since = days == null
				? null
				: Instant.now().minus(Math.max(days, 1), java.time.temporal.ChronoUnit.DAYS);

		// A blank search box is not a search for "%", which matches everything and reads as "the filter
		// does nothing".
		String search = q == null || q.isBlank() ? null : q.trim();

		var found = entries.search(action == null ? null : action.name(),
				group == null ? null : group.name(), groupActions,
				actorId, entityId, since, search, pageable);

		return new PageResponse<>(
				found.getContent().stream().map(AuditEntry::from).toList(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	/** One entry, for the detail drawer. */
	@GetMapping("/{id}")
	@Operation(summary = "One audit entry, with its diff")
	public ResponseEntity<AuditEntry> one(@PathVariable UUID id) {
		return entries.findById(id)
				.map(AuditEntry::from)
				.map(ResponseEntity::ok)
				// 404 rather than 403: the caller is an admin, so this is genuinely "no such entry"
				// and pretending otherwise would be a lie they could disprove.
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/** Everything that happened to one row. The question an owner asks after a suspension. */
	@GetMapping("/entity/{entityType}/{entityId}")
	@Operation(summary = "Every action taken against one row, newest first")
	public List<AuditEntry> forEntity(@PathVariable String entityType, @PathVariable UUID entityId) {
		return entries.findForEntity(entityType, entityId)
				.stream().map(AuditEntry::from).toList();
	}

	/**
	 * Counts per action, for the summary tiles.
	 *
	 * <p>Over the window, not the current page — a tile reading "suspensions: 1" because one
	 * happens to sit on page 3 is a lie that looks like a number.
	 */
	@GetMapping("/summary")
	@Operation(summary = "How many of each action, over a window")
	public Summary summary(@RequestParam(defaultValue = "30") @Min(1) int days) {
		Instant since = Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);

		Map<String, Long> byAction = new LinkedHashMap<>();
		for (Object[] row : entries.countByActionSince(since)) {
			byAction.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
		}

		Map<String, Long> byGroup = new LinkedHashMap<>();
		byAction.forEach((name, count) -> {
			AuditAction value = AuditAction.valueOf(name);
			byGroup.merge(value.group().name(), count, Long::sum);
		});

		return new Summary(days, byAction, byGroup, entries.distinctActorEmails());
	}

	// ------------------------------------------------------------------ types

	public record AuditEntry(
			String id,
			AuditAction action,
			AuditAction.Group group,
			/** Null for a scheduled job. "The scheduler did this" is a different claim. */
			String actorUserId,
			String actorEmail,
			String actorRoles,
			String entityType,
			String entityId,
			String summary,
			String reason,
			String ipAddress,
			Instant createdAt,
			Map<String, Object> before,
			Map<String, Object> after,
			/** The keys that differ, so the diff view can list them without recomputing. */
			List<String> changedFields) {

		static AuditEntry from(AuditLog entry) {
			return new AuditEntry(
					entry.getId().toString(),
					entry.getAction(),
					entry.getAction().group(),
					entry.getActorUserId() == null ? null : entry.getActorUserId().toString(),
					entry.getActorEmail(),
					entry.getActorRoles(),
					entry.getEntityType(),
					entry.getEntityId() == null ? null : entry.getEntityId().toString(),
					entry.getSummary(),
					entry.getReason(),
					entry.getIpAddress(),
					entry.getCreatedAt(),
					entry.getBeforeState(),
					entry.getAfterState(),
					entry.changedFields());
		}
	}

	public record Summary(int days, Map<String, Long> byAction,
			Map<String, Long> byGroup, List<String> actors) {
	}

	public record PageResponse<T>(
			List<T> content,
			int page,
			int size,
			long totalElements,
			int totalPages) {
	}
}
