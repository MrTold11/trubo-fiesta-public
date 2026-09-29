package ru.chmod777.trubofiesta.approach;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import ru.chmod777.trubofiesta.geo.Frame;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.model.Restriction;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Как труба может выйти из здания. Точка подключения лежит внутри дома, и по разделу 2.2
 * от неё идёт один финальный прямой участок до ближайшей к точке границы полигона; наружу
 * он выходит по нормали к стене и должен пройти зону минимального расстояния.
 *
 * Здесь только геометрия стен: какие выходы вообще существуют и какой из них ближайший.
 * Это общее знание, а не выбор алгоритма: им пользуются и нормализация входа
 * ({@link Approaches}), и проверка результата, и любая реализация трассировки.
 *
 * По умолчанию труба выходит через ближайшую к точке стену. Если через неё выйти нельзя
 * (за стеной вплотную другое здание), берутся и другие стены. Выход во двор-колодец —
 * только если других нет.
 */
public final class WallExits {

    /** Выходы дальше ближайшей стены больше чем на столько метров не рассматриваем. */
    private static final double EXTRA_WALL_DISTANCE = 15.0;
    /** Запас за зоной отступа, с которым ставится точка поворота на выходе из здания, м. */
    public static final double EXIT_MARGIN = 0.3;
    private static final int MAX_EXITS = 4;

    private WallExits() {
    }

    /** Один возможный выход трубы из здания. */
    public static class Exit {
        /** Единичный вектор в плане. */
        public double[] direction;
        /** От точки подключения до стены, м. */
        public double wallDistance;
        /** Длина перпендикуляра из точки на эту стену, м. По ней выбирается ближайшая стена. */
        public double footDistance;
        /** Раньше этого расстояния от точки трасса не поворачивает, м. */
        public double turnDistance;
        /** Выход по нормали к стене. False — запасной выход, когда нормального нет. */
        public boolean normal;
        /** Выход во внутренний двор, окружённый зданием. */
        public boolean courtyard;
    }

    /** Выходы с подгонкой направления под оси системы координат из параметров расчёта. */
    public static List<Exit> exits(ConnectionPoint p, InputData data, Frame frame, double clearance,
                                   CalcSettings settings, boolean nearestOnly) {
        return exits(p, data, frame, clearance, settings, nearestOnly, settings.exitSnapDeg);
    }

    /**
     * Выходы из здания. При nearestOnly — только через ближайшую к точке стену: точка
     * подключения стоит у стены, и труба выходит к ней кратчайшим путём. Во внутренний
     * двор-колодец выход берётся, только если других нет.
     *
     * @param snapDeg насколько стена может отклоняться от оси frame, чтобы выход развернулся
     *                точно по оси; ноль оставляет чистую нормаль к стене
     */
    public static List<Exit> exits(ConnectionPoint p, InputData data, Frame frame, double clearance,
                                   CalcSettings settings, boolean nearestOnly, double snapDeg) {
        return exits(p, data, frame, clearance, settings, nearestOnly, snapDeg, EXTRA_WALL_DISTANCE, MAX_EXITS);
    }

    /**
     * @param extraWallDistance насколько дальше ближайшей стены ещё имеет смысл искать выход, м
     * @param maxExits          сколько выходов вернуть, не больше
     */
    public static List<Exit> exits(ConnectionPoint p, InputData data, Frame frame, double clearance,
                                   CalcSettings settings, boolean nearestOnly, double snapDeg,
                                   double extraWallDistance, int maxExits) {
        Restriction building = p.buildingId == null ? null : data.restrictionById.get(p.buildingId);
        Polygon wall = building == null || building.geom == null ? null
                : "PART".equals(settings.ownBuildingScope) ? Approaches.partContaining(building.geom, p.at)
                : Approaches.corpus(p, data);
        if (wall == null) {
            return axisExits(frame, null, p.at, 0);
        }

        List<Exit> found = new ArrayList<>();
        Polygon shell = Geo.F.createPolygon(wall.getExteriorRing().getCoordinates());
        for (int r = -1; r < wall.getNumInteriorRing(); r++) {
            Coordinate[] ring = r < 0 ? wall.getExteriorRing().getCoordinates()
                    : wall.getInteriorRingN(r).getCoordinates();
            for (int i = 1; i < ring.length; i++) {
                Exit e = throughWall(p.at, ring[i - 1], ring[i], wall, shell, frame, clearance, snapDeg);
                if (e != null) {
                    found.add(e);
                }
            }
        }
        found.sort((a, b) -> Double.compare(a.footDistance, b.footDistance));

        List<Exit> out = new ArrayList<>();
        if (nearestOnly) {
            // Ближайшая стена, через которую действительно можно выйти по нормали. Короткие
            // ступеньки фасада и стены двора-колодца пропускаются: по их нормали труба
            // пошла бы вдоль дома или во двор.
            Exit first = null;
            for (Exit e : found) {
                if (!e.courtyard) {
                    first = e;
                    break;
                }
            }
            if (first == null) {
                return out;
            }
            for (Exit e : found) {
                if (!e.courtyard && e.footDistance <= first.footDistance + settings.approachTolerance) {
                    addDistinct(out, e);
                }
            }
            return out;
        }
        for (Exit e : found) {
            if (e.courtyard && out.stream().anyMatch(x -> !x.courtyard)) {
                continue;
            }
            if (!out.isEmpty() && e.footDistance > out.get(0).footDistance + extraWallDistance) {
                break;
            }
            addDistinct(out, e);
            if (out.size() >= maxExits) {
                break;
            }
        }
        return out.isEmpty() ? axisExits(frame, wall, p.at, clearance) : out;
    }

    private static void addDistinct(List<Exit> out, Exit e) {
        for (Exit kept : out) {
            double dot = kept.direction[0] * e.direction[0] + kept.direction[1] * e.direction[1];
            if (dot > Math.cos(Math.toRadians(10))) {
                return;
            }
        }
        out.add(e);
    }

    /** Выход через одну стену по нормали или null, если через эту стену выйти нельзя. */
    private static Exit throughWall(Coordinate p, Coordinate a, Coordinate b, Polygon wall, Polygon shell,
                                    Frame frame, double clearance, double snapDeg) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len = Math.hypot(dx, dy);
        if (len < 1.0) {
            return null;
        }
        double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (len * len);
        if (t < 0.02 || t > 0.98) {
            return null;
        }
        Coordinate foot = new Coordinate(a.x + t * dx, a.y + t * dy);
        double distance = foot.distance(p);
        double[] dir;
        if (distance > 0.05) {
            dir = new double[]{(foot.x - p.x) / distance, (foot.y - p.y) / distance};
        } else {
            dir = new double[]{-dy / len, dx / len};
            if (wall.covers(Geo.point(new Coordinate(foot.x + dir[0] * 0.3, foot.y + dir[1] * 0.3)))) {
                dir = new double[]{-dir[0], -dir[1]};
            }
        }

        // Труба должна выйти через эту стену, а не пройти по дороге сквозь другую часть дома.
        if (distance > 0.1) {
            Coordinate before = new Coordinate(foot.x - dir[0] * 0.05, foot.y - dir[1] * 0.05);
            if (!wall.covers(Geo.line(java.util.Arrays.asList(p, before)))) {
                return null;
            }
        }
        Coordinate beyond = new Coordinate(foot.x + dir[0] * 0.3, foot.y + dir[1] * 0.3);
        if (wall.covers(Geo.point(beyond))) {
            return null;
        }
        Coordinate far = new Coordinate(foot.x + dir[0] * clearance, foot.y + dir[1] * clearance);
        if (Geo.line(java.util.Arrays.asList(beyond, far)).intersects(wall)) {
            return null;
        }

        Exit e = new Exit();
        e.direction = snap(dir, frame, snapDeg);
        e.wallDistance = exitDistance(p, e.direction, wall, distance);
        e.footDistance = distance;
        e.turnDistance = e.wallDistance + clearance;
        Coordinate turn = new Coordinate(p.x + e.direction[0] * e.turnDistance, p.y + e.direction[1] * e.turnDistance);
        if (wall.distance(Geo.point(turn)) < clearance - 0.35) {
            // За стеной труба всё ещё в зоне своего здания: нормаль идёт вдоль крыла Г-образного
            // дома или ступеньки фасада. Раздел 2.2 допускает финальный прямой участок внутри
            // зоны своего полигона любой длины, поэтому точка поворота уходит дальше по нормали,
            // туда, где зона кончается. Сквозь другую часть дома так идти нельзя.
            e.turnDistance = leadOut(p, e.direction, e.turnDistance, clearance, wall);
            if (e.turnDistance < 0) {
                return null;
            }
        }
        e.normal = true;
        e.courtyard = shell.covers(Geo.point(beyond));
        return e;
    }

    /** Дальше этого финальный участок вдоль своего здания не тянем, м. */
    private static final double MAX_LEAD = 60.0;

    /**
     * Расстояние от точки подключения, на котором луч выходит из зоны своего здания,
     * или -1, если до этого он входит в другую часть дома или зона слишком длинная.
     */
    private static double leadOut(Coordinate p, double[] dir, double from, double clearance, Polygon wall) {
        for (double t = from; t <= from + MAX_LEAD; t += 0.5) {
            Coordinate q = new Coordinate(p.x + dir[0] * t, p.y + dir[1] * t);
            if (wall.covers(Geo.point(q))) {
                return -1;
            }
            if (wall.distance(Geo.point(q)) >= clearance - 0.35) {
                return t;
            }
        }
        return -1;
    }

    /**
     * Если стена почти параллельна осям, выход разворачивается точно по оси: тогда поворот
     * на сетку получается ровно 90 или 45 градусов. При нулевом допуске нормаль не трогается.
     */
    private static double[] snap(double[] dir, Frame frame, double maxDeg) {
        if (maxDeg <= 0) {
            return dir;
        }
        double[] local = frame.localVector(dir[0], dir[1]);
        double deg = Math.toDegrees(Math.atan2(local[1], local[0]));
        double axis = Math.round(deg / 45.0) * 45.0;
        if (Math.abs(deg - axis) > maxDeg) {
            return dir;
        }
        double rad = Math.toRadians(axis);
        return frame.worldVector(Math.cos(rad), Math.sin(rad));
    }

    /** Где луч из точки выходит из здания, м. */
    private static double exitDistance(Coordinate p, double[] dir, Polygon wall, double fallback) {
        Coordinate far = new Coordinate(p.x + dir[0] * (fallback + 50), p.y + dir[1] * (fallback + 50));
        LineString ray = Geo.line(java.util.Arrays.asList(p, far));
        Geometry inside = wall.intersection(ray);
        double best = fallback;
        for (int i = 0; i < inside.getNumGeometries(); i++) {
            Geometry part = inside.getGeometryN(i);
            for (Coordinate c : part.getCoordinates()) {
                if (c.distance(p) < 1e-6) {
                    best = Math.max(best, part.getLength());
                }
            }
        }
        return inside.isEmpty() ? fallback : best;
    }

    /** Запасной выход, когда через стены по нормали выйти не получается: по осям системы координат. */
    private static List<Exit> axisExits(Frame frame, Polygon wall, Coordinate p, double clearance) {
        List<Exit> out = new ArrayList<>();
        for (int d = 0; d < 8; d++) {
            double rad = Math.toRadians(45.0 * d);
            Exit e = new Exit();
            e.direction = frame.worldVector(Math.cos(rad), Math.sin(rad));
            e.wallDistance = wall == null ? 0 : exitDistance(p, e.direction, wall, 0);
            e.turnDistance = wall == null ? 0 : e.wallDistance + clearance;
            e.normal = false;
            out.add(e);
        }
        return out;
    }
}
