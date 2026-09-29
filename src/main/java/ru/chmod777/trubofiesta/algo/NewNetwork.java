package ru.chmod777.trubofiesta.algo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Новая сеть как набор рёбер сетки трассировки. Если две трассы идут по одному
 * коридору, они используют одни и те же рёбра, поэтому общий ствол получается сам,
 * а расходы на нём складываются.
 */
public class NewNetwork {

    public final Set<Integer> vertices = new LinkedHashSet<>();
    public final Map<Long, Link> links = new LinkedHashMap<>();
    /** Вершина, в которой выполняется врезка, и её кандидат. */
    public final Map<Integer, String> tieInAt = new LinkedHashMap<>();
    /** Вершина, в которой заканчивается трасса к ОКС. */
    public final Map<Integer, String> oksAt = new LinkedHashMap<>();
    public final List<String> unconnected = new ArrayList<>();
    /** Размер клетки индекса, по которому ищутся пересечения новых участков, м. */
    private static final double CELL = 4.0;
    private final Map<Long, List<double[]>> cells = new HashMap<>();
    private final Map<Integer, List<Integer>> adjacency = new HashMap<>();

    public static long key(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    public void addLink(int a, int b, double length, org.locationtech.jts.geom.Coordinate pa,
                        org.locationtech.jts.geom.Coordinate pb) {
        if (a == b || links.containsKey(key(a, b))) {
            return;
        }
        at.put(a, pa);
        at.put(b, pb);
        double[] segment = {pa.x, pa.y, pb.x, pb.y, a, b};
        for (long cell : cellsOf(pa, pb)) {
            cells.computeIfAbsent(cell, x -> new ArrayList<>()).add(segment);
        }
        links.put(key(a, b), new Link(Math.min(a, b), Math.max(a, b), length));
        vertices.add(a);
        vertices.add(b);
        adjacency.computeIfAbsent(a, x -> new ArrayList<>()).add(b);
        adjacency.computeIfAbsent(b, x -> new ArrayList<>()).add(a);
    }

    /** Убирает ребро. Вершина без рёбер тоже убирается: её больше нет в сети. */
    public void removeLink(int a, int b) {
        if (links.remove(key(a, b)) == null) {
            return;
        }
        for (long cell : cellsOf(linkPoint(a), linkPoint(b))) {
            List<double[]> list = cells.get(cell);
            if (list != null) {
                list.removeIf(s -> ((int) s[4] == a && (int) s[5] == b) || ((int) s[4] == b && (int) s[5] == a));
            }
        }
        adjacency.getOrDefault(a, new ArrayList<>()).remove(Integer.valueOf(b));
        adjacency.getOrDefault(b, new ArrayList<>()).remove(Integer.valueOf(a));
        for (int v : new int[]{a, b}) {
            if (degree(v) == 0) {
                vertices.remove(v);
                adjacency.remove(v);
            }
        }
    }

    /**
     * Убирает трассу одной точки подключения: от её вершины назад до первого узла, который
     * нужен кому-то ещё — развилки, места присоединения или другой точки подключения.
     * Так из дерева исчезает ровно личный отвод этой точки и ничего сверх него.
     *
     * @return узел, на котором отцепление остановилось, или -1, если отцеплять было нечего
     */
    public int detach(int leaf) {
        if (degree(leaf) != 1) {
            return -1;
        }
        String oks = oksAt.remove(leaf);
        int v = leaf;
        while (true) {
            List<Integer> around = neighbours(v);
            if (around.size() != 1) {
                break;
            }
            int next = around.get(0);
            removeLink(v, next);
            if (degree(next) >= 2 || tieInAt.containsKey(next) || oksAt.containsKey(next)) {
                prune();
                return next;
            }
            if (degree(next) == 0) {
                prune();
                return next;
            }
            v = next;
        }
        prune();
        return oks == null ? -1 : v;
    }

    /**
     * Убирает висячие концы: вершину степени один, которая не точка подключения и не место
     * присоединения, держать незачем — это обрубок от снятой ветки. Места присоединения
     * без единого участка тоже перестают быть местами присоединения.
     */
    public void prune() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int v : new ArrayList<>(vertices)) {
                if (degree(v) != 1 || oksAt.containsKey(v) || tieInAt.containsKey(v)) {
                    continue;
                }
                removeLink(v, neighbours(v).get(0));
                changed = true;
            }
        }
        tieInAt.keySet().removeIf(v -> degree(v) == 0);
    }

    private final Map<Integer, org.locationtech.jts.geom.Coordinate> at = new HashMap<>();

    private org.locationtech.jts.geom.Coordinate linkPoint(int v) {
        return at.get(v);
    }

    /** Копия сети: слияние камер пробуется на копии, чтобы неудачную попытку можно было отбросить. */
    public NewNetwork copy() {
        NewNetwork c = new NewNetwork();
        for (Link l : links.values()) {
            c.addLink(l.a, l.b, l.length, at.get(l.a), at.get(l.b));
        }
        c.tieInAt.putAll(tieInAt);
        c.oksAt.putAll(oksAt);
        c.unconnected.addAll(unconnected);
        return c;
    }

    /**
     * Пересекает ли отрезок уже построенную сеть не в общей вершине. Новые участки не должны
     * пересекаться между собой вне общего узла, раздел 2.3 кейса.
     */
    public boolean crosses(int a, int b, org.locationtech.jts.geom.Coordinate pa, org.locationtech.jts.geom.Coordinate pb) {
        for (long cell : cellsOf(pa, pb)) {
            List<double[]> list = cells.get(cell);
            if (list == null) {
                continue;
            }
            for (double[] s : list) {
                int sa = (int) s[4];
                int sb = (int) s[5];
                if ((sa == a && sb == b) || (sa == b && sb == a)) {
                    continue;
                }
                boolean shared = sa == a || sa == b || sb == a || sb == b;
                if (touches(pa.x, pa.y, pb.x, pb.y, s, shared)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Отрезки имеют общую точку, кроме общей вершины. */
    private static boolean touches(double ax, double ay, double bx, double by, double[] s, boolean shared) {
        double cx = s[0];
        double cy = s[1];
        double dx = s[2];
        double dy = s[3];
        double rx = bx - ax;
        double ry = by - ay;
        double qx = dx - cx;
        double qy = dy - cy;
        double den = rx * qy - ry * qx;
        double eps = 1e-6;
        if (Math.abs(den) < 1e-9) {
            // Параллельны: опасно только наложение на одной прямой.
            double cross = (cx - ax) * ry - (cy - ay) * rx;
            if (Math.abs(cross) > 1e-6 * Math.max(1, Math.hypot(rx, ry))) {
                return false;
            }
            double len2 = rx * rx + ry * ry;
            if (len2 < 1e-12) {
                return false;
            }
            double t0 = ((cx - ax) * rx + (cy - ay) * ry) / len2;
            double t1 = ((dx - ax) * rx + (dy - ay) * ry) / len2;
            double lo = Math.max(0, Math.min(t0, t1));
            double hi = Math.min(1, Math.max(t0, t1));
            return hi - lo > eps;
        }
        double t = ((cx - ax) * qy - (cy - ay) * qx) / den;
        double u = ((cx - ax) * ry - (cy - ay) * rx) / den;
        if (t < -eps || t > 1 + eps || u < -eps || u > 1 + eps) {
            return false;
        }
        if (!shared) {
            return true;
        }
        // Общая вершина — это касание в узле, оно разрешено.
        boolean atEndA = t < eps || t > 1 - eps;
        boolean atEndC = u < eps || u > 1 - eps;
        return !(atEndA && atEndC);
    }

    private List<Long> cellsOf(org.locationtech.jts.geom.Coordinate a, org.locationtech.jts.geom.Coordinate b) {
        List<Long> out = new ArrayList<>();
        long x0 = (long) Math.floor(Math.min(a.x, b.x) / CELL);
        long x1 = (long) Math.floor(Math.max(a.x, b.x) / CELL);
        long y0 = (long) Math.floor(Math.min(a.y, b.y) / CELL);
        long y1 = (long) Math.floor(Math.max(a.y, b.y) / CELL);
        for (long x = x0; x <= x1; x++) {
            for (long y = y0; y <= y1; y++) {
                out.add(x * 1_000_003L + y);
            }
        }
        return out;
    }

    public List<Integer> neighbours(int v) {
        List<Integer> list = adjacency.get(v);
        return list == null ? Collections.emptyList() : list;
    }

    public int degree(int v) {
        return neighbours(v).size();
    }

    public Link link(int a, int b) {
        return links.get(key(a, b));
    }

    public double totalLength() {
        double sum = 0;
        for (Link l : links.values()) {
            sum += l.length;
        }
        return sum;
    }

    public static class Link {
        public final int a;
        public final int b;
        public final double length;

        public Link(int a, int b, double length) {
            this.a = a;
            this.b = b;
            this.length = length;
        }
    }
}
