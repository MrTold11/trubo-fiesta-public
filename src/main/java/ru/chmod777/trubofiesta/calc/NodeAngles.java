package ru.chmod777.trubofiesta.calc;

import org.locationtech.jts.geom.Coordinate;
import ru.chmod777.trubofiesta.rules.Rules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Углы между лучами в узлах новой сети. Поворот внутри участка виден по его ломаной,
 * а поворот в камере, техническом узле или в точке присоединения — только по соседним
 * участкам. Правило одно: в узле труба продолжается с направления, с которым пришёл
 * участок со стороны сети, и каждый уходящий участок отклоняется от него на какой-то угол.
 *
 * Стоимости у угла нет (раздел 2.1: отдельное удорожание поворота не применяется).
 * Углы считаются как показатель инженерности: по ним отбирается инженерный вариант,
 * и они пишутся в выгрузку дополнительными свойствами.
 *
 * В узле присоединения нет приходящего участка: за опорное берётся направление самой
 * толстой ветки. Направление существующей трубы не учитывается.
 */
public final class NodeAngles {

    private NodeAngles() {
    }

    /**
     * Заполняет у каждого участка углы поворотов внутри него, угол в начальном узле и
     * число нестандартных отводов. В камере допуск шире, чем на трубе: отвода там нет,
     * труба входит в стенку камеры.
     */
    public static void measure(List<VariantResult.Seg> segments, Rules rules) {
        Map<String, double[]> arriving = new HashMap<>();
        for (VariantResult.Seg s : segments) {
            if (s.pts == null || s.pts.size() < 2) {
                continue;
            }
            arriving.put(s.endNodeId, direction(s.pts.get(s.pts.size() - 2), s.pts.get(s.pts.size() - 1)));
        }
        Map<String, VariantResult.Seg> reference = new HashMap<>();
        for (VariantResult.Seg s : segments) {
            if (s.pts == null || s.pts.size() < 2 || arriving.containsKey(s.startNodeId)) {
                continue;
            }
            VariantResult.Seg current = reference.get(s.startNodeId);
            if (current == null || s.dn > current.dn || (s.dn == current.dn && s.id.compareTo(current.id) < 0)) {
                reference.put(s.startNodeId, s);
            }
        }
        for (VariantResult.Seg s : segments) {
            s.turnAngles.clear();
            s.nodeAngle = null;
            s.oddTurns = 0;
            if (s.pts == null || s.pts.size() < 2) {
                continue;
            }
            for (int i = 1; i < s.pts.size() - 1; i++) {
                double deg = ru.chmod777.trubofiesta.geo.Geo.turnDegrees(s.pts.get(i - 1), s.pts.get(i), s.pts.get(i + 1));
                s.turnAngles.add(Math.round(deg * 10) / 10.0);
                if (!rules.turn.standard(deg)) {
                    s.oddTurns++;
                }
            }
            double[] ref = arriving.get(s.startNodeId);
            boolean technicalNode = false;
            if (ref == null) {
                VariantResult.Seg other = reference.get(s.startNodeId);
                if (other == null || other == s) {
                    continue;
                }
                ref = direction(other.pts.get(0), other.pts.get(1));
            } else {
                technicalNode = s.startNodeId != null && s.startNodeId.startsWith("node");
            }
            double angle = between(ref, direction(s.pts.get(0), s.pts.get(1)));
            s.nodeAngle = Math.round(angle * 10) / 10.0;
            double tolerance = technicalNode ? rules.turn.toleranceDeg
                    : Math.max(rules.turn.toleranceDeg, rules.turn.chamberToleranceDeg);
            if (!rules.turn.standard(angle, tolerance)) {
                s.oddTurns++;
            }
        }
    }

    /** Два луча лежат на одной звезде стандартных углов: между ними 0, 45 или 90 градусов. */
    public static boolean standardBetween(double[] a, double[] b, Rules rules) {
        return standardBetween(a, b, rules, rules.turn.toleranceDeg);
    }

    public static boolean standardBetween(double[] a, double[] b, Rules rules, double tolerance) {
        return rules.turn.standard(between(a, b), tolerance);
    }

    /** Угол между направлениями как между прямыми, 0–90 градусов. */
    public static double between(double[] a, double[] b) {
        double d = Math.abs(Math.toDegrees(Math.atan2(a[1], a[0])) - Math.toDegrees(Math.atan2(b[1], b[0]))) % 180;
        return d > 90 ? 180 - d : d;
    }

    /** Угол между двумя лучами, 0–180 градусов: 0 — идут в одну сторону, 180 — в разные. */
    public static double rayAngle(double[] a, double[] b) {
        double dot = Math.max(-1, Math.min(1, a[0] * b[0] + a[1] * b[1]));
        return Math.toDegrees(Math.acos(dot));
    }

    /** Направление участка прочь от узла или null, если узел не совпадает с концом участка. */
    public static double[] awayFrom(VariantResult.Seg s, String nodeId) {
        if (s.pts == null || s.pts.size() < 2) {
            return null;
        }
        if (nodeId.equals(s.startNodeId)) {
            return direction(s.pts.get(0), s.pts.get(1));
        }
        if (nodeId.equals(s.endNodeId)) {
            return direction(s.pts.get(s.pts.size() - 1), s.pts.get(s.pts.size() - 2));
        }
        return null;
    }

    public static double[] direction(Coordinate from, Coordinate to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double len = Math.hypot(dx, dy);
        return len < 1e-9 ? new double[]{1, 0} : new double[]{dx / len, dy / len};
    }
}
