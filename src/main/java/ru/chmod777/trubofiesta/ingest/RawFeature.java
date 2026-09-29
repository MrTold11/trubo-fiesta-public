package ru.chmod777.trubofiesta.ingest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Объект входного GeoJSON как он пришёл, до разбора в доменную модель. */
public class RawFeature {

    public String id;
    /** Тот же идентификатор, как он пришёл в JSON: число остаётся числом, раздел 7. */
    public Object rawId;
    public String objectType;
    public Map<String, Object> properties = new LinkedHashMap<>();
    public JsonNode geometry;

    public String geometryType() {
        return geometry == null || geometry.isNull() ? null : geometry.path("type").asText(null);
    }

    public String str(String key) {
        Object v = properties.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Double num(String key) {
        Object v = properties.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
