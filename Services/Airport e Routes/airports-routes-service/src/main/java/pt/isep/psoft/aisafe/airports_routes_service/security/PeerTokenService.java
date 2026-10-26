package pt.isep.psoft.aisafe.airports_routes_service.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class PeerTokenService {

    private final SecretKey signingKey;
    private final String subject;

    public PeerTokenService(@Value("${jwt.secret}") String secret,
                            @Value("${server.port:8081}") String serverPort) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("jwt.secret must contain at least 32 UTF-8 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.subject = "peer-" + serverPort;
    }

    public String createToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claim("roles", List.of("PEER"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(signingKey)
                .compact();
    }
}
