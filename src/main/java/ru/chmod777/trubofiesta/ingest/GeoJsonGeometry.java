package ru.chmod777.trubofiesta.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import ru.chmod777.trubofiesta.geo.Crs;
import ru.chmod777.trubofiesta.geo.Geo;

import java.util.ArrayList;
import java.util.List;

/** Геометрия GeoJSON в JTS, сразу с переводом координат в метры. */
public final class GeoJsonGeometry {

    private GeoJsonGeometry() {
    }

    public static Coordinate point(JsonNode geometry) {
        JsonNode c = geometry.path("coordinates");
        return Crs.toMeters(c.get(0).asDouble(), c.get(1).asDouble());
    }

    public static List<Coordinate> lineString(JsonNode geometry) {
        return coords(geometry.path("coordinates"));
    }

    /** Линия или мультилиния в одну геометрию: у ограничений допустимы оба типа, раздел 1.1. */
    public static Geometry linear(JsonNode geometry) {
        if (!"MultiLineString".equals(geometry.path("type").asText())) {
            return Geo.line(lineString(geometry));
        }
        List<LineString> parts = new ArrayList<>();
        for (JsonNode line : geometry.path("coordinates")) {
            List<Coordinate> pts = Geo.dedupe(coords(line));
            if (pts.size() >= 2) {
                parts.add(Geo.line(pts));
            }
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return Geo.F.createMultiLineString(parts.toArray(new LineString[0]));
    }

    /** Полигон или мультиполигон в одну геометрию. Дырки сохраняются. */
    public static Geometry polygonal(JsonNode geometry) {
        String type = geometry.path("type").asText();
        List<Polygon> polygons = new ArrayList<>();
        if ("Polygon".equals(type)) {
            polygons.add(polygon(geometry.path("coordinates")));
        } else if ("MultiPolygon".equals(type)) {
            for (JsonNode poly : geometry.path("coordinates")) {
                polygons.add(polygon(poly));
            }
        } else {
            return null;
        }
        if (polygons.size() == 1) {
            return polygons.get(0);
        }
        return Geo.F.createMultiPolygon(polygons.toArray(new Polygon[0]));
    }

    private static Polygon polygon(JsonNode rings) {
        LinearRing shell = ring(rings.get(0));
        List<LinearRing> holes = new ArrayList<>();
        for (int i = 1; i < rings.size(); i++) {
            holes.add(ring(rings.get(i)));
        }
        return Geo.F.createPolygon(shell, holes.toArray(new LinearRing[0]));
    }

    private static LinearRing ring(JsonNode node) {
        List<Coordinate> pts = coords(node);
        if (pts.size() < 4 || !pts.get(0).equals2D(pts.get(pts.size() - 1))) {
            pts.add(new Coordinate(pts.get(0)));
        }
        return Geo.F.createLinearRing(pts.toArray(new Coordinate[0]));
    }

    private static List<Coordinate> coords(JsonNode array) {
        List<Coordinate> pts = new ArrayList<>();
        for (JsonNode c : array) {
            pts.add(Crs.toMeters(c.get(0).asDouble(), c.get(1).asDouble()));
        }
        return pts;
    }
}
