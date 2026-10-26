package com.storefront.store.theme;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storefront.store.catalog.ShopType;
import com.storefront.store.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Admin: authoring the template marketplace (D8).
 *
 * <p>Class-level {@code @PreAuthorize} (D36), so every endpoint here needs ADMIN and a new one
 * cannot be added without an authorisation decision.
 *
 * <p>The create/update contract takes the theme document as a <b>raw JSON string</b> rather than
 * a typed object, deliberately. The document's shape is the zod schema's business and it changes;
 * binding it to a Java record would make every schema change a coordinated edit across two
 * languages and a deployment. The string is validated immediately, so nothing unvalidated reaches
 * the database.
 */
@RestController
@RequestMapping("/api/v1/admin/theme-templates")
@Tag(name = "Admin themes", description = "Author and publish the template marketplace")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminThemeController {

	private static final int MAX_PAGE_SIZE = 100;

	private final ThemeService themes;
	private final CurrentUser currentUser;
	private final ObjectMapper json;

	/**
	 * Every template, drafts included.
	 *
	 * <p>The marketplace only shows published ones, but an admin needs to see their drafts while
	 * they work on them.
	 */
	@GetMapping
	@Operation(summary = "All templates, including unpublished drafts")
	public List<TemplateView> list(
			@RequestParam(required = false) ShopType shopType,
			@RequestParam(required = false) Boolean published,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "50") @Min(1) int size) {

		return themes.allTemplates(PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
						Sort.by(Sort.Direction.ASC, "sortOrder")))
				.getContent().stream()
				.filter(t -> shopType == null || shopType.name().equals(t.getShopType()))
				.filter(t -> published == null || t.isPublished() == published)
				.map(this::toView)
				.toList();
	}

	@GetMapping("/{templateId}")
	@Operation(summary = "One template with its full theme document")
	public TemplateDetail get(@PathVariable UUID templateId) {
		ThemeTemplate template = themes.getTemplate(templateId);
		return new TemplateDetail(toView(template), template.getConfig(),
				themes.sectionCount(template.getConfig()));
	}

	@PostMapping
	@Operation(summary = "Create a template as a draft",
			description = "The document is validated against the shared schema. Nothing appears in the marketplace until published.")
	public ResponseEntity<TemplateDetail> create(@Valid @RequestBody CreateTemplateRequest request) {
		ThemeTemplate template = themes.createTemplate(request.code(), request.name(),
				request.description(), request.shopType(), request.config(), request.priceTier(),
				currentUser.optionalUserId());

		return ResponseEntity.status(201).body(
				new TemplateDetail(toView(template), template.getConfig(),
						themes.sectionCount(template.getConfig())));
	}

	@PatchMapping("/{templateId}")
	@Operation(summary = "Update a draft",
			description = "Refused once published: shops have installed it and their storefronts must not change underneath them.")
	public TemplateDetail update(@PathVariable UUID templateId,
			@Valid @RequestBody UpdateTemplateRequest request) {

		ThemeTemplate template = themes.updateTemplate(templateId, request.name(), request.description(),
				request.shopType(), request.config(), request.sortOrder(), request.previewMediaId());

		return new TemplateDetail(toView(template), template.getConfig(),
				themes.sectionCount(template.getConfig()));
	}

	@PostMapping("/{templateId}/publish")
	@Operation(summary = "Publish to the marketplace")
	public TemplateView publish(@PathVariable UUID templateId) {
		return toView(themes.publishTemplate(templateId, currentUser.optionalUserId()));
	}

	/**
	 * Withdraws from the marketplace.
	 *
	 * <p>Not a delete. Shops that installed it keep their own copy of the config, so withdrawing
	 * stops new installs without touching anyone who already has it.
	 */
	@PostMapping("/{templateId}/unpublish")
	@Operation(summary = "Withdraw from the marketplace",
			description = "Installed themes are untouched.")
	public TemplateView unpublish(@PathVariable UUID templateId) {
		return toView(themes.unpublishTemplate(templateId));
	}

	/** Hides a template without withdrawing it, for a seasonal campaign. */
	@PostMapping("/{templateId}/active")
	@Operation(summary = "Show or hide a template without unpublishing it")
	public TemplateView setActive(@PathVariable UUID templateId,
			@Valid @RequestBody ActiveRequest request) {
		return toView(themes.setTemplateActive(templateId, request.active()));
	}

	// ------------------------------------------------------------------ requests

	public record CreateTemplateRequest(
			/** Stable machine name, e.g. "pharmacy-clean". Unique, and used by seed scripts. */
			@NotBlank @Size(max = 60) String code,
			@NotBlank @Size(max = 120) String name,
			@Size(max = 1000) String description,
			/** Null suits every shop type. */
			ShopType shopType,
			/** The theme document, as JSON. Validated on write. */
			@NotBlank String config,
			ThemeTemplate.PriceTier priceTier) {
	}

	public record UpdateTemplateRequest(
			@Size(max = 120) String name,
			@Size(max = 1000) String description,
			ShopType shopType,
			/** Omit to leave the document alone. */
			String config,
			@Min(0) @Max(10_000) Integer sortOrder,
			UUID previewMediaId) {
	}

	public record ActiveRequest(boolean active) {
	}

	// ------------------------------------------------------------------ responses

	public record TemplateView(
			UUID id,
			String code,
			String name,
			String description,
			String category,
			ShopType shopType,
			ThemeTemplate.PriceTier priceTier,
			boolean active,
			boolean published,
			int sortOrder,
			String previewImageUrl,
			java.time.Instant publishedAt) {
	}

	/** A template with its document, which is what the editor loads. */
	public record TemplateDetail(TemplateView template, String config, int sectionCount) {
	}

	private TemplateView toView(ThemeTemplate t) {
		return new TemplateView(
				t.getId(),
				t.getCode(),
				t.getName(),
				t.getDescription(),
				t.getCategory(),
				t.shopTypeOrNull(),
				t.getPriceTier(),
				t.isActive(),
				t.isPublished(),
				t.getSortOrder(),
				themes.templatePreviewUrl(t),
				t.getPublishedAt());
	}
}
