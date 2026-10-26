package pt.isep.psoft.aisafe.flight_operations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Gera JWTs de TESTE (HS256) assinados com o mesmo jwt.secret dos servicos do grupo,
 * um por perfil do AISafe. So para testar no Postman enquanto a API Gateway nao emite tokens.
 * Correr: botao direito -> Run 'DevTokenGenerator.main()' e copiar os tokens da consola.
 */
public class DevTokenGenerator {

    // Igual ao jwt.secret do application.properties (de todos os servicos)
    private static final String SECRET = "change-this-development-secret-key-with-at-least-32-bytes";
    private static final long VALID_HOURS = 24;

    public static void main(String[] args) throws Exception {
        print("ATCC", "atcc.user", "[\"ATCC\"]");
        print("BACKOFFICE", "backoffice.user", "[\"BACKOFFICE\"]");
        print("ADMIN", "admin.user", "[\"ADMIN\"]");
        print("MAINTENANCE_TECHNICIAN", "tech.user", "[\"MAINTENANCE_TECHNICIAN\"]");
    }

    private static void print(String label, String user, String roles) throws Exception {
        System.out.println(label + ":");
        System.out.println(token(user, roles));
        System.out.println();
    }

    static String token(String user, String roles) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"" + user + "\",\"username\":\"" + user + "\",\"roles\":" + roles
                + ",\"iat\":" + now + ",\"exp\":" + (now + VALID_HOURS * 3600) + "}";

        String unsigned = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return unsigned + "." + b64(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
