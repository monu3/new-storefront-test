package com.storefront.store.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
	 * A shop that does not exist, or is not publicly visible.
	 *
	 * <p>404 rather than 403 on purpose: a suspended or unverified shop should be
	 * indistinguishable from one that was never created, so a customer cannot probe for shops
	 * that exist but are hidden.
	 */
	@ExceptionHandler(com.storefront.store.shop.ShopService.ShopNotFoundException.class)
	public ProblemDetail handleShopNotFound(
			com.storefront.store.shop.ShopService.ShopNotFoundException ex, HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Shop not found");
		problem.setProperty("code", "SHOP_NOT_FOUND");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A product that does not exist, or belongs to another shop.
	 *
	 * <p>404 for both, deliberately: a 403 would confirm the id exists somewhere, turning the
	 * product endpoint into an oracle for enumerating another shop's catalogue.
	 */
	@ExceptionHandler(com.storefront.store.catalog.ProductService.ProductNotFoundException.class)
	public ProblemDetail handleProductNotFound(
			com.storefront.store.catalog.ProductService.ProductNotFoundException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Product not found");
		problem.setProperty("code", "PRODUCT_NOT_FOUND");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * An image that does not exist, or belongs to another shop.
	 *
	 * <p>404 rather than 403 for the same reason as products: the media endpoint must not
	 * confirm that another shop's asset id is real.
	 */
	@ExceptionHandler(com.storefront.store.media.MediaService.MediaNotFoundException.class)
	public ProblemDetail handleMediaNotFound(
			com.storefront.store.media.MediaService.MediaNotFoundException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Image not found");
		problem.setProperty("code", "MEDIA_NOT_FOUND");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A rejected media upload or claim.
	 *
	 * <p>400 rather than 502: every one of these is the client's own doing — a blank public id,
	 * a duplicate, a claim about an image in a folder we did not sign. A Cloudinary outage is
	 * the only 502 case, and that surfaces through the generic handler.
	 */
	@ExceptionHandler(com.storefront.store.media.CloudinaryService.MediaException.class)
	public ProblemDetail handleMediaFailure(
			com.storefront.store.media.CloudinaryService.MediaException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Image could not be accepted");
		problem.setProperty("code", "MEDIA_REJECTED");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * An order that does not exist, or belongs to another shop.
	 *
	 * <p>404 for both, deliberately. A 403 would confirm the id is real, turning the order
	 * endpoint into an existence oracle for another shop's orders.
	 */
	@ExceptionHandler(com.storefront.store.order.OrderService.OrderNotFoundException.class)
	public ProblemDetail handleOrderNotFound(
			com.storefront.store.order.OrderService.OrderNotFoundException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Order not found");
		problem.setProperty("code", "ORDER_NOT_FOUND");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * Not enough stock.
	 *
	 * <p>409, with the available quantity in the body. The request was well-formed and the basket
	 * was legal when it was filled; the world has changed since, which is a conflict rather than a
	 * bad request. The client needs the real number to tell the customer what is actually left.
	 */
	@ExceptionHandler(com.storefront.store.inventory.Inventory.InsufficientStockException.class)
	public ProblemDetail handleInsufficientStock(
			com.storefront.store.inventory.Inventory.InsufficientStockException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Not enough stock");
		problem.setProperty("code", "INSUFFICIENT_STOCK");
		problem.setProperty("requested", ex.getRequested());
		problem.setProperty("available", ex.getAvailable());
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/** Something in the basket is no longer purchasable. */
	@ExceptionHandler(com.storefront.store.checkout.CheckoutService.OutOfStockException.class)
	public ProblemDetail handleOutOfStock(
			com.storefront.store.checkout.CheckoutService.OutOfStockException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Item no longer available");
		problem.setProperty("code", "OUT_OF_STOCK");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * The shop cannot be ordered from.
	 *
	 * <p>404 for an unverified shop as well as a missing one, so a customer cannot discover that
	 * a hidden shop exists.
	 */
	@ExceptionHandler(com.storefront.store.checkout.CheckoutService.ShopUnavailableException.class)
	public ProblemDetail handleShopUnavailable(
			com.storefront.store.checkout.CheckoutService.ShopUnavailableException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Shop not available");
		problem.setProperty("code", "SHOP_UNAVAILABLE");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * The owner has closed the shop.
	 *
	 * <p>409 rather than 404, and deliberately not {@code SHOP_UNAVAILABLE}: the shop exists, is
	 * browsable and will sell again. The customer needs "closed for now, try later", which is a
	 * different message from "this shop does not exist" — and conflating the two would make a
	 * temporarily closed shop look deleted.
	 */
	@ExceptionHandler(com.storefront.store.checkout.CheckoutService.ShopClosedException.class)
	public ProblemDetail handleShopClosed(
			com.storefront.store.checkout.CheckoutService.ShopClosedException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Shop is closed");
		problem.setProperty("code", "SHOP_CLOSED");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * The shop cannot afford the commission for this order.
	 *
	 * <p>409 with the shortfall, because this is the mechanism behind "shops must credit money
	 * before accepting any order" (D2) and the owner needs the exact number to top up.
	 */
	@ExceptionHandler(com.storefront.store.wallet.WalletService.InsufficientWalletBalanceException.class)
	public ProblemDetail handleInsufficientWallet(
			com.storefront.store.wallet.WalletService.InsufficientWalletBalanceException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Wallet balance too low");
		problem.setProperty("code", "INSUFFICIENT_WALLET_BALANCE");
		problem.setProperty("balanceMinor", ex.getCurrentBalanceMinor());
		problem.setProperty("requiredMinor", ex.getRequiredMinor());
		problem.setProperty("shortfallMinor", ex.getRequiredMinor() - ex.getCurrentBalanceMinor());
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * The shop has not bought the feature it just tried to use.
	 *
	 * <p><b>403, with the upgrade path in the body.</b> A bare 403 is a wall; this carries what
	 * would make it a yes — the feature, the numbers where there are limits, and the plan to buy.
	 * The shop owner is a customer of this platform paying a monthly subscription to somebody else,
	 * and the difference between "no" and "here is what would let you" is the entire feature.
	 *
	 * <p>Distinct from a {@code TenantGuard} 403, which means "this is not your shop" and offers no
	 * purchase that would help. Different problem, different code, different message.
	 */
	@ExceptionHandler(com.storefront.store.subscription.EntitlementService
			.EntitlementDeniedException.class)
	public ProblemDetail handleEntitlementDenied(
			com.storefront.store.subscription.EntitlementService.EntitlementDeniedException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		problem.setTitle("This is not in your plan");
		problem.setProperty("code", "ENTITLEMENT_DENIED");
		problem.setProperty("feature", ex.feature().key());

		// Null when no active plan grants it. Present-and-null is meaningful: the UI must not offer
		// an upgrade button that leads to a pricing page where nothing can be bought.
		problem.setProperty("upgradeTo", ex.upgradeToCode());

		if (ex.limit() != null) {
			problem.setProperty("currentCount", ex.limit().currentCount());
			problem.setProperty("adding", ex.limit().adding());
			problem.setProperty("limit", ex.limit().limit());
		}

		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/** A subscription purchase an admin rejected because the transfer did not cover the plan. */
	@ExceptionHandler(com.storefront.store.subscription.SubscriptionPaymentService
			.InsufficientFundsException.class)
	public ProblemDetail handleSubscriptionShortfall(
			com.storefront.store.subscription.SubscriptionPaymentService.InsufficientFundsException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Transfer does not cover the plan");
		problem.setProperty("code", "SUBSCRIPTION_SHORTFALL");
		problem.setProperty("availableMinor", ex.availableMinor());
		problem.setProperty("requiredMinor", ex.requiredMinor());
		problem.setProperty("shortfallMinor", ex.shortfallMinor());
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A plan that does not exist.
	 *
	 * <p>404. The exception's own Javadoc promised this from the day it was written; without the
	 * handler Spring turns it into a 500, and an admin who mistypes an id gets "internal server
	 * error" where the honest answer is "no such plan".
	 */
	@ExceptionHandler(com.storefront.store.subscription.SubscriptionService
			.NoSuchPlanException.class)
	public ProblemDetail handleNoSuchPlan(
			com.storefront.store.subscription.SubscriptionService.NoSuchPlanException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("No such plan");
		problem.setProperty("code", "NO_SUCH_PLAN");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A subscription that does not exist, or is not the caller's.
	 *
	 * <p>404 for both, deliberately: a 403 would confirm the id exists, and confirming that is the
	 * only thing an enumeration attempt needs. The two cases are indistinguishable to the caller on
	 * purpose.
	 */
	@ExceptionHandler(com.storefront.store.subscription.SubscriptionService
			.NoSuchSubscriptionException.class)
	public ProblemDetail handleNoSuchSubscription(
			com.storefront.store.subscription.SubscriptionService.NoSuchSubscriptionException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("No such subscription");
		problem.setProperty("code", "NO_SUCH_SUBSCRIPTION");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/** An admin approving an ordinary wallet top-up through the subscription screen. */
	@ExceptionHandler(com.storefront.store.subscription.SubscriptionPaymentService
			.TopUpNotForSubscriptionException.class)
	public ProblemDetail handleTopUpNotForSubscription(
			com.storefront.store.subscription.SubscriptionPaymentService
					.TopUpNotForSubscriptionException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Not a subscription purchase");
		problem.setProperty("code", "NOT_A_SUBSCRIPTION_TOPUP");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A shop owner trying to buy or read a subscription without owning a shop yet.
	 *
	 * <p>403 with the reason, because "complete shop signup first" is actionable and a bare 403 is
	 * not.
	 */
	@ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
	public ProblemDetail handleAccessDenied(
			org.springframework.security.access.AccessDeniedException ex,
			HttpServletRequest request) {

		// Only a TenantGuard denial carries a message worth showing. A bare authorisation failure
		// has nothing useful in it, so the status is kept and the title is generic.
		boolean explained = ex.getMessage() != null && !ex.getMessage().isBlank();

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
				explained ? ex.getMessage() : "You do not have access to that.");
		problem.setTitle(explained ? "You cannot do that yet" : "Forbidden");
		problem.setProperty("code", explained ? "SHOP_NOT_READY" : "FORBIDDEN");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/** A plan with live subscribers, whose terms cannot be changed. */
	@ExceptionHandler(com.storefront.store.subscription.AdminPlanController.PlanInUseException.class)
	public ProblemDetail handlePlanInUse(
			com.storefront.store.subscription.AdminPlanController.PlanInUseException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("That plan is in use");
		problem.setProperty("code", "PLAN_IN_USE");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/** A feature key that is not one this build knows. */
	@ExceptionHandler(com.storefront.store.subscription.AdminPlanController.UnknownFeatureException.class)
	public ProblemDetail handleUnknownFeature(
			com.storefront.store.subscription.AdminPlanController.UnknownFeatureException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Unknown feature");
		problem.setProperty("code", "UNKNOWN_FEATURE");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A payment method the shop enabled without configuring it.
	 *
	 * <p>400 rather than 500: the customer chose an option the shop offered, and the honest
	 * answer is "that method is not available", which is also what the message says.
	 */
	@ExceptionHandler(com.storefront.store.payment.BankTransferGateway.PaymentConfigurationException.class)
	public ProblemDetail handlePaymentConfiguration(
			com.storefront.store.payment.BankTransferGateway.PaymentConfigurationException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Payment method unavailable");
		problem.setProperty("code", "PAYMENT_METHOD_UNAVAILABLE");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A gateway that is not implemented yet, eSewa in Phase 1.
	 *
	 * <p>501, not 500: the request was understood and the platform deliberately does not do this
	 * yet. That distinction is what stops a client retrying something that will keep failing, and
	 * it is the honest status for a stubbed gateway.
	 */
	@ExceptionHandler(UnsupportedOperationException.class)
	public ProblemDetail handleUnsupported(UnsupportedOperationException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
		problem.setTitle("Not available yet");
		problem.setProperty("code", "NOT_IMPLEMENTED");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/** A shop that cannot be followed because it is hidden or missing. */
	@ExceptionHandler(com.storefront.store.social.FollowerController.ShopNotFoundForFollowerException.class)
	public ProblemDetail handleFollowTargetNotFound(
			com.storefront.store.social.FollowerController.ShopNotFoundForFollowerException ex,
			HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Shop not found");
		problem.setProperty("code", "SHOP_NOT_FOUND");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A theme document that fails validation.
	 *
	 * <p>400 with <b>every</b> problem in {@code issues}, not just the first. A shop owner fixing a
	 * theme through a form needs to see all of it at once; a validator that stops at the first
	 * failure turns five corrections into five round trips.
	 */
	@ExceptionHandler(com.storefront.store.theme.ThemeValidationException.class)
	public ProblemDetail handleThemeValidation(
			com.storefront.store.theme.ThemeValidationException ex, HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("The theme is not valid");
		problem.setProperty("code", "INVALID_THEME");
		problem.setProperty("issues", ex.issues());
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * A template, theme or asset that does not exist — or belongs to another shop.
	 *
	 * <p>404 for all three cases deliberately. A 403 on someone else's theme would confirm the
	 * id is real, turning these endpoints into an existence oracle across tenants.
	 */
	@ExceptionHandler({
			com.storefront.store.theme.ThemeService.TemplateNotFoundException.class,
			com.storefront.store.theme.ThemeService.ThemeNotFoundException.class,
			com.storefront.store.theme.ThemeService.AssetNotFoundException.class })
	public ProblemDetail handleThemeNotFound(RuntimeException ex, HttpServletRequest request) {

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Not found");
		problem.setProperty("code", "THEME_NOT_FOUND");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/** A state change that the current status does not allow. */
	@ExceptionHandler(IllegalStateException.class)
	public ProblemDetail handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Not allowed in this state");
		problem.setProperty("code", "ILLEGAL_STATE_TRANSITION");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/** An invalid argument that reached the service layer. */
	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid request");
		problem.setProperty("code", "INVALID_REQUEST");
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		return problem;
	}

	/**
	 * Bean Validation failures, with the field errors the client needs to highlight inputs.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));

		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"One or more fields are invalid");
		problem.setTitle("Validation failed");
		problem.setProperty("code", "VALIDATION_FAILED");
		problem.setProperty("fieldErrors", fields);
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
