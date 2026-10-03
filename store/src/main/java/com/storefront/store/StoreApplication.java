package com.storefront.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
/**
 * {@link EnableScheduling} is what runs the outbox relay.
 *
 * <p>It is enabled here rather than on the relay class so that it is a single, visible decision
 * at the entry point. Without it {@code @Scheduled} is silently inert — no error, no warning, and
 * events pile up unpublished while every test that does not check the outbox still passes.
 */
@EnableScheduling
public class StoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(StoreApplication.class, args);
	}

}
