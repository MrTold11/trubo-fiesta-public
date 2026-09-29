package ru.chmod777.trubofiesta.algo.octogrid;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.chmod777.trubofiesta.algo.VertexSpace;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.constraints.SpecialZone;
import ru.chmod777.trubofiesta.geo.Frame;
import ru.chmod777.trubofiesta.geo.Geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сетка трассировки в осях застройки. Из узла можно идти в восемь сторон: вдоль осей
 * и по диагонали, поэтому любой поворот трассы — 45 или 90 градусов, то есть
 * стандартный отвод.
 *
 * Точки подключения и врезки на узлы сетки не попадают. К ним ведут лучи: из точки
 * подключения — по нормали к стене здания, из врезки — в стороны от существующей трубы.
 * На каждом пересечении луча с линией сетки ставится вершина, и с неё можно свернуть
 * вдоль этой линии. Так трасса выходит из дома строго по нормали и без ступенек
 * садится на сетку.
 */
public class OctoGrid implements VertexSpace {

    static final int[] DI = {1, 1, 0, -1, -1, -1, 0, 1};
    static final int[] DJ = {0, 1, 1, 1, 0, -1, -1, -1};
    static final double[] DU = new double[8];
    static final double[] DV = new double[8];
    /** Направления вдоль луча, если он не совпал ни с одной осью сетки. */
    public static final int RAY_FORWARD = 8;
    public static final int RAY_BACK = 9;
    /** Сколько направлений хранится для каждой вершины при поиске пути. */
    public static final int STATES = 10;

    static {
        for (int d = 0; d < 8; d++) {
            double len = Math.hypot(DI[d], DJ[d]);
            DU[d] = DI[d] / len;
            DV[d] = DJ[d] / len;
        }
    }

    /** Ближе этого вершины считаются одной точкой, м. */
    private static final double SNAP = 0.05;
    private static final byte BLOCKED = 1;
    private static final byte NEAR = 2;
    private static final byte BAND = 4;
    private static final byte EDGE_OK = 1;
    private static final byte EDGE_NO = 2;
    private static final double SQRT2 = Math.sqrt(2);

    public final Frame frame;
    public final double step;
    public final boolean diagonals;
    public final int cols;
    public final int rows;
    private final double u0;
    private final double v0;
    private final int nodes;
    private final ObstacleSet obstacles;
    private final List<SpecialZone> zones;
    private final byte[] flags;
    private final int[] zoneAt;
    private final float[] axisAt;
    private final byte[] edgeState;

    private final List<Coordinate> extraAt = new ArrayList<>();
    /** Для вершин вне сетки: лежит ли вершина в зоне дороги или путей; считается при первом обращении. */
    private final List<Byte> extraZone = new ArrayList<>();
    /** Есть ли среди спецобъектов линейные: газопровод, кабель, существующая теплосеть. */
    private boolean lineZones;
    /** Пересекает ли ребро v→n линейный спецобъект рядом с v: ключ v·size + n. */
    private final Map<Long, Boolean> crossNear = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<double[]> rayVector = new ArrayList<>();
    private final List<List<Edge>> extraEdges = new ArrayList<>();
    private final Map<Integer, List<Edge>> nodeEdges = new HashMap<>();
    private final Set<Integer> terminals = new HashSet<>();
    /**
     * Рёбра сетки, на которые легли вершины лучей. Такое ребро целиком больше не используется:
     * путь по нему идёт через эти вершины, иначе две ветки лягут друг на друга.
     */
    private final Map<Long, List<double[]>> splits = new HashMap<>();
    /** Отрезки лучей: из них потом находятся пересечения лучей между собой. */
    private final List<RayLink> rayLinks = new ArrayList<>();
    private final List<Coordinate> rayTieIn = new ArrayList<>();

    /** Отрезок луча между соседними вершинами. */
    private static final class RayLink {
        final int ray;
        final int a;
        final int b;
        final double du;
        final double dv;

        RayLink(int ray, int a, int b, double du, double dv) {
            this.ray = ray;
            this.a = a;
            this.b = b;
            this.du = du;
            this.dv = dv;
        }
    }

    /** Ребро к вершине вне сетки или от неё. Индексы направлений нужны поиску пути. */
    static final class Edge {
        final int to;
        final double length;
        final double k;
        final double ux;
        final double uy;
        final int arriveThere;
        final int leaveThere;

        Edge(int to, double length, double k, double ux, double uy, int arriveThere, int leaveThere) {
            this.to = to;
            this.length = length;
            this.k = k;
            this.ux = ux;
            this.uy = uy;
            this.arriveThere = arriveThere;
            this.leaveThere = leaveThere;
        }
    }

    /** Получатель рёбер вершины. k равен NaN, если ребро провести нельзя. */
    public interface EdgeSink {
        void accept(int to, double length, double k, double ux, double uy, int arriveThere, int leaveThere);
    }

    public OctoGrid(ObstacleSet obstacles, Frame frame, Envelope area, double step, boolean diagonals, int maxNodes) {
        this.obstacles = obstacles;
        this.frame = frame;
        this.diagonals = diagonals;
        double umin = Double.MAX_VALUE;
        double umax = -Double.MAX_VALUE;
        double vmin = Double.MAX_VALUE;
        double vmax = -Double.MAX_VALUE;
        for (Coordinate c : new Coordinate[]{
                new Coordinate(area.getMinX(), area.getMinY()), new Coordinate(area.getMinX(), area.getMaxY()),
                new Coordinate(area.getMaxX(), area.getMinY()), new Coordinate(area.getMaxX(), area.getMaxY())}) {
            umin = Math.min(umin, frame.u(c));
            umax = Math.max(umax, frame.u(c));
            vmin = Math.min(vmin, frame.v(c));
            vmax = Math.max(vmax, frame.v(c));
        }
        double s = step;
        double cells = ((umax - umin) / s + 3) * ((vmax - vmin) / s + 3);
        if (cells > maxNodes) {
            // Огромная территория: укрупняем сетку, чтобы расчёт не упёрся в память.
            s = Math.ceil(Math.sqrt((umax - umin) * (vmax - vmin) / maxNodes) * 2) / 2;
        }
        this.step = s;
        this.u0 = Math.floor(umin / s) * s - s;
        this.v0 = Math.floor(vmin / s) * s - s;
        this.cols = (int) Math.ceil((umax - u0) / s) + 2;
        this.rows = (int) Math.ceil((vmax - v0) / s) + 2;
        this.nodes = cols * rows;
        this.flags = new byte[nodes];
        this.edgeState = new byte[nodes * 8];

        PreparedGeometry near = PreparedGeometryFactory.prepare(obstacles.near(s));
        for (int index = 0; index < nodes; index++) {
            Coordinate c = vertex(index);
            if (obstacles.blocked(c)) {
                flags[index] = BLOCKED;
            } else if (near.intersects(Geo.point(c))) {
                flags[index] = NEAR;
            }
        }

        this.zones = obstacles.specials();
        if (zones.isEmpty()) {
            this.zoneAt = null;
            this.axisAt = null;
        } else {
            this.zoneAt = new int[nodes];
            this.axisAt = new float[nodes];
            java.util.Arrays.fill(zoneAt, -1);
            rasterZones();
            for (SpecialZone z : zones) {
                lineZones |= z.object.getDimension() < 2;
            }
        }
    }

    private void rasterZones() {
        for (int z = 0; z < zones.size(); z++) {
            SpecialZone zone = zones.get(z);
            PreparedGeometry inZone = PreparedGeometryFactory.prepare(zone.zone);
            PreparedGeometry inBand = PreparedGeometryFactory.prepare(zone.band);
            int[] range = range(zone.zone.getEnvelopeInternal());
            for (int j = range[2]; j <= range[3]; j++) {
                for (int i = range[0]; i <= range[1]; i++) {
                    int index = j * cols + i;
                    if ((flags[index] & BLOCKED) != 0) {
                        continue;
                    }
                    Coordinate c = vertex(index);
                    if (!inZone.intersects(Geo.point(c))) {
                        continue;
                    }
                    if (inBand.intersects(Geo.point(c))) {
                        flags[index] |= BAND;
                    }
                    if (zoneAt[index] < 0 || zone.rule.k > zones.get(zoneAt[index]).rule.k) {
                        zoneAt[index] = z;
                        axisAt[index] = (float) (zone.axisAngle(c) - frame.angle);
                    }
                }
            }
        }
    }

    /** Диапазон узлов, покрывающий прямоугольник в плане: iMin, iMax, jMin, jMax. */
    private int[] range(Envelope box) {
        double umin = Double.MAX_VALUE;
        double umax = -Double.MAX_VALUE;
        double vmin = Double.MAX_VALUE;
        double vmax = -Double.MAX_VALUE;
        for (Coordinate c : new Coordinate[]{
                new Coordinate(box.getMinX(), box.getMinY()), new Coordinate(box.getMinX(), box.getMaxY()),
                new Coordinate(box.getMaxX(), box.getMinY()), new Coordinate(box.getMaxX(), box.getMaxY())}) {
            umin = Math.min(umin, frame.u(c));
            umax = Math.max(umax, frame.u(c));
            vmin = Math.min(vmin, frame.v(c));
            vmax = Math.max(vmax, frame.v(c));
        }
        return new int[]{
                Math.max(0, (int) Math.floor((umin - u0) / step)),
                Math.min(cols - 1, (int) Math.ceil((umax - u0) / step)),
                Math.max(0, (int) Math.floor((vmin - v0) / step)),
                Math.min(rows - 1, (int) Math.ceil((vmax - v0) / step))};
    }

    public int size() {
        return nodes + extraAt.size();
    }

    public int nodeCount() {
        return nodes;
    }

    public int freeNodes() {
        int free = 0;
        for (byte f : flags) {
            if ((f & BLOCKED) == 0) {
                free++;
            }
        }
        return free;
    }

    @Override
    public Coordinate vertex(int index) {
        if (index < nodes) {
            int i = index % cols;
            int j = index / cols;
            return frame.world(u0 + i * step, v0 + j * step);
        }
        return extraAt.get(index - nodes);
    }

    /** Точка подключения или врезка: через неё трасса не проходит, только начинается или кончается. */
    public boolean isTerminal(int index) {
        return terminals.contains(index);
    }

    /**
     * В вершине v нельзя повернуть с ребра к соседу в направлении neighbourState на ребро
     * к вершине other: спецпроход выполняется одним прямым участком (раздел 4). Для дороги
     * и путей это любая вершина внутри их зоны, для линейного объекта — вершина рядом с
     * пересечением на одном из двух рёбер.
     */
    public boolean noTurn(int v, int neighbourState, int other) {
        if (zoneAt == null) {
            return false;
        }
        if (inPolygonZone(v)) {
            return true;
        }
        if (!lineZones) {
            return false;
        }
        int n = neighbour(v, neighbourState);
        return (n >= 0 && crossesNear(v, n)) || crossesNear(v, other);
    }

    /** Противоположное направление: обратный ход по тому же ребру. */
    public static int opposite(int state) {
        if (state < 8) {
            return (state + 4) % 8;
        }
        return state == RAY_FORWARD ? RAY_BACK : RAY_FORWARD;
    }

    private boolean inPolygonZone(int index) {
        if (index < nodes) {
            int z = zoneAt[index];
            return z >= 0 && zones.get(z).object.getDimension() == 2;
        }
        int extra = index - nodes;
        byte known = extraZone.get(extra);
        if (known == 0) {
            known = obstacles.inPolygonZone(extraAt.get(extra)) ? (byte) 2 : (byte) 1;
            extraZone.set(extra, known);
        }
        return known == 2;
    }

    /** Ребро v→n пересекает линейный спецобъект ближе длины спецучастка к v. */
    private boolean crossesNear(int v, int n) {
        long key = (long) v * size() + n;
        Boolean known = crossNear.get(key);
        if (known == null) {
            known = obstacles.crossesLineNear(vertex(v), vertex(n), vertex(v), 0.5);
            crossNear.put(key, known);
        }
        return known;
    }

    /** Сосед вершины по ребру в заданном направлении или -1, если такого ребра нет. */
    private int neighbour(int v, int state) {
        double[] want = new double[2];
        vector(v, state, want);
        List<Edge> list = v < nodes ? nodeEdges.get(v) : extraEdges.get(v - nodes);
        if (list != null) {
            for (Edge e : list) {
                if (e.ux * want[0] + e.uy * want[1] > 0.999) {
                    return e.to;
                }
            }
        }
        if (v < nodes && state < 8) {
            int i = v % cols + DI[state];
            int j = v / cols + DJ[state];
            if (i >= 0 && j >= 0 && i < cols && j < rows) {
                return j * cols + i;
            }
        }
        return -1;
    }

    public boolean hasEdges(int index) {
        List<Edge> list = index < nodes ? nodeEdges.get(index) : extraEdges.get(index - nodes);
        return list != null && !list.isEmpty();
    }

    /** Клетка, которую пересекает диагональное ребро между двумя узлами, или -1. */
    public int diagonalCell(int a, int b) {
        if (a >= nodes || b >= nodes) {
            return -1;
        }
        int ia = a % cols;
        int ja = a / cols;
        int ib = b % cols;
        int jb = b / cols;
        if (Math.abs(ia - ib) != 1 || Math.abs(ja - jb) != 1) {
            return -1;
        }
        return Math.min(ja, jb) * cols + Math.min(ia, ib);
    }

    /** Единичный вектор направления в осях сетки для состояния вершины. */
    public void vector(int index, int state, double[] out) {
        if (state < 8) {
            out[0] = DU[state];
            out[1] = DV[state];
            return;
        }
        double[] ray = index >= nodes ? rayVector.get(index - nodes) : null;
        if (ray == null) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        double sign = state == RAY_FORWARD ? 1 : -1;
        out[0] = ray[0] * sign;
        out[1] = ray[1] * sign;
    }

    public void forEachEdge(int v, EdgeSink sink) {
        if (v < nodes) {
            for (int d = 0; d < 8; d++) {
                if (!diagonals && d % 2 == 1) {
                    continue;
                }
                int i = v % cols + DI[d];
                int j = v / cols + DJ[d];
                if (i < 0 || j < 0 || i >= cols || j >= rows) {
                    continue;
                }
                int to = j * cols + i;
                double k = gridEdge(v, to, d);
                if (Double.isNaN(k)) {
                    continue;
                }
                sink.accept(to, d % 2 == 1 ? step * SQRT2 : step, k, DU[d], DV[d], d, (d + 4) % 8);
            }
            List<Edge> extra = nodeEdges.get(v);
            if (extra != null) {
                for (Edge e : extra) {
                    sink.accept(e.to, e.length, e.k, e.ux, e.uy, e.arriveThere, e.leaveThere);
                }
            }
            return;
        }
        for (Edge e : extraEdges.get(v - nodes)) {
            sink.accept(e.to, e.length, e.k, e.ux, e.uy, e.arriveThere, e.leaveThere);
        }
    }

    /** Ребро сетки: NaN, если его провести нельзя, иначе коэффициент специального прохода. */
    private double gridEdge(int a, int b, int d) {
        if (((flags[a] | flags[b]) & BLOCKED) != 0) {
            return Double.NaN;
        }
        if (!splits.isEmpty() && splits.containsKey(splitKey(a, b, d))) {
            return Double.NaN;
        }
        if (d % 2 == 1) {
            int ia = a % cols;
            int ja = a / cols;
            int side1 = ja * cols + ia + DI[d];
            int side2 = (ja + DJ[d]) * cols + ia;
            if (((flags[side1] | flags[side2]) & BLOCKED) != 0) {
                return Double.NaN;
            }
        }
        if (((flags[a] | flags[b]) & NEAR) != 0) {
            int key = a * 8 + d;
            byte state = edgeState[key];
            if (state == 0) {
                state = obstacles.visible(vertex(a), vertex(b)) ? EDGE_OK : EDGE_NO;
                edgeState[key] = state;
                edgeState[b * 8 + (d + 4) % 8] = state;
            }
            if (state == EDGE_NO) {
                return Double.NaN;
            }
        }
        if (zoneAt == null) {
            return 1.0;
        }
        double k = zoneRule(a, d, 1.0);
        return Double.isNaN(k) ? k : zoneRule(b, d, k);
    }

    /**
     * Правила зоны специального прохода для шага из узла. Вдоль объекта внутри полосы
     * минимального расстояния идти нельзя, поперёк — только под допустимым углом.
     * Коэффициент берётся только за пересечение, а не за проход рядом.
     */
    private double zoneRule(int node, int d, double k) {
        int z = zoneAt[node];
        if (z < 0) {
            return k;
        }
        SpecialZone zone = zones.get(z);
        double angle = ObstacleSet.crossingAngle(Math.toRadians(45.0 * d), axisAt[node]);
        if (angle < ObstacleSet.ALONG_DEG) {
            return (flags[node] & BAND) != 0 ? Double.NaN : k;
        }
        if (zone.rule.minAngle > 0 && angle < zone.rule.minAngle - 0.5) {
            return Double.NaN;
        }
        return Math.max(k, zone.rule.k);
    }

    /** Добавляет точку подключения или врезку. Сама по себе она ни с чем не связана. */
    public int addTerminal(Coordinate at) {
        int index = addExtra(at, null);
        terminals.add(index);
        return index;
    }

    /** Вершина вне сетки без рёбер: точка поворота при слиянии камер. */
    @Override
    public int addVertex(Coordinate at) {
        return addExtra(at, null);
    }

    private int addExtra(Coordinate at, double[] localDirection) {
        extraAt.add(at);
        extraZone.add((byte) 0);
        rayVector.add(localDirection);
        extraEdges.add(new ArrayList<>());
        return nodes + extraAt.size() - 1;
    }

    /**
     * Луч из точки подключения или врезки. Вершины ставятся там, где луч пересекает линии
     * сетки, но не ближе tFrom: раньше этого расстояния трасса свернуть не может.
     *
     * @param dirWorld единичный вектор направления в плане
     * @param ignore   геометрия, которую первый отрезок луча может пересекать (зона своего здания)
     * @param tieIn    точка врезки, если луч выходит из неё
     * @return сколько вершин получилось на луче
     */
    public int ray(int from, double[] dirWorld, double tFrom, double tTo, Geometry ignore, Coordinate tieIn) {
        return ray(from, dirWorld, tFrom, tTo, ignore, null, tieIn);
    }

    /**
     * То же, но с контуром своего здания: финальный прямой участок от точки подключения
     * (раздел 2.2) может идти внутри зоны отступа своего полигона сколько угодно, например
     * вдоль крыла Г-образного дома, но не сквозь другое крыло.
     *
     * @param ownWalls контур своего здания; null — как {@link #ray(int, double[], double, double, Geometry, Coordinate)}
     */
    public int ray(int from, double[] dirWorld, double tFrom, double tTo, Geometry ignore, Geometry ownWalls,
                   Coordinate tieIn) {
        Coordinate origin = vertex(from);
        double uo = frame.u(origin);
        double vo = frame.v(origin);
        double[] local = frame.localVector(dirWorld[0], dirWorld[1]);
        double du = local[0];
        double dv = local[1];
        boolean axis = axisIndex(du, dv) >= 0;
        int rayId = rayTieIn.size();
        rayTieIn.add(tieIn);

        List<double[]> stops = new ArrayList<>();
        collectCrossings(stops, uo, du, u0, cols, tFrom, tTo, 0);
        collectCrossings(stops, vo, dv, v0, rows, tFrom, tTo, 1);
        Collections.sort(stops, (x, y) -> Double.compare(x[0], y[0]));

        int prev = from;
        Coordinate prevAt = origin;
        boolean first = true;
        double lastT = -1;
        int created = 0;
        for (double[] stop : stops) {
            double t = stop[0];
            if (t - lastT < 1e-6) {
                continue;
            }
            double u = uo + t * du;
            double v = vo + t * dv;
            double fi = (u - u0) / step;
            double fj = (v - v0) / step;
            long ri = Math.round(fi);
            long rj = Math.round(fj);
            if (fi < 0 || fj < 0 || fi > cols - 1 || fj > rows - 1) {
                break;
            }
            // Вершина в паре сантиметров от узла сетки — это сам узел: иначе появляется ребро
            // в сантиметр, которое рвёт дерево при выгрузке.
            boolean onNode = Math.hypot(fi - ri, fj - rj) * step < SNAP;
            if (onNode && !axis) {
                // Неосевой луч прошёл ровно через узел: остановку пропускаем,
                // отрезок просто продлится до следующего пересечения.
                continue;
            }
            Coordinate at = frame.world(u, v);
            if (obstacles.blocked(at)) {
                // Внутри зоны своего здания финальный участок может идти дальше: вдоль крыла
                // Г-образного дома или через выступ стены. Сквозь другое крыло — нет. Без
                // контура здания ограничиваемся парой шагов, как раньше.
                if (first && ignore != null && ignore.covers(Geo.point(at))
                        && (ownWalls != null ? !reenters(ownWalls, origin, dirWorld, tFrom, t)
                        : t <= tFrom + 2 * step)) {
                    continue;
                }
                break;
            }
            ObstacleSet.EdgeInfo info = obstacles.edge(prevAt, at, first ? ignore : null, tieIn);
            if (info.blocked) {
                break;
            }
            int here;
            if (onNode) {
                here = (int) (rj * cols + ri);
                if ((flags[here] & BLOCKED) != 0) {
                    break;
                }
            } else {
                here = addExtra(at, new double[]{du, dv});
            }
            if (!(prev < nodes && here < nodes)) {
                link(prev, here, prevAt.distance(at), info.k, du, dv);
                // Первый отрезок идёт сквозь зону здания или от самой врезки: свернуть на нём нельзя.
                if (!first && axis) {
                    rayLinks.add(new RayLink(rayId, prev, here, du, dv));
                }
            }
            if (!onNode) {
                if (stop[1] == 0) {
                    int i = (int) ri;
                    lateral(here, i, (int) Math.floor(fj) + 1, 2);
                } else {
                    int j = (int) rj;
                    lateral(here, (int) Math.floor(fi) + 1, j, 0);
                }
            }
            prev = here;
            prevAt = at;
            lastT = t;
            first = false;
            created++;
        }
        return created;
    }

    /** Луч между расстояниями tFrom и t от точки подключения снова входит в своё здание. */
    private static boolean reenters(Geometry ownWalls, Coordinate origin, double[] dir, double tFrom, double t) {
        Coordinate a = new Coordinate(origin.x + dir[0] * tFrom, origin.y + dir[1] * tFrom);
        Coordinate b = new Coordinate(origin.x + dir[0] * t, origin.y + dir[1] * t);
        return ownWalls.intersects(Geo.line(java.util.Arrays.asList(a, b)));
    }

    /** Пересечения луча с линиями одного семейства: t и признак семейства (0 — столбцы, 1 — строки). */
    private void collectCrossings(List<double[]> out, double origin, double direction, double base,
                                  int count, double tFrom, double tTo, int family) {
        if (Math.abs(direction) < 1e-9) {
            return;
        }
        double a = (origin + tFrom * direction - base) / step;
        double b = (origin + tTo * direction - base) / step;
        int lo = (int) Math.ceil(Math.min(a, b) - 1e-9);
        int hi = (int) Math.floor(Math.max(a, b) + 1e-9);
        for (int n = Math.max(0, lo); n <= Math.min(count - 1, hi); n++) {
            double t = (base + n * step - origin) / direction;
            if (t >= tFrom - 1e-9 && t <= tTo + 1e-9) {
                out.add(new double[]{t, family});
            }
        }
    }

    /**
     * С вершины на луче можно свернуть вдоль пересечённой линии сетки к соседним узлам.
     * Вершина встраивается в ребро сетки между этими узлами: ребро делится на части.
     */
    private void lateral(int here, int i, int j, int d) {
        int ia = i - DI[d];
        int ja = j - DJ[d];
        if (ia < 0 || ja < 0 || i < 0 || j < 0 || ia >= cols || i >= cols || ja >= rows || j >= rows) {
            return;
        }
        if (d == 2 || d == 0) {
            insertOnEdge(here, ja * cols + ia, j * cols + i, d);
        }
    }

    private long splitKey(int a, int b, int d) {
        return a < b ? (long) a * 8 + d : (long) b * 8 + (d + 4) % 8;
    }

    /** Встраивает вершину луча в ребро сетки от узла a к узлу b по направлению d. */
    private void insertOnEdge(int vertex, int a, int b, int d) {
        long key = splitKey(a, b, d);
        int from = a < b ? a : b;
        int to = a < b ? b : a;
        List<double[]> chain = splits.get(key);
        if (chain == null) {
            chain = new ArrayList<>();
            chain.add(new double[]{0, from});
            chain.add(new double[]{1, to});
            splits.put(key, chain);
        } else {
            // Старое деление этого ребра перестраивается вместе с новой вершиной.
            for (int n = 1; n < chain.size(); n++) {
                unlink((int) chain.get(n - 1)[1], (int) chain.get(n)[1]);
            }
        }
        Coordinate start = vertex(from);
        if (start.distance(vertex(vertex)) < SNAP || vertex(to).distance(vertex(vertex)) < SNAP) {
            // Вершина луча легла на узел сетки: делить ребро нечем, узел и так есть.
            if (chain.size() == 2) {
                splits.remove(key);
            } else {
                relinkChain(chain, a < b ? d : (d + 4) % 8);
            }
            return;
        }
        double t = start.distance(vertex(vertex)) / step;
        int at = 1;
        while (at < chain.size() - 1 && chain.get(at)[0] < t) {
            at++;
        }
        chain.add(at, new double[]{t, vertex});
        relinkChain(chain, a < b ? d : (d + 4) % 8);
    }

    /** Заново связывает части делённого ребра сетки по цепочке вершин. */
    private void relinkChain(List<double[]> chain, int dirFromTo) {
        for (int n = 1; n < chain.size(); n++) {
            int p = (int) chain.get(n - 1)[1];
            int q = (int) chain.get(n)[1];
            if ((p < nodes && (flags[p] & BLOCKED) != 0) || (q < nodes && (flags[q] & BLOCKED) != 0)) {
                continue;
            }
            Coordinate pa = vertex(p);
            Coordinate qa = vertex(q);
            double length = pa.distance(qa);
            if (length < 1e-6) {
                continue;
            }
            ObstacleSet.EdgeInfo info = obstacles.edge(pa, qa, null, null);
            if (!info.blocked && zoneAllows(p, q, dirFromTo)) {
                link(p, q, length, info.k, DU[dirFromTo], DV[dirFromTo]);
            }
        }
    }

    /** Для частей делённого ребра правила зон проверяются так же, как для целого ребра сетки. */
    private boolean zoneAllows(int p, int q, int d) {
        if (zoneAt == null) {
            return true;
        }
        for (int node : new int[]{p, q}) {
            if (node < nodes && Double.isNaN(zoneRule(node, d, 1.0))) {
                return false;
            }
        }
        return true;
    }

    private void unlink(int a, int b) {
        edges(a).removeIf(e -> e.to == b);
        edges(b).removeIf(e -> e.to == a);
    }

    /**
     * Прямое ребро между двумя уже заведёнными вершинами, если отрезок проходит по правилам.
     * Так кандидат присоединения напротив дома связывается с самим домом: ввод одним отрезком.
     *
     * @param ignore геометрия, которую отрезок может пересекать (зона своего здания)
     * @param tieIn  точка присоединения, если отрезок выходит из неё
     */
    public boolean connect(int a, int b, Geometry ignore, Coordinate tieIn) {
        Coordinate pa = vertex(a);
        Coordinate pb = vertex(b);
        double length = pa.distance(pb);
        if (length < SNAP) {
            return false;
        }
        ObstacleSet.EdgeInfo info = obstacles.edge(pa, pb, ignore, tieIn);
        if (info.blocked) {
            return false;
        }
        double[] local = frame.localVector((pb.x - pa.x) / length, (pb.y - pa.y) / length);
        link(a, b, length, info.k, local[0], local[1]);
        return true;
    }

    /**
     * Точка поворота на выходе из здания: ровно там, где кончается зона минимального расстояния.
     * Отвод от точки подключения до неё проходит сквозь своё здание, дальше из неё можно идти
     * прямо или свернуть вдоль фасада. Так трасса проходит и узкий проход между домами,
     * куда линии сетки не попадают.
     *
     * @return номер вершины или -1, если в этой точке повернуть нельзя
     */
    public int exitVertex(int from, double[] dirWorld, double distance, Geometry ignore) {
        Coordinate origin = vertex(from);
        Coordinate at = new Coordinate(origin.x + dirWorld[0] * distance, origin.y + dirWorld[1] * distance);
        double[] local = frame.localVector(dirWorld[0], dirWorld[1]);
        double fi = (frame.u(at) - u0) / step;
        double fj = (frame.v(at) - v0) / step;
        if (fi < 0 || fj < 0 || fi > cols - 1 || fj > rows - 1 || obstacles.blocked(at)) {
            return -1;
        }
        ObstacleSet.EdgeInfo info = obstacles.edge(origin, at, ignore, null);
        if (info.blocked) {
            return -1;
        }
        int vertex = addExtra(at, local);
        link(from, vertex, distance, info.k, local[0], local[1]);
        return vertex;
    }

    /**
     * Узлы на пересечениях лучей между собой. Без них ствол, идущий по лучу от врезки,
     * не может ответвиться точно на линию выхода из дома: ветка уходит на соседнюю линию
     * сетки и возвращается ступенькой.
     */
    public int connectRayCrossings() {
        org.locationtech.jts.index.strtree.STRtree index = new org.locationtech.jts.index.strtree.STRtree();
        for (int i = 0; i < rayLinks.size(); i++) {
            RayLink l = rayLinks.get(i);
            Envelope box = new Envelope(vertex(l.a), vertex(l.b));
            index.insert(box, i);
        }
        Map<Integer, List<double[]>> inserts = new HashMap<>();
        int found = 0;
        for (int i = 0; i < rayLinks.size(); i++) {
            RayLink l = rayLinks.get(i);
            Coordinate a = vertex(l.a);
            Coordinate b = vertex(l.b);
            for (Object o : index.query(new Envelope(a, b))) {
                int j = (Integer) o;
                RayLink m = rayLinks.get(j);
                if (j <= i || m.ray == l.ray || Math.abs(l.du * m.dv - l.dv * m.du) < 1e-6) {
                    continue;
                }
                double[] t = crossing(a, b, vertex(m.a), vertex(m.b));
                if (t == null) {
                    continue;
                }
                Coordinate at = new Coordinate(a.x + (b.x - a.x) * t[0], a.y + (b.y - a.y) * t[0]);
                // Пересечение рядом с концом одного из отрезков: вершиной становится этот конец,
                // и он встраивается в другой луч. Новая вершина в сантиметре от старой дала бы
                // ребро-заглушку, которое теряется при выгрузке и рвёт дерево.
                boolean nearOwn = at.distance(a) < SNAP || at.distance(b) < SNAP;
                boolean nearOther = at.distance(vertex(m.a)) < SNAP || at.distance(vertex(m.b)) < SNAP;
                if (nearOwn && nearOther) {
                    continue;
                }
                if (nearOwn) {
                    int end = at.distance(a) < SNAP ? l.a : l.b;
                    inserts.computeIfAbsent(j, x -> new ArrayList<>()).add(new double[]{t[1], end});
                } else if (nearOther) {
                    int end = at.distance(vertex(m.a)) < SNAP ? m.a : m.b;
                    inserts.computeIfAbsent(i, x -> new ArrayList<>()).add(new double[]{t[0], end});
                } else {
                    int vertex = addExtra(at, null);
                    inserts.computeIfAbsent(i, x -> new ArrayList<>()).add(new double[]{t[0], vertex});
                    inserts.computeIfAbsent(j, x -> new ArrayList<>()).add(new double[]{t[1], vertex});
                }
                found++;
            }
        }
        for (Map.Entry<Integer, List<double[]>> e : inserts.entrySet()) {
            RayLink l = rayLinks.get(e.getKey());
            List<double[]> chain = e.getValue();
            chain.sort((x, y) -> Double.compare(x[0], y[0]));
            chain.add(0, new double[]{0, l.a});
            chain.add(new double[]{1, l.b});
            unlink(l.a, l.b);
            Coordinate tieIn = rayTieIn.get(l.ray);
            for (int n = 1; n < chain.size(); n++) {
                int p = (int) chain.get(n - 1)[1];
                int q = (int) chain.get(n)[1];
                Coordinate pa = vertex(p);
                Coordinate qa = vertex(q);
                if (pa.distance(qa) < 1e-6) {
                    continue;
                }
                ObstacleSet.EdgeInfo info = obstacles.edge(pa, qa, null, tieIn);
                if (!info.blocked) {
                    link(p, q, pa.distance(qa), info.k, l.du, l.dv);
                }
            }
        }
        return found;
    }

    /** Параметры точки пересечения двух отрезков внутри обоих или null. */
    private static double[] crossing(Coordinate a, Coordinate b, Coordinate c, Coordinate d) {
        double rx = b.x - a.x;
        double ry = b.y - a.y;
        double sx = d.x - c.x;
        double sy = d.y - c.y;
        double denom = rx * sy - ry * sx;
        if (Math.abs(denom) < 1e-12) {
            return null;
        }
        double t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / denom;
        double u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / denom;
        double eps = 1e-9;
        if (t <= eps || t >= 1 - eps || u <= eps || u >= 1 - eps) {
            return null;
        }
        return new double[]{t, u};
    }

    private void link(int a, int b, double length, double k, double ux, double uy) {
        edges(a).add(new Edge(b, length, k, ux, uy, dirIndex(b, ux, uy), dirIndex(b, -ux, -uy)));
        edges(b).add(new Edge(a, length, k, -ux, -uy, dirIndex(a, -ux, -uy), dirIndex(a, ux, uy)));
    }

    private List<Edge> edges(int index) {
        if (index < nodes) {
            return nodeEdges.computeIfAbsent(index, x -> new ArrayList<>());
        }
        return extraEdges.get(index - nodes);
    }

    private int dirIndex(int vertex, double ux, double uy) {
        int axis = axisIndex(ux, uy);
        if (axis >= 0) {
            return axis;
        }
        double[] ray = vertex >= nodes ? rayVector.get(vertex - nodes) : null;
        if (ray == null) {
            return RAY_FORWARD;
        }
        return ux * ray[0] + uy * ray[1] >= 0 ? RAY_FORWARD : RAY_BACK;
    }

    /** Направление в плане совпадает с одной из восьми осей сетки. */
    public boolean alongAxis(double[] dirWorld) {
        double[] local = frame.localVector(dirWorld[0], dirWorld[1]);
        return axisIndex(local[0], local[1]) >= 0;
    }

    /** Номер оси сетки, вдоль которой смотрит вектор, или -1. */
    static int axisIndex(double ux, double uy) {
        double deg = Math.toDegrees(Math.atan2(uy, ux));
        int d = (int) Math.round(deg / 45.0);
        if (Math.abs(deg - d * 45.0) > 0.01) {
            return -1;
        }
        return ((d % 8) + 8) % 8;
    }
}
