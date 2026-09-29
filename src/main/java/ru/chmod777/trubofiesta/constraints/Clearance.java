package ru.chmod777.trubofiesta.constraints;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.model.Restriction;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.List;
import java.util.Locale;

/**
 * Точная проверка минимальных расстояний для готового участка по его собственному диаметру.
 * Сетка строится с одним отступом на весь расчёт, а норма зависит от Ду участка, который
 * проходит рядом: так ответили организаторы. Поэтому каждый участок проверяется отдельно,
 * по исходным полигонам, без упрощения.
 */
public class Clearance {

    /**
     * Запас сверх нормы. Организаторы разрешают округлять 4,999 до 5 м, но выгрузка
     * округляет координаты, и чужая проверка может насчитать на миллиметры меньше.
     */
    private static final double ROUNDING = -0.005;

    private final Obstacles obstacles;
    private final STRtree index = new STRtree();
    private final List<SpecialZone> specials;

    private static final class Part {
        final Restriction restriction;
        final Geometry geometry;

        Part(Restriction restriction, Geometry geometry) {
            this.restriction = restriction;
            this.geometry = geometry;
        }
    }

    private final CalcSettings settings;

    public Clearance(InputData data, Obstacles obstacles, CalcSettings settings, List<SpecialZone> specials) {
        this.obstacles = obstacles;
        this.settings = settings;
        this.specials = specials;
        for (Restriction r : data.restrictions) {
            if (r.geom == null || r.geom.isEmpty() || r.rule.isSpecial()) {
                continue;
            }
            if (r.geom.getDimension() == 2 && r.geom.getArea() < settings.minRestrictionArea) {
                continue;
            }
            if (r.futureOksBuilding && !settings.futureOksAsObstacle) {
                continue;
            }
            for (int i = 0; i < r.geom.getNumGeometries(); i++) {
                Geometry part = r.geom.getGeometryN(i);
                index.insert(part.getEnvelopeInternal(), new Part(r, part));
            }
        }
        index.build();
    }

    /**
     * Точки, где новая сеть пересекает спецобъекты. Рядом с ними обычный участок подходит
     * к объекту под углом — это подход к пересечению, а не проход вдоль.
     */
    public java.util.List<Coordinate> crossings(java.util.List<java.util.List<Coordinate>> lines) {
        return crossings(lines, null);
    }

    /** Пересечения новых участков со спецобъектами одного типа; null — со всеми. */
    public java.util.List<Coordinate> crossings(java.util.List<java.util.List<Coordinate>> lines, String type) {
        java.util.List<Coordinate> out = new java.util.ArrayList<>();
        for (java.util.List<Coordinate> pts : lines) {
            LineString line = Geo.line(pts);
            for (SpecialZone z : specials) {
                if (type != null && !type.equals(z.type)) {
                    continue;
                }
                if (line.getEnvelopeInternal().intersects(z.object.getEnvelopeInternal()) && line.intersects(z.object)) {
                    Geometry hit = z.object.getDimension() == 2 ? line.intersection(z.object.getBoundary())
                            : line.intersection(z.object);
                    java.util.Collections.addAll(out, hit.getCoordinates());
                }
            }
        }
        return out;
    }

    /**
     * Обычный участок рядом со спецобъектом: дорога, газопровод, кабель, существующая теплосеть.
     * Идти вдоль них ближе нормы нельзя; отступ — между наружными границами габаритов.
     * Не считается проходом вдоль подход к точке пересечения и отход от своей врезки.
     *
     * @param tieIn точка врезки, если участок начинается в ней
     */
    public String sideViolation(String segmentId, List<Coordinate> pts, int dn, List<Coordinate> crossings,
                                Coordinate tieIn) {
        double half = obstacles.halfWidth(dn);
        LineString line = Geo.line(pts);
        for (SpecialZone z : specials) {
            if (!line.getEnvelopeInternal().intersects(z.band.getEnvelopeInternal())) {
                continue;
            }
            double side = z.rule.clearance == null ? 0 : z.rule.clearance;
            // Запас на упрощение границ и на чужую проекцию: независимая проверка на наборе
            // полигона мерила 2,00 м там, где мы насчитали 2,04, и сочла это проходом вдоль.
            double need = side + half + z.existingHalf + Math.max(0, settings.obstacleSimplify);
            if (z.object.distance(line) >= need - ROUNDING) {
                continue;
            }
            double reach = Math.max(need, z.rule.specialBuffer) + 0.3;
            // У своей врезки труба неизбежно рядом с сетью, в которую врезается: это отход, а не
            // проход вдоль. Отход под углом α выходит за норму через need / sin α метров пути.
            boolean ownTieIn = tieIn != null && z.object.distance(Geo.point(tieIn)) < 0.5;
            double departure = ownTieIn ? need / Math.sin(Math.toRadians(Math.max(
                    ObstacleSet.ALONG_DEG, departureAngle(pts, z, tieIn)))) + 0.3 : 0;
            for (int i = 1; i < pts.size(); i++) {
                Coordinate a = pts.get(i - 1);
                Coordinate b = pts.get(i);
                double length = a.distance(b);
                int steps = Math.max(1, (int) Math.ceil(length / 0.25));
                for (int k = 0; k <= steps; k++) {
                    Coordinate p = new Coordinate(a.x + (b.x - a.x) * k / steps, a.y + (b.y - a.y) * k / steps);
                    boolean approach = ownTieIn && tieIn.distance(p) <= departure;
                    for (Coordinate c : crossings) {
                        if (c.distance(p) <= reach) {
                            approach = true;
                            break;
                        }
                    }
                    if (approach) {
                        continue;
                    }
                    double d = z.object.distance(Geo.point(p));
                    if (d < need - ROUNDING) {
                        return String.format(Locale.US, "Участок %s (Ду%d) идёт вдоль объекта %s %s в %.2f м от оси, норма %.2f м",
                                segmentId, dn, z.rule.title, z.restrictionId, d, need);
                    }
                }
            }
        }
        return null;
    }

    /** Угол между первым звеном от врезки и осью существующей трубы в этой точке, градусы. */
    private static double departureAngle(List<Coordinate> pts, SpecialZone z, Coordinate tieIn) {
        double first = Math.atan2(pts.get(1).y - pts.get(0).y, pts.get(1).x - pts.get(0).x);
        return ObstacleSet.crossingAngle(first, z.axisAngle(tieIn));
    }

    /**
     * Нарушение минимального расстояния или null. Последний прямой отрезок к точке подключения
     * может проходить сквозь своё здание: это отвод, по нормали выходящий из дома.
     *
     * @param ownBuilding своё здание точки на конце участка или null
     * @param ownId       id объекта, к которому относится своё здание
     */
    @SuppressWarnings("unchecked")
    public String violation(String segmentId, List<Coordinate> pts, int dn, Geometry ownBuilding, String ownId) {
        double half = obstacles.halfWidth(dn);
        for (int i = 1; i < pts.size(); i++) {
            LineString edge = Geo.line(java.util.Arrays.asList(pts.get(i - 1), pts.get(i)));
            boolean lead = ownBuilding != null && i == pts.size() - 1;
            Envelope box = new Envelope(edge.getEnvelopeInternal());
            box.expandBy(10 + half);
            for (Part p : (List<Part>) index.query(box)) {
                double need = obstacles.clearance(p.restriction, p.geometry, dn) + half;
                double d = p.geometry.distance(edge);
                if (d >= need - ROUNDING) {
                    continue;
                }
                if (lead && p.restriction.id.equals(ownId) && p.geometry.distance(ownBuilding) < 0.6) {
                    continue;
                }
                return String.format(Locale.US, "Участок %s (Ду%d) проходит в %.2f м от %s %s, норма %.2f м от оси",
                        segmentId, dn, d, p.restriction.rule.title, p.restriction.id, need);
            }
        }
        return null;
    }
}
