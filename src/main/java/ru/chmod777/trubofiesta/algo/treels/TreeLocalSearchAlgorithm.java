package ru.chmod777.trubofiesta.algo.treels;

import org.springframework.stereotype.Component;
import ru.chmod777.trubofiesta.algo.AlgorithmContext;
import ru.chmod777.trubofiesta.algo.AlgorithmResult;
import ru.chmod777.trubofiesta.algo.NewNetwork;
import ru.chmod777.trubofiesta.algo.ProgressLog;
import ru.chmod777.trubofiesta.algo.octogrid.GridSetup;
import ru.chmod777.trubofiesta.algo.octogrid.OctoGridAlgorithm;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Та же сетка и то же жадное дерево, что у {@code OCTOGRID}, но улучшается оно иначе:
 * ходами прямо по дереву, а не перестановками в очереди вставки.
 *
 * Что чинится. В {@code OCTOGRID} сосед по локальному поиску — это тот же порядок обхода,
 * в котором один дом переставлен на другое место, и ради этого дерево строится с нуля целиком.
 * Оператор косвенный: связь между «дом номер семь стал третьим» и «ствол пошёл по другой
 * улице» неочевидна, поэтому улучшений мало, а каждая попытка стоит N трассировок. Отсюда
 * и сотни стартов, и минута на дерево.
 *
 * Как чинится. Выемка и пересборка: из готового дерева снимается небольшая группа домов —
 * соседних по местности или питающихся через одну ветку, — а потом они присоединяются заново
 * к тому, что осталось. Один ход стоит столько трассировок, сколько снято домов, то есть
 * в разы меньше полного перестроения, и меняет ровно тот кусок сети, где может быть выигрыш.
 * Группы берутся двумя способами:
 *
 * <ul>
 *   <li><b>по местности</b> — случайный дом и его ближайшие соседи. Так перерешивается,
 *       как квартал заводится на ствол;</li>
 *   <li><b>по ветке</b> — все дома, питающиеся через одно ответвление камеры или места
 *       присоединения. Так перерешивается, куда вообще идёт целая ветка.</li>
 * </ul>
 *
 * Ход принимается, только если показатель стал меньше. Оценка варианта не меняется ни на шаг:
 * стоимость по-прежнему считает {@code Evaluator} по фактической геометрии.
 */
@Component
public class TreeLocalSearchAlgorithm extends OctoGridAlgorithm {

    /** Сколько стартовых деревьев строить перебором порядков: дальше работает локальный поиск. */
    private static final int STARTS = 6;
    /** Столько ходов подряд без улучшения — и этот старт считается исчерпанным. */
    private static final int STALE_LIMIT = 60;
    /** Какую долю домов снимать за один ход, не больше. */
    private static final int RUIN_SHARE = 4;

    @Override
    public String key() {
        return "TREELS";
    }

    @Override
    public String title() {
        return "Сетка в осях застройки, локальный поиск выемкой и пересборкой";
    }

    @Override
    protected List<VariantResult> search(AlgorithmContext context, GridSetup s, AlgorithmResult out, ProgressLog log) {
        CalcSettings settings = context.settings;
        log.step("Стартовые деревья подключения");
        int starts = "GREEDY".equals(settings.planMode) ? 1
                : Math.min(Math.max(1, settings.effectiveStarts(context.data.points.size())), STARTS);
        List<List<ConnectionPoint>> orders = s.planner.orders(starts, settings.seed);
        List<VariantResult> all = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            all.add(s.evaluator.evaluate(s.planner.build(orders.get(i)), "tmp" + i, strategyName(i), s.set));
            out.plansEvaluated++;
        }
        if (!settings.relink || all.isEmpty()) {
            return all;
        }

        List<VariantResult> ranked = new ArrayList<>(all);
        ranked.sort(Comparator.comparingDouble(v -> v.score));
        List<VariantResult> seeds = distinct(ranked, settings, IMPROVE_STARTS);
        int budget = Math.max(60, settings.effectiveStarts(context.data.points.size()) - all.size());
        log.step("Локальный поиск по дереву: бюджет " + budget + " ходов, стартовых деревьев " + seeds.size());
        for (int k = 0; k < seeds.size(); k++) {
            int share = k == 0 ? budget - budget / 2 : (budget / 2) / Math.max(1, seeds.size() - 1);
            out.plansEvaluated += localSearch(s, seeds.get(k), all, share, settings.seed + 1 + k);
        }
        return all;
    }

    /**
     * Выемка и пересборка от одного стартового дерева, пока есть бюджет и ходы улучшают
     * показатель.
     *
     * @return сколько ходов сделано
     */
    protected int localSearch(GridSetup s, VariantResult start, List<VariantResult> all, int budget, long seed) {
        if (budget <= 0 || s.context.data.points.size() < 2 || start.net == null) {
            return 0;
        }
        NewNetwork best = start.net;
        double bestScore = start.score;
        Random random = new Random(seed);
        int used = 0;
        int stale = 0;
        while (used < budget && stale < STALE_LIMIT) {
            NewNetwork candidate = best.copy();
            List<ConnectionPoint> removed = ruin(s, candidate, random);
            if (removed.isEmpty()) {
                stale++;
                continue;
            }
            recreate(s, candidate, removed, random);
            VariantResult v = s.evaluator.evaluate(candidate, "ls" + used, "Выемка и пересборка", s.set);
            used++;
            all.add(v);
            if (v.score < bestScore - 1e-9) {
                best = candidate;
                bestScore = v.score;
                stale = 0;
            } else {
                stale++;
            }
        }
        return used;
    }

    /** Снимает группу домов с дерева и возвращает, кого сняли. */
    private List<ConnectionPoint> ruin(GridSetup s, NewNetwork net, Random random) {
        List<ConnectionPoint> chosen = random.nextBoolean() ? nearby(s, random) : branch(s, net, random);
        List<ConnectionPoint> removed = new ArrayList<>();
        for (ConnectionPoint p : chosen) {
            Integer v = s.houseVertex.get(p.id);
            if (v == null) {
                continue;
            }
            net.unconnected.remove(p.id);
            if (net.degree(v) == 1) {
                net.detach(v);
            }
            removed.add(p);
        }
        net.prune();
        return removed;
    }

    /** Выемка по местности: случайный дом и его ближайшие соседи. */
    private List<ConnectionPoint> nearby(GridSetup s, Random random) {
        List<ConnectionPoint> points = s.context.data.points;
        ConnectionPoint seed = points.get(random.nextInt(points.size()));
        List<ConnectionPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(p -> p.at.distance(seed.at)));
        int most = Math.max(1, points.size() / RUIN_SHARE);
        int take = 1 + random.nextInt(most);
        return new ArrayList<>(sorted.subList(0, Math.min(take, sorted.size())));
    }

    /** Выемка по ветке: все дома, питающиеся через одно ответвление узла. */
    private List<ConnectionPoint> branch(GridSetup s, NewNetwork net, Random random) {
        List<Integer> junctions = new ArrayList<>();
        for (int v : net.vertices) {
            if (net.degree(v) >= 3 || net.tieInAt.containsKey(v)) {
                junctions.add(v);
            }
        }
        if (junctions.isEmpty()) {
            return nearby(s, random);
        }
        int node = junctions.get(random.nextInt(junctions.size()));
        List<Integer> around = net.neighbours(node);
        if (around.isEmpty()) {
            return nearby(s, random);
        }
        Set<String> ids = served(net, node, around.get(random.nextInt(around.size())));
        Map<String, ConnectionPoint> byId = new HashMap<>();
        for (ConnectionPoint p : s.context.data.points) {
            byId.put(p.id, p);
        }
        List<ConnectionPoint> out = new ArrayList<>();
        for (String id : ids) {
            ConnectionPoint p = byId.get(id);
            if (p != null) {
                out.add(p);
            }
        }
        // Снимать всё дерево целиком смысла нет: это и есть построение с нуля.
        return out.isEmpty() || out.size() == s.context.data.points.size() ? nearby(s, random) : out;
    }

    /** Какие точки подключения питаются через ответвление node → via. */
    private Set<String> served(NewNetwork net, int node, int via) {
        Set<String> out = new LinkedHashSet<>();
        Set<Integer> seen = new HashSet<>();
        seen.add(node);
        seen.add(via);
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(via);
        while (!queue.isEmpty()) {
            int v = queue.poll();
            String oks = net.oksAt.get(v);
            if (oks != null) {
                out.add(oks);
            }
            for (int n : net.neighbours(v)) {
                if (seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return out;
    }

    /**
     * Присоединяет снятые дома заново. Порядок либо по убыванию расхода — сначала тот, кто
     * задаёт ствол, — либо случайный: от порядка зависит, какое дерево получится, и оба
     * варианта стоит пробовать.
     */
    private void recreate(GridSetup s, NewNetwork net, List<ConnectionPoint> removed, Random random) {
        List<ConnectionPoint> order = new ArrayList<>(removed);
        if (random.nextBoolean()) {
            order.sort((a, b) -> Double.compare(b.flow, a.flow));
        } else {
            Collections.shuffle(order, random);
        }
        for (ConnectionPoint p : order) {
            s.planner.insert(net, p);
        }
    }
}
