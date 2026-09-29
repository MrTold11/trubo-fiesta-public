package ru.chmod777.trubofiesta.calc;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;
import ru.chmod777.trubofiesta.algo.AlgorithmContext;
import ru.chmod777.trubofiesta.algo.AlgorithmRegistry;
import ru.chmod777.trubofiesta.algo.AlgorithmResult;
import ru.chmod777.trubofiesta.algo.ProgressLog;
import ru.chmod777.trubofiesta.algo.RoutingAlgorithm;
import ru.chmod777.trubofiesta.approach.Approach;
import ru.chmod777.trubofiesta.approach.Approaches;
import ru.chmod777.trubofiesta.constraints.Clearance;
import ru.chmod777.trubofiesta.constraints.Obstacles;
import ru.chmod777.trubofiesta.export.ResultChecker;
import ru.chmod777.trubofiesta.ingest.InputBuilder;
import ru.chmod777.trubofiesta.ingest.RawFeature;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.Diagnostic;
import ru.chmod777.trubofiesta.model.Segment;
import ru.chmod777.trubofiesta.rules.RulesService;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.TopologyBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Весь расчёт от разобранных объектов до готовых вариантов.
 *
 * Сервис делает три вещи и ни одной больше.
 *
 * <ol>
 *   <li><b>Нормализация.</b> Разбор входа, сборка существующей сети в граф, препятствия
 *       с минимальными расстояниями и точки подхода к каждой точке подключения ОКС.
 *       Это правила технического приложения, они одинаковы для любого алгоритма.</li>
 *   <li><b>Трассировка.</b> Вызов выбранного {@link RoutingAlgorithm}. Что он делает внутри,
 *       сервис не знает: ему возвращают посчитанных кандидатов.</li>
 *   <li><b>Отбор.</b> Проверка кандидатов по списку правил, выбор до трёх содержательно
 *       разных деревьев и ранжирование по показателю раздела 6. Один алгоритм, одна
 *       доводка, три результата: варианты различаются местами присоединения и тем,
 *       какие дома питаются от какой ветки.</li>
 * </ol>
 *
 * Такое разделение позволяет добавить новый алгоритм рядом и сравнить его с прежним
 * на одних данных и по одной мерке, ничего в них не меняя.
 */
@Service
public class CalculationService {

    /** Запас вокруг сети и точек подключения, в котором ищется трасса, м. */
    private static final double AREA_MARGIN = 150;

    private final RulesService rulesService;
    private final AlgorithmRegistry algorithms;

    public CalculationService(RulesService rulesService, AlgorithmRegistry algorithms) {
        this.rulesService = rulesService;
        this.algorithms = algorithms;
    }

    public CalcResult run(List<RawFeature> features, CalcSettings settings, Consumer<String> progress) {
        long started = System.currentTimeMillis();
        CalcResult result = new CalcResult();
        ProgressLog log = message -> step(progress, result, message);

        AlgorithmContext context = prepare(features, settings, result, log);
        if (context == null) {
            result.millis = System.currentTimeMillis() - started;
            return result;
        }

        RoutingAlgorithm algorithm = algorithms.get(settings.algorithm);
        log.step("Трассировка: алгоритм «" + algorithm.title() + "» (" + algorithm.key() + ")");
        AlgorithmResult plan = algorithm.plan(context, log);
        result.diagnostics.addAll(plan.diagnostics);
        result.plansEvaluated = plan.plansEvaluated;
        result.gridStep = plan.gridStep;
        result.gridAngle = plan.gridAngle;
        result.graphVertices = plan.graphVertices;
        result.graphEdges = plan.graphEdges;

        log.step("Отбор, проверка и ранжирование вариантов: кандидатов " + plan.candidates.size());
        // Проверка по списку правил до отбора: кандидат с нарушением не выдаётся, пока есть чистые.
        ResultChecker checker = new ResultChecker(context.rules, settings);
        for (VariantResult v : plan.candidates) {
            v.warnings.addAll(checker.check(v, v.obstacleSet, context.clearance, context.ownBuildings, context.data));
        }
        List<VariantResult> chosen = choose(plan.candidates, settings);

        // Номер варианта совпадает с рангом: варианты идут по возрастанию показателя раздела 6.
        for (int i = 0; i < chosen.size(); i++) {
            VariantResult v = chosen.get(i);
            v.variantId = String.valueOf(i + 1);
            v.rank = i + 1;
            v.strategy = null;
            for (int n = 0; n < v.segments.size(); n++) {
                v.segments.get(n).id = "new_" + v.variantId + "_" + (n + 1);
            }
            Explainer.explain(v, chosen.get(0), context.data);
            List<String> problems = new ArrayList<>(v.warnings);
            v.warnings.clear();
            v.warnings.addAll(v.planWarnings);
            if (settings.selfCheck) {
                v.warnings.addAll(problems);
            }
        }
        result.variants = chosen;
        result.obstacles = plan.obstacles;
        result.ownBuildings = plan.ownZones;
        result.millis = System.currentTimeMillis() - started;
        log.step("Готово за " + result.millis + " мс");
        return result;
    }

    /**
     * Нормализация входа: разбор, граф существующей сети, препятствия, точки подхода к ОКС.
     * Null, если считать нечего.
     *
     * Всё, что здесь посчитано, одинаково для любого алгоритма трассировки и меняться внутри
     * него не должно — иначе результаты разных алгоритмов нельзя сравнивать.
     */
    public AlgorithmContext prepare(List<RawFeature> features, CalcSettings settings, CalcResult result,
                                    ProgressLog log) {
        AlgorithmContext c = new AlgorithmContext();
        c.rules = rulesService.get();
        c.settings = settings;
        log.step("Разбор объектов и проверка данных");
        c.data = new InputBuilder(c.rules, settings).build(features);
        result.diagnostics.addAll(c.data.diagnostics);
        if (c.data.source == null || c.data.segments.isEmpty() || c.data.points.isEmpty()) {
            log.step("Расчёт невозможен: во входных данных нет источника, сети или точек подключения");
            return null;
        }

        log.step("Сборка существующей сети: узлы и направление к источнику");
        int before = c.data.diagnostics.size();
        c.topology = new TopologyBuilder(settings).build(c.data);
        result.diagnostics.addAll(c.data.diagnostics.subList(before, c.data.diagnostics.size()));

        log.step("Построение препятствий с минимальными расстояниями");
        c.obstacles = new Obstacles(c.data, c.rules, settings);
        c.clearance = new Clearance(c.data, c.obstacles, settings,
                c.obstacles.forDn(c.rules.diameters.get(0).dn).specials());
        for (ConnectionPoint point : c.data.points) {
            Geometry own = Approaches.ownBuilding(point, c.data, settings);
            if (own != null) {
                c.ownBuildings.put(point.id, own);
            }
        }

        log.step("Точки подхода к ОКС: нормали к стенам своего здания");
        c.approaches = Approaches.of(c.data, c.rules, settings, c.clearance);
        for (Map.Entry<String, List<Approach>> e : c.approaches.entrySet()) {
            if (!c.ownBuildings.containsKey(e.getKey())) {
                continue;
            }
            List<Approach> list = e.getValue();
            Approach best = list.isEmpty() ? null : list.get(0);
            if (best == null || !best.normal) {
                result.diagnostics.add(Diagnostic.warning("approach_not_found",
                        "Нормаль к стене построить не удалось, подход придётся искать перебором", e.getKey()));
                continue;
            }
            if (!best.free) {
                // Ни один подход не помещается в минимальные расстояния даже при наименьшем
                // диаметре: законного финального участка у этой точки нет ни при каком Ду.
                result.diagnostics.add(Diagnostic.error("approach_blocked",
                        "Все подходы к точке перекрыты: " + best.blockedBy, e.getKey()));
            } else if (!best.nearest) {
                result.diagnostics.add(Diagnostic.warning("approach_not_nearest",
                        "Подходы через ближайшие стены перекрыты, свободен только подход через стену "
                                + "в " + Math.round(best.footDistance) + " м от точки", e.getKey()));
            }
        }

        int tieIns = c.topology.tieIns.size();
        new TopologyBuilder(settings).addApproachTieIns(c.topology, c.data, c.rules, c.approaches);
        log.step("Кандидаты присоединения напротив домов: " + (c.topology.tieIns.size() - tieIns));

        c.trunkDn = c.rules.byFlow(c.data.totalFlow()).dn;
        for (ConnectionPoint point : c.data.points) {
            c.branchDn = Math.max(c.branchDn, c.rules.byFlow(point.flow).dn);
        }
        c.area = workingArea(c);
        return c;
    }

    /**
     * Отбор вариантов из доведённых кандидатов: лучшие по показателю деревья, каждое —
     * с другими местами присоединения или другой группировкой домов. Одно дерево дважды
     * не выдаётся. Кандидат с нарушением отступов, с замечанием проверки или с
     * нестандартным углом при включённом «только 45° и 90°» не годится.
     */
    private List<VariantResult> choose(List<VariantResult> candidates, CalcSettings settings) {
        candidates.sort(java.util.Comparator.comparingDouble(v -> v.score));
        List<VariantResult> out = new ArrayList<>();
        Set<Integer> usedTrees = new java.util.HashSet<>();
        Set<String> usedSignatures = new java.util.HashSet<>();
        int max = Math.max(1, Math.min(3, settings.variantCount));
        double tolerance = Math.max(1, settings.variantMinDifference);
        // Сначала деревья без камер вплотную друг к другу, потом, если вариантов не хватило,
        // остальные: две камеры в метре друг от друга правилам не противоречат, но инженер
        // начертил бы одну, и такое дерево уступает место чистому.
        for (int pass = 0; pass < 2 && out.size() < max; pass++) {
            for (VariantResult v : candidates) {
                if (out.size() >= max) {
                    break;
                }
                if (v.clearanceViolations > 0 || v.standardViolated || !v.warnings.isEmpty()
                        || (pass == 0 && closeChambers(v))
                        || usedTrees.contains(v.treeIndex) || usedSignatures.contains(v.signature(tolerance))) {
                    continue;
                }
                usedTrees.add(v.treeIndex);
                usedSignatures.add(v.signature(tolerance));
                out.add(v);
            }
        }
        if (out.isEmpty() && !candidates.isEmpty()) {
            out.add(candidates.get(0));
        }
        out.sort(java.util.Comparator.comparingDouble(v -> v.score));
        return out;
    }

    /** Две новые камеры ближе этого — физически одна камера, м. */
    private static final double CHAMBER_PAIR = 3.0;

    private static boolean closeChambers(VariantResult v) {
        for (int i = 0; i < v.chambers.size(); i++) {
            for (int j = i + 1; j < v.chambers.size(); j++) {
                if (v.chambers.get(i).at.distance(v.chambers.get(j).at) < CHAMBER_PAIR) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Рабочая область: существующая сеть, точки подключения и запас вокруг них. */
    private Envelope workingArea(AlgorithmContext c) {
        Envelope area = new Envelope();
        for (Segment s : c.topology.segments) {
            for (Coordinate p : s.pts) {
                area.expandToInclude(p);
            }
        }
        for (ConnectionPoint p : c.data.points) {
            area.expandToInclude(p.at);
        }
        area.expandBy(AREA_MARGIN);
        return area;
    }

    /** Шаг журнала с временем, прошедшим от предыдущего шага: видно, что сколько стоит. */
    private void step(Consumer<String> progress, CalcResult result, String message) {
        long now = System.currentTimeMillis();
        if (result.lastStepMillis > 0) {
            message = message + " (+" + (now - result.lastStepMillis) + " мс)";
        }
        result.lastStepMillis = now;
        result.log.add(message);
        if (progress != null) {
            progress.accept(message);
        }
    }
}
