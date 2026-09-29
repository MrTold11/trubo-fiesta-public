package ru.chmod777.trubofiesta.constraints;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.chmod777.trubofiesta.rules.RestrictionRule;

import java.util.ArrayList;
import java.util.List;

/** Зона специального прохода: дорога, пути, газопровод, кабель, существующая теплосеть. */
public class SpecialZone {
    public String restrictionId;
    public String type;
    /** Где труба считается спецучастком: объект плюс запас из таблицы 5.1. */
    public Geometry zone;
    /** Сам объект. Спецучасток бывает только там, где трасса его действительно пересекает. */
    public Geometry object;
    /** Полоса минимального расстояния сбоку: вдоль объекта внутри неё идти нельзя. */
    public Geometry band;
    public RestrictionRule rule;
    /** Половина габарита существующей коммуникации: отступ считается от её наружной границы. */
    public double existingHalf;
    /** Отрезки оси объекта. Угол пересечения считается от ближайшего из них. */
    public final List<Coordinate[]> axis = new ArrayList<>();

    public void collectAxis() {
        Geometry source = object.getDimension() == 2 ? object.getBoundary() : object;
        for (int g = 0; g < source.getNumGeometries(); g++) {
            Coordinate[] line = source.getGeometryN(g).getCoordinates();
            for (int i = 1; i < line.length; i++) {
                if (line[i - 1].distance(line[i]) > 1e-6) {
                    axis.add(new Coordinate[]{line[i - 1], line[i]});
                }
            }
        }
    }

    /** Направление объекта рядом с точкой, радианы в плане. */
    public double axisAngle(Coordinate at) {
        double best = Double.MAX_VALUE;
        double angle = 0;
        for (Coordinate[] s : axis) {
            double d = distance(at, s[0], s[1]);
            if (d < best) {
                best = d;
                angle = Math.atan2(s[1].y - s[0].y, s[1].x - s[0].x);
            }
        }
        return angle;
    }

    private static double distance(Coordinate p, Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2));
        return Math.hypot(a.x + t * dx - p.x, a.y + t * dy - p.y);
    }
}
