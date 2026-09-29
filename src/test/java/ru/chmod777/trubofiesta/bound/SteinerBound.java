package ru.chmod777.trubofiesta.bound;

import ru.chmod777.trubofiesta.algo.AlgorithmContext;
import ru.chmod777.trubofiesta.algo.AlgorithmResult;
import ru.chmod777.trubofiesta.algo.ProgressLog;
import ru.chmod777.trubofiesta.algo.octogrid.GridSetup;
import ru.chmod777.trubofiesta.algo.octogrid.OctoGridAlgorithm;
import ru.chmod777.trubofiesta.calc.CalcResult;
import ru.chmod777.trubofiesta.calc.CalculationService;

import static ru.chmod777.trubofiesta.Algorithms.registry;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.ingest.GeoJsonReader;
import ru.chmod777.trubofiesta.ingest.RawFeature;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.algo.octogrid.OctoGrid;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.rules.RulesService;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.TieInCandidate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Нижняя граница показателя S для подзадачи из нескольких домов: точное дерево Штейнера
 * на той же сетке, что использует расчёт, алгоритмом Дрейфуса–Вагнера. Ослабления по сравнению
 * с настоящей задачей: нет запретов на повороты круче 90° и ступеньки, ветки могут
 * пересекаться, в существующую камеру можно прийти несколькими ветками за одну врезку. Всё это только уменьшает стоимость, поэтому результат — честная
 * нижняя граница для любого решения на этой сетке.
 *
 * Стоимость ребра зависит от расхода поддерева ниже него: диаметр подбирается по расходу,
 * как в расчёте. Ветвление стоит камеру по диаметру ствола, врезка — по правилам 8.2.
 *
 * Запуск (в контейнере Maven, после test-compile):
 *   java -cp target/classes:target/test-classes:$(cat cp.txt) ru.chmod777.trubofiesta.bound.SteinerBound
 *        data/dataset.geojson 9,10,13,14,15
 * Печатает нижнюю границу и результат обычного расчёта на той же подзадаче.
 */
public final class SteinerBound {

    private SteinerBound() {
    }

    public static void main(String[] args) throws Exception {
        String dataset = args[0];
        List<String> houses = Arrays.asList(args[1].split(","));
        CalcSettings settings = new CalcSettings();
        settings.standardAnglesOnly = true;
        List<RawFeature> all = new ArrayList<>();
        try (InputStream in = Files.newInputStream(Paths.get(dataset))) {
            new GeoJsonReader().read(in, all::add);
        }
        // Подзадача: во входе остаются только выбранные точки подключения.
        List<RawFeature> features = new ArrayList<>();
        for (RawFeature f : all) {
            if ("oks_connection_point".equals(f.objectType) && !houses.contains(f.id)) {
                continue;
            }
            features.add(f);
        }
        CalculationService service = new CalculationService(new RulesService(), registry());
        long t0 = System.currentTimeMillis();
        CalcResult result = service.run(features, settings, null);
        long plannerMillis = System.currentTimeMillis() - t0;
        VariantResult best = result.variants.get(0);
        System.out.println(String.format(java.util.Locale.US,
                "расчёт: S=%.4f стоимость=%.0f длина=%.1f камер=%d присоединений=%d за %d мс",
                best.score, best.calculatedCost, best.newNetworkLength, best.chambers.size(),
                best.attachments.size(), plannerMillis));

        CalcResult scratch = new CalcResult();
        OctoGridAlgorithm algorithm = new OctoGridAlgorithm();
        AlgorithmContext p = service.prepare(features, settings, scratch, ProgressLog.none());
        GridSetup s = algorithm.setup(p, algorithm.frame(p), p.branchDn, new AlgorithmResult(),
                ProgressLog.none(), null);
        t0 = System.currentTimeMillis();
        double bound = bound(p, s);
        System.out.println(String.format(java.util.Locale.US, "нижняя граница на сетке: S=%.4f за %d мс, отрыв %.1f %%",
                bound, System.currentTimeMillis() - t0, (best.score - bound) / bound * 100));
    }

    /** Дрейфус–Вагнер по подмножествам домов; стоимости в единицах S. */
    static double bound(AlgorithmContext p, GridSetup s) {
        Rules rules = p.rules;
        OctoGrid grid = s.grid;
        int n = grid.size();
        List<ConnectionPoint> points = new ArrayList<>();
        for (ConnectionPoint point : p.data.points) {
            if (s.houseVertex.containsKey(point.id)) {
                points.add(point);
            }
        }
        int k = points.size();
        if (k > 8) {
            throw new IllegalArgumentException("Точное решение считается для восьми домов и меньше");
        }
        int subsets = 1 << k;
        double[] flow = new double[subsets];
        for (int mask = 1; mask < subsets; mask++) {
            for (int i = 0; i < k; i++) {
                if ((mask & (1 << i)) != 0) {
                    flow[mask] += points.get(i).flow;
                }
            }
        }
        double spr = rules.scorePerRuble();
        double spm = rules.scorePerMeter();
        float[][] dp = new float[subsets][];
        boolean[] terminal = new boolean[n];
        for (int v = 0; v < n; v++) {
            terminal[v] = grid.isTerminal(v);
        }
        Map<Integer, TieInCandidate> tieAt = new HashMap<>();
        for (TieInCandidate c : p.topology.tieIns) {
            Integer v = s.tieInVertex.get(c.id);
            if (v != null) {
                tieAt.put(v, c);
            }
        }

        for (int mask = 1; mask < subsets; mask++) {
            float[] d = new float[n];
            Arrays.fill(d, Float.POSITIVE_INFINITY);
            int bits = Integer.bitCount(mask);
            if (bits == 1) {
                int i = Integer.numberOfTrailingZeros(mask);
                d[s.houseVertex.get(points.get(i).id)] = 0;
            } else {
                // Слияние двух поддеревьев в узле: камера по диаметру ствола.
                float chamber = (float) (rules.chamberCost(rules.byFlow(flow[mask]).dn) * spr);
                for (int sub = (mask - 1) & mask; sub > 0; sub = (sub - 1) & mask) {
                    int rest = mask ^ sub;
                    if (sub > rest) {
                        continue;
                    }
                    float[] a = dp[sub];
                    float[] b = dp[rest];
                    for (int v = 0; v < n; v++) {
                        if (terminal[v] && !tieAt.containsKey(v)) {
                            continue;
                        }
                        float c = a[v] + b[v] + (tieAt.containsKey(v) ? 0 : chamber);
                        if (c < d[v]) {
                            d[v] = c;
                        }
                    }
                }
            }
            double perMeter = rules.byFlow(flow[mask]).newCost * spr + spm;
            dijkstra(grid, d, terminal, perMeter, bits == 1 ? s.houseVertex.get(points.get(Integer.numberOfTrailingZeros(mask)).id) : -1);
            dp[mask] = d;
        }

        // Корень: врезка. Несколько групп могут идти в разные врезки, каждая со своей ценой.
        double[] best = new double[subsets];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        for (int mask = 1; mask < subsets; mask++) {
            int dn = rules.byFlow(flow[mask]).dn;
            for (Map.Entry<Integer, TieInCandidate> e : tieAt.entrySet()) {
                float at = dp[mask][e.getKey()];
                if (Float.isInfinite(at)) {
                    continue;
                }
                TieInCandidate c = e.getValue();
                // Раздел 3.2: врезка в существующую камеру — 5 млн, новая камера на трубе
                // оплачивается по шкале и уже включает присоединение.
                double cost = c.chamberId != null
                        ? rules.existingChamberTieInCost
                        : rules.chamberCost(Math.max(c.existingDn, dn));
                best[mask] = Math.min(best[mask], at + cost * spr);
            }
            for (int sub = (mask - 1) & mask; sub > 0; sub = (sub - 1) & mask) {
                int rest = mask ^ sub;
                if (sub > rest) {
                    continue;
                }
                best[mask] = Math.min(best[mask], best[sub] + best[rest]);
            }
        }
        return best[subsets - 1];
    }

    /**
     * Дейкстра от всех вершин с конечной стоимостью. Из точек подключения и врезок путь
     * не продолжается: транзитом через дом трасса не идёт, а врезка — конец дерева.
     */
    private static void dijkstra(OctoGrid grid, float[] d, boolean[] terminal, double perMeter, int source) {
        int n = d.length;
        Heap heap = new Heap();
        for (int v = 0; v < n; v++) {
            if (!Float.isInfinite(d[v])) {
                heap.push(d[v], v);
            }
        }
        while (!heap.isEmpty()) {
            float key = heap.topKey();
            int v = heap.pop();
            if (key > d[v]) {
                continue;
            }
            if (terminal[v] && v != source) {
                continue;
            }
            final float here = key;
            grid.forEachEdge(v, (to, length, k, ux, uy, arriveThere, leaveThere) -> {
                if (Double.isNaN(k)) {
                    return;
                }
                float c = (float) (here + length * perMeter * k);
                if (c < d[to]) {
                    d[to] = c;
                    heap.push(c, to);
                }
            });
        }
    }

    /** Двоичная куча на примитивах, как в Router. */
    static final class Heap {
        private float[] keys = new float[1 << 12];
        private int[] values = new int[1 << 12];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        float topKey() {
            return keys[0];
        }

        void push(float key, int value) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                values = Arrays.copyOf(values, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (keys[parent] <= key) {
                    break;
                }
                keys[i] = keys[parent];
                values[i] = values[parent];
                i = parent;
            }
            keys[i] = key;
            values[i] = value;
        }

        int pop() {
            int top = values[0];
            size--;
            if (size > 0) {
                float key = keys[size];
                int value = values[size];
                int i = 0;
                while (true) {
                    int l = 2 * i + 1;
                    if (l >= size) {
                        break;
                    }
                    int c = l + 1 < size && keys[l + 1] < keys[l] ? l + 1 : l;
                    if (keys[c] >= key) {
                        break;
                    }
                    keys[i] = keys[c];
                    values[i] = values[c];
                    i = c;
                }
                keys[i] = key;
                values[i] = value;
            }
            return top;
        }
    }
}
