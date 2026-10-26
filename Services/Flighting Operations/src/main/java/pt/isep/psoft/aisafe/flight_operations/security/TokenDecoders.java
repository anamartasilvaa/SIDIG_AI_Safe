package pt.isep.psoft.aisafe.flight_operations.security;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;

public final class TokenDecoders {

    private TokenDecoders() {}

    public static JwtDecoder create(String jwkSetUri, String issuer, String devSecret, boolean acceptDevTokens) {
        JwtDecoder keycloak = null;
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            NimbusJwtDecoder rs = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            if (issuer != null && !issuer.isBlank()) {
                rs.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            }
            keycloak = rs;
        }
        return create(keycloak, devSecret, acceptDevTokens);
    }

    public static JwtDecoder create(JwtDecoder keycloakDecoder, String devSecret, boolean acceptDevTokens) {
        JwtDecoder dev = acceptDevTokens ? hs256(devSecret) : null;
        return token -> {
            String alg = algorithmOf(token);
            if ("HS256".equals(alg)) {
                if (dev == null) {
                    throw new BadJwtException("development tokens (HS256) are disabled");
                }
                return dev.decode(token);
            }
            if (keycloakDecoder == null) {
                throw new BadJwtException("no identity provider configured for " + alg + " tokens");
            }
            return keycloakDecoder.decode(token);
        };
    }

    private static JwtDecoder hs256(String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("jwt.secret must contain at least 32 bytes (HS256).");
        }
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private static String algorithmOf(String token) {
        try {
            return JWTParser.parse(token).getHeader().getAlgorithm().getName();
        } catch (ParseException | RuntimeException e) {
            throw new BadJwtException("malformed token");
        }
    }
}
