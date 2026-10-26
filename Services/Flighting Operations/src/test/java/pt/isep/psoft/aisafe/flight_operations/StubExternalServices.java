package pt.isep.psoft.aisafe.flight_operations;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

public class StubExternalServices {

    // Nomes dos campos alinhados com o Domain Model (Route / Aircraft + AircraftModel)
    private static final Map<String, String> ROUTES = Map.of(
            "OPO-LIS", """
                    {"routeId":"OPO-LIS","status":"ACTIVE","distance":300,"minimumRange":300,"minimumCapacity":50}""",
            "OPO-JFK", """
                    {"routeId":"OPO-JFK","status":"ACTIVE","distance":5300,"minimumRange":5300,"minimumCapacity":150}""",
            "OPO-FNC", """
                    {"routeId":"OPO-FNC","status":"INACTIVE","distance":1000,"minimumRange":1000,"minimumCapacity":50}""");

    private static final Map<String, String> AIRCRAFT = Map.of(
            "CS-TTA", """
                    {"registrationNumber":"CS-TTA","status":"ACTIVE","maximumRange":6000,"actualSeatingCapacity":180}""",
            "CS-TTB", """
                    {"registrationNumber":"CS-TTB","status":"ACTIVE","maximumRange":2500,"actualSeatingCapacity":120}""",
            "CS-TTM", """
                    {"registrationNumber":"CS-TTM","status":"UNDER_MAINTENANCE","maximumRange":6000,"actualSeatingCapacity":180}""");

    public static void main(String[] args) throws IOException {
       // start(8091, "/api/routes/", ROUTES);
        start(8071, "/api/aircraft/", AIRCRAFT);
        System.out.println("Stub a correr: routes em :8091, aircraft em :8071 (Stop para parar)");
    }

    private static void start(int port, String prefix, Map<String, String> data) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(prefix, exchange -> {
            String key = exchange.getRequestURI().getPath().substring(prefix.length())
                    .replace("/", "").toUpperCase(Locale.ROOT);
            String body = data.get(key);
            int status = body == null ? 404 : 200;
            System.out.printf("[stub %d] %s %s -> %d%n", port, exchange.getRequestMethod(),
                    exchange.getRequestURI(), status);
            reply(exchange, status, body == null ? "{\"error\":\"not found\"}" : body);
        });
        server.start();
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}