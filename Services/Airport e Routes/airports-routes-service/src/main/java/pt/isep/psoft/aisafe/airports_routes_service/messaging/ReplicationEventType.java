package pt.isep.psoft.aisafe.airports_routes_service.messaging;

/**
 * Tipos de eventos de dominio publicados no broker.
 * Cada evento corresponde a um comando de escrita aceite por uma instancia
 * e que as restantes instancias tem de aplicar.
 */
public enum ReplicationEventType {
    AIRPORT_CREATED,
    AIRPORT_DETAILS_UPDATED,
    AIRPORT_STATUS_CHANGED,
    ROUTE_CREATED,
    ROUTE_UPDATED,
    ROUTE_DEACTIVATED;

    /** Deduz o tipo de evento a partir do metodo e do caminho registados no outbox. */
    public static ReplicationEventType from(String method, String path) {
        String m = method == null ? "" : method.toUpperCase();
        String p = path == null ? "" : path;
        if (p.startsWith("/api/airports")) {
            if ("POST".equals(m)) return AIRPORT_CREATED;
            if ("PATCH".equals(m) && p.endsWith("/details")) return AIRPORT_DETAILS_UPDATED;
            if ("PATCH".equals(m) && p.endsWith("/status")) return AIRPORT_STATUS_CHANGED;
        } else if (p.startsWith("/api/routes")) {
            if ("POST".equals(m)) return ROUTE_CREATED;
            if ("PATCH".equals(m) && p.endsWith("/deactivate")) return ROUTE_DEACTIVATED;
            if ("PATCH".equals(m)) return ROUTE_UPDATED;
        }
        throw new IllegalArgumentException("Operacao sem evento correspondente: " + m + " " + p);
    }

    /**
     * Identificador do recurso no caminho: /api/airports/{iata}/details -> iata;
     * /api/routes/{id}/deactivate -> id. Devolve null quando o caminho nao tem identificador.
     */
    public static String resourceId(String path) {
        if (path == null) return null;
        String[] parts = path.split("/");
        // ["", "api", "airports", "{id}", ...]
        return parts.length > 3 && !parts[3].isBlank() ? parts[3] : null;
    }
}
