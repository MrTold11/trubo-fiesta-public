package ru.chmod777.trubofiesta.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.chmod777.trubofiesta.calc.CalcResult;
import ru.chmod777.trubofiesta.db.JobRepository;
import ru.chmod777.trubofiesta.jobs.JobService;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** Запуск расчёта, состояние задания и выгрузка результата. */
@RestController
@RequestMapping("/api")
@Tag(name = "Расчёт", description = "Запуск, состояние и результат")
public class CalculationController {

    private final JobService jobService;
    private final JobRepository jobs;
    private final ru.chmod777.trubofiesta.calc.ImportedPlanService imported;
    private final ru.chmod777.trubofiesta.db.FeatureRepository features;
    private final ru.chmod777.trubofiesta.rules.RulesService rules;

    /** Схемы, загруженные для сравнения с автоматическим расчётом. */
    private final java.util.Map<UUID, java.util.Map<String, ru.chmod777.trubofiesta.calc.VariantResult>> extras =
            new java.util.concurrent.ConcurrentHashMap<>();

    public CalculationController(JobService jobService, JobRepository jobs,
                                 ru.chmod777.trubofiesta.calc.ImportedPlanService imported,
                                 ru.chmod777.trubofiesta.db.FeatureRepository features,
                                 ru.chmod777.trubofiesta.rules.RulesService rules) {
        this.jobService = jobService;
        this.jobs = jobs;
        this.imported = imported;
        this.features = features;
        this.rules = rules;
    }

    @PostMapping(value = "/jobs/{id}/imported", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить свою схему разводки и сравнить её с расчётной")
    public java.util.Map<String, Object> imported(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam("file")
                    org.springframework.web.multipart.MultipartFile file) throws IOException {
        JobRepository.Job job = require(id);
        CalcResult result = jobService.resultOrRecompute(id);
        ru.chmod777.trubofiesta.settings.CalcSettings settings = settingsOf(job);
        ru.chmod777.trubofiesta.calc.VariantResult variant;
        try (java.io.InputStream in = file.getInputStream()) {
            variant = imported.evaluate(features.findAll(job.projectId), settings, in);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        variant.variantId = "imported";
        variant.strategy = "Загруженная схема";
        extras.computeIfAbsent(id, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put("imported", variant);

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("variant", variant);
        if (result != null && !result.variants.isEmpty()) {
            ru.chmod777.trubofiesta.calc.VariantResult best = result.variants.get(0);
            out.put("costDelta", variant.calculatedCost - best.calculatedCost);
            out.put("lengthDelta", variant.newNetworkLength - best.newNetworkLength);
            out.put("scoreDelta", variant.score - best.score);
        }
        return out;
    }

    @GetMapping("/jobs/{id}/excel")
    @Operation(summary = "Выгрузить результат в Excel")
    public ResponseEntity<byte[]> excel(@PathVariable UUID id) throws IOException {
        CalcResult result = jobService.resultOrRecompute(id);
        java.util.List<ru.chmod777.trubofiesta.calc.VariantResult> variants = new java.util.ArrayList<>();
        if (result != null) {
            variants.addAll(result.variants);
        }
        variants.addAll(extras.getOrDefault(id, new java.util.concurrent.ConcurrentHashMap<>()).values());
        if (variants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Результат недоступен: запустите расчёт заново");
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        new ru.chmod777.trubofiesta.export.ExcelExporter(rules.get()).write(variants, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"trubo-fiesta-" + id + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
    }

    private ru.chmod777.trubofiesta.settings.CalcSettings settingsOf(JobRepository.Job job) {
        if (job.settings == null) {
            return new ru.chmod777.trubofiesta.settings.CalcSettings();
        }
        return ru.chmod777.trubofiesta.db.Json.mapper().convertValue(job.settings,
                ru.chmod777.trubofiesta.settings.CalcSettings.class);
    }

    @GetMapping("/jobs/{id}/extras")
    @Operation(summary = "Загруженные схемы, добавленные к расчёту для сравнения")
    public java.util.Map<String, ru.chmod777.trubofiesta.calc.VariantResult> extras(@PathVariable UUID id) {
        return extras.getOrDefault(id, new java.util.concurrent.ConcurrentHashMap<>());
    }

    @GetMapping("/jobs/{id}/extras/{key}/result")
    @Operation(summary = "Выгрузить загруженную схему в GeoJSON с посчитанными атрибутами")
    public ResponseEntity<byte[]> extraResult(@PathVariable UUID id, @PathVariable String key) throws IOException {
        ru.chmod777.trubofiesta.calc.VariantResult variant = extras
                .getOrDefault(id, new java.util.concurrent.ConcurrentHashMap<>()).get(key);
        if (variant == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Такой схемы нет");
        }
        CalcResult wrapper = new CalcResult();
        wrapper.variants.add(variant);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        new ru.chmod777.trubofiesta.export.GeoJsonWriter().write(wrapper, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + key + "-" + id + ".geojson\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(out.toByteArray());
    }

    @PostMapping("/projects/{id}/calculations")
    @Operation(summary = "Запустить расчёт с заданными параметрами")
    public JobRepository.Job start(@PathVariable UUID id, @RequestBody(required = false) CalcSettings settings) {
        return jobService.submit(id, "plan", settings == null ? new CalcSettings() : settings);
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "Состояние расчёта")
    public JobRepository.Job job(@PathVariable UUID id) {
        return require(id);
    }

    @GetMapping("/jobs/{id}/variants")
    @Operation(summary = "Подробности вариантов, включая объяснение ранжирования")
    public CalcResult variants(@PathVariable UUID id) {
        // Здесь намеренно без повторного прогона: открытие страницы не должно
        // запускать расчёт заново, сводка по варианту есть в базе.
        CalcResult result = jobService.result(id);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Подробности расчёта недоступны: сервис перезапускался, сводка есть в задании");
        }
        return result;
    }

    @GetMapping("/jobs/{id}/result")
    @Operation(summary = "Результат в GeoJSON")
    public ResponseEntity<InputStreamResource> result(@PathVariable UUID id) throws IOException {
        JobRepository.Job job = require(id);
        if (job.resultPath == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Расчёт ещё не завершён");
        }
        Path path = Paths.get(job.resultPath);
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл результата не найден");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"result-" + id + ".geojson\"")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(Files.size(path))
                .body(new InputStreamResource(Files.newInputStream(path)));
    }

    private JobRepository.Job require(UUID id) {
        JobRepository.Job job = jobs.find(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Расчёт не найден");
        }
        return job;
    }
}
