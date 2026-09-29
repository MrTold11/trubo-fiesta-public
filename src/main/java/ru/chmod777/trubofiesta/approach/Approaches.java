package ru.chmod777.trubofiesta.approach;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import ru.chmod777.trubofiesta.constraints.Clearance;
import ru.chmod777.trubofiesta.geo.Frame;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.model.Restriction;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Нормализация входа перед трассировкой: что считать своим зданием точки подключения,
 * какой отступ от него держать и куда труба должна к этой точке подойти.
 *
 * Это не часть алгоритма: правила здесь взяты из раздела 2.2 и таблицы 2 технического
 * приложения, и все алгоритмы обязаны работать с одной и той же нормализацией — иначе их
 * результаты не сравнить. Сам выбор трассы до точки подхода остаётся за алгоритмом.
 */
public final class Approaches {

    /** Отдельные части объекта ближе этого считаются одним зданием, м. */
    private static final double TOUCH = 0.5;

    private Approaches() {
    }

    /** Сколько подходов к одной точке имеет смысл держать. */
    private static final int MAX_CANDIDATES = 8;
    /**
     * Насколько дальше ближайшей стены ещё имеет смысл искать подход, м. Запасные стены
     * нужны, когда ближайшую перекрыл соседний ОКС, и для крупного корпуса противоположный
     * фасад может быть в десятках метров.
     */
    private static final double MAX_EXTRA_WALL = 60.0;

    /**
     * Подходы ко всем точкам подключения: нормали к стенам своего здания, продлённые
     * за зону минимального расстояния наименьшего диаметра справочника.
     *
     * Для каждой точки возвращается ранжированный список: сначала свободные подходы через
     * ближайшие стены, затем свободные через стены подальше, затем перекрытые. Выбор между
     * ними — дело алгоритма, здесь только факты.
     *
     * Для точки без своего полигона ОКС подходом считается сама точка: трасса ведётся прямо
     * к ней, выходить не из чего.
     *
     * @param clearance проверка минимальных расстояний; null отключает проверку, и все
     *                  подходы считаются свободными
     */
    public static Map<String, List<Approach>> of(InputData data, Rules rules, CalcSettings settings,
                                                 Clearance clearance) {
        Map<String, List<Approach>> out = new LinkedHashMap<>();
        int dn = rules.diameters.get(0).dn;
        double gap = clearance(rules, settings, dn);
        for (ConnectionPoint p : data.points) {
            out.put(p.id, of(p, data, settings, gap, dn, clearance));
        }
        return out;
    }

    /**
     * Подходы к одной точке. Ближайшая стена ищется по длине перпендикуляра из точки, нормаль
     * не подгоняется ни под какие оси: у нормализации нет сетки. Ближайших стен может
     * оказаться несколько — тогда все они одинаково законны по разделу 2.2.
     */
    public static List<Approach> of(ConnectionPoint p, InputData data, CalcSettings settings, double gap,
                                    int dn, Clearance clearance) {
        List<Approach> out = new ArrayList<>();
        Geometry own = ownBuilding(p, data, settings);
        if (own == null) {
            out.add(direct(p, gap));
            return out;
        }
        List<WallExits.Exit> found = WallExits.exits(p, data, new Frame(0), gap, settings, false, 0,
                MAX_EXTRA_WALL, MAX_CANDIDATES);
        if (found.isEmpty()) {
            out.add(direct(p, gap));
            return out;
        }
        double closest = Double.MAX_VALUE;
        for (WallExits.Exit e : found) {
            closest = Math.min(closest, e.footDistance);
        }
        for (WallExits.Exit e : found) {
            Approach a = new Approach();
            a.pointId = p.id;
            a.point = p.at;
            a.clearance = gap;
            a.direction = e.direction;
            a.wallDistance = e.wallDistance;
            a.footDistance = e.footDistance;
            a.normal = e.normal;
            a.courtyard = e.courtyard;
            a.nearest = e.footDistance <= closest + settings.approachTolerance;
            a.wall = new Coordinate(p.at.x + e.direction[0] * e.wallDistance,
                    p.at.y + e.direction[1] * e.wallDistance);
            a.at = a.at(gap);
            // Отступ к своему полигону на финальный участок не распространяется, а к чужим —
            // распространяется. Проверяем при наименьшем диаметре: если участок не помещается
            // даже так, он не поместится ни при каком.
            // Те же 0,3 м запаса, с которыми алгоритм ставит точку подхода на сетку:
            // проверять надо ровно тот отрезок, который будет построен.
            a.blockedBy = clearance == null ? null : clearance.violation("approach_" + p.id,
                    Arrays.asList(a.point, a.at(gap + 0.3)), dn, own, p.buildingId);
            a.free = a.blockedBy == null;
            out.add(a);
        }
        // Сначала свободные, среди них ближайшие, среди равных — те, до чьей стены ближе.
        out.sort(java.util.Comparator
                .<Approach>comparingInt(a -> a.free ? 0 : 1)
                .thenComparingInt(a -> a.nearest ? 0 : 1)
                .thenComparingDouble(a -> a.footDistance));
        return out;
    }

    /** Подход прямо в точку: своего полигона у неё нет, выходить не из чего. */
    private static Approach direct(ConnectionPoint p, double gap) {
        Approach a = new Approach();
        a.pointId = p.id;
        a.point = p.at;
        a.clearance = gap;
        a.direction = new double[]{1, 0};
        a.wall = p.at;
        a.at = p.at;
        a.nearest = true;
        a.free = true;
        return a;
    }

    /**
     * Своё здание с минимальным расстоянием. Его пересекает только отвод от точки
     * подключения, для чужих трасс оно остаётся препятствием. Формула буфера та же,
     * что в Obstacles: иначе точка окажется заперта в собственном доме.
     */
    public static Map<String, Geometry> ownZones(InputData data, Rules rules, CalcSettings settings, int dnClass) {
        Map<String, Geometry> out = new HashMap<>();
        double clearance = clearance(rules, settings, dnClass);
        for (ConnectionPoint p : data.points) {
            Geometry own = ownBuilding(p, data, settings);
            if (own != null) {
                out.put(p.id, own.buffer(clearance + 0.5));
            }
        }
        return out;
    }

    /** Минимальное расстояние до стены с половиной ширины пары труб и запасом на упрощение. */
    public static double clearance(Rules rules, CalcSettings settings, int dnClass) {
        double half = settings.clearanceFromGabarit ? rules.byDn(dnClass).width / 2 : 0;
        return rules.rule("oks").clearanceFor(dnClass) + half + Math.max(0, settings.obstacleSimplify);
    }

    /**
     * Что считать своим домом. Один объект restriction часто содержит десятки зданий
     * одного адреса. CORPUS — корпус с точкой и примыкающие к нему вплотную части: это одно
     * здание, разрезанное на полигоны. FEATURE — весь объект, PART — только полигон с точкой.
     */
    public static Geometry ownBuilding(ConnectionPoint p, InputData data, CalcSettings settings) {
        if (p.buildingId == null) {
            return null;
        }
        Restriction building = data.restrictionById.get(p.buildingId);
        if (building == null || building.geom == null) {
            return null;
        }
        if ("FEATURE".equals(settings.ownBuildingScope)) {
            return building.geom;
        }
        if ("PART".equals(settings.ownBuildingScope)) {
            return partContaining(building.geom, p.at);
        }
        return corpus(p, data);
    }

    /**
     * Корпус, в котором стоит точка: полигон с точкой и все части того же объекта,
     * примыкающие к нему вплотную, цепочкой. Щели до полуметра закрываются.
     */
    public static Polygon corpus(ConnectionPoint p, InputData data) {
        Restriction building = p.buildingId == null ? null : data.restrictionById.get(p.buildingId);
        if (building == null || building.geom == null) {
            return null;
        }
        Polygon seed = partContaining(building.geom, p.at);
        if (seed == null) {
            return null;
        }
        List<Geometry> group = new ArrayList<>();
        group.add(seed);
        List<Geometry> rest = new ArrayList<>();
        for (int i = 0; i < building.geom.getNumGeometries(); i++) {
            if (building.geom.getGeometryN(i) != seed) {
                rest.add(building.geom.getGeometryN(i));
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (java.util.Iterator<Geometry> it = rest.iterator(); it.hasNext(); ) {
                Geometry part = it.next();
                for (Geometry g : group) {
                    if (part.distance(g) <= TOUCH) {
                        group.add(part);
                        it.remove();
                        grew = true;
                        break;
                    }
                }
            }
        }
        if (group.size() == 1) {
            return seed;
        }
        Geometry merged = org.locationtech.jts.operation.union.UnaryUnionOp.union(group)
                .buffer(TOUCH / 2).buffer(-TOUCH / 2);
        Polygon best = partContaining(merged, p.at);
        return best != null ? best : seed;
    }

    /** Полигон, внутри которого лежит точка, или ближайший, если точка чуть снаружи. */
    public static Polygon partContaining(Geometry geometry, Coordinate point) {
        Polygon nearest = null;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry part = geometry.getGeometryN(i);
            if (!(part instanceof Polygon)) {
                continue;
            }
            if (part.covers(Geo.point(point))) {
                return (Polygon) part;
            }
            double d = part.distance(Geo.point(point));
            if (d < best) {
                best = d;
                nearest = (Polygon) part;
            }
        }
        return best < 1.0 ? nearest : null;
    }
}
