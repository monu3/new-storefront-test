package com.storefront.store.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.storefront.store.catalog.CategoryValidator;
import com.storefront.store.platform.MetaController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns internal exceptions into RFC 7807 problem responses.
 *
 * <p>A bare Spring error page or a stack trace in a JSON body makes a client unable to
 * distinguish "you sent bad data" from "the server is broken". Every response here carries a
 * stable machine-readable {@code code} so the frontend can branch without string matching.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(CategoryValidator.InvalidCategoryException.class)
	public ProblemDetail handleInvalidCategory(
			CategoryValidator.InvalidCategoryException ex, HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid category");
		problem.setProperty("code", "INVALID_CATEGORY");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	@ExceptionHandler(MetaController.UnknownShopTypeException.class)
	public ProblemDetail handleUnknownShopType(
			MetaController.UnknownShopTypeException ex, HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Unknown shop type");
		problem.setProperty("code", "UNKNOWN_SHOP_TYPE");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A shop type change that would strand existing products.
	 *
	 * <p>409 rather than 400: the request is well-formed but conflicts with the shop's current
	 * data. The response names the blocking categories so the owner gets a to-do list instead of
	 * a bare rejection.
	 */
	@ExceptionHandler(CategoryValidator.ShopTypeChangeConflictException.class)
	public ProblemDetail handleShopTypeConflict(
			CategoryValidator.ShopTypeChangeConflictException ex, HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Cannot change shop type yet");
		problem.setProperty("code", "SHOP_TYPE_CHANGE_CONFLICT");
		problem.setProperty("targetType", ex.targetType().name());
		problem.setProperty("blockingCategories",
				ex.blockingCategories().stream().map(Enum::name).sorted().toList());
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}
}
