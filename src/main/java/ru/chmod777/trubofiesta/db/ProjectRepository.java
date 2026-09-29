package ru.chmod777.trubofiesta.db;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.chmod777.trubofiesta.model.Diagnostic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Проекты: загруженный набор данных и его сводка. */
@Repository
public class ProjectRepository {

    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Project p) {
        jdbc.update("insert into projects (id, name, input_path, feature_count, bbox, counts, diagnostics) "
                        + "values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)",
                p.id, p.name, p.inputPath, p.featureCount,
                Json.write(p.bbox), Json.write(p.counts), Json.write(p.diagnostics));
    }

    public void updateSummary(Project p) {
        jdbc.update("update projects set feature_count = ?, bbox = ?::jsonb, counts = ?::jsonb, "
                        + "diagnostics = ?::jsonb where id = ?",
                p.featureCount, Json.write(p.bbox), Json.write(p.counts), Json.write(p.diagnostics), p.id);
    }

    public List<Project> findAll() {
        return jdbc.query("select * from projects order by created_at desc", mapper());
    }

    public Project find(UUID id) {
        List<Project> found = jdbc.query("select * from projects where id = ?", mapper(), id);
        return found.isEmpty() ? null : found.get(0);
    }

    public void delete(UUID id) {
        jdbc.update("delete from projects where id = ?", id);
    }

    private RowMapper<Project> mapper() {
        return (rs, n) -> {
            Project p = new Project();
            p.id = (UUID) rs.getObject("id");
            p.name = rs.getString("name");
            p.createdAt = rs.getString("created_at");
            p.inputPath = rs.getString("input_path");
            p.featureCount = rs.getLong("feature_count");
            p.bbox = Json.read(rs.getString("bbox"), new TypeReference<List<Double>>() {
            });
            p.counts = Json.read(rs.getString("counts"), new TypeReference<Map<String, Integer>>() {
            });
            p.diagnostics = Json.read(rs.getString("diagnostics"), new TypeReference<List<Diagnostic>>() {
            });
            return p;
        };
    }

    public static class Project {
        public UUID id;
        public String name;
        public String createdAt;
        public String inputPath;
        public long featureCount;
        public List<Double> bbox;
        public Map<String, Integer> counts;
        public List<Diagnostic> diagnostics;
    }
}
