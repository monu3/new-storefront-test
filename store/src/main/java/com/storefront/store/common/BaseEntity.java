package com.storefront.store.common;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PreUpdate;

/**
 * Audit timestamps shared by every mutable entity.
 *
 * <p>Kept as a mapped superclass rather than a {@code @Embeddable} so that {@code createdAt}
 * and {@code updatedAt} cannot be left off a table by accident — a column a query depends on
 * that an entity forgets is a runtime failure rather than a compile-time one.
 *
 * <p><b>Why field initialisers rather than {@code @CreationTimestamp}.</b> Hibernate's timestamp
 * annotations populate the field <em>after</em> the INSERT statement has been assembled, so a
 * NOT NULL column with no database default fails at flush with
 * "No value specified for parameter N" — the annotation looks correct and is not.
 * {@code @PrePersist} has the same problem for the same reason. Initialising the fields at
 * declaration time means the values exist before Hibernate ever builds the statement.
 */
@MappedSuperclass
public abstract class BaseEntity {

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
