package ru.chmod777.trubofiesta.model;

import org.locationtech.jts.geom.Coordinate;
import ru.chmod777.trubofiesta.geo.Geo;

import java.util.List;

/** Существующий участок тепловой сети. Координаты в метрах. */
public class Segment {

    public String id;
    public List<Coordinate> pts;
    public int dn;

    /** ID исходного участка входного файла, если участок был разрезан камерой. */
    public String originId;
    /** Смещение начала участка вдоль исходного, м. */
    public double originOffset;

    /** Узел, которым участок смотрит в сторону источника. Заполняет TopologyBuilder. */
    public String upstreamNode;
    public String downstreamNode;

    public double length() {
        return Geo.length(pts);
    }

    public Coordinate start() {
        return pts.get(0);
    }

    public Coordinate end() {
        return pts.get(pts.size() - 1);
    }
}
