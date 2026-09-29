package ru.chmod777.trubofiesta.constraints;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.chmod777.trubofiesta.geo.Geo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Препятствия для одного класса условных диаметров: запретная геометрия с уже
 * заложенными минимальными расстояниями и отдельно зоны специальных проходов.
 */
public class ObstacleSet {

    /** Движение вдоль линейного препятствия ближе этого угла — не пересечение, а проход рядом. */
    public static final double ALONG_DEG = 20;

    public final int dn;
    private final Geometry forbidden;
    private final PreparedGeometry prepared;
    private final List<SpecialZone> specials;
    private final STRtree specialIndex = new STRtree();
    private final Map<Double, Geometry> near = new HashMap<>();
    /** В этом радиусе от врезки её собственная сеть не считается пересечением. */
    public final double tieInExemptRadius;

    public ObstacleSet(int dn, Geometry forbidden, List<SpecialZone> specials, double tieInExemptRadius) {
        this.dn = dn;
        this.forbidden = forbidden;
        this.prepared = PreparedGeometryFactory.prepare(forbidden);
        this.specials = specials;
        this.tieInExemptRadius = tieInExemptRadius;
        for (SpecialZone z : specials) {
            specialIndex.insert(z.zone.getEnvelopeInternal(), z);
        }
        if (!specials.isEmpty()) {
            specialIndex.build();
        }
    }

    public Geometry forbidden() {
        return forbidden;
    }

    public List<SpecialZone> specials() {
        return specials;
    }

    public boolean blocked(Coordinate c) {
        return prepared.intersects(Geo.point(c));
    }

    /** Запретная геометрия, расширенная на distance: узлы в этой полосе проверяются точно. */
    public synchronized Geometry near(double distance) {
        return near.computeIfAbsent(distance, d -> forbidden.isEmpty() ? forbidden : forbidden.buffer(d, 4));
    }

    /** Отрезок проходим, если не задевает запретную геометрию. */
    public boolean visible(Coordinate a, Coordinate b) {
        if (a.distance(b) < 1e-9) {
            return !blocked(a);
        }
        LineString line = Geo.F.createLineString(new Coordinate[]{a, b});
        return !prepared.intersects(line);
    }

    /**
     * Отрезок проходим, если задевает только собственное здание подключаемого ОКС.
     * Труба выходит из своего дома, и минимальное расстояние до него к ней не применяется.
     */
    public boolean visibleIgnoring(Coordinate a, Coordinate b, Geometry ignore) {
        if (visible(a, b)) {
            return true;
        }
        if (ignore == null) {
            return false;
        }
        LineString line = Geo.F.createLineString(new Coordinate[]{a, b});
        Geometry hit = forbidden.intersection(line);
        if (hit.isEmpty()) {
            return true;
        }
        Geometry rest = hit.difference(ignore);
        return rest.isEmpty() || rest.getLength() < 0.05;
    }

    /** Итог проверки одного отрезка трассы: можно ли его провести и какой коэффициент на нём. */
    public static class EdgeInfo {
        public boolean blocked;
        public double k = 1.0;
    }

    /**
     * Проверяет отрезок по всем правилам таблицы 5.1: запретные зоны, угол пересечения,
     * запрет идти вдоль линейного объекта ближе минимального расстояния.
     *
     * @param ignore       геометрия, которую отрезок может пересекать (своё здание)
     * @param tieIn        точка врезки, рядом с которой её собственная сеть не пересечение
     */
    @SuppressWarnings("unchecked")
    public EdgeInfo edge(Coordinate a, Coordinate b, Geometry ignore, Coordinate tieIn) {
        EdgeInfo out = new EdgeInfo();
        boolean ok = ignore == null ? visible(a, b) : visibleIgnoring(a, b, ignore);
        if (!ok) {
            out.blocked = true;
            return out;
        }
        if (specials.isEmpty() || a.distance(b) < 1e-9) {
            return out;
        }
        LineString line = Geo.F.createLineString(new Coordinate[]{a, b});
        for (SpecialZone z : (List<SpecialZone>) specialIndex.query(line.getEnvelopeInternal())) {
            if (!z.zone.intersects(line)) {
                continue;
            }
            Geometry inside = z.zone.intersection(line);
            if (inside.getLength() < 1e-6) {
                continue;
            }
            Coordinate mid = inside.getCentroid().getCoordinate();
            if (tieIn != null && Obstacles.HEAT_NETWORK.equals(z.type) && mid.distance(tieIn) <= tieInExemptRadius) {
                continue;
            }
            double angle = crossingAngle(Math.atan2(b.y - a.y, b.x - a.x), z.axisAngle(mid));
            if (angle < ALONG_DEG) {
                if (z.band.intersects(line)) {
                    out.blocked = true;
                    return out;
                }
                continue;
            }
            if (z.rule.minAngle > 0 && angle < z.rule.minAngle - 0.5) {
                out.blocked = true;
                return out;
            }
            out.k = Math.max(out.k, z.rule.k);
        }
        return out;
    }

    /**
     * Точка лежит в зоне полигонного спецобъекта: дороги или трамвайных путей. Спецпроход
     * выполняется одним прямым участком (раздел 4), и он тянется на 3 м за границу полигона,
     * поэтому в такой точке трасса не поворачивает.
     */
    @SuppressWarnings("unchecked")
    public boolean inPolygonZone(Coordinate c) {
        if (specials.isEmpty()) {
            return false;
        }
        org.locationtech.jts.geom.Point p = Geo.point(c);
        for (SpecialZone z : (List<SpecialZone>) specialIndex.query(new org.locationtech.jts.geom.Envelope(c))) {
            if (z.object.getDimension() == 2 && z.zone.covers(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Отрезок a→b пересекает линейный спецобъект (газопровод, кабель, существующую теплосеть)
     * ближе к точке at, чем длина спецучастка вокруг пересечения. Поворот в такой точке
     * разорвал бы спецучасток на два. Касание концом отрезка (отход от врезки, которая стоит
     * на самой трубе) пересечением не считается.
     */
    @SuppressWarnings("unchecked")
    public boolean crossesLineNear(Coordinate a, Coordinate b, Coordinate at, double margin) {
        if (specials.isEmpty() || a.distance(b) < 1e-9) {
            return false;
        }
        LineString seg = Geo.F.createLineString(new Coordinate[]{a, b});
        for (SpecialZone z : (List<SpecialZone>) specialIndex.query(seg.getEnvelopeInternal())) {
            if (z.object.getDimension() == 2 || !z.object.intersects(seg)) {
                continue;
            }
            for (Coordinate p : seg.intersection(z.object).getCoordinates()) {
                if (p.distance(a) > 1e-6 && p.distance(b) > 1e-6 && p.distance(at) <= z.rule.specialBuffer + margin) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Зоны, в которые попадает прямоугольник, для разбиения ломаной на спецучастки. */
    @SuppressWarnings("unchecked")
    public List<SpecialZone> zonesNear(org.locationtech.jts.geom.Envelope box) {
        return specials.isEmpty() ? new java.util.ArrayList<>() : specialIndex.query(box);
    }

    /** Острый угол между двумя направлениями, градусы от 0 до 90. */
    public static double crossingAngle(double a, double b) {
        double d = Math.abs(Math.toDegrees(a - b)) % 180;
        return d > 90 ? 180 - d : d;
    }
}
