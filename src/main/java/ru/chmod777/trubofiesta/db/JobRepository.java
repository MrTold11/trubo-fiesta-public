package ru.chmod777.trubofiesta.db;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Задания расчёта: статус, журнал шагов и путь к результату. */
@Repository
public class JobRepository {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Job job) {
        jdbc.update("insert into jobs (id, project_id, mode, settings, status, progress, log) "
                        + "values (?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)",
                job.id, job.projectId, job.mode, Json.write(job.settings), job.status, job.progress,
                Json.write(job.log));
    }

    public void updateProgress(UUID id, int progress, List<String> log) {
        jdbc.update("update jobs set progress = ?, log = ?::jsonb, status = ? where id = ?",
                progress, Json.write(log), RUNNING, id);
    }

    public void finish(UUID id, String resultPath, Object summary, List<String> log) {
        jdbc.update("update jobs set status = ?, progress = 100, finished_at = now(), "
                        + "result_path = ?, summary = ?::jsonb, log = ?::jsonb where id = ?",
                DONE, resultPath, Json.write(summary), Json.write(log), id);
    }

    public void fail(UUID id, String error, List<String> log) {
        jdbc.update("update jobs set status = ?, finished_at = now(), error = ?, log = ?::jsonb where id = ?",
                FAILED, error, Json.write(log), id);
    }

    public Job find(UUID id) {
        List<Job> found = jdbc.query("select * from jobs where id = ?", mapper(), id);
        return found.isEmpty() ? null : found.get(0);
    }

    public List<Job> findByProject(UUID projectId) {
        return jdbc.query("select * from jobs where project_id = ? order by started_at desc", mapper(), projectId);
    }

    /** Ключ алгоритма задания; у старых заданий его в параметрах нет, там подразумевался базовый. */
    public static final String ALGORITHM_SQL = "coalesce(settings ->> 'algorithm', 'OCTOGRID')";

    /**
     * Последний удавшийся расчёт по каждому алгоритму. Новый расчёт тем же алгоритмом
     * вытесняет прежний, а расчёты другими алгоритмами остаются: их результаты по одному
     * и тому же набору должно быть видно одновременно.
     */
    public List<Job> latestDoneByAlgorithm(UUID projectId) {
        return jdbc.query("select distinct on (" + ALGORITHM_SQL + ") * from jobs "
                + "where project_id = ? and status = '" + DONE + "' "
                + "order by " + ALGORITHM_SQL + ", started_at desc", mapper(), projectId);
    }

    /** Расчёты, которые прямо сейчас в очереди или считаются. */
    public List<Job> activeByProject(UUID projectId) {
        return jdbc.query("select * from jobs where project_id = ? and status in ('" + QUEUED + "', '" + RUNNING + "') "
                + "order by started_at desc", mapper(), projectId);
    }

    /** Каким алгоритмом считалось задание. */
    public static String algorithmOf(Job job) {
        Object value = job.settings == null ? null : job.settings.get("algorithm");
        String key = value == null ? null : String.valueOf(value);
        return key == null || key.isBlank() ? "OCTOGRID" : key;
    }

    private RowMapper<Job> mapper() {
        return (rs, n) -> {
            Job j = new Job();
            j.id = (UUID) rs.getObject("id");
            j.projectId = (UUID) rs.getObject("project_id");
            j.mode = rs.getString("mode");
            j.status = rs.getString("status");
            j.progress = rs.getInt("progress");
            j.error = rs.getString("error");
            j.startedAt = rs.getString("started_at");
            j.finishedAt = rs.getString("finished_at");
            j.resultPath = rs.getString("result_path");
            j.settings = Json.read(rs.getString("settings"), new TypeReference<Map<String, Object>>() {
            });
            j.log = Json.read(rs.getString("log"), new TypeReference<List<String>>() {
            });
            j.summary = Json.read(rs.getString("summary"), new TypeReference<Object>() {
            });
            return j;
        };
    }

    public static class Job {
        public UUID id;
        public UUID projectId;
        public String mode;
        public Map<String, Object> settings;
        public String status;
        public int progress;
        public List<String> log;
        public String error;
        public String startedAt;
        public String finishedAt;
        public String resultPath;
        public Object summary;
    }
}
