package pt.isep.psoft.aisafe.airports_routes_service.util;

public final class PartialResultContext {
    private static final ThreadLocal<Boolean> PARTIAL = ThreadLocal.withInitial(() -> false);

    private PartialResultContext() {
    }

    public static void start() {
        PARTIAL.set(false);
    }

    public static void markPartial() {
        PARTIAL.set(true);
    }

    public static boolean isPartial() {
        return Boolean.TRUE.equals(PARTIAL.get());
    }

    public static void clear() {
        PARTIAL.remove();
    }
}
