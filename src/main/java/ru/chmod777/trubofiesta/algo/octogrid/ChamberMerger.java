package ru.chmod777.trubofiesta.algo.octogrid;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.chmod777.trubofiesta.algo.NewNetwork;
import ru.chmod777.trubofiesta.approach.Approaches;
import ru.chmod777.trubofiesta.calc.NodeAngles;
import ru.chmod777.trubofiesta.constraints.Clearance;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.TieInCandidate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Слияние близких камер. Жадное дерево ставит камеру там, где очередному дому дешевле всего
 * присоединиться, и две камеры ветвления могут оказаться в нескольких метрах друг от друга.
 * Правилам это не противоречит, но одна камера с четырьмя лучами законна и дешевле.
 *
 * Слияние: нижняя камера перестаёт быть узлом, её ветки переносятся в верхнюю. Ветка
 * подходит к своей прежней линии новым отрезком по оси сетки, поэтому отвод остаётся
 * стандартным, а отвод к дому по-прежнему идёт по нормали к стене. Принимается только
 * слияние, после которого вариант дешевле по показателю и проходит все проверки.
 */
final class ChamberMerger {

    /**
     * Наименьший отрезок от камеры до точки поворота перенесённой ветки, м. Короче правила
     * о ступеньке: две ветки, пришедшие к стволу в метре друг от друга, физически входят
     * в одну камеру размером в несколько метров, и подводка к ней короче трёх метров —
     * это не зигзаг трассы, а разводка в камере.
     */
    private static final double HOST_LEG_MIN = 1.0;

    private final OctoGrid grid;
    private final ObstacleSet obstacles;
    private final Clearance clearance;
    private final Rules rules;
    private final CalcSettings settings;
    private final Map<Integer, TieInCandidate> tieInAtVertex;
    private final Map<String, Geometry> ownBuildings;
    private final Map<String, Geometry> ownZones;
    private final Map<String, String> buildingOfPoint;

    ChamberMerger(OctoGrid grid, ObstacleSet obstacles, Clearance clearance, Rules rules, CalcSettings settings,
                  Map<Integer, TieInCandidate> tieInAtVertex, Map<String, Geometry> ownBuildings,
                  Map<String, Geometry> ownZones, Map<String, String> buildingOfPoint) {
        this.grid = grid;
        this.obstacles = obstacles;
        this.clearance = clearance;
        this.rules = rules;
        this.settings = settings;
        this.tieInAtVertex = tieInAtVertex;
        this.ownBuildings = ownBuildings;
        this.ownZones = ownZones;
        this.buildingOfPoint = buildingOfPoint;
    }

    /**
     * Одно слияние: ближайшая пара камер, которую ещё не пробовали. Возвращает новую сеть
     * или null, если сливать больше нечего. Пара помечается в tried независимо от исхода:
     * принять или отвергнуть результат решает вызывающий по показателю.
     *
     * @param dn           условный диаметр каждого ребра сети
     * @param standardOnly слияние допустимо, только если все углы остаются стандартными
     */
    NewNetwork mergeOne(NewNetwork net, Map<Long, Integer> dn, boolean standardOnly, Set<String> tried) {
        Map<Integer, Integer> parent = parents(net);
        List<int[]> pairs = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (int c2 : net.vertices) {
            if (!isChamber(net, c2) || net.tieInAt.containsKey(c2)) {
                continue;
            }
            double length = 0;
            Integer up = parent.get(c2);
            int prev = c2;
            while (up != null) {
                length += grid.vertex(prev).distance(grid.vertex(up));
                if (length > settings.chamberMergeDistance) {
                    break;
                }
                if (isChamber(net, up) || net.tieInAt.containsKey(up)) {
                    // Нижняя камера в верхнюю и, если верхняя не врезка, верхняя в нижнюю:
                    // какой из двух переносов проходит по геометрии, заранее не известно.
                    if (hostAllowed(net, up) && !tried.contains(up + ">" + c2)) {
                        pairs.add(new int[]{up, c2});
                        distances.add(length);
                    }
                    if (!net.tieInAt.containsKey(up) && !tried.contains(c2 + ">" + up)) {
                        pairs.add(new int[]{c2, up});
                        distances.add(length + 0.001);
                    }
                    break;
                }
                prev = up;
                up = parent.get(up);
            }
        }
        if (pairs.isEmpty()) {
            return null;
        }
        int best = 0;
        for (int i = 1; i < pairs.size(); i++) {
            if (distances.get(i) < distances.get(best)) {
                best = i;
            }
        }
        tried.add(pairs.get(best)[0] + ">" + pairs.get(best)[1]);
        NewNetwork merged = merge(net, dn, parent, pairs.get(best)[0], pairs.get(best)[1], standardOnly);
        if (DEBUG) {
            System.err.printf(java.util.Locale.US, "merge pair %d -> %d at %.1f m: %s%n", pairs.get(best)[1],
                    pairs.get(best)[0], distances.get(best), merged == null ? "no geometry" : "candidate");
        }
        return merged != null ? merged : mergeOne(net, dn, standardOnly, tried);
    }

    /** Отладочная печать слияний в stderr: переменная окружения TRUBO_ALIGN_DEBUG. */
    private static final boolean DEBUG = System.getenv("TRUBO_ALIGN_DEBUG") != null;

    /** Камера ветвления: узел с тремя и более участками, не точка подключения. */
    private boolean isChamber(NewNetwork net, int v) {
        return net.degree(v) >= 3 && !net.oksAt.containsKey(v);
    }

    /**
     * В какую камеру можно перенести ветки: в камеру ветвления или в новую камеру на трубе.
     * В существующую камеру каждая ветка была бы отдельной врезкой за пять миллионов.
     */
    private boolean hostAllowed(NewNetwork net, int v) {
        TieInCandidate c = tieInAtVertex.get(v);
        if (c == null) {
            return isChamber(net, v);
        }
        return c.chamberId == null;
    }

    static Map<Integer, Integer> parents(NewNetwork net) {
        Map<Integer, Integer> parent = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();
        for (int root : net.tieInAt.keySet()) {
            parent.put(root, null);
            queue.add(root);
        }
        Set<Integer> seen = new HashSet<>(net.tieInAt.keySet());
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (int n : net.neighbours(v)) {
                if (seen.add(n)) {
                    parent.put(n, v);
                    queue.add(n);
                }
            }
        }
        return parent;
    }

    /**
     * Переносит ветки камеры c2 в камеру c1. Если c1 выше по дереву, приход в c2 — это
     * звено от родителя; если c1 ниже, приход — звено от c1, и родительская ветка c2
     * становится обычной веткой для переноса.
     */
    private NewNetwork merge(NewNetwork net, Map<Long, Integer> dn, Map<Integer, Integer> parent,
                             int c1, int c2, boolean standardOnly) {
        Integer above = parent.get(c2);
        if (above == null) {
            return null;
        }
        boolean hostBelow = !isAncestor(parent, c1, c2);
        int towardsHost = hostBelow ? neighbourTowards(net, parent, c2, c1) : above;
        if (towardsHost < 0) {
            return null;
        }
        double[] arriving = NodeAngles.direction(grid.vertex(towardsHost), grid.vertex(c2));
        List<Integer> children = new ArrayList<>();
        for (int n : net.neighbours(c2)) {
            if (n != towardsHost) {
                children.add(n);
            }
        }
        // Сквозной луч: ветка, продолжающая приход в камеру. Остальные переезжают.
        int through = -1;
        double bestTurn = Double.MAX_VALUE;
        for (int child : children) {
            double turn = turn(arriving, NodeAngles.direction(grid.vertex(c2), grid.vertex(child)));
            if (turn < bestTurn) {
                bestTurn = turn;
                through = child;
            }
        }
        if (bestTurn > 90 + rules.turn.toleranceDeg || (standardOnly && !rules.turn.standard(bestTurn))) {
            through = -1;
        }
        List<Integer> moved = new ArrayList<>();
        for (int child : children) {
            if (child != through) {
                moved.add(child);
            }
        }
        int existingPipes = tieInAtVertex.containsKey(c1) ? 2 : 0;
        if (net.degree(c1) + moved.size() + existingPipes > settings.chamberMaxBranches) {
            if (DEBUG) {
                System.err.printf("   merge %d -> %d: branch limit, host degree %d, moved %d%n", c2, c1, net.degree(c1), moved.size());
            }
            return null;
        }

        NewNetwork out = net.copy();
        List<double[]> raysAtHost = rays(net, c1, parent);
        for (int x : moved) {
            int branchDn = dn.getOrDefault(NewNetwork.key(c2, x), rules.diameters.get(0).dn);
            List<Integer> chain = chainFrom(net, c2, x);
            // Прямой пробег ветки от камеры до первого поворота: точка поворота ищется на нём,
            // промежуточные вершины сетки на прямой исчезают.
            int run = 1;
            while (run + 1 < chain.size() && Geo.turnDegrees(grid.vertex(chain.get(run - 1)),
                    grid.vertex(chain.get(run)), grid.vertex(chain.get(run + 1))) <= rules.turn.toleranceDeg) {
                run++;
            }
            for (int i = 1; i <= run; i++) {
                out.removeLink(chain.get(i - 1), chain.get(i));
            }
            int end = chain.get(run);
            Coordinate turnPoint = turnPoint(out, c1, c2, end, chain.subList(run, chain.size()), branchDn,
                    raysAtHost, standardOnly);
            if (turnPoint == null) {
                if (DEBUG) {
                    System.err.printf("   merge %d -> %d: no turn point for branch to %d (lead %s, leg %.1f m)%n", c2, c1, end,
                            out.oksAt.containsKey(chain.get(chain.size() - 1)), grid.vertex(c2).distance(grid.vertex(end)));
                }
                return null;
            }
            if (turnPoint.distance(grid.vertex(c1)) < 1e-6) {
                out.addLink(c1, end, grid.vertex(c1).distance(grid.vertex(end)), grid.vertex(c1), grid.vertex(end));
                raysAtHost.add(NodeAngles.direction(grid.vertex(c1), grid.vertex(end)));
                continue;
            }
            int p = existingVertexAt(out, turnPoint);
            if (p < 0) {
                p = grid.addVertex(turnPoint);
            }
            out.addLink(c1, p, grid.vertex(c1).distance(turnPoint), grid.vertex(c1), turnPoint);
            out.addLink(p, end, turnPoint.distance(grid.vertex(end)), turnPoint, grid.vertex(end));
            raysAtHost.add(NodeAngles.direction(grid.vertex(c1), turnPoint));
        }
        if (through < 0) {
            // Продолжать нечем: путь между камерами больше не нужен.
            int v = c2;
            int next = towardsHost;
            while (true) {
                int following = -1;
                for (int n : out.neighbours(next)) {
                    if (n != v) {
                        following = n;
                    }
                }
                out.removeLink(v, next);
                if (next == c1 || out.degree(next) > 0 || following < 0) {
                    break;
                }
                v = next;
                next = following;
            }
        }
        return out;
    }

    private static boolean isAncestor(Map<Integer, Integer> parent, int ancestor, int v) {
        Integer up = parent.get(v);
        while (up != null) {
            if (up == ancestor) {
                return true;
            }
            up = parent.get(up);
        }
        return false;
    }

    /** Сосед вершины from, через который лежит путь вниз по дереву к вершине to. */
    private static int neighbourTowards(NewNetwork net, Map<Integer, Integer> parent, int from, int to) {
        int v = to;
        Integer up = parent.get(v);
        while (up != null) {
            if (up == from) {
                return v;
            }
            v = up;
            up = parent.get(v);
        }
        return -1;
    }

    /** Вершина сети в этой точке, если она уже есть: две вершины в одной точке — это разрыв дерева. */
    int existingVertexAt(NewNetwork out, Coordinate at) {
        for (int v : out.vertices) {
            if (grid.vertex(v).distance(at) < 0.01) {
                return v;
            }
        }
        return -1;
    }

    /** Лучи новой сети в узле: направления всех примыкающих участков от узла наружу. */
    private List<double[]> rays(NewNetwork net, int v, Map<Integer, Integer> parent) {
        List<double[]> out = new ArrayList<>();
        for (int n : net.neighbours(v)) {
            out.add(NodeAngles.direction(grid.vertex(v), grid.vertex(n)));
        }
        return out;
    }

    /** Вершины ветки от узла from через next до следующего узла сети. */
    static List<Integer> chainFrom(NewNetwork net, int from, int next) {
        List<Integer> chain = new ArrayList<>();
        chain.add(from);
        chain.add(next);
        int prev = from;
        int current = next;
        while (net.degree(current) == 2 && !net.oksAt.containsKey(current) && !net.tieInAt.containsKey(current)) {
            int step = -1;
            for (int n : net.neighbours(current)) {
                if (n != prev) {
                    step = n;
                }
            }
            if (step < 0) {
                break;
            }
            chain.add(step);
            prev = current;
            current = step;
        }
        return chain;
    }

    /**
     * Точка поворота на прежней линии ветки: от верхней камеры к ней идёт отрезок по одной
     * из осей сетки. Из всех допустимых берётся ближняя. Ветка к дому не должна повернуть
     * внутри зоны минимального расстояния от здания.
     *
     * @param x    конец прямого пробега ветки от нижней камеры
     * @param rest вершины ветки от x до её конца
     */
    Coordinate turnPoint(NewNetwork out, int c1, int c2, int x, List<Integer> rest, int branchDn,
                                 List<double[]> raysAtHost, boolean standardOnly) {
        Coordinate host = grid.vertex(c1);
        Coordinate from = grid.vertex(c2);
        Coordinate to = grid.vertex(x);
        double legLength = from.distance(to);
        double[] w = NodeAngles.direction(from, to);
        // Ветка кончается в доме: последний отрезок — отвод по нормали, его зона не считается.
        String pointId = out.oksAt.get(rest.get(rest.size() - 1));
        Geometry own = pointId == null ? null : ownBuildings.get(pointId);
        boolean lead = own != null && rest.size() == 1;
        Geometry ownZone = lead ? ownZones.get(pointId) : null;
        double zone = lead ? Approaches.clearance(rules, settings, branchDn) : 0;
        Coordinate hostTieIn = tieInAtVertex.containsKey(c1) ? host : null;
        Coordinate best = null;
        double bestLength = Double.MAX_VALUE;
        // Прямо в камеру, если ветка отклонится от своего направления не больше допуска угла
        // в камере: так и рисуют — два ввода в одну камеру с разных точек её стенки.
        double[] direct = NodeAngles.direction(host, to);
        double deviation = turn(direct, w);
        if (deviation <= rules.turn.chamberToleranceDeg && host.distance(to) >= HOST_LEG_MIN) {
            // Камера — узел, а не поворот трубы, но и она должна стоять за зоной минимального
            // расстояния от здания по норме, без запаса на упрощение границ.
            double chamberZone = zone - Math.max(0, settings.obstacleSimplify);
            boolean ok = !(lead && own.distance(Geo.point(host)) < chamberZone - 0.01)
                    && !obstacles.edge(host, to, ownZone, hostTieIn).blocked
                    && !out.crosses(c1, x, host, to);
            if (ok) {
                List<Coordinate> pts = new ArrayList<>();
                pts.add(host);
                for (int v : rest) {
                    pts.add(grid.vertex(v));
                }
                if (clearance == null || clearance.violation("merge", pts, branchDn, own,
                        pointId == null ? null : buildingOfPoint.get(pointId)) == null) {
                    return host;
                }
            }
        }
        for (int d = 0; d < 8; d++) {
            if (!settings.diagonals && d % 2 == 1) {
                continue;
            }
            double rad = Math.toRadians(45.0 * d);
            double[] u = grid.frame.worldVector(Math.cos(rad), Math.sin(rad));
            double den = u[0] * w[1] - u[1] * w[0];
            if (Math.abs(den) < 1e-9) {
                continue;
            }
            double dx = from.x - host.x;
            double dy = from.y - host.y;
            // host + a·u = from + t·w, решение по Крамеру.
            double a = (dx * w[1] - dy * w[0]) / den;
            double t = (u[1] * dx - u[0] * dy) / den;
            if (a < HOST_LEG_MIN || t <= 0.01 || t >= legLength - 0.01) {
                continue;
            }
            Coordinate p = new Coordinate(from.x + w[0] * t, from.y + w[1] * t);
            double remaining = legLength - t;
            if (!lead && remaining < settings.minStraightRun) {
                continue;
            }
            if (lead && own.distance(Geo.point(p)) < zone - 0.01) {
                continue;
            }
            double bend = turn(u, w);
            if (bend > 90 + rules.turn.toleranceDeg || (standardOnly && !rules.turn.standard(bend))) {
                continue;
            }
            if (standardOnly) {
                boolean ok = true;
                for (double[] ray : raysAtHost) {
                    if (!NodeAngles.standardBetween(ray, u, rules)) {
                        ok = false;
                    }
                }
                if (!ok) {
                    continue;
                }
            }
            if (obstacles.blocked(p) || obstacles.inPolygonZone(p)
                    || obstacles.crossesLineNear(host, p, p, 0.5) || obstacles.crossesLineNear(p, to, p, 0.5)
                    || obstacles.edge(host, p, null, hostTieIn).blocked
                    || obstacles.edge(p, to, ownZone, null).blocked) {
                continue;
            }
            if (out.crosses(c1, -1, host, p) || out.crosses(-1, x, p, to)) {
                continue;
            }
            List<Coordinate> pts = new ArrayList<>();
            pts.add(host);
            pts.add(p);
            for (int v : rest) {
                pts.add(grid.vertex(v));
            }
            if (clearance != null && clearance.violation("merge", pts, branchDn, own,
                    pointId == null ? null : buildingOfPoint.get(pointId)) != null) {
                continue;
            }
            double total = a + remaining;
            if (total < bestLength) {
                bestLength = total;
                best = p;
            }
        }
        if (best == null && !standardOnly) {
            best = shortLink(out, c1, x, from, to, w, legLength, lead, own, ownZone, zone, hostTieIn, branchDn, rest,
                    pointId);
        }
        return best;
    }

    /**
     * Подводка любого направления, если она короче минимального прямого отрезка: две камеры в
     * метре-двух друг от друга — это одна камера, и отрезок от неё до прежней линии ветки — не
     * поворот трассы, а разводка в самой камере. Точка поворота берётся на прежней линии ветки
     * как можно ближе к камере, но за зоной отступа от дома, если ветка — ввод в дом.
     */
    private Coordinate shortLink(NewNetwork out, int c1, int x, Coordinate from, Coordinate to, double[] w,
                                 double legLength, boolean lead, Geometry own, Geometry ownZone, double zone,
                                 Coordinate hostTieIn, int branchDn, List<Integer> rest, String pointId) {
        Coordinate host = grid.vertex(c1);
        // Проекция камеры на линию ветки, зажатая внутрь звена и не ближе трети метра к
        // прежней камере: в самой прежней камере точка поворота легла бы на путь между камерами.
        double t = (host.x - from.x) * w[0] + (host.y - from.y) * w[1];
        t = Math.max(0.3, Math.min(legLength - 0.01, t));
        for (int step = 0; step < 40; step++) {
            Coordinate p = new Coordinate(from.x + w[0] * t, from.y + w[1] * t);
            double a = host.distance(p);
            if (a > settings.minStraightRun) {
                return null;
            }
            boolean ok = a >= HOST_LEG_MIN
                    && (lead || legLength - t >= settings.minStraightRun)
                    && !(lead && own.distance(Geo.point(p)) < zone - 0.01)
                    && turn(NodeAngles.direction(host, p), w) <= 90 + rules.turn.toleranceDeg
                    && !obstacles.blocked(p) && !obstacles.inPolygonZone(p)
                    && !obstacles.edge(host, p, null, hostTieIn).blocked
                    && !obstacles.edge(p, to, ownZone, null).blocked
                    && !out.crosses(c1, -1, host, p) && !out.crosses(-1, x, p, to);
            if (DEBUG) {
                System.err.printf(java.util.Locale.US, "      short link t %.2f a %.2f ok %s: leg %s zone %s turn %.0f blocked %s edge1 %s edge2 %s cross1 %s cross2 %s%n",
                        t, a, ok, a >= HOST_LEG_MIN, !(lead && own.distance(Geo.point(p)) < zone - 0.01),
                        turn(NodeAngles.direction(host, p), w), obstacles.blocked(p) || obstacles.inPolygonZone(p),
                        obstacles.edge(host, p, null, hostTieIn).blocked, obstacles.edge(p, to, ownZone, null).blocked,
                        out.crosses(c1, -1, host, p), out.crosses(-1, x, p, to));
            }
            if (ok) {
                List<Coordinate> pts = new ArrayList<>();
                pts.add(host);
                pts.add(p);
                for (int v : rest) {
                    pts.add(grid.vertex(v));
                }
                if (clearance == null || clearance.violation("merge", pts, branchDn, own,
                        pointId == null ? null : buildingOfPoint.get(pointId)) == null) {
                    return p;
                }
            }
            // Здесь не вышло: точка поворота уходит по звену дальше от прежней камеры.
            t += 0.25;
            if (t > legLength - 0.01) {
                return null;
            }
        }
        return null;
    }

    private static double turn(double[] a, double[] b) {
        double dot = Math.max(-1, Math.min(1, a[0] * b[0] + a[1] * b[1]));
        return Math.toDegrees(Math.acos(dot));
    }
}
