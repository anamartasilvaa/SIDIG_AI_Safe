package pt.isep.psoft.aisafe.flight_operations.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Tokens JWT para os testes:
 *  - HS256 (desenvolvimento): assinados com o jwt.secret partilhado;
 *  - RS256 (como o Keycloak): assinados com uma chave RSA gerada para os testes,
 *    com os perfis em realm_access.roles e o utilizador em preferred_username.
 */
public final class TestTokens {

    /** Igual ao jwt.secret por omissao do application.properties. */
    public static final String SECRET = "change-this-development-secret-key-with-at-least-32-bytes";
    public static final String ISSUER = "http://localhost:8180/realms/aisafe";

    private static final long HOUR = 3_600_000;
    private static final RSAKey KEYCLOAK_KEY = rsaKey("keycloak-test");
    private static final RSAKey OTHER_KEY = rsaKey("intruder");

    private TestTokens() {}

    // ---------------------------------------------------------------- HS256 (desenvolvimento)

    public static String valid(String user, Object roles) {
        return hs256(SECRET, user, roles, HOUR);
    }

    /** Expirado ha 10 min (acima da tolerancia de relogio de 60 s do Spring Security). */
    public static String expired(String user, Object roles) {
        return hs256(SECRET, user, roles, -600_000);
    }

    public static String signedWithOtherSecret(String user, Object roles) {
        return hs256("another-secret-that-is-also-at-least-32-bytes-long!!", user, roles, HOUR);
    }

    // ---------------------------------------------------------------- RS256 (Keycloak)

    /** Descodificador que confia na chave "do Keycloak" dos testes e exige o issuer do realm aisafe. */
    public static JwtDecoder keycloakDecoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEYCLOAK_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String keycloak(String user, List<String> roles) {
        return rs256(KEYCLOAK_KEY, ISSUER, user, roles);
    }

    public static String keycloakWithIssuer(String issuer, String user, List<String> roles) {
        return rs256(KEYCLOAK_KEY, issuer, user, roles);
    }

    public static String keycloakSignedWithOtherKey(String user, List<String> roles) {
        return rs256(OTHER_KEY, ISSUER, user, roles);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---------------------------------------------------------------- auxiliares

    private static String hs256(String secret, String user, Object roles, long validForMillis) {
        JWTClaimsSet.Builder claims = times(validForMillis);
        if (user != null) {
            claims.subject(user).claim("username", user);
        }
        if (roles != null) {
            claims.claim("roles", roles);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mesmo formato de um access token do Keycloak (sem as claims extra "roles"/"username"). */
    private static String rs256(RSAKey key, String issuer, String user, List<String> roles) {
        JWTClaimsSet claims = times(HOUR)
                .issuer(issuer)
                .subject("c24f6560-0000-0000-0000-000000000000")
                .claim("preferred_username", user)
                .claim("realm_access", Map.of("roles", roles))
                .claim("azp", "aisafe-client")
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet.Builder times(long validForMillis) {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder()
                .issueTime(new Date(validForMillis < 0 ? now + validForMillis - 3_600_000 : now - 120_000))
                .expirationTime(new Date(now + validForMillis));
    }

    private static RSAKey rsaKey(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
