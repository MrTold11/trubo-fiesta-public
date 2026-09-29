package ru.chmod777.trubofiesta.algo.octogrid;

import ru.chmod777.trubofiesta.rules.Rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Поиск трассы по сетке с учётом поворотов. Цена пути — метры трубы и повороты,
 * всё в единицах показателя S: так поиск сравнивает деньги и длину по той же формуле,
 * по которой оценивается вариант.
 *
 * Для каждого дома один раз считается поле: сколько стоит дотянуть трубу до этого дома
 * из любой вершины сетки. После этого выбрать, откуда подключать дом — к уже построенной
 * сети или новой врезкой, — значит просто сравнить числа в поле. Сама трасса затем
 * восстанавливается коротким поиском по этому полю.
 */
public class Router {

    private static final int START = OctoGrid.STATES;
    private static final int KEY = OctoGrid.STATES + 1;
    /**
     * Кроме вершины и направления, состояние помнит, сколько метров не хватает прямому
     * отрезку после поворота до минимальной длины, с округлением вверх. Поворот, пока
     * не хватает хотя бы метра, — ступенька, и она дорогая.
     */
    private static final int MAX_LEVELS = 8;
    private static final double TURN_DOT = Math.cos(Math.toRadians(0.5));
    /** Предел раскрытий при восстановлении трассы: защита от зацикливания на огромных данных. */
    private static final int MAX_EXPANDED = 3_000_000;

    private final OctoGrid grid;
    private final Rules.TurnRules turn;

    public Router(OctoGrid grid, Rules.TurnRules turn) {
        this.grid = grid;
        this.turn = turn;
    }

    /** Цена трубы для одного дома в единицах S. */
    public static class Price {
        /** Деньги за метр трубы, переведённые в S. */
        public double money;
        /** Длина за метр, переведённая в S. */
        public double meter;
        /** Цена одного поворота трассы. */
        public double bend;
        /** Доплата за ступеньку: два поворота с коротким отрезком между ними. */
        public double jog;
        /** Отрезок между поворотами короче этого — ступенька, м. */
        public double minRun;
        /**
         * Цена метра на конкретном ребре сетки; null — везде одна и та же.
         *
         * Нужна алгоритмам, которые знают, что по части рёбер уже пойдёт чужой расход:
         * метр трубы, которая там и так будет, стоит для этого дома не полную цену,
         * а только разницу между диаметрами — иногда и вовсе ноль.
         */
        public EdgeMoney edgeMoney;

        /** Полная цена метра на ребре в единицах S, включая длину и коэффициент спецпрохода. */
        public interface EdgeMoney {
            double perMetre(int a, int b, double k);
        }

        double edge(int from, int to, double length, double k) {
            if (edgeMoney != null) {
                return length * edgeMoney.perMetre(from, to, k);
            }
            return length * (money * k + meter);
        }

        int levels() {
            return Math.min(MAX_LEVELS, (int) Math.ceil(Math.max(0, minRun)) + 1);
        }

        /** Сколько метров не хватает отрезку длиной length до минимального, с округлением вверх. */
        int shortage(double length) {
            return length >= minRun ? 0 : Math.min(levels() - 1, (int) Math.ceil(minRun - length - 1e-9));
        }
    }

    /**
     * Цена поворота между направлениями; NaN, если поворот круче предельного угла.
     * Удорожания за нестандартный угол нет (раздел 2.1), поэтому цена у всех поворотов одна.
     */
    double bend(double inX, double inY, double outX, double outY, Price price) {
        double dot = Math.max(-1, Math.min(1, inX * outX + inY * outY));
        double deg = Math.toDegrees(Math.acos(dot));
        if (deg < 0.5) {
            return 0;
        }
        if (deg > turn.maxDeg + turn.toleranceDeg) {
            return Double.NaN;
        }
        return price.bend;
    }

    private boolean turns(double inX, double inY, double outX, double outY) {
        return inX * outX + inY * outY < TURN_DOT;
    }

    /**
     * Поле стоимости до дома: для каждой вершины — сколько стоит трасса от неё до точки
     * подключения. Дальше limit не считаем: такой дом выгоднее не подключать.
     *
     * Состояние — вершина, направление, в котором из неё уходим, и признак: отрезок в этом
     * направлении короткий и кончается поворотом. Поворот перед таким отрезком даёт ступеньку.
     */
    public float[] field(int target, Price price, double limit) {
        int n = grid.size();
        final int levels = price.levels();
        float[] dist = new float[n * OctoGrid.STATES * levels];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        float[] best = new float[n];
        Arrays.fill(best, Float.POSITIVE_INFINITY);
        Heap heap = new Heap();

        grid.forEachEdge(target, (to, length, k, ux, uy, arriveThere, leaveThere) -> {
            if (Double.isNaN(k)) {
                return;
            }
            int state = (to * OctoGrid.STATES + leaveThere) * levels;
            float cost = (float) price.edge(target, to, length, k);
            if (cost < dist[state]) {
                dist[state] = cost;
                heap.push(cost, state);
            }
        });

        double[] out = new double[2];
        while (!heap.isEmpty()) {
            float d = heap.topKey();
            int state = heap.pop();
            if (d > dist[state]) {
                continue;
            }
            if (d > limit) {
                break;
            }
            final int ahead = state % levels;
            int directional = state / levels;
            int w = directional / OctoGrid.STATES;
            if (d < best[w]) {
                best[w] = d;
            }
            if (grid.isTerminal(w)) {
                continue;
            }
            grid.vector(w, directional % OctoGrid.STATES, out);
            final double outX = out[0];
            final double outY = out[1];
            final float here = d;
            grid.forEachEdge(w, (to, length, k, ux, uy, arriveThere, leaveThere) -> {
                if (Double.isNaN(k) || to == target) {
                    return;
                }
                double turnCost = bend(-ux, -uy, outX, outY, price);
                if (Double.isNaN(turnCost)) {
                    return;
                }
                boolean turned = turns(-ux, -uy, outX, outY);
                // Спецпроход — один прямой участок: внутри зоны дороги, путей, газопровода
                // или кабеля трасса не поворачивает.
                if (turned && grid.noTurn(w, directional % OctoGrid.STATES, to)) {
                    return;
                }
                double jog = turned && ahead > 0 ? price.jog : 0;
                int level = turned ? price.shortage(length) : Math.max(0, (int) Math.ceil(ahead - length - 1e-9));
                float cost = (float) (here + price.edge(w, to, length, k) + turnCost + jog);
                int next = (to * OctoGrid.STATES + leaveThere) * levels + level;
                if (cost < dist[next]) {
                    dist[next] = cost;
                    heap.push(cost, next);
                }
            });
        }
        return best;
    }

    /** Что уже занято построенной сетью: пересекать её участки вне общего узла нельзя. */
    public interface Occupied {
        boolean crosses(int a, int b);
    }

    /**
     * Трасса от вершины до дома. Поле служит подсказкой: поиск идёт почти прямо
     * по самому дешёвому пути и почти ничего лишнего не раскрывает.
     *
     * @return вершины от source до target или пустой список
     */
    public List<Integer> path(int source, int target, Price price, float[] field, Occupied occupied, int maxExpanded) {
        Map<Integer, Double> g = new HashMap<>();
        Map<Integer, Integer> parent = new HashMap<>();
        Heap open = new Heap();
        final int levels = price.levels();
        int start = (source * KEY + START) * levels;
        g.put(start, 0.0);
        open.push(field[source], start);
        double[] in = new double[2];
        int expanded = 0;

        while (!open.isEmpty()) {
            float f = open.topKey();
            int state = open.pop();
            final int behind = state % levels;
            int directional = state / levels;
            int v = directional / KEY;
            int dir = directional % KEY;
            double gv = g.get(state);
            double h = v == target ? 0 : field[v];
            if (f > gv + h + 1e-5) {
                continue;
            }
            if (v == target) {
                return unwind(state, parent, levels);
            }
            if (v != source && grid.isTerminal(v)) {
                continue;
            }
            if (++expanded > Math.min(MAX_EXPANDED, maxExpanded)) {
                break;
            }
            boolean fresh = dir == START;
            if (!fresh) {
                grid.vector(v, dir, in);
            }
            final double inX = in[0];
            final double inY = in[1];
            grid.forEachEdge(v, (to, length, k, ux, uy, arriveThere, leaveThere) -> {
                if (Double.isNaN(k)) {
                    return;
                }
                boolean goal = to == target;
                if (!goal && (grid.isTerminal(to) || Float.isInfinite(field[to]))) {
                    return;
                }
                if (occupied != null && occupied.crosses(v, to)) {
                    return;
                }
                double turnCost = fresh ? 0 : bend(inX, inY, ux, uy, price);
                if (Double.isNaN(turnCost)) {
                    return;
                }
                boolean turned = !fresh && turns(inX, inY, ux, uy);
                if (turned && grid.noTurn(v, OctoGrid.opposite(dir), to)) {
                    return;
                }
                double jog = turned && behind > 0 ? price.jog : 0;
                int level = fresh ? 0 : turned ? price.shortage(length)
                        : Math.max(0, (int) Math.ceil(behind - length - 1e-9));
                double cost = gv + price.edge(v, to, length, k) + turnCost + jog;
                int next = (to * KEY + arriveThere) * levels + level;
                Double old = g.get(next);
                if (old == null || cost < old - 1e-9) {
                    g.put(next, cost);
                    parent.put(next, state);
                    open.push((float) (cost + (goal ? 0 : field[to])), next);
                }
            });
        }
        return new ArrayList<>();
    }

    private List<Integer> unwind(int state, Map<Integer, Integer> parent, int levels) {
        List<Integer> out = new ArrayList<>();
        Integer at = state;
        while (at != null) {
            int v = at / levels / KEY;
            if (out.isEmpty() || out.get(out.size() - 1) != v) {
                out.add(v);
            }
            at = parent.get(at);
        }
        Collections.reverse(out);
        return out;
    }

    /** Двоичная куча на примитивах: поле строится по миллионам состояний. */
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
                int p = (i - 1) >>> 1;
                if (keys[p] <= key) {
                    break;
                }
                keys[i] = keys[p];
                values[i] = values[p];
                i = p;
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
