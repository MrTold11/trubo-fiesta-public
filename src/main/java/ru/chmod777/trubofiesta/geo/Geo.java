package ru.chmod777.trubofiesta.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.util.ArrayList;
import java.util.List;

/** Мелкие геометрические операции, которых не хватает в JTS в готовом виде. */
public final class Geo {

    public static final GeometryFactory F = new GeometryFactory(new PrecisionModel(), 32637);

    private Geo() {
    }

    public static Point point(Coordinate c) {
        return F.createPoint(c);
    }

    public static LineString line(List<Coordinate> pts) {
        return F.createLineString(pts.toArray(new Coordinate[0]));
    }

    /** Длина ломаной в метрах. */
    public static double length(List<Coordinate> pts) {
        double sum = 0;
        for (int i = 1; i < pts.size(); i++) {
            sum += pts.get(i - 1).distance(pts.get(i));
        }
        return sum;
    }

    /**
     * Длина, которая уходит в выгрузку: до сантиметра. Стоимость считается ровно от неё,
     * чтобы цену можно было проверить по полям length и cost без расхождений на округлении.
     */
    public static double roundedLength(List<Coordinate> pts) {
        return Math.round(length(pts) * 100) / 100.0;
    }

    /**
     * Убирает вершины, в которых трасса почти не поворачивает.
     * Нужно, чтобы результат не выглядел мелкой случайной ломаной: этого прямо
     * требует раздел 2.3 кейса и критерий «качество трассировки».
     */
    public static List<Coordinate> simplify(List<Coordinate> pts, double minAngleDeg) {
        if (pts.size() < 3) {
            return new ArrayList<>(pts);
        }
        List<Coordinate> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            Coordinate a = out.get(out.size() - 1);
            Coordinate b = pts.get(i);
            Coordinate c = pts.get(i + 1);
            if (turnDegrees(a, b, c) >= minAngleDeg) {
                out.add(b);
            }
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    /** Угол поворота трассы в точке b, градусы. 0 — идём прямо. */
    public static double turnDegrees(Coordinate a, Coordinate b, Coordinate c) {
        double a1 = Math.atan2(b.y - a.y, b.x - a.x);
        double a2 = Math.atan2(c.y - b.y, c.x - b.x);
        double d = Math.toDegrees(Math.abs(a2 - a1));
        if (d > 180) {
            d = 360 - d;
        }
        return d;
    }

    /** Острый угол между двумя отрезками, градусы. Нужен для правила «пересечение не менее 45 градусов». */
    public static double crossingAngle(Coordinate a1, Coordinate a2, Coordinate b1, Coordinate b2) {
        double d = Math.toDegrees(Math.abs(
                Math.atan2(a2.y - a1.y, a2.x - a1.x) - Math.atan2(b2.y - b1.y, b2.x - b1.x)));
        d = d % 180;
        return d > 90 ? 180 - d : d;
    }

    /** Точка на ломаной, отстоящая от начала на distance метров. */
    public static Coordinate along(List<Coordinate> pts, double distance) {
        double left = distance;
        for (int i = 1; i < pts.size(); i++) {
            double seg = pts.get(i - 1).distance(pts.get(i));
            if (left <= seg || i == pts.size() - 1) {
                double t = seg == 0 ? 0 : Math.max(0, Math.min(1, left / seg));
                Coordinate a = pts.get(i - 1);
                Coordinate b = pts.get(i);
                return new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            }
            left -= seg;
        }
        return pts.get(pts.size() - 1);
    }

    /** Расстояние от начала ломаной до проекции точки на неё. */
    public static double measure(List<Coordinate> pts, Coordinate p) {
        double best = Double.MAX_VALUE;
        double bestMeasure = 0;
        double passed = 0;
        for (int i = 1; i < pts.size(); i++) {
            Coordinate a = pts.get(i - 1);
            Coordinate b = pts.get(i);
            double seg = a.distance(b);
            double t = seg == 0 ? 0 : ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / (seg * seg);
            t = Math.max(0, Math.min(1, t));
            Coordinate proj = new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            double d = proj.distance(p);
            if (d < best) {
                best = d;
                bestMeasure = passed + seg * t;
            }
            passed += seg;
        }
        return bestMeasure;
    }

    /** Часть ломаной от from до to метров по её длине. */
    public static List<Coordinate> substring(List<Coordinate> pts, double from, double to) {
        List<Coordinate> out = new ArrayList<>();
        out.add(along(pts, from));
        double passed = 0;
        for (int i = 1; i < pts.size(); i++) {
            double seg = pts.get(i - 1).distance(pts.get(i));
            double at = passed + seg;
            if (at > from && at < to) {
                out.add(pts.get(i));
            }
            passed = at;
        }
        out.add(along(pts, to));
        return dedupe(out);
    }

    /** Выбрасывает точки, совпадающие с предыдущей. */
    public static List<Coordinate> dedupe(List<Coordinate> pts) {
        List<Coordinate> out = new ArrayList<>();
        for (Coordinate c : pts) {
            if (out.isEmpty() || out.get(out.size() - 1).distance(c) > 1e-6) {
                out.add(c);
            }
        }
        if (out.size() == 1 && pts.size() > 1) {
            out.add(pts.get(pts.size() - 1));
        }
        return out;
    }

    /** Ключ точки с точностью до сантиметра — чтобы узлы сети сходились. */
    public static String key(Coordinate c) {
        return Math.round(c.x * 100) + "_" + Math.round(c.y * 100);
    }

    public static Geometry emptyPolygon() {
        return F.createPolygon();
    }
}
