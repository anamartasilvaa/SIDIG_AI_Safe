package pt.isep.psoft.aisafe.flight_operations.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
public final class TestTokens {
    public static final String SECRET = "change-this-development-secret-key-with-at-least-32-bytes";

    private TestTokens() {}

    public static String valid(String user, Object roles) {
        return build(SECRET, user, roles, 3_600_000);
    }

    public static String expired(String user, Object roles) {
        return build(SECRET, user, roles, -60_000);
    }

    public static String signedWithOtherSecret(String user, Object roles) {
        return build("another-secret-that-is-also-at-least-32-bytes-long!!", user, roles, 3_600_000);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String build(String secret, String user, Object roles, long validForMillis) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .issuedAt(new Date(now - 120_000))
                .expiration(new Date(now + validForMillis));
        if (user != null) {
            builder.subject(user).claim("username", user);
        }
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }
}
