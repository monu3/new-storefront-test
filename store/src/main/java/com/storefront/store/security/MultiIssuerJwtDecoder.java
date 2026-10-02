package com.storefront.store.security;

import java.util.Map;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Routes each token to a decoder based on its {@code iss} (issuer) claim.
 *
 * <p>Spring Boot has no built-in multi-issuer decoder, and one is required because this API
 * trusts two independent Keycloak realms (plan decision D3). The routing is deliberately strict:
 *
 * <ul>
 *   <li>An unrecognised issuer is rejected outright. It is <em>not</em> tried against every
 *       decoder, because that would let a token from a rogue issuer sharing a JWK Set through.</li>
 *   <li>The claim is read from the unverified token only to <em>select</em> a decoder. The
 *       selected decoder then performs full signature and issuer validation, so an attacker
 *       gains nothing by forging {@code iss}.</li>
 * </ul>
 */
public class MultiIssuerJwtDecoder implements JwtDecoder {

	private final Map<String, JwtDecoder> decodersByIssuer;

	public MultiIssuerJwtDecoder(Map<String, JwtDecoder> decodersByIssuer) {
		if (decodersByIssuer == null || decodersByIssuer.isEmpty()) {
			throw new IllegalArgumentException("At least one issuer decoder is required");
		}
		this.decodersByIssuer = Map.copyOf(decodersByIssuer);
	}

	@Override
	public Jwt decode(String token) throws JwtException {
		Jwt unverified = peekIssuer(token);

		JwtDecoder decoder = decodersByIssuer.get(unverified.getIssuer().toString());
		if (decoder == null) {
			throw new JwtException("Untrusted issuer: " + unverified.getIssuer()
					+ ". Expected one of " + decodersByIssuer.keySet());
		}

		// Full validation happens here: signature, exp/nbf, and issuer.
		return decoder.decode(token);
	}

	/**
	 * Parses the token without verifying it, solely to read {@code iss}.
	 *
	 * <p>Uses Nimbus directly rather than trusting a regex on the raw string: a hand-rolled
	 * parse is where JWT confusion bugs live. Nothing from this parse is used for
	 * authorization, only to choose which decoder performs the real verification.
	 */
	private Jwt peekIssuer(String token) {
		try {
			var parsed = com.nimbusds.jwt.SignedJWT.parse(token);
			String issuer = parsed.getJWTClaimsSet().getIssuer();
			if (issuer == null || issuer.isBlank()) {
				throw new JwtException("Token has no 'iss' claim");
			}
			// Reuse Spring's Jwt so the value is a real Jwt instance for routing.
			return Jwt.withTokenValue(token)
					.header("alg", parsed.getHeader().getAlgorithm().getName())
					.claim("iss", issuer)
					.build();
		} catch (JwtException e) {
			throw e;
		} catch (Exception e) {
			throw new JwtException("Malformed bearer token", e);
		}
	}
}
