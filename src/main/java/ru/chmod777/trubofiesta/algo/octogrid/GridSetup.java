package ru.chmod777.trubofiesta.algo.octogrid;

import org.locationtech.jts.geom.Geometry;
import ru.chmod777.trubofiesta.algo.AlgorithmContext;
import ru.chmod777.trubofiesta.algo.GeometryRefiner;
import ru.chmod777.trubofiesta.algo.NewNetwork;
import ru.chmod777.trubofiesta.calc.Evaluator;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.TieInCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сетка с лучами, поля стоимости и планировщик для одного класса отступов. Отступы зависят
 * от условного диаметра, поэтому за один расчёт таких наборов бывает несколько: сначала под
 * самый толстый отвод к одному дому, и, если стволу где-то тесно, ещё раз под ствол.
 */
public class GridSetup {

    /** Мир, в котором построена эта сетка. Параметры расчёта берутся отсюда. */
    public AlgorithmContext context;
    /** Класс условного диаметра, под отступы которого построена сетка. */
    public int dn;
    public ObstacleSet set;
    /** Зоны минимального расстояния вокруг своих зданий для этого класса отступов. */
    public Map<String, Geometry> ownZones;
    public OctoGrid grid;
    /** Вершины сетки, в которых стоят кандидаты присоединения и точки подключения. */
    public Map<String, Integer> tieInVertex = new LinkedHashMap<>();
    public Map<String, Integer> houseVertex = new LinkedHashMap<>();
    public Router router;
    /** Поле стоимости до каждой точки подключения: сколько стоит трасса из любой вершины. */
    public Map<String, float[]> fields;
    /**
     * Расход рёбер по ранее построенному дереву, т/ч. Null означает, что о будущих расходах
     * ничего не известно и метр трубы везде стоит полную цену по расходу одной точки.
     * Алгоритмы с итеративным уточнением цены заполняют это поле между заходами поиска.
     */
    public Map<Long, Double> busy;
    public Planner planner;
    public Evaluator evaluator;

    /** Доводчик ломаных: один на все варианты. */
    public GeometryRefiner refiner() {
        return new Straightener(context.rules, context.settings, context.clearance, set);
    }

    /**
     * Слияние близких камер по одной паре, пока это уменьшает показатель. Каждое слияние
     * проверяется полной оценкой: отступы, пересечения, углы. Возвращает лучшее дерево.
     *
     * Жадное дерево ставит камеру там, где очередному дому дешевле всего присоединиться,
     * и две камеры ветвления могут оказаться в нескольких метрах друг от друга. Правилам
     * это не противоречит, но одна камера с четырьмя лучами законна и дешевле.
     */
    public NewNetwork mergeChambers(NewNetwork net) {
        CalcSettings settings = context.settings;
        if (settings.chamberMergeDistance <= 0) {
            return net;
        }
        boolean standardOnly = settings.standardAnglesOnly;
        Map<String, String> buildingOfPoint = new HashMap<>();
        for (ConnectionPoint p : context.data.points) {
            buildingOfPoint.put(p.id, p.buildingId);
        }
        ChamberMerger merger = new ChamberMerger(grid, set, context.clearance, context.rules, settings,
                tieInAtVertex(), context.ownBuildings, ownZones, buildingOfPoint);
        NewNetwork current = net;
        VariantResult best = evaluator.evaluate(current, "merge", set, null);
        Set<String> tried = new HashSet<>();
        for (int guard = 0; guard < 50; guard++) {
            NewNetwork next = merger.mergeOne(current, evaluator.linkDiameters(current), standardOnly, tried);
            if (next == null) {
                break;
            }
            VariantResult candidate = evaluator.evaluate(next, "merge", set, null);
            // До доводки в дереве бывают углы, которые доводка потом выправит, поэтому
            // слияние не должно добавлять нестандартных углов, а не обнулять их.
            boolean anglesOk = !standardOnly || candidate.oddTurns <= best.oddTurns;
            boolean accepted = candidate.clearanceViolations == 0 && anglesOk
                    && candidate.unconnected.size() == best.unconnected.size()
                    && candidate.score < best.score - 1e-9;
            if (DEBUG) {
                System.err.printf(java.util.Locale.US, "merge %s: score %.4f -> %.4f violations %d chambers %d -> %d%n",
                        accepted ? "accepted" : "rejected", best.score, candidate.score, candidate.clearanceViolations,
                        best.chambers.size(), candidate.chambers.size());
            }
            if (accepted) {
                current = next;
                best = candidate;
            }
        }
        return current;
    }

    /** Отладочная печать переносов камер в stderr: переменная окружения TRUBO_ALIGN_DEBUG. */
    private static final boolean DEBUG = System.getenv("TRUBO_ALIGN_DEBUG") != null;
    /** Сколько лучших по быстрой оценке переносов одной камеры проверять доводкой. */
    private static final int ALIGN_REFINED = 3;
    /** Запас быстрой оценки: перенос, который окупается только после доводки, не отсеивается заранее. */
    private static final double ALIGN_SLACK = 0.05;

    /**
     * Камеры ветвления на линию ствола, по одной за шаг, пока есть перенос, который проходит
     * все проверки и не ухудшает показатель. Показатель до доводки итог не предсказывает:
     * заглушка, которую доводка убрала бы и так, после переноса может стать длиннее, поэтому
     * лучшие по быстрой оценке переносы сравниваются уже доведёнными. Допуск на ухудшение —
     * доплата за вид трассы {@code beautyAllowance}: камера напротив дома и ввод одним
     * отрезком стоят инженеру нескольких метров трубы.
     */
    public NewNetwork alignChambers(NewNetwork net) {
        CalcSettings settings = context.settings;
        if (!settings.alignChambers) {
            return net;
        }
        Map<String, String> buildingOfPoint = new HashMap<>();
        for (ConnectionPoint p : context.data.points) {
            buildingOfPoint.put(p.id, p.buildingId);
        }
        ChamberAligner aligner = new ChamberAligner(grid, set, context.clearance, context.rules, settings,
                tieInAtVertex(), context.ownBuildings, ownZones, buildingOfPoint, context.topology::pipeDirections);
        NewNetwork current = net;
        VariantResult quick = evaluator.evaluate(current, "align", set, null);
        VariantResult best = evaluator.evaluate(current, "align", set, refiner());
        double allowance = Math.max(0, settings.beautyAllowance) * context.rules.scorePerRuble();
        Set<Integer> done = new HashSet<>();
        for (int guard = 0; guard < 50; guard++) {
            boolean moved = false;
            for (int chamber : aligner.chambers(current)) {
                if (!done.add(chamber)) {
                    continue;
                }
                List<NewNetwork> candidates = aligner.candidates(current, evaluator.linkDiameters(current), chamber);
                List<VariantResult> screened = new ArrayList<>();
                for (NewNetwork candidate : candidates) {
                    VariantResult r = evaluator.evaluate(candidate, "align", set, null);
                    if (acceptable(r, quick, settings) && r.score <= quick.score + allowance + ALIGN_SLACK) {
                        screened.add(r);
                    }
                }
                screened.sort(java.util.Comparator.comparingDouble(r -> r.score));
                NewNetwork bestNet = null;
                VariantResult bestResult = null;
                if (DEBUG) {
                    System.err.printf(java.util.Locale.US, "align chamber %d at %s: candidates %d, screened %d, quick %.4f best %.4f%n",
                            chamber, grid.vertex(chamber), candidates.size(), screened.size(), quick.score, best.score);
                }
                for (VariantResult r : screened.subList(0, Math.min(ALIGN_REFINED, screened.size()))) {
                    VariantResult refined = evaluator.evaluate(r.net, "align", set, refiner());
                    if (DEBUG) {
                        System.err.printf(java.util.Locale.US, "   quick %.4f refined %.4f violations %d odd %d%n",
                                r.score, refined.score, refined.clearanceViolations, refined.oddTurns);
                    }
                    if (acceptable(refined, best, settings) && refined.score <= best.score + allowance + 1e-9
                            && (bestResult == null || refined.score < bestResult.score - 1e-9)) {
                        bestNet = r.net;
                        bestResult = refined;
                    }
                }
                if (bestNet != null) {
                    // Новая камера — новая вершина: второй раз её не двигаем.
                    for (int v : aligner.chambers(bestNet)) {
                        if (!current.vertices.contains(v)) {
                            done.add(v);
                        }
                    }
                    current = bestNet;
                    best = bestResult;
                    quick = evaluator.evaluate(current, "align", set, null);
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                break;
            }
        }
        return current;
    }

    /** Перенос годится: правила выполнены, никто не отключился, нестандартных углов не прибавилось. */
    private static boolean acceptable(VariantResult candidate, VariantResult current, CalcSettings settings) {
        boolean anglesOk = !settings.standardAnglesOnly || candidate.oddTurns <= current.oddTurns;
        return candidate.clearanceViolations == 0 && anglesOk
                && candidate.unconnected.size() == current.unconnected.size();
    }

    /** Кандидаты присоединения по вершинам сетки, в которых они стоят. */
    public Map<Integer, TieInCandidate> tieInAtVertex() {
        Map<String, TieInCandidate> byId = new HashMap<>();
        for (TieInCandidate c : context.topology.tieIns) {
            byId.put(c.id, c);
        }
        Map<Integer, TieInCandidate> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : tieInVertex.entrySet()) {
            out.put(e.getValue(), byId.get(e.getKey()));
        }
        return out;
    }
}
