package com.storefront.store.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The order lifecycle graph.
 *
 * <p>A pure unit test with no Spring context: the graph is the highest-risk logic in the platform
 * and it should be readable end to end on one screen. Exhaustiveness matters more than usual,
 * because a status added to the enum without a decision about its successors becomes a dead end a
 * customer discovers.
 */
class OrderStateMachineTest {

	private final OrderStateMachine machine = new OrderStateMachine();

	@Nested
	@DisplayName("the happy path")
	class HappyPath {

		@Test
		@DisplayName("a placed order can be accepted, packed, shipped and delivered")
		void fullFulfilment() {
			// The phase-1 exit criterion: PENDING -> ACCEPTED -> PROCESSING -> SHIPPED -> DELIVERED
			assertThat(machine.canTransition(ShopOrder.Status.PENDING, ShopOrder.Status.ACCEPTED)).isTrue();
			assertThat(machine.canTransition(ShopOrder.Status.ACCEPTED, ShopOrder.Status.PROCESSING)).isTrue();
			assertThat(machine.canTransition(ShopOrder.Status.PROCESSING, ShopOrder.Status.SHIPPED)).isTrue();
			assertThat(machine.canTransition(ShopOrder.Status.SHIPPED, ShopOrder.Status.DELIVERED)).isTrue();
		}

		@Test
		@DisplayName("states are never skipped")
		void noSkippingAhead() {
			// Each hop is a fact the customer is told. Skipping ACCEPTED would skip the point at
			// which commission is charged, which is the one edge that must never be jumped.
			assertThat(machine.canTransition(ShopOrder.Status.PENDING, ShopOrder.Status.SHIPPED)).isFalse();
			assertThat(machine.canTransition(ShopOrder.Status.PENDING, ShopOrder.Status.DELIVERED)).isFalse();
			assertThat(machine.canTransition(ShopOrder.Status.ACCEPTED, ShopOrder.Status.DELIVERED)).isFalse();
			assertThat(machine.canTransition(ShopOrder.Status.PROCESSING, ShopOrder.Status.DELIVERED)).isFalse();
		}

		@Test
		@DisplayName("states are never revisited")
		void noGoingBackwards() {
			assertThat(machine.canTransition(ShopOrder.Status.SHIPPED, ShopOrder.Status.PROCESSING)).isFalse();
			assertThat(machine.canTransition(ShopOrder.Status.ACCEPTED, ShopOrder.Status.PENDING)).isFalse();
			assertThat(machine.canTransition(ShopOrder.Status.DELIVERED, ShopOrder.Status.SHIPPED)).isFalse();
		}
	}

	@Nested
	@DisplayName("cancellation")
	class Cancellation {

		@Test
		@DisplayName("only a PENDING order can be cancelled")
		void onlyPendingIsCancellable() {
			// This single rule removes the need to reason about cancelling after the goods have
			// shipped, which is a return and belongs to Phase 8.
			assertThat(machine.isCancellable(ShopOrder.Status.PENDING)).isTrue();
			assertThat(machine.isCancellable(ShopOrder.Status.ACCEPTED)).isFalse();
			assertThat(machine.isCancellable(ShopOrder.Status.PROCESSING)).isFalse();
			assertThat(machine.isCancellable(ShopOrder.Status.SHIPPED)).isFalse();
			assertThat(machine.isCancellable(ShopOrder.Status.DELIVERED)).isFalse();
		}

		@Test
		@DisplayName("an accepted order cannot be cancelled")
		void acceptedCannotBeCancelled() {
			// The shop may already have packed it and commission has been taken. Silently dropping
			// a customer's order is worse than telling them it needs a return.
			assertThatThrownBy(() -> machine.require(ShopOrder.Status.ACCEPTED, ShopOrder.Status.CANCELLED))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("cannot go from ACCEPTED to CANCELLED");
		}

		@Test
		@DisplayName("the error says the order is final, not merely refused")
		void errorIsActionable() {
			assertThatThrownBy(() -> machine.require(ShopOrder.Status.CANCELLED, ShopOrder.Status.ACCEPTED))
					.as("a shop owner seeing this needs to know the order is finished")
					.hasMessageContaining("final");
		}
	}

	@Nested
	@DisplayName("terminal states")
	class TerminalStates {

		@ParameterizedTest
		@EnumSource(value = ShopOrder.Status.class, names = {"CANCELLED", "RETURNED", "REFUNDED"})
		@DisplayName("terminal statuses cannot move anywhere")
		void terminalIsTerminal(ShopOrder.Status status) {
			assertThat(machine.isTerminal(status)).isTrue();
			assertThat(machine.successors(status)).isEmpty();
		}

		@Test
		@DisplayName("DELIVERED is closed but not terminal")
		void deliveredIsClosedNotTerminal() {
			// The distinction that matters: a delivered order has no more fulfilment steps, but a
			// customer can still return it in Phase 8. Calling it terminal would be a promise the
			// transition graph then breaks.
			assertThat(machine.isClosed(ShopOrder.Status.DELIVERED)).isTrue();
			assertThat(machine.isTerminal(ShopOrder.Status.DELIVERED)).isFalse();
		}

		@Test
		@DisplayName("a terminal status has no successors, and vice versa")
		void terminalAndDeadEndAgree() {
			// The contradiction that started this: DELIVERED was in TERMINAL_STATUSES and had two
			// successors at the same time.
			for (ShopOrder.Status status : ShopOrder.Status.values()) {
				boolean deadEnd = machine.successors(status).isEmpty();
				assertThat(machine.isTerminal(status))
						.as(status + " disagrees with its own successors")
						.isEqualTo(deadEnd);
			}
		}

		@Test
		@DisplayName("a delivered order may be returned or refunded, which Phase 8 will use")
		void deliveredCanBeReturned() {
			// Declared now so the graph is complete, but Phase 1 cannot drive it — see the class
			// note on OrderStateMachine.
			assertThat(machine.canTransition(ShopOrder.Status.DELIVERED, ShopOrder.Status.RETURNED))
					.isTrue();
			assertThat(machine.canTransition(ShopOrder.Status.DELIVERED, ShopOrder.Status.REFUNDED))
					.isTrue();
		}
	}

	@Nested
	@DisplayName("commission follows the graph")
	class Money {

		@Test
		@DisplayName("commission is charged only on PENDING -> ACCEPTED")
		void commissionOnlyOnAccept() {
			// The graph is the authority, so "where does commission happen" cannot drift from the
			// transition table.
			assertThat(machine.chargesCommission(ShopOrder.Status.PENDING)).isTrue();
			assertThat(machine.chargesCommission(ShopOrder.Status.ACCEPTED)).isFalse();
			assertThat(machine.chargesCommission(ShopOrder.Status.PROCESSING)).isFalse();
		}

		@Test
		@DisplayName("cancelling before acceptance reverses nothing")
		void noReversalBeforeAccept() {
			// Crediting a wallet for an order that was never accepted would inflate every balance
			// on the platform.
			assertThat(machine.reversesCommission(ShopOrder.Status.PENDING)).isFalse();
		}

		@Test
		@DisplayName("cancelling after acceptance reverses the commission")
		void reversalAfterAccept() {
			assertThat(machine.reversesCommission(ShopOrder.Status.ACCEPTED)).isTrue();
			assertThat(machine.reversesCommission(ShopOrder.Status.PROCESSING)).isTrue();
			assertThat(machine.reversesCommission(ShopOrder.Status.SHIPPED)).isTrue();
			assertThat(machine.reversesCommission(ShopOrder.Status.DELIVERED)).isTrue();
		}
	}

	@Nested
	@DisplayName("the graph is complete")
	class Completeness {

		@Test
		@DisplayName("every status is open or closed, never neither and never both")
		void partitionIsTotal() {
			Set<ShopOrder.Status> classified = new HashSet<>(OrderStateMachine.OPEN_STATUSES);
			classified.addAll(OrderStateMachine.CLOSED_STATUSES);

			assertThat(classified)
					.as("a status that is neither open nor closed would be invisible to the board")
					.containsExactlyInAnyOrder(ShopOrder.Status.values());
		}

		@Test
		@DisplayName("terminal is a subset of closed")
		void terminalIsSubsetOfClosed() {
			assertThat(OrderStateMachine.CLOSED_STATUSES)
					.containsAll(OrderStateMachine.TERMINAL_STATUSES);
		}

		@Test
		@DisplayName("open and closed do not overlap")
		void noOverlap() {
			for (ShopOrder.Status status : OrderStateMachine.OPEN_STATUSES) {
				assertThat(machine.isClosed(status))
						.as(status + " is in both sets, so a filter for it is ambiguous")
						.isFalse();
			}
		}

		@Test
		@DisplayName("the declared edges are the ones reachable")
		void declaredEdgesAreReachable() {
			Set<String> declared = new HashSet<>();
			for (ShopOrder.Status from : ShopOrder.Status.values()) {
				for (ShopOrder.Status to : machine.successors(from)) {
					declared.add(from + ">" + to);
					assertThat(machine.canTransition(from, to)).isTrue();
				}
			}
			assertThat(declared).contains("PENDING>ACCEPTED", "PENDING>CANCELLED",
					"ACCEPTED>PROCESSING", "PROCESSING>SHIPPED", "SHIPPED>DELIVERED");
		}

		@Test
		@DisplayName("no status is a dead end unless it is terminal")
		void noUnexpectedDeadEnds() {
			EnumSet<ShopOrder.Status> allowedDeadEnds =
					EnumSet.of(ShopOrder.Status.CANCELLED, ShopOrder.Status.RETURNED,
							ShopOrder.Status.REFUNDED);

			for (ShopOrder.Status status : ShopOrder.Status.values()) {
				if (machine.successors(status).isEmpty()) {
					assertThat(allowedDeadEnds)
							.as(status + " has no successors but is not terminal")
							.contains(status);
				}
			}
		}

		@Test
		@DisplayName("null is never a legal transition")
		void nullIsRefused() {
			assertThat(machine.canTransition(null, ShopOrder.Status.ACCEPTED)).isFalse();
			assertThat(machine.canTransition(ShopOrder.Status.PENDING, null)).isFalse();
			assertThat(machine.canTransition(null, null)).isFalse();
			assertThat(machine.successors(null)).isEmpty();
		}
	}
}
