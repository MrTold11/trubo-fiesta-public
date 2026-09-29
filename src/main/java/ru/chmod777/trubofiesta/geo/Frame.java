package ru.chmod777.trubofiesta.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.List;

/**
 * Оси, по которым прокладывается трасса. Кварталы редко смотрят строго на север,
 * а инженер ведёт сеть вдоль фасадов и улиц. Поэтому сетка поворачивается на угол,
 * который чаще всего встречается у стен зданий, и тогда повороты на 45 и 90 градусов
 * ложатся вдоль застройки.
 */
public final class Frame {

    public final double angle;
    private final double cos;
    private final double sin;

    public Frame(double angle) {
        this.angle = angle;
        this.cos = Math.cos(angle);
        this.sin = Math.sin(angle);
    }

    public double u(Coordinate c) {
        return c.x * cos + c.y * sin;
    }

    public double v(Coordinate c) {
        return -c.x * sin + c.y * cos;
    }

    public Coordinate world(double u, double v) {
        return new Coordinate(u * cos - v * sin, u * sin + v * cos);
    }

    /** Направление в плане для вектора, заданного в осях сетки. */
    public double[] worldVector(double du, double dv) {
        return new double[]{du * cos - dv * sin, du * sin + dv * cos};
    }

    /** Вектор в осях сетки для направления, заданного в плане. */
    public double[] localVector(double dx, double dy) {
        return new double[]{dx * cos + dy * sin, -dx * sin + dy * cos};
    }

    /**
     * Преобладающее направление стен. Каждая стена голосует своей длиной за угол
     * по модулю 90 градусов, побеждает самый частый угол.
     */
    public static Frame dominant(List<Geometry> buildings) {
        double[] votes = new double[90];
        for (Geometry g : buildings) {
            for (int p = 0; p < g.getNumGeometries(); p++) {
                Coordinate[] ring = g.getGeometryN(p).getBoundary().getCoordinates();
                for (int i = 1; i < ring.length; i++) {
                    double dx = ring[i].x - ring[i - 1].x;
                    double dy = ring[i].y - ring[i - 1].y;
                    double length = Math.hypot(dx, dy);
                    if (length < 2.0) {
                        continue;
                    }
                    double deg = Math.toDegrees(Math.atan2(dy, dx));
                    int bin = (int) Math.floor(((deg % 90) + 90) % 90);
                    votes[Math.min(89, bin)] += length;
                }
            }
        }
        int best = 0;
        double bestScore = -1;
        for (int i = 0; i < 90; i++) {
            double score = 0;
            for (int k = -2; k <= 2; k++) {
                score += votes[(i + k + 90) % 90];
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (bestScore <= 0) {
            return new Frame(0);
        }
        // Уточняем угол средним по соседним корзинам, чтобы не терять доли градуса.
        double sum = 0;
        double weight = 0;
        for (int k = -2; k <= 2; k++) {
            double w = votes[(best + k + 90) % 90];
            sum += (best + k + 0.5) * w;
            weight += w;
        }
        double deg = weight > 0 ? sum / weight : best;
        return new Frame(Math.toRadians(((deg % 90) + 90) % 90));
    }
}
