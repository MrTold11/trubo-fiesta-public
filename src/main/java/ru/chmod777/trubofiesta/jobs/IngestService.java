package ru.chmod777.trubofiesta.jobs;

import org.springframework.stereotype.Service;
import ru.chmod777.trubofiesta.db.FeatureRepository;
import ru.chmod777.trubofiesta.db.ProjectRepository;
import ru.chmod777.trubofiesta.ingest.GeoJsonReader;
import ru.chmod777.trubofiesta.ingest.InputBuilder;
import ru.chmod777.trubofiesta.ingest.RawFeature;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.rules.RulesService;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Загрузка набора данных: файл на диск, объекты в базу, сводка и диагностика. */
@Service
public class IngestService {

    private final Storage storage;
    private final ProjectRepository projects;
    private final FeatureRepository features;
    private final RulesService rules;

    public IngestService(Storage storage, ProjectRepository projects, FeatureRepository features, RulesService rules) {
        this.storage = storage;
        this.projects = projects;
        this.features = features;
        this.rules = rules;
    }

    public ProjectRepository.Project ingest(String name, InputStream in) throws IOException {
        UUID id = UUID.randomUUID();
        Path saved = storage.saveInput(id, in);

        ProjectRepository.Project project = new ProjectRepository.Project();
        project.id = id;
        project.name = name;
        project.inputPath = saved.toString();
        projects.insert(project);

        List<RawFeature> batch = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        double[] bbox = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};

        try (InputStream file = storage.read(saved)) {
            project.featureCount = new GeoJsonReader().read(file, f -> {
                counts.merge(f.objectType == null ? "без типа" : f.objectType, 1, Integer::sum);
                expand(bbox, f);
                batch.add(f);
                if (batch.size() >= features.batchSize()) {
                    features.insertBatch(id, batch);
                    batch.clear();
                }
            });
            if (!batch.isEmpty()) {
                features.insertBatch(id, batch);
            }
        }

        InputData data = new InputBuilder(rules.get(), new CalcSettings()).build(features.findAll(id));
        project.counts = counts;
        project.diagnostics = data.diagnostics;
        project.bbox = bbox[0] == Double.MAX_VALUE ? null
                : java.util.Arrays.asList(bbox[0], bbox[1], bbox[2], bbox[3]);
        projects.updateSummary(project);
        return project;
    }

    private void expand(double[] bbox, RawFeature f) {
        if (f.geometry == null) {
            return;
        }
        walk(f.geometry.path("coordinates"), bbox);
    }

    private void walk(com.fasterxml.jackson.databind.JsonNode node, double[] bbox) {
        if (node.isArray() && node.size() >= 2 && node.get(0).isNumber() && node.get(1).isNumber()) {
            double x = node.get(0).asDouble();
            double y = node.get(1).asDouble();
            bbox[0] = Math.min(bbox[0], x);
            bbox[1] = Math.min(bbox[1], y);
            bbox[2] = Math.max(bbox[2], x);
            bbox[3] = Math.max(bbox[3], y);
            return;
        }
        for (com.fasterxml.jackson.databind.JsonNode child : node) {
            walk(child, bbox);
        }
    }
}
