package com.storefront.store.campaign;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.common.PageResponse;
import com.storefront.store.security.TenantGuard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Promotional campaigns (Phase 8).
 *
 * <p><b>The public endpoint takes no shop id.</b> A storefront asking "what is running here" already
 * knows which shop it is serving, so the shop comes from the caller's context rather than a
 * parameter — which would otherwise let any storefront ask for any shop's campaigns.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Campaigns", description = "Scheduled and promotional campaigns")
@RequiredArgsConstructor
public class CampaignController {

	private final CampaignService campaigns;
	private final TenantGuard tenantGuard;

	/**
	 * What is running on a storefront right now.
	 *
	 * <p><b>{@code shop} is a parameter here, and that is deliberate.</b> Every other shop-scoped
	 * endpoint on this platform takes the shop from the token, because they are all about a shop's
	 * private data. This one is not: it is the public storefront asking what to render, and an
	 * anonymous visitor has no shop in their token. Campaigns are already public — the storefront
	 * shows them to anyone — so naming one in a query is not an authorisation decision.
	 *
	 * <p>With no {@code shop}, only platform-wide campaigns are returned, which is what a page that
	 * is not about a particular shop should show.
	 */
	@GetMapping("/campaigns/live")
	@Operation(summary = "Campaigns live at this moment, platform-wide first",
			description = "Pass ?shop=<uuid> for that shop's own campaigns too. Omit for "
					+ "platform-wide only.")
	public List<CampaignService.CampaignView> live(
			@RequestParam(required = false) UUID shop) {
		return campaigns.live(shop);
	}

	/** A shop's own campaigns. */
	@GetMapping("/shops/campaigns")
	@PreAuthorize("hasRole('ROLE_SHOP') or hasRole('ROLE_ADMIN')")
	@Operation(summary = "This shop's campaigns, newest first")
	public PageResponse<CampaignService.CampaignView> forShop(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {

		var found = campaigns.forShop(tenantGuard.requireOwnedShop().getId(),
				PageRequest.of(page, Math.min(size, 100)));

		return new PageResponse<>(
				found.getContent(),
				found.getNumber(), found.getSize(),
				found.getTotalElements(), found.getTotalPages());
	}

	@PostMapping("/shops/campaigns")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('ROLE_SHOP') or hasRole('ROLE_ADMIN')")
	@Operation(summary = "Schedule a campaign")
	public CampaignService.CampaignView create(@Valid @RequestBody CampaignRequest body) {
		return CampaignService.CampaignView.from(campaigns.create(
				tenantGuard.requireOwnedShop().getId(),
				body.name(), body.kind(), body.discountBp(),
				body.parsedStartsAt(), body.parsedEndsAt(),
				body.headline(), body.body(), body.description()));
	}

	// ------------------------------------------------------------------ types

	public record CampaignRequest(
			@NotBlank @Size(max = 120) String name,
			@NotNull Campaign.Kind kind,
			Integer discountBp,
			@NotNull String startsAt,
			String endsAt,
			@Size(max = 160) String headline,
			@Size(max = 2000) String body,
			@Size(max = 1000) String description) {

		/** Parses the ISO instants here so a bad date is a 400 with a useful message. */
		Instant parsedStartsAt() {
			try {
				return Instant.parse(startsAt);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException(
						"startsAt must be an ISO-8601 instant such as 2026-12-01T00:00:00Z, not \""
								+ startsAt + "\".");
			}
		}

		Instant parsedEndsAt() {
			if (endsAt == null || endsAt.isBlank()) {
				return null;
			}
			try {
				return Instant.parse(endsAt);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException(
						"endsAt must be an ISO-8601 instant or be absent, not \"" + endsAt + "\".");
			}
		}
	}
}
