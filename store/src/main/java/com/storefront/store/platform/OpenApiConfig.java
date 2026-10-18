package com.storefront.store.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;

/**
 * The OpenAPI contract, as a bean.
 *
 * <p><b>Declared rather than assumed.</b> springdoc fills in an {@link OpenAPI} bean if one exists
 * and publishes nothing injectable if it does not, so without this class the data explorer
 * ({@link DataExplorerController}) fails to start with a {@code NoSuchBeanDefinitionException} that
 * names a swagger type nobody knew was a dependency. Declaring the bean makes the contract part of
 * the application's own surface, which is what "the explorer reads the live contract" requires.
 *
 * <p>The security scheme is declared here too, so the generated contract says how to authenticate.
 * A contract that lists bearer-protected endpoints and no way to get a token is a contract that
 * cannot be followed.
 */
@Configuration
@OpenAPIDefinition(info = @Info(
		title = "store.app",
		version = "v1",
		description = """
				A multi-tenant marketplace: shops sell, customers buy, and platform admins run the \
				marketplace itself.

				Two Keycloak realms. `ops` holds ROLE_ADMIN and ROLE_SHOP; `customers` holds \
				ROLE_CUSTOMER. A shop is always derived from the caller's token and never taken from \
				a request parameter, so there is no endpoint on this API where one shop can act as \
				another by editing a URL.

				Admin endpoints are marked separately: their data is the marketplace's commercial \
				position, not a shop's, and each writes to the audit log.
				""",
		license = @io.swagger.v3.oas.annotations.info.License(name = "Proprietary")))
@SecurityScheme(
		name = "bearer",
		type = SecuritySchemeType.HTTP,
		scheme = "bearer",
		bearerFormat = "JWT",
		description = """
				A Keycloak access token. Send it as `Authorization: Bearer <token>`.

				Tokens are per-realm, so an `ops` token will not authenticate a customer endpoint and \
				a customer token will not authenticate an admin one. That separation is enforced, not \
				merely documented: an admin endpoint checks ROLE_ADMIN on the class, before any \
				method body runs.
				""")
public class OpenApiConfig {

	@Bean
	public OpenAPI storeApiDefinition() {
		return new OpenAPI()
				.components(new Components().addSecuritySchemes("bearer",
						new io.swagger.v3.oas.models.security.SecurityScheme()
								.type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("A Keycloak access token for either realm.")));
	}
}