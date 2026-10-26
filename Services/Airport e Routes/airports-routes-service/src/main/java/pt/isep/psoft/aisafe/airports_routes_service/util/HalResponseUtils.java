package pt.isep.psoft.aisafe.airports_routes_service.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

public final class HalResponseUtils {

    private HalResponseUtils() {
    }

    public static JsonNode firstEmbeddedArray(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        if (body.isArray()) {
            return body;
        }

        JsonNode embedded = body.path("_embedded");
        if (!embedded.isObject()) {
            return null;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = embedded.fields();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue();
            if (value.isArray()) {
                return value;
            }
        }
        return null;
    }
}
