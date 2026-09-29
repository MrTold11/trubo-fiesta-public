package ru.chmod777.trubofiesta.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.chmod777.trubofiesta.algo.AlgorithmRegistry;
import ru.chmod777.trubofiesta.db.JobRepository;
import ru.chmod777.trubofiesta.db.ProjectRepository;
import ru.chmod777.trubofiesta.jobs.IngestService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/** Проекты: загрузка набора, список, сводка, диагностика, исходные данные для карты. */
@RestController
@RequestMapping("/api/projects")
@Tag(name = "Проекты", description = "Загрузка и просмотр исходных данных")
public class ProjectController {

    private final IngestService ingest;
    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final AlgorithmRegistry algorithms;

    public ProjectController(IngestService ingest, ProjectRepository projects, JobRepository jobs,
                             AlgorithmRegistry algorithms) {
        this.ingest = ingest;
        this.projects = projects;
        this.jobs = jobs;
        this.algorithms = algorithms;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить GeoJSON с исходными данными")
    public ProjectRepository.Project upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "name", required = false) String name)
            throws IOException {
        String title = name != null && !name.isBlank() ? name
                : (file.getOriginalFilename() == null ? "Набор данных" : file.getOriginalFilename());
        return ingest.ingest(title, file.getInputStream());
    }

    @GetMapping
    @Operation(summary = "Список проектов")
    public List<ProjectRepository.Project> list() {
        return projects.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Сводка по проекту")
    public ProjectRepository.Project one(@PathVariable UUID id) {
        return require(id);
    }

    @GetMapping("/{id}/input")
    @Operation(summary = "Исходный файл как есть, для отображения на карте")
    public ResponseEntity<InputStreamResource> input(@PathVariable UUID id) throws IOException {
        ProjectRepository.Project p = require(id);
        java.nio.file.Path path = Paths.get(p.inputPath);
        if (!Files.exists(path)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Файл проекта не найден");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(Files.size(path))
                .body(new InputStreamResource(Files.newInputStream(path)));
    }

    @GetMapping("/{id}/jobs")
    @Operation(summary = "Расчёты проекта")
    public List<JobRepository.Job> jobs(@PathVariable UUID id) {
        return jobs.findByProject(id);
    }

    /**
     * Готовые результаты по каждому алгоритму и расчёты, которые идут прямо сейчас.
     *
     * Результаты разных алгоритмов по одному набору живут рядом и видны одновременно:
     * новый расчёт вытесняет только прежний результат того же алгоритма. Поэтому запущенный
     * расчёт не закрывает то, что уже посчитано, — он просто добавляется отдельной строкой.
     */
    @GetMapping("/{id}/results")
    @Operation(summary = "Последний результат по каждому алгоритму и идущие расчёты")
    public java.util.Map<String, Object> results(@PathVariable UUID id) {
        require(id);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("results", describe(jobs.latestDoneByAlgorithm(id)));
        out.put("running", describe(jobs.activeByProject(id)));
        return out;
    }

    private List<java.util.Map<String, Object>> describe(List<JobRepository.Job> found) {
        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (JobRepository.Job job : found) {
            String key = JobRepository.algorithmOf(job);
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("algorithm", key);
            item.put("title", algorithms.title(key));
            item.put("job", job);
            out.add(item);
        }
        return out;
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить проект")
    public void delete(@PathVariable UUID id) {
        projects.delete(id);
    }

    private ProjectRepository.Project require(UUID id) {
        ProjectRepository.Project p = projects.find(id);
        if (p == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Проект не найден");
        }
        return p;
    }
}
