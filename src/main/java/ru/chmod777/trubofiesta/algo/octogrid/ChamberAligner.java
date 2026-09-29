package ru.chmod777.trubofiesta.algo.octogrid;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.chmod777.trubofiesta.algo.NewNetwork;
import ru.chmod777.trubofiesta.approach.Approaches;
import ru.chmod777.trubofiesta.calc.NodeAngles;
import ru.chmod777.trubofiesta.constraints.Clearance;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.TieInCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Камера на линии ствола. Жадное дерево ставит камеру ветвления там, где к стволу подошла
 * очередная трасса: на вершине луча рядом с линией сетки или на изломе ствола. Отсюда
 * заглушки в метр-два у камеры и горбы ствола вокруг неё. Инженер ведёт ствол прямо,
 * ставит камеру на нём напротив дома и пускает ввод одним отрезком.
 *
 * Перенос: камера переезжает в точку, где линия ветки (для ввода в дом — нормаль к стене)
 * пересекает прямую одного из звеньев ствола. Ствол проходит через новую камеру прямо,
 * ветка приходит в неё одним отрезком, остальные ветки подводятся так же, как при слиянии
 * камер. Если новая точка лежит за пределами звена, соседнее звено ствола продлевается до
 * её прямой, а вершины между ними исчезают. Здесь только геометрия и быстрые проверки:
 * принять перенос или отвергнуть решает вызывающий по полной оценке варианта.
 */
final class ChamberAligner {

    /** Дальше этого камеру не двигаем, м: перенос — местная правка, а не новое дерево. */
    private static final double MAX_SHIFT = 30.0;
    /** Точка ближе этого к вершине звена считается самой вершиной, м. */
    private static final double AT_VERTEX = 0.05;
    /** Наименьшее звено от камеры до узла или до подводки перенесённой ветки, м. */
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
    private final Function<Coordinate, List<double[]>> pipes;
    private final ChamberMerger merger;

    /** Сеть и диаметры её рёбер, для которых сейчас строятся кандидаты. */
    private NewNetwork net;
    private Map<Long, Integer> dn;

    /**
     * @param pipes направления существующих труб в точке присоединения: от неё ствол уходит
     *              не положе минимального угла
     */
    ChamberAligner(OctoGrid grid, ObstacleSet obstacles, Clearance clearance, Rules rules, CalcSettings settings,
                   Map<Integer, TieInCandidate> tieInAtVertex, Map<String, Geometry> ownBuildings,
                   Map<String, Geometry> ownZones, Map<String, String> buildingOfPoint,
                   Function<Coordinate, List<double[]>> pipes) {
        this.grid = grid;
        this.obstacles = obstacles;
        this.clearance = clearance;
        this.rules = rules;
        this.settings = settings;
        this.tieInAtVertex = tieInAtVertex;
        this.ownBuildings = ownBuildings;
        this.ownZones = ownZones;
        this.buildingOfPoint = buildingOfPoint;
        this.pipes = pipes;
        this.merger = new ChamberMerger(grid, obstacles, clearance, rules, settings, tieInAtVertex, ownBuildings,
                ownZones, buildingOfPoint);
    }

    /** Точка ломаной: вершина сети, если она уже есть, иначе только координата. */
    private static final class Pt {
        int vertex;
        final Coordinate at;

        Pt(int vertex, Coordinate at) {
            this.vertex = vertex;
            this.at = at;
        }
    }

    /** Новый ствол и место камеры в нём. */
    private static final class Trunk {
        final List<Pt> pts = new ArrayList<>();
        int chamber;
    }

    /** Прямая, на которой должна оказаться камера, и куда ветка от неё придёт. */
    private static final class Line {
        /** Единичное направление от камеры к опорной вершине. */
        final double[] dir;
        /** Опорная вершина ветки: первый поворот, конец второго звена или сама точка подключения. */
        final int anchor;
        /** Ветка по поворотам от опорной вершины до конца. */
        final List<Integer> tail;
        /** Ввод в дом одним отрезком по нормали: камера должна стоять за зоной своего здания. */
        final boolean lead;

        Line(double[] dir, int anchor, List<Integer> tail, boolean lead) {
            this.dir = dir;
            this.anchor = anchor;
            this.tail = tail;
            this.lead = lead;
        }
    }

    /** Камеры ветвления: узлы с тремя и более участками, не точки подключения и не присоединения. */
    List<Integer> chambers(NewNetwork net) {
        List<Integer> out = new ArrayList<>();
        for (int v : net.vertices) {
            if (net.degree(v) >= 3 && !net.oksAt.containsKey(v) && !net.tieInAt.containsKey(v)) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * Все допустимые переносы одной камеры, каждый — отдельная сеть. Какой из них взять
     * и брать ли вообще, решает вызывающий по показателю.
     *
     * @param dn условный диаметр каждого ребра сети
     */
    List<NewNetwork> candidates(NewNetwork net, Map<Long, Integer> dn, int c) {
        this.net = net;
        this.dn = dn;
        List<NewNetwork> out = new ArrayList<>();
        Map<Integer, Integer> parent = ChamberMerger.parents(net);
        Integer up = parent.get(c);
        if (up == null) {
            return out;
        }
        List<Integer> upChain = ChamberMerger.chainFrom(net, c, up);
        List<List<Integer>> children = new ArrayList<>();
        for (int n : net.neighbours(c)) {
            if (n != up) {
                children.add(ChamberMerger.chainFrom(net, c, n));
            }
        }
        double[] arriving = NodeAngles.direction(grid.vertex(upChain.get(1)), grid.vertex(c));
        int dnUp = dnOf(c, up);
        for (List<Integer> branch : children) {
            // Ствол продолжает любая из остальных веток, в которую приход поворачивает не круче
            // предельного угла, или никакая: заранее не известно, что из этого окажется стволом,
            // а что подводкой. Какой вариант лучше, покажет показатель.
            List<List<Integer>> throughs = new ArrayList<>();
            throughs.add(null);
            for (List<Integer> chain : children) {
                double turn = NodeAngles.rayAngle(arriving, NodeAngles.direction(grid.vertex(c), grid.vertex(chain.get(1))));
                if (chain != branch && turn <= rules.turn.maxDeg + rules.turn.toleranceDeg) {
                    throughs.add(chain);
                }
            }
            int branchDn = dnOf(c, branch.get(1));
            for (List<Integer> through : throughs) {
                // Ствол: от верхнего узла через камеру до нижнего, только повороты.
                List<Integer> trunkChain = new ArrayList<>(upChain);
                Collections.reverse(trunkChain);
                if (through != null) {
                    trunkChain.addAll(through.subList(1, through.size()));
                }
                List<Integer> trunk = turns(trunkChain);
                int dnDown = through == null ? dnUp : dnOf(c, through.get(1));
                for (Line line : lines(branch)) {
                    for (int k = 0; k + 1 < trunk.size(); k++) {
                        Trunk moved = trunkThrough(trunk, k, line, through != null, branchDn, branch, grid.vertex(c));
                        NewNetwork candidate = moved == null ? null : move(trunkChain, moved, c, children, through,
                                branch, line, branchDn, dnUp, dnDown);
                        if (candidate != null) {
                            out.add(candidate);
                        }
                    }
                }
            }
        }
        return out;
    }

    private int dnOf(int a, int b) {
        return dn.getOrDefault(NewNetwork.key(a, b), rules.diameters.get(0).dn);
    }

    /** Вершины ломаной, в которых она поворачивает, плюс концы; изломы в пределах допуска — не повороты. */
    private List<Integer> turns(List<Integer> chain) {
        List<Integer> out = new ArrayList<>();
        out.add(chain.get(0));
        for (int i = 1; i < chain.size() - 1; i++) {
            Coordinate a = grid.vertex(out.get(out.size() - 1));
            if (Geo.turnDegrees(a, grid.vertex(chain.get(i)), grid.vertex(chain.get(i + 1))) > rules.turn.toleranceDeg) {
                out.add(chain.get(i));
            }
        }
        out.add(chain.get(chain.size() - 1));
        return out;
    }

    /**
     * Прямые, на которые можно поставить камеру ради этой ветки: линия её первого звена;
     * линия второго звена, если первое — заглушка короче минимального отрезка; для ввода
     * в дом — нормаль к стене, тогда ввод становится одним отрезком.
     */
    private List<Line> lines(List<Integer> branch) {
        List<Line> out = new ArrayList<>();
        List<Integer> pts = turns(branch);
        int end = pts.get(pts.size() - 1);
        boolean lead = net.oksAt.containsKey(end);
        Coordinate b0 = grid.vertex(pts.get(0));
        Coordinate b1 = grid.vertex(pts.get(1));
        out.add(new Line(NodeAngles.direction(b0, b1), pts.get(1), pts.subList(1, pts.size()), lead && pts.size() == 2));
        if (pts.size() >= 3 && b0.distance(b1) < settings.minStraightRun) {
            Coordinate b2 = grid.vertex(pts.get(2));
            out.add(new Line(NodeAngles.direction(b1, b2), pts.get(2), pts.subList(2, pts.size()), lead && pts.size() == 3));
        }
        if (lead && out.get(out.size() - 1).anchor != end) {
            Coordinate before = grid.vertex(pts.get(pts.size() - 2));
            out.add(new Line(NodeAngles.direction(before, grid.vertex(end)), end,
                    pts.subList(pts.size() - 1, pts.size()), true));
        }
        return out;
    }

    /**
     * Ствол, проходящий через новую камеру на прямой звена k. Внутри звена камера просто
     * встаёт на него. За его концом соседнее звено продлевается до прямой звена k, а вершины
     * между ними исчезают; берётся ближайшее звено, которое так продлить можно.
     *
     * @return новый ствол или null, если камеру на эту прямую не поставить
     */
    private Trunk trunkThrough(List<Integer> trunk, int k, Line line, boolean hasThrough, int branchDn,
                               List<Integer> branch, Coordinate old) {
        int n = trunk.size() - 1;
        Coordinate tk = grid.vertex(trunk.get(k));
        Coordinate tk1 = grid.vertex(trunk.get(k + 1));
        double len = tk.distance(tk1);
        double[] d = NodeAngles.direction(tk, tk1);
        Coordinate anchor = grid.vertex(line.anchor);
        double[] st = intersect(tk, d, anchor, line.dir);
        if (st == null || st[1] >= -1e-9) {
            // Параллельны, или камера оказалась бы за опорной вершиной ветки.
            return null;
        }
        Coordinate p = new Coordinate(tk.x + d[0] * st[0], tk.y + d[1] * st[0]);
        if (p.distance(old) > MAX_SHIFT || p.distance(old) < AT_VERTEX || !reachOk(line, p, -st[1], branchDn, branch)) {
            return null;
        }
        double along = st[0];
        Trunk t = new Trunk();
        if (along > -AT_VERTEX && along < len + AT_VERTEX) {
            if (along < AT_VERTEX || along > len - AT_VERTEX) {
                // Камера на существующем повороте ствола; на узел ствола её не ставим.
                int at = along < AT_VERTEX ? k : k + 1;
                if (at == 0 || at == n) {
                    return null;
                }
                copy(trunk, 0, n, t);
                t.chamber = at;
                return t;
            }
            copy(trunk, 0, k, t);
            t.pts.add(new Pt(-1, p));
            copy(trunk, k + 1, n, t);
            t.chamber = k + 1;
            return t;
        }
        if (along < 0) {
            // Камера перед началом звена: звено выше по стволу продлевается до его прямой.
            for (int j = k - 1; j >= 0; j--) {
                Coordinate tj = grid.vertex(trunk.get(j));
                double[] dj = NodeAngles.direction(tj, grid.vertex(trunk.get(j + 1)));
                double[] ab = intersect(tj, dj, p, d);
                if (ab == null) {
                    continue;
                }
                double minRun = j == 0 ? HOST_LEG_MIN : settings.minStraightRun;
                if (ab[0] < minRun || ab[1] > -settings.minStraightRun || !turnOk(dj, d)) {
                    continue;
                }
                copy(trunk, 0, j, t);
                t.pts.add(new Pt(-1, new Coordinate(tj.x + dj[0] * ab[0], tj.y + dj[1] * ab[0])));
                t.pts.add(new Pt(-1, p));
                copy(trunk, k + 1, n, t);
                t.chamber = j + 2;
                return t;
            }
            return null;
        }
        // Камера за концом звена: звено ниже по стволу продлевается назад до его прямой.
        if (k + 1 == n) {
            if (hasThrough) {
                return null;
            }
            copy(trunk, 0, k, t);
            t.pts.add(new Pt(-1, p));
            t.chamber = k + 1;
            return t;
        }
        for (int j = k + 1; j < n; j++) {
            Coordinate tj = grid.vertex(trunk.get(j));
            Coordinate tj1 = grid.vertex(trunk.get(j + 1));
            double[] dj = NodeAngles.direction(tj, tj1);
            double[] ab = intersect(p, d, tj, dj);
            if (ab == null) {
                continue;
            }
            double minRun = j + 1 == n ? HOST_LEG_MIN : settings.minStraightRun;
            if (ab[0] < settings.minStraightRun || tj.distance(tj1) - ab[1] < minRun || !turnOk(d, dj)) {
                continue;
            }
            copy(trunk, 0, k, t);
            t.pts.add(new Pt(-1, p));
            t.pts.add(new Pt(-1, new Coordinate(p.x + d[0] * ab[0], p.y + d[1] * ab[0])));
            copy(trunk, j + 1, n, t);
            t.chamber = k + 1;
            return t;
        }
        return null;
    }

    private void copy(List<Integer> trunk, int from, int to, Trunk t) {
        for (int i = from; i <= to; i++) {
            t.pts.add(new Pt(trunk.get(i), grid.vertex(trunk.get(i))));
        }
    }

    /**
     * Отрезок от камеры до опорной вершины достаточно длинный: до узла — метр, до поворота —
     * минимальный прямой отрезок. Ввод в дом одним отрезком начинается за зоной своего здания.
     */
    private boolean reachOk(Line line, Coordinate p, double reach, int branchDn, List<Integer> branch) {
        if (line.lead) {
            Geometry own = ownBuildings.get(net.oksAt.get(branch.get(branch.size() - 1)));
            double zone = Approaches.clearance(rules, settings, branchDn) - Math.max(0, settings.obstacleSimplify);
            return reach >= HOST_LEG_MIN && (own == null || own.distance(Geo.point(p)) >= zone - 0.01);
        }
        return reach >= (line.tail.size() == 1 ? HOST_LEG_MIN : settings.minStraightRun);
    }

    private boolean turnOk(double[] a, double[] b) {
        double turn = NodeAngles.rayAngle(a, b);
        if (turn > rules.turn.maxDeg + rules.turn.toleranceDeg) {
            return false;
        }
        return !settings.standardAnglesOnly || rules.turn.standard(turn);
    }

    /** a + s·u = b + t·w; возвращает {s, t} или null, если прямые параллельны. */
    private static double[] intersect(Coordinate a, double[] u, Coordinate b, double[] w) {
        double den = u[0] * w[1] - u[1] * w[0];
        if (Math.abs(den) < 1e-9) {
            return null;
        }
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        return new double[]{(dx * w[1] - dy * w[0]) / den, (dx * u[1] - dy * u[0]) / den};
    }

    /**
     * Строит сеть с перенесённой камерой: старые звенья ствола и первые звенья веток
     * снимаются, добавляются новый ствол, ввод по выбранной прямой и подводки остальных
     * веток. Null, если какая-то из быстрых проверок не прошла.
     */
    private NewNetwork move(List<Integer> trunkChain, Trunk moved, int c, List<List<Integer>> children,
                            List<Integer> through, List<Integer> branch, Line line, int branchDn,
                            int dnUp, int dnDown) {
        Pt host = moved.pts.get(moved.chamber);
        Coordinate p = host.at;
        if (obstacles.blocked(p) || obstacles.inPolygonZone(p)) {
            return null;
        }
        // Ствол до камеры и после неё — две ломаные с разными диаметрами. Проверяются только
        // новые звенья и повороты рядом с ними: старая геометрия до доводки бывает с изломами,
        // которые доводка уберёт, и браковать за них перенос нельзя.
        List<Coordinate> upPts = new ArrayList<>();
        List<Boolean> upFresh = new ArrayList<>();
        for (int i = 0; i <= moved.chamber; i++) {
            upPts.add(moved.pts.get(i).at);
            upFresh.add(moved.pts.get(i).vertex < 0);
        }
        List<Coordinate> downPts = new ArrayList<>();
        List<Boolean> downFresh = new ArrayList<>();
        for (int i = moved.chamber; i < moved.pts.size(); i++) {
            downPts.add(moved.pts.get(i).at);
            downFresh.add(moved.pts.get(i).vertex < 0);
        }
        Coordinate tieIn = tieInAtVertex.containsKey(trunkChain.get(0)) ? grid.vertex(trunkChain.get(0)) : null;
        String downPoint = downPts.size() > 1 ? net.oksAt.get(moved.pts.get(moved.pts.size() - 1).vertex) : null;
        if (!polylineOk(upPts, upFresh, dnUp, tieIn, null)
                || (downPts.size() > 1 && !polylineOk(downPts, downFresh, dnDown, null, downPoint))) {
            return null;
        }
        // Ввод по выбранной прямой: от камеры до опорной вершины и дальше по старой ветке.
        List<Coordinate> branchPts = new ArrayList<>();
        List<Boolean> branchFresh = new ArrayList<>();
        branchPts.add(p);
        branchFresh.add(true);
        for (int v : line.tail) {
            branchPts.add(grid.vertex(v));
            branchFresh.add(false);
        }
        if (!polylineOk(branchPts, branchFresh, branchDn, null, net.oksAt.get(branch.get(branch.size() - 1)))) {
            return null;
        }

        NewNetwork out = net.copy();
        for (int i = 1; i < trunkChain.size(); i++) {
            out.removeLink(trunkChain.get(i - 1), trunkChain.get(i));
        }
        int anchorIndex = branch.indexOf(line.anchor);
        for (int i = 1; i <= anchorIndex; i++) {
            out.removeLink(branch.get(i - 1), branch.get(i));
        }
        // У остальных веток снимается прямой пробег от камеры до первого поворота.
        List<int[]> runs = new ArrayList<>();
        for (int b = 0; b < children.size(); b++) {
            List<Integer> other = children.get(b);
            if (other == branch || other == through) {
                continue;
            }
            int run = 1;
            while (run + 1 < other.size() && Geo.turnDegrees(grid.vertex(other.get(run - 1)),
                    grid.vertex(other.get(run)), grid.vertex(other.get(run + 1))) <= rules.turn.toleranceDeg) {
                run++;
            }
            for (int i = 1; i <= run; i++) {
                out.removeLink(other.get(i - 1), other.get(i));
            }
            runs.add(new int[]{b, run});
        }

        for (int i = 1; i < moved.pts.size(); i++) {
            Pt a = moved.pts.get(i - 1);
            Pt b = moved.pts.get(i);
            if (out.crosses(a.vertex, b.vertex, a.at, b.at)) {
                return null;
            }
            link(out, a, b);
        }
        Pt anchor = new Pt(line.anchor, grid.vertex(line.anchor));
        if (out.crosses(host.vertex, anchor.vertex, p, anchor.at)) {
            return null;
        }
        link(out, host, anchor);

        // Подводки остальных веток — тем же способом, что при слиянии камер.
        List<double[]> raysAtHost = new ArrayList<>();
        for (int n : out.neighbours(host.vertex)) {
            raysAtHost.add(NodeAngles.direction(p, grid.vertex(n)));
        }
        for (int[] r : runs) {
            List<Integer> other = children.get(r[0]);
            int end = other.get(r[1]);
            Coordinate turnPoint = merger.turnPoint(out, host.vertex, c, end, other.subList(r[1], other.size()),
                    dnOf(c, other.get(1)), raysAtHost, settings.standardAnglesOnly);
            if (turnPoint == null) {
                return null;
            }
            if (turnPoint.distance(p) < 1e-6) {
                out.addLink(host.vertex, end, p.distance(grid.vertex(end)), p, grid.vertex(end));
                raysAtHost.add(NodeAngles.direction(p, grid.vertex(end)));
                continue;
            }
            int q = merger.existingVertexAt(out, turnPoint);
            if (q < 0) {
                q = grid.addVertex(turnPoint);
            }
            out.addLink(host.vertex, q, p.distance(turnPoint), p, turnPoint);
            out.addLink(q, end, turnPoint.distance(grid.vertex(end)), turnPoint, grid.vertex(end));
            raysAtHost.add(NodeAngles.direction(p, turnPoint));
        }
        return branchesApart(raysAtHost) ? out : null;
    }

    /** Две ветки из одной камеры не должны уходить почти в одну сторону. */
    private boolean branchesApart(List<double[]> rays) {
        for (int i = 0; i < rays.size(); i++) {
            for (int j = i + 1; j < rays.size(); j++) {
                if (NodeAngles.rayAngle(rays.get(i), rays.get(j)) < settings.minBranchAngle) {
                    return false;
                }
            }
        }
        return true;
    }

    private void link(NewNetwork out, Pt a, Pt b) {
        int va = vertexOf(out, a);
        int vb = vertexOf(out, b);
        out.addLink(va, vb, a.at.distance(b.at), a.at, b.at);
    }

    private int vertexOf(NewNetwork out, Pt p) {
        if (p.vertex < 0) {
            int v = merger.existingVertexAt(out, p.at);
            p.vertex = v >= 0 ? v : grid.addVertex(p.at);
        }
        return p.vertex;
    }

    /**
     * Быстрая проверка ломаной: новые звенья и повороты рядом с ними против запретов,
     * предельного угла, ступенек, спецзон, отхода от трубы в присоединении и отступов.
     * Последний отрезок ввода в дом идёт сквозь зону своего здания, остальные — нет.
     *
     * @param fresh   какие вершины новые: звено с новой вершиной и поворот рядом с ней
     * @param tieIn   точка присоединения, если ломаная начинается в ней
     * @param pointId точка подключения, если ломаная кончается в ней
     */
    private boolean polylineOk(List<Coordinate> pts, List<Boolean> fresh, int dn, Coordinate tieIn, String pointId) {
        Geometry own = pointId == null ? null : ownBuildings.get(pointId);
        Geometry ownZone = pointId == null ? null : ownZones.get(pointId);
        String ownId = pointId == null ? null : buildingOfPoint.get(pointId);
        int last = pts.size() - 1;
        for (int i = 1; i <= last; i++) {
            if (!fresh.get(i - 1) && !fresh.get(i)) {
                continue;
            }
            boolean lead = own != null && i == last;
            Coordinate a = pts.get(i - 1);
            Coordinate b = pts.get(i);
            if (a.distance(b) < HOST_LEG_MIN - 1e-6
                    || obstacles.edge(a, b, lead ? ownZone : null, i == 1 ? tieIn : null).blocked) {
                return false;
            }
            if (clearance != null && clearance.violation("align", java.util.Arrays.asList(a, b), dn,
                    lead ? own : null, lead ? ownId : null) != null) {
                return false;
            }
        }
        List<Integer> turns = new ArrayList<>();
        for (int i = 1; i < last; i++) {
            if (Geo.turnDegrees(pts.get(i - 1), pts.get(i), pts.get(i + 1)) > rules.turn.toleranceDeg) {
                turns.add(i);
            }
        }
        for (int n = 0; n < turns.size(); n++) {
            int i = turns.get(n);
            if (!fresh.get(i - 1) && !fresh.get(i) && !fresh.get(i + 1)) {
                continue;
            }
            double turn = Geo.turnDegrees(pts.get(i - 1), pts.get(i), pts.get(i + 1));
            if (turn > rules.turn.maxDeg + rules.turn.toleranceDeg
                    || (settings.standardAnglesOnly && !rules.turn.standard(turn))) {
                return false;
            }
            Coordinate at = pts.get(i);
            if (obstacles.inPolygonZone(at) || obstacles.crossesLineNear(pts.get(i - 1), at, at, 0.5)
                    || obstacles.crossesLineNear(at, pts.get(i + 1), at, 0.5)) {
                return false;
            }
            if ((n > 0 && pts.get(turns.get(n - 1)).distance(at) < settings.minStraightRun)
                    || (n + 1 < turns.size() && pts.get(turns.get(n + 1)).distance(at) < settings.minStraightRun)) {
                return false;
            }
        }
        if (own != null && last >= 1 && (fresh.get(last - 1) || (last >= 2 && fresh.get(last - 2)))) {
            // Точка поворота перед вводом стоит за зоной минимального расстояния от своего дома.
            double zone = Approaches.clearance(rules, settings, dn) - Math.max(0, settings.obstacleSimplify);
            if (own.distance(Geo.point(pts.get(last - 1))) < zone - 0.01) {
                return false;
            }
        }
        if (tieIn != null && fresh.get(1)) {
            double[] first = NodeAngles.direction(pts.get(0), pts.get(1));
            for (double[] pipe : pipes.apply(tieIn)) {
                double along = Math.toDegrees(Math.acos(Math.min(1, Math.abs(first[0] * pipe[0] + first[1] * pipe[1]))));
                if (along < settings.tieInMinAngle - rules.turn.toleranceDeg) {
                    return false;
                }
            }
        }
        return true;
    }
}
