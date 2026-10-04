package com.storefront.store.invoice;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Invoice PDFs for the shop's own orders.
 *
 * <p>Served as {@code application/pdf} with a filename of the order's number, so the browser
 * downloads {@code ORD-0042.pdf} rather than a UUID — a customer told "we emailed your invoice"
 * should be able to find the file again.
 *
 * <p>Class-level {@code @PreAuthorize} and no shop id in the path: the order is resolved through
 * the tenant guard, so another shop's invoice is a 404 rather than a cross-tenant read.
 */
@RestController
@RequestMapping("/api/v1/shop/invoices")
@Tag(name = "Invoices", description = "PDF invoices for the shop's own orders")
@PreAuthorize("hasRole('SHOP')")
public class InvoiceController {

	private final InvoiceService invoices;

	public InvoiceController(InvoiceService invoices) {
		this.invoices = invoices;
	}

	@GetMapping(value = "/{orderId}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	@Operation(summary = "The invoice PDF for one order")
	public ResponseEntity<byte[]> download(@PathVariable UUID orderId) {
		byte[] pdf = invoices.render(orderId);

		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				// Attachment rather than inline: a PDF served inline can execute script in some
				// viewers, and this document is generated from owner-supplied text.
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename("invoice-" + orderId + ".pdf")
						.build().toString())
				.contentLength(pdf.length)
				.body(pdf);
	}
}