package com.storefront.store.checkout;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.security.TenantGuard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The owner's discount management.
 *
 * <p>No shop id in any path: the shop comes from the token (D35), so there is no cross-tenant
 * question on this controller at all. A request for another shop's discount id resolves to the
 * same 404 as a request for one that does not exist.
 *
 * <p><b>The response carries a live {@code usableNow} and {@code state}, not just the stored
 * columns.</b> A discount list that only shows its fields leaves the owner to mentally evaluate
 * the start/end dates and usage counts against today. Those two fields are the whole reason a
 * campaign is not working, so the server computes them.
 */
@RestController
@RequestMapping("/api/v1/shop/discounts")
@Tag(name = "Discounts", description = "Codes, automatic discounts, limits and windows")
@PreAuthorize("hasRole('SHOP')")
public class DiscountController {

	private final DiscountService service;

	public DiscountController(DiscountService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "This shop's discounts, paged")
	public PageResponse<DiscountView> list(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {

		Page<Discount> result = service.list(PageRequest.of(page, size, Sort.by("id")));
		return PageResponse.of(result.map(this::toView));
	}

	@GetMapping("/{discountId}")
	@Operation(summary = "One discount")
	public DiscountView get(@PathVariable UUID discountId) {
		return toView(service.get(discountId));
	}

	@PostMapping
	@Operation(summary = "Create a discount")
	public ResponseEntity<DiscountView> create(@Valid @RequestBody DraftRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(toView(service.create(body.toDraft())));
	}

	@PutMapping("/{discountId}")
	@Operation(summary = "Amend a discount",
			description = "The code cannot be changed; pause this one and create another instead.")
	public DiscountView update(@PathVariable UUID discountId, @Valid @RequestBody DraftRequest body) {
		return toView(service.update(discountId, body.toDraft()));
	}

	/**
	 * Pauses or resumes.
	 *
	 * <p>{@code DELETE} in the path but a pause in the body: deleting a discount would reset its
	 * usage counters, so a redeployed campaign would let every customer who already used it use it
	 * again. The owner is told as much on the response's {@code state}.
	 */
	@DeleteMapping("/{discountId}")
	@Operation(summary = "Pause a discount, preserving its usage counts")
	public DiscountView pause(@PathVariable UUID discountId) {
		return toView(service.setActive(discountId, false));
	}

	@PatchMapping("/{discountId}/resume")
	@Operation(summary = "Resume a paused discount")
	public DiscountView resume(@PathVariable UUID discountId) {
		return toView(service.setActive(discountId, true));
	}

	// ------------------------------------------------------------------ mapping

	/**
	 * A discount as the console needs it.
	 *
	 * <p>{@code usableNow} and {@code notUsableReason} come from the entity's own
	 * {@link Discount#isUsableNow()} and {@link Discount#unusableReason(long)} rather than being
	 * re-derived here. That is the point of the pair: this explanation is produced by the same code
	 * that will refuse the discount at checkout, so the console cannot tell an owner their code
	 * works when it does not.
	 *
	 * <p>The subtotal passed to {@code unusableReason} is 0 deliberately. That asks only the
	 * window-and-usage questions, which is what the console can know without a basket; the
	 * minimum-order question belongs to the cart and is answered by the quote.
	 */
	private DiscountView toView(Discount d) {
		boolean usable = d.isUsableNow();
		String reason = usable ? null : d.unusableReason(0L);

		return new DiscountView(
				d.getId(),
				d.getCode(),
				d.getName(),
				d.getType(),
				d.getValue(),
				d.getMinOrderMinor(),
				d.getMaxDiscountMinor(),
				d.getUsageLimit(),
				d.getUsageUsed(),
				d.getPerCustomerLimit(),
				d.getStartsAt(),
				d.getEndsAt(),
				d.isActive(),
				usable,
				reason,
				/** Remaining uses, for the progress bar. Null when unlimited. */
				d.getUsageLimit() == null ? null : Math.max(0, d.getUsageLimit() - d.getUsageUsed()));
	}

	// ------------------------------------------------------------------ request

	/**
	 * What the console sends.
	 *
	 * <p>{@code value} is basis points for a percentage and minor units for a fixed amount, matching
	 * {@link Discount.Type}'s storage. Taking the percentage as a raw {@code 10} and multiplying it
	 * somewhere downstream is how a "10% off" discount quietly becomes 1000% or 0.1%.
	 */
	public record DraftRequest(
			@Size(max = 40) String code,
			@NotBlank @Size(max = 120) String name,
			@NotNull Discount.Type type,
			@Min(0) long value,
			@Min(0) Long minOrderMinor,
			@Min(0) Long maxDiscountMinor,
			@Min(1) Integer usageLimit,
			@Min(1) Integer perCustomerLimit,
			Instant startsAt,
			Instant endsAt,
			boolean active) {

		DiscountService.Draft toDraft() {
			return new DiscountService.Draft(code, name, type, value, minOrderMinor, maxDiscountMinor,
					usageLimit, perCustomerLimit, startsAt, endsAt, active);
		}
	}

	// ------------------------------------------------------------------ response

	public record DiscountView(
			UUID id,
			String code,
			String name,
			Discount.Type type,
			long value,
			Long minOrderMinor,
			Long maxDiscountMinor,
			Integer usageLimit,
			int usageUsed,
			Integer perCustomerLimit,
			Instant startsAt,
			Instant endsAt,
			boolean active,
			boolean usableNow,
			String notUsableReason,
			Integer remainingUses) {
	}

	/** A page, in the same shape the rest of the API pages in. */
	public record PageResponse<T>(
			List<T> content,
			int page,
			int size,
			long totalElements,
			int totalPages) {

		static <T> PageResponse<T> of(Page<T> page) {
			return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
					page.getTotalElements(), page.getTotalPages());
		}
	}
}