package ru.chmod777.trubofiesta.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.chmod777.trubofiesta.calc.CalcResult;
import ru.chmod777.trubofiesta.calc.CalculationService;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.db.FeatureRepository;
import ru.chmod777.trubofiesta.db.JobRepository;
import ru.chmod777.trubofiesta.db.ProjectRepository;
import ru.chmod777.trubofiesta.export.GeoJsonWriter;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Очередь расчётов: запуск в фоне, прогресс по шагам, результат на диск. */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    private final Map<UUID, CalcResult> finished = new ConcurrentHashMap<>();

    private final JobRepository jobs;
    private final ProjectRepository projects;
    private final FeatureRepository features;
    private final CalculationService calculation;
    private final Storage storage;

    public JobService(JobRepository jobs, ProjectRepository projects, FeatureRepository features,
                      CalculationService calculation, Storage storage) {
        this.jobs = jobs;
        this.projects = projects;
        this.features = features;
        this.calculation = calculation;
        this.storage = storage;
    }

    public JobRepository.Job submit(UUID projectId, String mode, CalcSettings settings) {
        JobRepository.Job job = new JobRepository.Job();
        job.id = UUID.randomUUID();
        job.projectId = projectId;
        job.mode = mode;
        job.status = JobRepository.QUEUED;
        job.log = new ArrayList<>();
        job.settings = toMap(settings);
        jobs.insert(job);
        pool.submit(() -> run(job.id, projectId, settings));
        return job;
    }

    private void run(UUID jobId, UUID projectId, CalcSettings settings) {
        List<String> steps = new ArrayList<>();
        try {
            jobs.updateProgress(jobId, 5, steps);
            CalcResult result = calculation.run(features.findAll(projectId), settings, message -> {
                steps.add(message);
                jobs.updateProgress(jobId, Math.min(95, 5 + steps.size() * 10), steps);
            });
            ProjectRepository.Project project = projects.find(projectId);
            Path path = storage.resultPath(projectId, jobId);
            try (OutputStream out = storage.write(path)) {
                new GeoJsonWriter().write(result, out);
            }
            finished.put(jobId, result);
            jobs.finish(jobId, path.toString(), summary(result), steps);
            log.info("Расчёт {} завершён за {} мс, вариантов: {}", jobId, result.millis, result.variants.size());
        } catch (Exception e) {
            log.error("Расчёт " + jobId + " не удался", e);
            jobs.fail(jobId, e.getMessage() == null ? e.toString() : e.getMessage(), steps);
        }
    }

    public CalcResult result(UUID jobId) {
        return finished.get(jobId);
    }

    /**
     * Результат расчёта после перезапуска сервиса в памяти не остаётся, а он нужен
     * для ручной правки и сравнения со своей схемой. Расчёт детерминирован: те же
     * параметры и то же зерно дают тот же ответ, поэтому его можно просто повторить.
     */
    public CalcResult resultOrRecompute(UUID jobId) {
        CalcResult cached = finished.get(jobId);
        if (cached != null) {
            return cached;
        }
        JobRepository.Job job = jobs.find(jobId);
        if (job == null || !JobRepository.DONE.equals(job.status)) {
            return null;
        }
        CalcSettings settings = job.settings == null ? new CalcSettings()
                : ru.chmod777.trubofiesta.db.Json.mapper().convertValue(job.settings, CalcSettings.class);
        log.info("Восстанавливаю результат расчёта {} повторным прогоном", jobId);
        CalcResult result = calculation.run(features.findAll(job.projectId), settings, null);
        finished.put(jobId, result);
        return result;
    }

    private Map<String, Object> summary(CalcResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("millis", result.millis);
        out.put("graphVertices", result.graphVertices);
        out.put("graphEdges", result.graphEdges);
        out.put("plansEvaluated", result.plansEvaluated);
        List<Map<String, Object>> variants = new ArrayList<>();
        for (VariantResult v : result.variants) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variantId", v.variantId);
            m.put("rank", v.rank);
            m.put("strategy", v.strategy);
            m.put("score", v.score);
            m.put("calculatedCost", v.calculatedCost);
            m.put("length", v.newNetworkLength);
            m.put("explanation", v.explanation);
            m.put("unconnected", v.unconnected);
            variants.add(m);
        }
        out.put("variants", variants);
        return out;
    }

    private Map<String, Object> toMap(CalcSettings settings) {
        return ru.chmod777.trubofiesta.db.Json.mapper().convertValue(settings,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                });
    }
}
