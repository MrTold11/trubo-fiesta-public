package ru.chmod777.trubofiesta.constraints;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.chmod777.trubofiesta.geo.Geo;

import java.util.ArrayList;
import java.util.List;

/**
 * Делит ломаную новой сети на обычные и специальные участки. Одна реализация на
 * автоматический расчёт и на загруженную схему: одинаковая геометрия — одинаковая цена.
 *
 * Спецучасток появляется только там, где трасса действительно пересекает объект.
 * Проход рядом с дорогой в пределах её трёхметровой зоны — обычная прокладка.
 * Если зоны нескольких объектов накладываются, получается один спецучасток
 * с наибольшим коэффициентом, без перемножения.
 */
public final class LayingSplit {

    /** Ближе этого к концу ломаной технический узел не ставится, м. */
    private static final double END_SNAP = 1.0;

    private LayingSplit() {
    }

    /** Часть ломаной с одним способом прокладки. */
    public static class Part {
        public List<Coordinate> pts = new ArrayList<>();
        /** Тип пересекаемого объекта или null для обычной прокладки. */
        public String type;
        public double k = 1.0;

        public boolean special() {
            return type != null;
        }
    }

    /**
     * @param tieIns точки врезки на концах ломаной: сеть, в которую врезаемся,
     *               рядом с ними не считается пересечением
     */
    public static List<Part> split(ObstacleSet obstacles, List<Coordinate> pts, List<Coordinate> tieIns) {
        List<Part> out = new ArrayList<>();
        if (pts.size() < 2) {
            return out;
        }
        LineString line = Geo.line(pts);
        double total = Geo.length(pts);
        List<double[]> cuts = new ArrayList<>();
        List<String> types = new ArrayList<>();

        for (SpecialZone z : obstacles.zonesNear(line.getEnvelopeInternal())) {
            if (!line.intersects(z.object)) {
                continue;
            }
            if (z.object.getDimension() < 2) {
                // Газопровод, кабель, теплосеть: спецучасток по specialBuffer метров вдоль трассы
                // от каждой точки пересечения, таблица 5.1.
                for (Coordinate touch : line.intersection(z.object).getCoordinates()) {
                    if (tieIns != null && Obstacles.HEAT_NETWORK.equals(z.type)
                            && nearAny(touch, tieIns, obstacles.tieInExemptRadius)) {
                        continue;
                    }
                    double m = Geo.measure(pts, touch);
                    types.add(z.type);
                    cuts.add(new double[]{Math.max(0, m - z.rule.specialBuffer),
                            Math.min(total, m + z.rule.specialBuffer), z.rule.k, types.size() - 1});
                }
                continue;
            }
            // Дорога, пути: полигон и specialBuffer метров за его границей с каждой стороны.
            Geometry inside = z.object.buffer(z.rule.specialBuffer).intersection(line);
            for (int g = 0; g < inside.getNumGeometries(); g++) {
                Geometry part = inside.getGeometryN(g);
                if (part.getLength() < 0.01 || !part.intersects(z.object)) {
                    continue;
                }
                Coordinate[] c = part.getCoordinates();
                double m1 = Geo.measure(pts, c[0]);
                double m2 = Geo.measure(pts, c[c.length - 1]);
                types.add(z.type);
                cuts.add(new double[]{Math.min(m1, m2), Math.max(m1, m2), z.rule.k, types.size() - 1});
            }
        }

        cuts = merge(cuts);
        // Граница спецучастка ближе метра к концу ломаной притягивается к концу: камера или
        // точка подключения стоит почти на границе зоны, и технический узел в двух сантиметрах
        // от неё с обычным участком такой же длины никому не нужен.
        for (double[] cut : cuts) {
            if (cut[0] < END_SNAP) {
                cut[0] = 0;
            }
            if (cut[1] > total - END_SNAP) {
                cut[1] = total;
            }
        }
        cuts = merge(cuts);
        double cursor = 0;
        for (double[] cut : cuts) {
            if (cut[0] > cursor + 0.01) {
                add(out, Geo.substring(pts, cursor, cut[0]), null, 1.0);
            }
            add(out, Geo.substring(pts, Math.max(cursor, cut[0]), cut[1]), types.get((int) cut[3]), cut[2]);
            cursor = Math.max(cursor, cut[1]);
        }
        if (cursor < total - 0.01) {
            add(out, Geo.substring(pts, cursor, total), null, 1.0);
        }
        return out;
    }

    private static boolean nearAny(Coordinate c, List<Coordinate> points, double radius) {
        for (Coordinate p : points) {
            if (p != null && p.distance(c) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** Наложенные зоны сливаются в один спецучасток с наибольшим коэффициентом. */
    private static List<double[]> merge(List<double[]> cuts) {
        List<double[]> sorted = new ArrayList<>(cuts);
        sorted.sort((x, y) -> Double.compare(x[0], y[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] cut : sorted) {
            double[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && cut[0] <= last[1] + 1e-6) {
                last[1] = Math.max(last[1], cut[1]);
                if (cut[2] > last[2]) {
                    last[2] = cut[2];
                    last[3] = cut[3];
                }
            } else {
                merged.add(new double[]{cut[0], cut[1], cut[2], cut[3]});
            }
        }
        return merged;
    }

    private static void add(List<Part> out, List<Coordinate> pts, String type, double k) {
        if (pts.size() < 2 || Geo.length(pts) < 0.01) {
            return;
        }
        Part last = out.isEmpty() ? null : out.get(out.size() - 1);
        if (last != null && java.util.Objects.equals(last.type, type) && Math.abs(last.k - k) < 1e-9) {
            for (int i = 1; i < pts.size(); i++) {
                last.pts.add(pts.get(i));
            }
            return;
        }
        Part part = new Part();
        part.pts.addAll(pts);
        part.type = type;
        part.k = k;
        out.add(part);
    }
}
