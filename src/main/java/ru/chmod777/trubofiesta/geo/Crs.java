package ru.chmod777.trubofiesta.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Перевод координат между WGS 84 (вход и выход) и UTM 37N (все расчёты).
 * Техническое приложение требует считать длины и буферы в EPSG:32637.
 */
public final class Crs {

    public static final String WGS84 = "EPSG:4326";
    public static final String UTM37N = "EPSG:32637";

    private static final CoordinateTransform TO_METERS;
    private static final CoordinateTransform TO_DEGREES;

    static {
        CRSFactory crs = new CRSFactory();
        CoordinateReferenceSystem wgs = crs.createFromName(WGS84);
        CoordinateReferenceSystem utm = crs.createFromName(UTM37N);
        CoordinateTransformFactory factory = new CoordinateTransformFactory();
        TO_METERS = factory.createTransform(wgs, utm);
        TO_DEGREES = factory.createTransform(utm, wgs);
    }

    private Crs() {
    }

    /** Долгота и широта в метры UTM. */
    public static Coordinate toMeters(double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        TO_METERS.transform(new ProjCoordinate(lon, lat), out);
        return new Coordinate(out.x, out.y);
    }

    /** Метры UTM обратно в долготу и широту. */
    public static Coordinate toDegrees(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        TO_DEGREES.transform(new ProjCoordinate(x, y), out);
        return new Coordinate(out.x, out.y);
    }
}
