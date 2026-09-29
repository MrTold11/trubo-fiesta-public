package ru.chmod777.trubofiesta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.chmod777.trubofiesta.calc.CalcResult;
import ru.chmod777.trubofiesta.calc.CalculationService;

import static ru.chmod777.trubofiesta.Algorithms.registry;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.export.GeoJsonWriter;
import ru.chmod777.trubofiesta.ingest.GeoJsonReader;
import ru.chmod777.trubofiesta.ingest.RawFeature;
import ru.chmod777.trubofiesta.rules.RulesService;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Полный расчёт на синтетическом наборе и на конкурсном, если он лежит рядом. */
class CalculationTest {

    private final CalculationService service = new CalculationService(new RulesService(), registry());

    CalculationTest() throws IOException {
    }

    private List<RawFeature> read(InputStream in) throws IOException {
        List<RawFeature> features = new ArrayList<>();
        new GeoJsonReader().read(in, features::add);
        return features;
    }

    private CalcResult run(CalcSettings settings) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample.geojson")) {
            return service.run(read(in), settings, null);
        }
    }

    @Test
    void все_окс_подключаются_и_стоимость_сходится() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 8;
        CalcResult result = run(settings);

        assertFalse(result.variants.isEmpty(), "должен получиться хотя бы один вариант");
        VariantResult best = result.variants.get(0);
        assertTrue(best.unconnected.isEmpty(), "все точки подключения должны быть подключены");
        assertTrue(best.segments.size() > 0);

        double sum = 0;
        for (VariantResult.Seg s : best.segments) {
            sum += s.cost;
        }
        assertEquals(sum, best.segmentsCost, 1.0);
        assertEquals(best.segmentsCost + best.chamberConstructionCost + best.existingChamberTieInCost,
                best.constructionCost, 1.0);
        assertEquals(best.constructionCost + best.unconnectedPenalty, best.calculatedCost, 1.0);
    }

    @Test
    void диаметр_каждого_участка_покрывает_его_расход() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 4;
        CalcResult result = run(settings);
        var rules = new RulesService().get();
        for (VariantResult v : result.variants) {
            for (VariantResult.Seg s : v.segments) {
                assertTrue(rules.byDn(s.dn).capacity >= s.flow - 1e-6,
                        "участок " + s.id + ": Ду" + s.dn + " не тянет " + s.flow + " т/ч");
            }
        }
    }

    @Test
    void пересечение_дороги_идёт_специальным_проходом() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 4;
        CalcResult result = run(settings);
        boolean special = false;
        for (VariantResult.Seg s : result.variants.get(0).segments) {
            if ("special".equals(s.laying)) {
                special = true;
            }
        }
        assertTrue(special, "трасса пересекает дорогу, должен появиться специальный участок");
    }

    @Test
    void на_границе_специального_прохода_появляется_технический_узел() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 4;
        CalcResult result = run(settings);
        VariantResult v = result.variants.get(0);
        boolean base = false;
        boolean special = false;
        for (VariantResult.Seg s : v.segments) {
            if ("special".equals(s.laying)) special = true;
            else base = true;
        }
        assertTrue(base && special, "трасса должна делиться на обычные и специальные участки");
        assertTrue(v.nodes.size() > 0, "в точке смены способа прокладки должен стоять технический узел");
    }

    @Test
    void выход_содержит_обязательные_null_поля() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 2;
        CalcResult result = run(settings);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new GeoJsonWriter().write(result, out);
        JsonNode root = new ObjectMapper().readTree(out.toByteArray());

        assertEquals("FeatureCollection", root.path("type").asText());
        boolean summaryFound = false;
        boolean depthNullFound = false;
        for (JsonNode f : root.path("features")) {
            String type = f.path("properties").path("object_type").asText();
            if ("variant_summary".equals(type)) {
                summaryFound = true;
                assertTrue(f.path("geometry").isNull(), "у сводной записи geometry должен быть null");
                assertTrue(f.path("properties").has("unconnected_oks_ids"));
            }
            if ("heat_network".equals(type)) {
                assertTrue(f.path("properties").has("depth_start"));
                if (f.path("properties").path("depth_start").isNull()) {
                    depthNullFound = true;
                }
                assertFalse(f.path("properties").has("existing_object_id"),
                        "у участка не должно быть полей чужого типа");
            }
        }
        assertTrue(summaryFound, "в выходе должна быть сводная запись");
        assertTrue(depthNullFound, "в двумерной задаче глубина должна быть null");
    }

    @Test
    void параметры_меняют_результат_а_не_ломают_расчёт() throws IOException {
        CalcSettings a = new CalcSettings();
        a.starts = 4;
        a.futureOksAsObstacle = true;
        CalcSettings b = new CalcSettings();
        b.starts = 4;
        b.futureOksAsObstacle = false;
        assertFalse(run(a).variants.isEmpty());
        assertFalse(run(b).variants.isEmpty());

        CalcSettings c = new CalcSettings();
        c.starts = 4;
        c.pointsInSameBuilding = "MERGE";
        assertFalse(run(c).variants.isEmpty());
    }

    @Test
    void встроенная_проверка_не_находит_нарушений() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 6;
        for (VariantResult v : run(settings).variants) {
            assertTrue(v.warnings.isEmpty(), "проверка нашла нарушения: " + v.warnings);
        }
    }

    @Test
    void идентификаторы_объектов_уникальны() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 4;
        for (VariantResult v : run(settings).variants) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            v.segments.forEach(s -> assertTrue(ids.add(s.id), "повтор " + s.id));
            v.chambers.forEach(c -> assertTrue(ids.add(c.id), "повтор " + c.id));
            v.nodes.forEach(n -> assertTrue(ids.add(n.id), "повтор " + n.id));
        }
    }

    @Test
    void повторный_запуск_даёт_тот_же_результат() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 6;
        double first = run(settings).variants.get(0).score;
        double second = run(settings).variants.get(0).score;
        assertEquals(first, second, 1e-9);
    }

    @Test
    void точка_подключения_это_конец_ровно_одного_участка() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 6;
        for (VariantResult v : run(settings).variants) {
            java.util.Map<String, Integer> degree = new java.util.HashMap<>();
            for (VariantResult.Seg s : v.segments) {
                degree.merge(s.startNodeId, 1, Integer::sum);
                degree.merge(s.endNodeId, 1, Integer::sum);
            }
            for (String point : new String[]{"p1", "p2"}) {
                assertEquals(1, degree.getOrDefault(point, 0), "через точку " + point + " не должна идти трасса транзитом");
            }
            java.util.Set<String> chambers = new java.util.HashSet<>();
            v.chambers.forEach(c -> chambers.add(c.id));
            v.attachments.forEach(a -> chambers.add(a.nodeId));
            for (java.util.Map.Entry<String, Integer> e : degree.entrySet()) {
                if (e.getValue() >= 3) {
                    assertTrue(chambers.contains(e.getKey()), "ветвление в " + e.getKey() + " должно быть в камере");
                }
            }
        }
    }

    @Test
    void только_стандартные_отводы_дают_варианты_без_нестандартных_углов() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 6;
        settings.standardAnglesOnly = true;
        CalcResult result = run(settings);
        assertFalse(result.variants.isEmpty());
        for (VariantResult v : result.variants) {
            assertEquals(0, v.oddTurns, "вариант " + v.variantId + " содержит нестандартные отводы");
            for (VariantResult.Seg s : v.segments) {
                assertEquals(0, s.oddTurns, "участок " + s.id + " поворачивает на нестандартный угол");
            }
            assertTrue(v.warnings.isEmpty(), "проверка нашла нарушения: " + v.warnings);
        }
    }

    @Test
    void варианты_это_разные_деревья_и_идут_по_возрастанию_показателя() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 8;
        CalcResult result = run(settings);
        java.util.Set<String> signatures = new java.util.HashSet<>();
        double previous = -1;
        int rank = 0;
        for (VariantResult v : result.variants) {
            assertEquals(++rank, v.rank, "номер варианта совпадает с рангом");
            assertEquals(String.valueOf(rank), v.variantId);
            assertTrue(signatures.add(v.signature(settings.variantMinDifference)), "варианты должны отличаться деревом");
            assertTrue(v.score >= previous, "ранг должен идти по возрастанию показателя");
            assertTrue(v.explanation.contains("Трассировка"), "в объяснении должна быть геометрия");
            assertTrue(v.warnings.isEmpty(), "проверка нашла нарушения: " + v.warnings);
            previous = v.score;
        }
    }

    /**
     * До правок 19.09 доводка трассы могла увести две ветки из одной камеры в одну сторону:
     * первые 9 метров они шли по одной прямой, а независимая проверка находила пересечение
     * вне общего узла. Проверка на расхождение веток должна ловить это и на синтетике,
     * и в каждом выданном варианте.
     */
    @Test
    void две_ветки_из_одного_узла_не_ложатся_в_одну_траншею() throws IOException {
        VariantResult bad = new VariantResult();
        bad.segments.add(seg("a", "ch_1", "1", 0, 0, 90, 0));
        bad.segments.add(seg("b", "ch_1", "2", 0, 0, 30, 2));
        assertFalse(ru.chmod777.trubofiesta.calc.Evaluator.overlappingBranches(bad, 15).isEmpty(),
                "ветки под 4° друг к другу должны быть замечены");

        VariantResult good = new VariantResult();
        good.segments.add(seg("a", "ch_1", "1", 0, 0, 90, 0));
        good.segments.add(seg("b", "ch_1", "2", 0, 0, 0, 30));
        assertTrue(ru.chmod777.trubofiesta.calc.Evaluator.overlappingBranches(good, 15).isEmpty(),
                "ветки под 90° друг к другу — обычное ветвление");

        CalcSettings settings = new CalcSettings();
        settings.starts = 6;
        for (VariantResult v : run(settings).variants) {
            assertTrue(ru.chmod777.trubofiesta.calc.Evaluator.overlappingBranches(v, settings.minBranchAngle).isEmpty(),
                    "вариант " + v.variantId + ": "
                            + ru.chmod777.trubofiesta.calc.Evaluator.overlappingBranches(v, settings.minBranchAngle));
        }
    }

    private static VariantResult.Seg seg(String id, String from, String to, double... xy) {
        VariantResult.Seg s = new VariantResult.Seg();
        s.id = id;
        s.startNodeId = from;
        s.endNodeId = to;
        s.pts = new java.util.ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            s.pts.add(new org.locationtech.jts.geom.Coordinate(xy[i], xy[i + 1]));
        }
        return s;
    }

    @Test
    void нет_ступенек_короче_трёх_метров_между_поворотами() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 6;
        for (VariantResult v : run(settings).variants) {
            for (VariantResult.Seg s : v.segments) {
                java.util.List<Integer> turns = new java.util.ArrayList<>();
                for (int i = 1; i < s.pts.size() - 1; i++) {
                    if (ru.chmod777.trubofiesta.geo.Geo.turnDegrees(s.pts.get(i - 1), s.pts.get(i), s.pts.get(i + 1)) > 2) {
                        turns.add(i);
                    }
                }
                for (int k = 1; k < turns.size(); k++) {
                    double run = ru.chmod777.trubofiesta.geo.Geo.length(s.pts.subList(turns.get(k - 1), turns.get(k) + 1));
                    assertTrue(run >= 3.0, "участок " + s.id + ": ступенька " + run + " м между поворотами");
                }
            }
        }
    }

    @Test
    void врезка_в_существующую_камеру_стоит_пять_миллионов_за_участок() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 6;
        var rules = new RulesService().get();
        for (VariantResult v : run(settings).variants) {
            for (VariantResult.Attachment a : v.attachments) {
                if (a.existingChamber) {
                    assertEquals(a.branches * rules.existingChamberTieInCost, a.cost, 1.0);
                } else {
                    assertEquals(0.0, a.cost, 1e-9, "новая камера на трубе включает присоединение");
                }
            }
        }
    }

    @Test
    void диаметр_не_убывает_к_месту_присоединения() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 6;
        for (VariantResult v : run(settings).variants) {
            java.util.Map<String, java.util.List<VariantResult.Seg>> out = new java.util.HashMap<>();
            for (VariantResult.Seg s : v.segments) {
                out.computeIfAbsent(s.startNodeId, k -> new java.util.ArrayList<>()).add(s);
            }
            for (VariantResult.Seg s : v.segments) {
                for (VariantResult.Seg child : out.getOrDefault(s.endNodeId, new java.util.ArrayList<>())) {
                    assertTrue(child.dn <= s.dn, "Ду убывает к присоединению: " + child.id + " " + child.dn
                            + " против " + s.id + " " + s.dn);
                }
            }
        }
    }

    @Test
    void невалидная_геометрия_попадает_в_диагностику() throws IOException {
        String bowTie = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37.60,55.70],[37.601,55.701],"
                + "[37.601,55.70],[37.60,55.701],[37.60,55.70]]]},"
                + "\"properties\":{\"id\":\"bad\",\"object_type\":\"restriction\",\"restriction_type\":\"park\"}}]}";
        List<RawFeature> features = read(new java.io.ByteArrayInputStream(bowTie.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var data = new ru.chmod777.trubofiesta.ingest.InputBuilder(new RulesService().get(), new CalcSettings()).build(features);
        assertTrue(data.diagnostics.stream().anyMatch(d -> "invalid_geometry".equals(d.code) && "bad".equals(d.objectId)),
                "о невалидной геометрии нужно сообщить");
    }

    @Test
    void конкурсный_набор_считается_целиком() throws IOException {
        Path dataset = Paths.get("data/dataset.geojson");
        if (!Files.exists(dataset)) {
            return;
        }
        CalcSettings settings = new CalcSettings();
        settings.starts = 12;
        CalcResult result;
        try (InputStream in = Files.newInputStream(dataset)) {
            result = service.run(read(in), settings, null);
        }
        assertFalse(result.variants.isEmpty());
        VariantResult best = result.variants.get(0);
        result.diagnostics.stream()
                .filter(d -> !"info".equals(d.level))
                .forEach(d -> System.out.println("  " + d.level + " " + d.code + " " + d.objectId + " " + d.message));
        System.out.println("Конкурсный набор: вариантов " + result.variants.size()
                + ", лучший S=" + String.format("%.3f", best.score)
                + ", стоимость " + Math.round(best.calculatedCost)
                + ", новая сеть " + Math.round(best.newNetworkLength) + " м"
                + ", не подключено " + best.unconnected.size() + " " + best.unconnected
                + ", время " + result.millis + " мс");
        assertTrue(best.unconnected.isEmpty(), "на конкурсном наборе должны подключиться все ОКС");
        assertTrue(best.warnings.isEmpty(), "встроенная проверка: " + best.warnings);
        System.out.println("  присоединений " + best.attachments.size() + ", новых камер " + best.chambers.size()
                + ", участков " + best.segments.size() + ", узлов " + best.nodes.size());
    }
}
