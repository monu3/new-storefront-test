package com.storefront.store.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.ActiveProfiles;

/**
 * Marks a suite as one that commits and is therefore responsible for its own cleanup.
 *
 * <p>Documentation rather than behaviour: the cleaner is injected explicitly by the suite, so
 * truncating the database is always a visible decision at the call site rather than an annotation
 * nobody notices.
 *
 * <p>Deliberately does <em>not</em> make the test transactional. That is the whole distinction
 * from every other suite here: a rolled-back test can show that a failure threw, but only a
 * committed one can show that nothing was left behind.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ActiveProfiles("test")
public @interface ResetTestDatabase {
}
