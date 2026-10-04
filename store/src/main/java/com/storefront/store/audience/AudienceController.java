package com.storefront.store.audience;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storefront.store.common.Money;
import com.storefront.store.security.TenantGuard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * The owner's audience: who buys from this shop.
 *
 * <p>Class-level {@code @PreAuthorize} so authorisation cannot be forgotten on one method, and no
 * shop id in any path — the shop is the caller's own (D35).
 *
 * <p><b>The private notes live here and only here.</b> They are the owner's own annotations about
 * real customers, returned to the owner who wrote them and to no one else. No public or customer
 * endpoint on this controller exists, which is what keeps that structural rather than a matter of
 * remembering to filter a field.
 */
@RestController
@RequestMapping("/api/v1/shop/audience")
@Tag(name = "Audience", description = "Customers of this shop, with spend stats and private notes")
@PreAuthorize("hasRole('SHOP')")
public class AudienceController {

	private final AudienceService service;

	public AudienceController(AudienceService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "This shop's customers, paged",
			description = "Sorted by lifetime spend, most recent order, or first seen.")
	public PageResponse<CustomerView> list(
			@RequestParam(defaultValue = "SPENT") AudienceService.Sort sort,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {

		Page<Customer> result = service.list(sort, page, size);
		return PageResponse.of(result.map(this::toView));
	}

	@GetMapping("/summary")
	@Operation(summary = "Lifetime audience figures",
			description = "Totals are summed in the database over every customer, not the visible page.")
	public AudienceService.Summary summary() {
		return service.summary();
	}

	@GetMapping("/{customerId}")
	@Operation(summary = "One customer")
	public CustomerView get(@PathVariable UUID customerId) {
		return toView(service.get(customerId));
	}

	@PatchMapping("/{customerId}/notes")
	@Operation(summary = "Save the private note",
			description = "Shop-private. Stamped with the author and time.")
	public CustomerView annotate(
			@PathVariable UUID customerId,
			@Valid @RequestBody NoteRequest body) {
		return toView(service.annotate(customerId, body.notes()));
	}

	/**
	 * Exports the audience as CSV.
	 *
	 * <p>No notes column. The export is the one artefact likely to leave the console by being
	 * emailed or pasted into a spreadsheet, and a private note about a real person has no business
	 * travelling that way. An owner who wants their notes backed up has the database.
	 */
	@GetMapping(value = "/export.csv", produces = "text/csv; charset=UTF-8")
	@Operation(summary = "Export customers as CSV, without the private notes")
	public ResponseEntity<byte[]> exportCsv(
			@RequestParam(defaultValue = "SPENT") AudienceService.Sort sort) {

		// Bounded rather than streaming the whole audience: a shop with 200k customers should not
		// be able to make the backend build a 40MB string in memory because one owner clicked.
		// The page size is a deliberate cap and the response says so.
		int cap = 10_000;
		Page<Customer> page = service.list(sort, 0, cap);

		StringBuilder csv = new StringBuilder();
		csv.append("email,name,phone,orders,total_spent_npr,average_order_npr,first_seen,last_order_at\n");
		for (Customer c : page.getContent()) {
			csv.append(csvCell(c.getEmail())).append(',')
					.append(csvCell(c.getFullName())).append(',')
					.append(csvCell(c.getPhone())).append(',')
					.append(c.getTotalOrders()).append(',')
					.append(Money.ofMinor(c.getTotalSpentMinor()).toDisplay()).append(',')
					.append(Money.ofMinor(c.averageOrderMinor()).toDisplay()).append(',')
					.append(c.getCreatedAt() == null ? "" : c.getCreatedAt().toString()).append(',')
					.append(c.getLastOrderAt() == null ? "" : c.getLastOrderAt().toString())
					.append('\n');
		}

		String body = csv.toString();
		boolean truncated = page.getTotalElements() > cap;

		return ResponseEntity.ok()
				.header("Content-Disposition", "attachment; filename=\"audience.csv\"")
				// The flag is a header on a truncated export, because a silently shortened customer
				// list is worse than no export: the owner would reconcile against the wrong number.
				.header("X-Export-Truncated", Boolean.toString(truncated))
				.header("X-Export-Total", Long.toString(page.getTotalElements()))
				.body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	// ------------------------------------------------------------------ mapping

	/**
	 * One customer row.
	 *
	 * <p>Money is sent as both a minor-unit integer and a display string. The integer is what the
	 * table sorts on and what any future calculation must use; the string is what the owner reads.
	 * A frontend that formatted the integer itself would eventually round it differently from the
	 * server, which is the same bug the ledger exists to prevent.
	 */
	private CustomerView toView(Customer c) {
		return new CustomerView(
				c.getId(),
				c.getEmail(),
				c.displayName(),
				c.getFullName(),
				c.getPhone(),
				c.getTotalOrders(),
				c.getTotalSpentMinor(),
				Money.ofMinor(c.getTotalSpentMinor()).toDisplay(),
				c.averageOrderMinor(),
				c.getLastOrderAt(),
				c.getCreatedAt(),
				c.getNotes(),
				c.getPrivateNoteUpdatedAt(),
				c.hasOrdered());
	}

	// ------------------------------------------------------------------ csv

	/**
	 * Quotes a CSV cell.
	 *
	 * <p>A customer is a real person and a real person's name may contain a comma, a quote or a
	 * newline — {@code "Sharma, Priya"} would otherwise become two columns and shift every value
	 * after it onto the wrong customer.
	 */
	private static String csvCell(String value) {
		if (value == null) {
			return "";
		}
		boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
				|| value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
		if (!needsQuotes) {
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	// ------------------------------------------------------------------ types

	public record NoteRequest(@Size(max = 2000) String notes) {
	}

	public record CustomerView(
			UUID id,
			String email,
			String displayName,
			String fullName,
			String phone,
			int orders,
			long totalSpentMinor,
			String totalSpentDisplay,
			long averageOrderMinor,
			Instant lastOrderAt,
			Instant firstSeenAt,
			String notes,
			Instant notesUpdatedAt,
			boolean hasOrdered) {
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