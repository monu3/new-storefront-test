package com.storefront.store.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.identity.AppUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Support tooling and account management (Phase 7).
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')")} (D36) — both the message history and the
 * people list are the platform's own business, and neither is a shop's.
 *
 * <p><b>Suspending an admin is refused in the service, not here.</b> The rule has to hold for every
 * caller, and a rule enforced in a controller is a rule the next caller does not get — see
 * {@link UserAdministrationService}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin support", description = "Notification history, the email log, and accounts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminSupportController {

	private final SupportService support;
	private final UserAdministrationService users;

	/**
	 * The notification history, filtered.
	 *
	 * <p>{@code status=FAILED} is the query support runs when a shop says "your reset link never
	 * arrived", and {@code channel=EMAIL} is the email log — the same rows, not a second table
	 * that could disagree with the first.
	 */
	@GetMapping("/notifications")
	@Operation(summary = "Notification history with delivery status, retries and errors",
			description = "Filter by channel (EMAIL is the email log), status, audience, type or text.")
	public SupportService.History notifications(
			@RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
			@RequestParam(required = false) String channel,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String audience,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
			@RequestParam(defaultValue = "0") @Min(0) int offset) {

		return support.history(new SupportService.NotificationFilters(
				days, channel, status, audience, type, q, limit, offset));
	}

	/** Just the emails, for the support agent who was asked about one specific email. */
	@GetMapping("/notifications/emails")
	@Operation(summary = "The email log — the same rows with channel=EMAIL")
	public SupportService.History emails(
			@RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
			@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
			@RequestParam(defaultValue = "0") @Min(0) int offset) {

		return support.emailLog(days, limit, offset);
	}

	/**
	 * Every account in both realms.
	 *
	 * <p>{@code realm} narrows rather than being required, which is the point: an admin's questions
	 * cross the realm line constantly, and a screen that shows only one makes them search the other
	 * by hand and guess.
	 */
	@GetMapping("/users")
	@Operation(summary = "Every account in both realms, filterable")
	public com.storefront.store.common.PageResponse<UserView> allUsers(
			@RequestParam(required = false) AppUser.Realm realm,
			@RequestParam(required = false) AppUser.Status status,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {

		var found = users.browse(realm, status, q,
				org.springframework.data.domain.PageRequest.of(page, size));

		return new com.storefront.store.common.PageResponse<>(
				found.getContent().stream().map(UserView::from).toList(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	@PostMapping("/users/{userId}/suspend")
	@Operation(summary = "Suspend an account",
			description = "Refused for a platform admin: any admin could otherwise lock every "
					+ "operator out of the platform with no recovery path from inside it.")
	public UserView suspend(@PathVariable java.util.UUID userId,
			@Valid @RequestBody ReasonBody body) {
		return UserView.from(users.suspend(userId, body.reason()));
	}

	@PostMapping("/users/{userId}/reinstate")
	@Operation(summary = "Put a suspended account back")
	public UserView reinstate(@PathVariable java.util.UUID userId) {
		return UserView.from(users.reinstate(userId));
	}

	// ------------------------------------------------------------------ types

	/**
	 * A required reason.
	 *
	 * <p>Required rather than optional because a suspension with no recorded reason is one the next
	 * operator cannot act on: the account is locked, nobody knows why, and reinstating it is the
	 * only move available to anyone.
	 */
	public record ReasonBody(@NotBlank @Size(max = 500) String reason) {
	}

	/**
	 * An account in the admin's list.
	 *
	 * <p>{@code realm} is here so an admin can see at a glance which of the two Keycloak realms this
	 * person belongs to, rather than inferring it from an email address, and {@code isAdmin} is
	 * there because the console greys out the suspend button — the server refuses it too, but a
	 * button that always fails is worse than one that is not offered.
	 */
	public record UserView(
			java.util.UUID id,
			AppUser.Realm realm,
			String email,
			String fullName,
			AppUser.Status status,
			String roles,
			boolean isAdmin,
			java.time.Instant createdAt,
			java.time.Instant lastLoginAt) {

		static UserView from(AppUser user) {
			return new UserView(
					user.getId(), user.getRealm(), user.getEmail(), user.getFullName(),
					user.getStatus(), user.getRoles(), user.isAdmin(),
					user.getCreatedAt(), user.getLastLoginAt());
		}
	}
}