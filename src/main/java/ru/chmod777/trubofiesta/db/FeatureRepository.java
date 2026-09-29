package ru.chmod777.trubofiesta.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.chmod777.trubofiesta.ingest.RawFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Объекты входного файла. Пишутся пачками во время потокового разбора. */
@Repository
public class FeatureRepository {

    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;

    public FeatureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertBatch(UUID projectId, List<RawFeature> batch) {
        List<Object[]> rows = new ArrayList<>(batch.size());
        for (RawFeature f : batch) {
            rows.add(new Object[]{projectId, f.id, f.objectType,
                    Json.write(f.properties), f.geometry == null ? null : f.geometry.toString()});
        }
        jdbc.batchUpdate("insert into features (project_id, object_id, object_type, properties, geometry) "
                + "values (?, ?, ?, ?::jsonb, ?::jsonb)", rows);
    }

    public int batchSize() {
        return BATCH;
    }

    public List<RawFeature> findAll(UUID projectId) {
        return jdbc.query("select object_id, object_type, properties, geometry from features where project_id = ? order by id",
                (rs, n) -> fromRow(rs.getString("object_id"), rs.getString("object_type"),
                        rs.getString("properties"), rs.getString("geometry")),
                projectId);
    }

    /**
     * Объект из строки таблицы. Исходный тип идентификатора (число или строка) в колонке
     * object_id не хранится, зато он остаётся в JSON свойств: оттуда и берём, иначе все
     * ссылки в выгрузке станут строками, а раздел 7 требует тот же тип, что во входе.
     */
    static RawFeature fromRow(String objectId, String objectType, String propertiesJson, String geometryJson) {
        RawFeature f = new RawFeature();
        f.id = objectId;
        f.objectType = objectType;
        Map<String, Object> props = Json.read(propertiesJson, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        if (props != null) {
            f.properties = props;
        }
        f.rawId = f.properties.get("id") != null ? f.properties.get("id") : objectId;
        f.geometry = Json.read(geometryJson, JsonNode.class);
        return f;
    }
}
