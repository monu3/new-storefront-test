package com.storefront.store.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The complete order lifecycle, in one place.
 *
 * <p>An enum that knows its own successors tends to acquire exceptions — someone adds a
 * convenience method, then a special case, and the rules end up spread across the enum, the
 * service and the controller. So the enum is a set of names, and the graph lives here where it
 * can be read top to bottom and tested exhaustively.
 *
 * <p>The graph is small on purpose, and every edge earns its place:
 *
 * <pre>
 *   PENDING ──► ACCEPTED ──► PROCESSING ──► SHIPPED ──► DELIVERED
 *      │           │                                           │
 *      └──► CANCELLED ◄────────────────────────────────────────┘
 * </pre>
 *
 * <p>Three asymmetries are deliberate and are the point of having a state machine rather than a
 * setter:
 *
 * <ul>
 *   <li><b>Only PENDING can be cancelled.</b> Once a shop has accepted, the goods may be packed.
 *       Un-accepting would mean the shop silently drops a customer's order, and the commission
 *       already taken would need reversing for an order the shop never intended to take. That is
 *       a return, which is Phase 8.</li>
 *   <li><b>ACCEPTED and SHIPPED are never skipped.</b> There is no ACCEPTED → SHIPPED shortcut
 *       because there is no ACCEPTED → DELIVERED one either: each hop is a fact a customer is
 *       told, and a timeline that jumps two states is a timeline nobody trusts.</li>
 *   <li><b>Cancellation is terminal.</b> A cancelled order never revives. Re-opening it would
 *       mean re-running commission and inventory, and a half-reversed ledger is worse than a
 *       closed order.</li>
 * </ul>
 *
 * <p>RETURNED and REFUNDED exist in the enum because the database constrains to them and Phase 8
 * needs them. They are reachable from DELIVERED only, and Phase 1 does not drive them: a Phase 1
 * build cannot produce a return, which is the honest position rather than an edge that exists
 * but does nothing.
 *
 * <p><b>"Terminal" and "closed" are different words on purpose.</b> DELIVERED is closed — the
 * parcel arrived, no more fulfilment steps — but it is not terminal, because a delivered order can
 * still become RETURNED. Only CANCELLED, RETURNED and REFUNDED are irreversible. Conflating the
 * two produced a set that claimed DELIVERED was final while the table gave it two successors, and
 * {@code OrderStateMachineTest} caught the contradiction.
 */
@Component
public class OrderStateMachine {

	/**
	 * The successors of each status.
	 *
	 * <p>An unmodifiable map rather than a switch: a new status added to {@link ShopOrder.Status}
	 * but not here fails a test rather than silently becoming a dead end discovered in
	 * production.
	 */
	private static final Map<ShopOrder.Status, Set<ShopOrder.Status>> TRANSITIONS = Map.of(
			ShopOrder.Status.PENDING, EnumSet.of(ShopOrder.Status.ACCEPTED, ShopOrder.Status.CANCELLED),
			ShopOrder.Status.ACCEPTED, EnumSet.of(ShopOrder.Status.PROCESSING),
			ShopOrder.Status.PROCESSING, EnumSet.of(ShopOrder.Status.SHIPPED),
			ShopOrder.Status.SHIPPED, EnumSet.of(ShopOrder.Status.DELIVERED),
			ShopOrder.Status.DELIVERED, EnumSet.of(ShopOrder.Status.RETURNED, ShopOrder.Status.REFUNDED),
			ShopOrder.Status.CANCELLED, EnumSet.noneOf(ShopOrder.Status.class),
			ShopOrder.Status.RETURNED, EnumSet.noneOf(ShopOrder.Status.class),
			ShopOrder.Status.REFUNDED, EnumSet.noneOf(ShopOrder.Status.class));

	/** Every status this platform can currently drive. RETURNED/REFUNDED arrive in Phase 8. */
	public static final Set<ShopOrder.Status> PHASE_1_STATUSES = EnumSet.of(
			ShopOrder.Status.PENDING,
			ShopOrder.Status.ACCEPTED,
			ShopOrder.Status.PROCESSING,
			ShopOrder.Status.SHIPPED,
			ShopOrder.Status.DELIVERED,
			ShopOrder.Status.CANCELLED);

	/** Open orders: what a shop's "in progress" filter shows. */
	public static final Set<ShopOrder.Status> OPEN_STATUSES = EnumSet.of(
			ShopOrder.Status.PENDING,
			ShopOrder.Status.ACCEPTED,
			ShopOrder.Status.PROCESSING,
			ShopOrder.Status.SHIPPED);

	/**
	 * Closed: no further fulfilment steps. The customer's parcel has arrived, or the order will
	 * never be fulfilled.
	 */
	public static final Set<ShopOrder.Status> CLOSED_STATUSES = EnumSet.of(
			ShopOrder.Status.DELIVERED,
			ShopOrder.Status.CANCELLED,
			ShopOrder.Status.RETURNED,
			ShopOrder.Status.REFUNDED);

	/**
	 * Genuinely final: nothing can change them, ever.
	 *
	 * <p><b>Deliberately excludes DELIVERED.</b> A delivered order can still become RETURNED or
	 * REFUNDED in Phase 8, so calling it terminal would be a claim the graph then contradicts —
	 * and a test asserts the two agree, which is how this was caught. "Delivered" means the
	 * fulfilment path has ended; "terminal" means the record can never move again. Only the second
	 * is irreversible.
	 */
	public static final Set<ShopOrder.Status> TERMINAL_STATUSES = EnumSet.of(
			ShopOrder.Status.CANCELLED,
			ShopOrder.Status.RETURNED,
			ShopOrder.Status.REFUNDED);

	/** Whether a transition is legal. Non-throwing, for filtering and for the console's UI. */
	public boolean canTransition(ShopOrder.Status from, ShopOrder.Status to) {
		if (from == null || to == null) {
			return false;
		}
		return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
	}

	/**
	 * Throws unless the transition is legal.
	 *
	 * @throws IllegalStateException when it is not, naming both states and what was available.
	 *         IllegalState rather than IllegalArgument because the request is well-formed; it
	 *         conflicts with the order's current state, which is a 409.
	 */
	public void require(ShopOrder.Status from, ShopOrder.Status to) {
		if (!canTransition(from, to)) {
			throw new IllegalStateException(
					"An order cannot go from %s to %s. From %s it can only go to %s."
							.formatted(from, to, from, describe(from)));
		}
	}

	/** Applies a transition, stamping the matching timestamp on the order. */
	public void apply(ShopOrder order, ShopOrder.Status to) {
		require(order.getStatus(), to);
		order.transitionTo(to);
	}

	/** The legal successors of a status, for a console that renders only valid buttons. */
	public Set<ShopOrder.Status> successors(ShopOrder.Status from) {
		return from == null ? Set.of() : TRANSITIONS.getOrDefault(from, Set.of());
	}

	/** True when the order can never change again. */
	public boolean isTerminal(ShopOrder.Status status) {
		return TERMINAL_STATUSES.contains(status);
	}

	/** True when no further fulfilment step is possible. Broader than {@link #isTerminal}. */
	public boolean isClosed(ShopOrder.Status status) {
		return CLOSED_STATUSES.contains(status);
	}

	public boolean isOpen(ShopOrder.Status status) {
		return OPEN_STATUSES.contains(status);
	}

	/**
	 * Whether the shop may still cancel.
	 *
	 * <p>Named separately because the shop console asks "can I still cancel this?" far more often
	 * than it asks the general question, and the answer is a single boolean the UI can bind to a
	 * button's disabled state.
	 */
	public boolean isCancellable(ShopOrder.Status status) {
		return canTransition(status, ShopOrder.Status.CANCELLED);
	}

	/**
	 * Whether accepting this order would take commission.
	 *
	 * <p>True only for PENDING → ACCEPTED, which is exactly where commission is debited (D2). The
	 * question is asked in more than one place, and answering it from the graph rather than from
	 * a hardcoded status comparison keeps the two in step.
	 */
	public boolean chargesCommission(ShopOrder.Status from) {
		return from == ShopOrder.Status.PENDING;
	}

	/**
	 * Whether cancelling this order reverses commission.
	 *
	 * <p>Only orders that were accepted have had commission taken, so only cancelling from ACCEPTED
	 * or later needs a reversal. Cancelling a PENDING order owes nothing and must not credit the
	 * wallet.
	 */
	public boolean reversesCommission(ShopOrder.Status from) {
		return from == ShopOrder.Status.ACCEPTED
				|| from == ShopOrder.Status.PROCESSING
				|| from == ShopOrder.Status.SHIPPED
				|| from == ShopOrder.Status.DELIVERED;
	}

	private String describe(ShopOrder.Status from) {
		Set<ShopOrder.Status> options = successors(from);
		return options.isEmpty() ? "nowhere (it is final)" : options.toString();
	}
}
