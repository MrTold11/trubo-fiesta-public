package ru.chmod777.trubofiesta.ingest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Потоковое чтение входного GeoJSON: объекты разбираются по одному и сразу отдаются
 * дальше. Файл целиком в память не поднимается — это требование раздела 3.2 кейса
 * про вход до 3 ГБ.
 */
public class GeoJsonReader {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Возвращает число прочитанных объектов. */
    public long read(InputStream in, Consumer<RawFeature> sink) throws IOException {
        long count = 0;
        try (JsonParser p = mapper.getFactory().createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Ожидался объект GeoJSON верхнего уровня");
            }
            while (p.nextToken() != JsonToken.END_OBJECT && p.currentToken() != null) {
                String field = p.currentName();
                p.nextToken();
                if ("features".equals(field)) {
                    if (p.currentToken() != JsonToken.START_ARRAY) {
                        throw new IOException("Поле features должно быть массивом");
                    }
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        sink.accept(parse(p.readValueAsTree()));
                        count++;
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
        if (count == 0) {
            throw new IOException("В файле нет ни одного объекта: проверьте, что это FeatureCollection");
        }
        return count;
    }

    private RawFeature parse(JsonNode node) {
        RawFeature f = new RawFeature();
        JsonNode props = node.path("properties");
        Iterator<String> names = props.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            f.properties.put(name, value(props.get(name)));
        }
        JsonNode geometry = node.get("geometry");
        f.geometry = geometry == null || geometry.isNull() ? null : geometry;
        f.rawId = f.properties.get("id");
        if (f.rawId == null && node.hasNonNull("id")) {
            f.rawId = value(node.get("id"));
        }
        f.id = f.rawId == null ? null : String.valueOf(f.rawId);
        f.objectType = f.str("object_type");
        return f;
    }

    private Object value(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.isIntegralNumber() ? (Object) n.asLong() : (Object) n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isTextual()) {
            return n.asText();
        }
        return n.toString();
    }
}
