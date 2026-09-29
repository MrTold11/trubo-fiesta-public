package ru.chmod777.trubofiesta.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Маленькая обёртка: в базе структуры лежат как jsonb. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать значение", e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return json == null ? null : MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось разобрать значение из базы", e);
        }
    }

    public static <T> T read(String json, TypeReference<T> type) {
        try {
            return json == null ? null : MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось разобрать значение из базы", e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
