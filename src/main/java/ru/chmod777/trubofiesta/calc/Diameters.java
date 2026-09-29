package ru.chmod777.trubofiesta.calc;

import ru.chmod777.trubofiesta.rules.Rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Подбор условного диаметра для дерева новой сети, раздел 2.3 и ответы 1–2.
 *
 * Порядок такой: минимальный Ду по расходу, затем подъём до диаметра, который проходит
 * и по предельной длине непрерывного пути, затем выравнивание — от точки подключения
 * к месту присоединения Ду не убывает. Подъём делается сразу до первого подходящего
 * диаметра, на сколько бы ступеней это ни пришлось; произвольное завышение запрещено.
 *
 * Одна реализация на оба пути расчёта: автоматический подбор и загруженная схема должны
 * давать на одинаковом дереве одинаковые диаметры.
 */
public final class Diameters {

    private Diameters() {
    }

    /** Ребро дерева: узел со стороны дома, узел со стороны сети, длина и расход. */
    public static class Edge {
        public final int node;
        public final int parent;
        public final double length;
        public final double flow;
        public int dn;

        public Edge(int node, int parent, double length, double flow) {
            this.node = node;
            this.parent = parent;
            this.length = length;
            this.flow = flow;
        }
    }

    /**
     * @param byPath предельная длина считается по каждому непрерывному пути (ответ 2);
     *               иначе — как сумма всех участков одного Ду, включая ветки
     * @return замечания, если предел не удалось выдержать даже на наибольшем диаметре
     */
    public static List<String> choose(Rules rules, boolean byPath, boolean upsize, List<Edge> edges) {
        List<String> warnings = new ArrayList<>();
        for (Edge e : edges) {
            e.dn = rules.byFlow(e.flow).dn;
        }
        Map<Integer, List<Edge>> children = new LinkedHashMap<>();
        for (Edge e : edges) {
            children.computeIfAbsent(e.parent, k -> new ArrayList<>()).add(e);
        }
        for (int pass = 0; pass < rules.diameters.size() + 2; pass++) {
            boolean changed = nonDecreasing(edges, children);
            changed |= lengthLimit(rules, byPath, upsize, edges, children, warnings,
                    pass == rules.diameters.size() + 1);
            if (!changed) {
                break;
            }
        }
        return warnings;
    }

    /** По направлению к месту присоединения условный диаметр не уменьшается. */
    private static boolean nonDecreasing(List<Edge> edges, Map<Integer, List<Edge>> children) {
        boolean any = false;
        for (int guard = 0; guard < edges.size() + 1; guard++) {
            boolean changed = false;
            for (Edge e : edges) {
                for (Edge child : children.getOrDefault(e.node, java.util.Collections.emptyList())) {
                    if (child.dn > e.dn) {
                        e.dn = child.dn;
                        changed = true;
                    }
                }
            }
            any |= changed;
            if (!changed) {
                return any;
            }
        }
        return any;
    }

    /**
     * Непрерывная часть одного Ду не длиннее предельной длины. Часть — связное множество
     * рёбер одного диаметра; её длина считается по самому длинному пути внутри неё или
     * как сумма, в зависимости от выбранного режима.
     */
    private static boolean lengthLimit(Rules rules, boolean byPath, boolean upsize, List<Edge> edges,
                                       Map<Integer, List<Edge>> children, List<String> warnings, boolean last) {
        boolean changed = false;
        Set<Edge> seen = new HashSet<>();
        for (Edge start : edges) {
            if (!seen.add(start)) {
                continue;
            }
            List<Edge> component = component(start, edges, children, seen);
            double limit = rules.byDn(start.dn).maxLength;
            double actual = byPath ? longestPath(component, children) : sum(component);
            if (actual <= limit + rules.roundingTolerance) {
                continue;
            }
            double maxFlow = 0;
            for (Edge e : component) {
                maxFlow = Math.max(maxFlow, e.flow);
            }
            int next = rules.byFlowAndLength(maxFlow, actual).dn;
            if (!upsize || next <= start.dn) {
                if (last || next <= start.dn || !upsize) {
                    warnings.add("Непрерывная часть Ду" + start.dn + " длиной " + Math.round(actual)
                            + " м превышает предельную длину " + Math.round(limit) + " м");
                }
                continue;
            }
            for (Edge e : component) {
                e.dn = next;
            }
            changed = true;
        }
        return changed;
    }

    /**
     * Связное множество рёбер того же условного диаметра: вниз по веткам, вверх к сети и
     * вбок — к соседним веткам из того же узла. Камера отсчёт не начинает, поэтому труба
     * одного Ду продолжается через неё во все стороны.
     */
    private static List<Edge> component(Edge start, List<Edge> edges, Map<Integer, List<Edge>> children,
                                        Set<Edge> seen) {
        Map<Integer, Edge> above = new HashMap<>();
        for (Edge e : edges) {
            above.put(e.node, e);
        }
        List<Edge> out = new ArrayList<>();
        Deque<Edge> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Edge e = queue.poll();
            out.add(e);
            List<Edge> neighbours = new ArrayList<>(children.getOrDefault(e.node, java.util.Collections.emptyList()));
            neighbours.addAll(children.getOrDefault(e.parent, java.util.Collections.emptyList()));
            Edge parent = above.get(e.parent);
            if (parent != null) {
                neighbours.add(parent);
            }
            for (Edge n : neighbours) {
                if (n.dn == e.dn && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return out;
    }

    private static double sum(List<Edge> component) {
        double total = 0;
        for (Edge e : component) {
            total += e.length;
        }
        return total;
    }

    /**
     * Самый длинный непрерывный путь внутри части. Общий участок разветвлённой сети входит
     * в каждый путь, параллельные ветви между собой не суммируются.
     */
    private static double longestPath(List<Edge> component, Map<Integer, List<Edge>> children) {
        Set<Edge> inPart = new HashSet<>(component);
        Map<Edge, Double> down = new HashMap<>();
        double longest = 0;
        for (Edge e : component) {
            longest = Math.max(longest, down(e, inPart, children, down));
        }
        return longest;
    }

    private static double down(Edge e, Set<Edge> inPart, Map<Integer, List<Edge>> children, Map<Edge, Double> memo) {
        Double ready = memo.get(e);
        if (ready != null) {
            return ready;
        }
        // Защита от повторного входа на случай испорченного дерева с циклом.
        memo.put(e, e.length);
        double best = 0;
        for (Edge child : children.getOrDefault(e.node, java.util.Collections.emptyList())) {
            if (inPart.contains(child)) {
                best = Math.max(best, down(child, inPart, children, memo));
            }
        }
        double value = e.length + best;
        memo.put(e, value);
        return value;
    }
}
