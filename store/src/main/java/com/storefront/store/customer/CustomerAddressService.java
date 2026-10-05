package com.storefront.store.customer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.security.CurrentUser;

import lombok.RequiredArgsConstructor;

/**
 * Saved delivery addresses (Phase 5).
 *
 * <p><b>Exactly one default per customer, enforced in the database</b> by a partial unique index on
 * {@code (user_id) WHERE is_default}. The service sets the new default and clears the old one in
 * the same transaction; the index is what makes a second default impossible even if some future
 * code path forgets to clear the old row.
 *
 * <p><b>An address is not an order's address.</b> Orders snapshot the delivery address as frozen
 * jsonb (see {@code ShopOrder.shippingAddress}) precisely so that editing or deleting a saved address
 * cannot rewrite where a parcel was sent. Editing an address here affects the <em>next</em> order
 * and no other.
 */
@Service
@RequiredArgsConstructor
public class CustomerAddressService {

	private final CustomerAddressRepository addresses;
	private final CurrentUser currentUser;

	/** This customer's addresses, default first. */
	@Transactional(readOnly = true)
	public List<CustomerAddress> list() {
		UUID userId = currentUser.require().getId();
		return addresses.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId);
	}

	/**
	 * The default address, or the newest one.
	 *
	 * <p>Falls back to the newest when there is no default, because a customer with exactly one
	 * saved address does not need to have ticked a box for it to be the obvious one.
	 */
	@Transactional(readOnly = true)
	public CustomerAddress defaultAddress() {
		List<CustomerAddress> all = list();
		return all.stream()
				.filter(CustomerAddress::isDefault)
				.findFirst()
				.or(() -> all.stream().findFirst())
				.orElse(null);
	}

	@Transactional
	public CustomerAddress create(AddressDraft draft) {
		UUID userId = currentUser.require().getId();

		boolean first = addresses.countByUserId(userId) == 0;
		CustomerAddress address = new CustomerAddress(userId,
				draft.label(), draft.recipient(), draft.phone(),
				draft.line1(), draft.line2(), draft.city(),
				draft.state(), draft.postalCode(), draft.country());

		// The first address is the default whether or not the owner asked for it. A customer who
		// saves one address and opens checkout should not have to pick one.
		if (draft.isDefault() || first) {
			clearDefault(userId);
			address.setDefault(true);
		}

		return addresses.save(address);
	}

	/**
	 * Updates an address.
	 *
	 * <p>Partial: a null field is left alone rather than blanked. The console sends the whole form,
	 * but a future partial client that omits one field should not erase it.
	 */
	@Transactional
	public CustomerAddress update(UUID addressId, AddressDraft draft) {
		UUID userId = currentUser.require().getId();
		CustomerAddress address = owned(addressId, userId);

		if (draft.label() != null) {
			address.setLabel(draft.label());
		}
		if (draft.recipient() != null) {
			address.setRecipient(draft.recipient());
		}
		if (draft.phone() != null) {
			address.setPhone(draft.phone());
		}
		if (draft.line1() != null) {
			address.setLine1(draft.line1());
		}
		if (draft.line2() != null) {
			address.setLine2(draft.line2());
		}
		if (draft.city() != null) {
			address.setCity(draft.city());
		}
		if (draft.state() != null) {
			address.setState(draft.state());
		}
		if (draft.postalCode() != null) {
			address.setPostalCode(draft.postalCode());
		}
		if (draft.country() != null) {
			address.setCountry(draft.country());
		}

		if (Boolean.TRUE.equals(draft.isDefault())) {
			makeDefault(address.getId());
		}

		return addresses.save(address);
	}

	/** Promotes an address to be the default, demoting whichever one was. */
	@Transactional
	public CustomerAddress makeDefault(UUID addressId) {
		UUID userId = currentUser.require().getId();
		CustomerAddress address = owned(addressId, userId);
		clearDefault(userId);
		address.setDefault(true);
		return addresses.save(address);
	}

	/**
	 * Deletes an address.
	 *
	 * <p>Refuses to remove the last one, and says why. A customer with no saved address is not
	 * blocked from ordering -- checkout accepts a typed address -- so this is a convenience guard
	 * rather than a correctness one; but silently emptying the list after a mis-click is worse than
	 * a message.
	 */
	@Transactional
	public void delete(UUID addressId) {
		UUID userId = currentUser.require().getId();
		CustomerAddress address = owned(addressId, userId);

		if (addresses.countByUserId(userId) <= 1) {
			throw new IllegalArgumentException(
					"This is your only saved address. Add another one before removing this.");
		}

		boolean wasDefault = address.isDefault();
		addresses.delete(address);

		// Removing the default must promote something, or checkout would have nothing to offer.
		if (wasDefault) {
			addresses.findFirstByUserIdOrderByCreatedAtDesc(userId).ifPresent(next -> {
				next.setDefault(true);
				addresses.save(next);
			});
		}
	}

	// ------------------------------------------------------------------ helpers

	/** Fetches an address and proves it is this customer's. */
	private CustomerAddress owned(UUID addressId, UUID userId) {
		return addresses.findById(addressId)
				.filter(a -> a.belongsTo(userId))
				.orElseThrow(() -> new IllegalArgumentException(
						"That address does not exist, or is not yours."));
	}

	private void clearDefault(UUID userId) {
		addresses.clearDefaultFor(userId);
	}

	// ------------------------------------------------------------------ types

	/** What the console sends. Every field nullable, because updates are partial. */
	public record AddressDraft(
			String label,
			String recipient,
			String phone,
			String line1,
			String line2,
			String city,
			String state,
			String postalCode,
			String country,
			/** {@link Boolean} not {@code boolean}: null means "leave as it is". */
			Boolean isDefault) {
	}

	/** An address as returned, for the checkout picker. */
	public record AddressView(
			String id,
			String label,
			String recipient,
			String phone,
			String line1,
			String line2,
			String city,
			String state,
			String postalCode,
			String country,
			boolean isDefault) {

		public static AddressView from(CustomerAddress a) {
			return new AddressView(
					a.getId().toString(), a.getLabel(), a.getRecipient(), a.getPhone(),
					a.getLine1(), a.getLine2(), a.getCity(), a.getState(),
					a.getPostalCode(), a.getCountry(), a.isDefault());
		}
	}
}