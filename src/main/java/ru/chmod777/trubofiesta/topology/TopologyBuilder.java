package ru.chmod777.trubofiesta.topology;

import org.locationtech.jts.geom.Coordinate;
import ru.chmod777.trubofiesta.approach.Approach;
import ru.chmod777.trubofiesta.approach.Approaches;
import ru.chmod777.trubofiesta.approach.WallExits;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.model.Chamber;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.Diagnostic;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.model.Segment;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Собирает существующую сеть в граф. Направление к источнику берётся из
 * upstream_object_id, а если его нет — восстанавливается обходом по геометрии.
 * В конкурсном наборе этого поля нет, поэтому второй путь обязателен.
 */
public class TopologyBuilder {

    private final CalcSettings settings;

    public TopologyBuilder(CalcSettings settings) {
        this.settings = settings;
    }

    public Topology build(InputData data) {
        Topology t = new Topology();
        t.segments = new ArrayList<>(data.segments);

        splitSegmentsAtChambers(data, t);
        buildNodes(t);
        attachSource(data, t);
        attachChambers(data, t);
        orient(data, t);
        buildTieIns(data, t);
        return t;
    }

    /** Камера в середине участка делит его на два: дальше это точки ветвления и врезки. */
    private void splitSegmentsAtChambers(InputData data, Topology t) {
        for (Chamber c : data.chambers) {
            Segment target = null;
            double best = settings.snapTolerance;
            for (Segment s : t.segments) {
                double d = Geo.point(c.at).distance(Geo.line(s.pts));
                if (d < best) {
                    best = d;
                    target = s;
                }
            }
            if (target == null) {
                continue;
            }
            double m = Geo.measure(target.pts, c.at);
            double len = target.length();
            if (m <= settings.snapTolerance || m >= len - settings.snapTolerance) {
                continue;
            }
            Segment a = copy(target);
            a.id = target.id + "#1";
            a.pts = Geo.substring(target.pts, 0, m);
            Segment b = copy(target);
            b.id = target.id + "#2";
            b.pts = Geo.substring(target.pts, m, len);
            b.originOffset = target.originOffset + m;
            int at = t.segments.indexOf(target);
            t.segments.set(at, a);
            t.segments.add(at + 1, b);
            data.diagnostics.add(Diagnostic.info("segment_split",
                    "Участок разделён тепловой камерой " + c.id, target.id));
        }
    }

    private Segment copy(Segment s) {
        Segment c = new Segment();
        c.id = s.id;
        c.pts = s.pts;
        c.dn = s.dn;
        c.originId = s.originId != null ? s.originId : s.id;
        c.originOffset = s.originOffset;
        return c;
    }

    private void buildNodes(Topology t) {
        List<Coordinate> anchors = new ArrayList<>();
        for (Segment s : t.segments) {
            if (s.originId == null) {
                s.originId = s.id;
            }
            s.downstreamNode = nodeFor(t, anchors, s.start());
            s.upstreamNode = nodeFor(t, anchors, s.end());
            t.byId.put(s.id, s);
            t.segmentsAtNode.computeIfAbsent(s.downstreamNode, k -> new ArrayList<>()).add(s.id);
            t.segmentsAtNode.computeIfAbsent(s.upstreamNode, k -> new ArrayList<>()).add(s.id);
        }
    }

    /** Склейка концов участков в один узел с допуском из параметров. */
    private String nodeFor(Topology t, List<Coordinate> anchors, Coordinate c) {
        for (Coordinate a : anchors) {
            if (a.distance(c) <= settings.snapTolerance) {
                return Geo.key(a);
            }
        }
        anchors.add(c);
        String key = Geo.key(c);
        t.nodes.put(key, c);
        return key;
    }

    private void attachSource(InputData data, Topology t) {
        if (data.source == null) {
            return;
        }
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<String, Coordinate> e : t.nodes.entrySet()) {
            double d = e.getValue().distance(data.source);
            if (d < bestDist) {
                bestDist = d;
                best = e.getKey();
            }
        }
        t.sourceNode = best;
        if (bestDist > 5.0) {
            data.diagnostics.add(Diagnostic.warning("source_off_network",
                    "Источник отстоит от ближайшего узла сети на " + Math.round(bestDist) + " м", data.sourceId));
        }
    }

    private void attachChambers(InputData data, Topology t) {
        for (Chamber c : data.chambers) {
            String best = null;
            double bestDist = Double.MAX_VALUE;
            for (Map.Entry<String, Coordinate> e : t.nodes.entrySet()) {
                double d = e.getValue().distance(c.at);
                if (d < bestDist) {
                    bestDist = d;
                    best = e.getKey();
                }
            }
            if (best == null) {
                continue;
            }
            if (bestDist > settings.snapTolerance) {
                data.diagnostics.add(Diagnostic.warning("chamber_off_node",
                        "Камера отстоит от узла сети на " + String.format("%.1f", bestDist) + " м", c.id));
            }
            c.node = best;
            t.chamberAtNode.put(best, c);
            c.existingBranches = t.branchesAt(best);
            if (c.dn == null && settings.chamberDnFromSegments) {
                int max = 0;
                for (String id : t.segmentsAtNode.getOrDefault(best, new ArrayList<>())) {
                    max = Math.max(max, t.byId.get(id).dn);
                }
                c.dn = max;
                data.diagnostics.add(Diagnostic.info("chamber_dn_from_segments",
                        "Условный диаметр камеры принят по примыкающим участкам: " + max + " мм", c.id));
            }
        }
    }

    /**
     * Разворачивает каждый участок так, чтобы pts[0] был дальним от источника концом,
     * и заполняет направление к источнику.
     */
    private void orient(InputData data, Topology t) {
        if (t.sourceNode == null) {
            return;
        }
        Set<String> seenNodes = new HashSet<>();
        Set<String> seenSegments = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(t.sourceNode);
        seenNodes.add(t.sourceNode);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (String id : t.segmentsAtNode.getOrDefault(node, new ArrayList<>())) {
                if (!seenSegments.add(id)) {
                    continue;
                }
                Segment s = t.byId.get(id);
                if (!node.equals(s.upstreamNode)) {
                    Collections.reverse(s.pts);
                    String tmp = s.upstreamNode;
                    s.upstreamNode = s.downstreamNode;
                    s.downstreamNode = tmp;
                }
                if (seenNodes.add(s.downstreamNode)) {
                    queue.add(s.downstreamNode);
                }
            }
        }
        for (Segment s : t.segments) {
            if (!seenSegments.contains(s.id)) {
                t.disconnected.add(s.id);
                data.diagnostics.add(Diagnostic.warning("segment_disconnected",
                        "Участок не связан с источником, врезка в него невозможна", s.id));
            }
        }
    }

    /**
     * Кандидаты врезки. Если рядом, в пределах радиуса, есть существующая камера со
     * свободным местом, врезка выполняется в неё и трасса приходит в саму камеру:
     * кандидаты на трубе около такой камеры не создаются. Если камер в радиусе
     * несколько, каждая остаётся отдельным кандидатом, и дешёвую выбирает расчёт.
     */
    private void buildTieIns(InputData data, Topology t) {
        Set<String> disconnected = new HashSet<>(t.disconnected);
        List<Chamber> open = new ArrayList<>();
        for (Map.Entry<String, Chamber> e : t.chamberAtNode.entrySet()) {
            if (t.branchesAt(e.getKey()) >= settings.chamberMaxBranches) {
                continue;
            }
            TieInCandidate c = chamberCandidate(t, e.getKey(), e.getValue(), disconnected);
            if (c != null) {
                t.tieIns.add(c);
                open.add(e.getValue());
            }
        }
        for (Segment s : t.segments) {
            if (disconnected.contains(s.id)) {
                continue;
            }
            double len = s.length();
            List<Double> measures = new ArrayList<>();
            measures.add(0.0);
            for (double m = settings.tieInStep; m < len; m += settings.tieInStep) {
                measures.add(m);
            }
            measures.add(len);
            for (double m : measures) {
                Coordinate at = Geo.along(s.pts, m);
                if (nearOpenChamber(open, at)) {
                    continue;
                }
                TieInCandidate c = new TieInCandidate();
                c.at = at;
                c.segmentId = s.id;
                c.measure = m;
                c.existingDn = s.dn;
                c.id = "cand_" + s.id + "_" + Math.round(m);
                t.tieIns.add(c);
            }
        }
        if (settings.allowTieInToSource && t.sourceNode != null) {
            TieInCandidate c = new TieInCandidate();
            c.at = t.nodes.get(t.sourceNode);
            c.atSource = true;
            c.id = "cand_source";
            c.existingDn = 0;
            for (String id : t.segmentsAtNode.getOrDefault(t.sourceNode, new ArrayList<>())) {
                c.existingDn = Math.max(c.existingDn, t.byId.get(id).dn);
                c.segmentId = id;
            }
            c.measure = 0;
            t.tieIns.add(c);
        }
    }

    /**
     * Кандидаты присоединения напротив домов. Из каждого свободного подхода к точке пускается
     * луч по нормали и два перпендикуляра к ней из точки подхода; там, где луч пересекает
     * существующую трубу, ставится кандидат по тем же правилам, что и обычные: не ближе
     * радиуса к открытой камере, с диаметром и номером участка. Обычный кандидат ближе
     * полуметра уступает место. Такой кандидат получит прямое ребро к дому, и ввод пойдёт
     * одним отрезком по нормали.
     *
     * Перпендикуляры пускаются из точки поворота за зоной отступа для самого толстого
     * отвода: с таким отступом трассируется сетка, и угол у дома получается ровно 90°.
     */
    public void addApproachTieIns(Topology t, InputData data, Rules rules, Map<String, List<Approach>> approaches) {
        if (!settings.approachTieIns) {
            return;
        }
        int branchDn = rules.diameters.get(0).dn;
        for (ConnectionPoint p : data.points) {
            branchDn = Math.max(branchDn, rules.byFlow(p.flow).dn);
        }
        double turnDistance = Approaches.clearance(rules, settings, branchDn) + WallExits.EXIT_MARGIN;
        Set<String> disconnected = new HashSet<>(t.disconnected);
        List<Chamber> open = new ArrayList<>();
        for (Map.Entry<String, Chamber> e : t.chamberAtNode.entrySet()) {
            if (t.branchesAt(e.getKey()) < settings.chamberMaxBranches) {
                open.add(e.getValue());
            }
        }
        for (ConnectionPoint p : data.points) {
            for (Approach a : approaches.getOrDefault(p.id, Collections.emptyList())) {
                // Только ближайшие стены: раздел 2.2 допускает финальный участок лишь от
                // ближайшей к точке границы полигона, и через дальнюю стену выход незаконен.
                if (!a.free || !a.normal || !a.nearest || a.wallDistance <= 0) {
                    continue;
                }
                Coordinate corner = a.at(turnDistance);
                double[] n = a.direction;
                double[][] rays = {n, {-n[1], n[0]}, {n[1], -n[0]}};
                for (int r = 0; r < rays.length; r++) {
                    Coordinate from = r == 0 ? a.wall : corner;
                    Segment hit = null;
                    double best = settings.rayLength;
                    for (Segment s : t.segments) {
                        if (disconnected.contains(s.id)) {
                            continue;
                        }
                        double d = rayHit(from, rays[r], s.pts);
                        if (d > 0.5 && d < best) {
                            best = d;
                            hit = s;
                        }
                    }
                    if (hit == null) {
                        continue;
                    }
                    Coordinate at = new Coordinate(from.x + rays[r][0] * best, from.y + rays[r][1] * best);
                    if (nearOpenChamber(open, at) || alongPipe(rays[r], hit.pts, at)) {
                        continue;
                    }
                    if (t.tieIns.stream().anyMatch(c -> c.pointId != null && c.at.distance(at) < DEDUPE)) {
                        continue;
                    }
                    t.tieIns.removeIf(c -> c.chamberId == null && !c.atSource && c.at.distance(at) < DEDUPE);
                    TieInCandidate c = new TieInCandidate();
                    c.at = at;
                    c.segmentId = hit.id;
                    c.measure = Geo.measure(hit.pts, at);
                    c.existingDn = hit.dn;
                    c.pointId = p.id;
                    c.approach = a;
                    c.lateral = r > 0;
                    c.id = "cand_" + hit.id + "_" + p.id + (r == 0 ? "_normal" : "_side" + r);
                    t.tieIns.add(c);
                }
            }
        }
    }

    /** Кандидаты ближе этого считаются одним местом присоединения, м. */
    private static final double DEDUPE = 0.5;

    /** Расстояние вдоль луча до ближайшего пересечения с ломаной или бесконечность. */
    private static double rayHit(Coordinate from, double[] dir, List<Coordinate> pts) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i < pts.size(); i++) {
            Coordinate a = pts.get(i - 1);
            Coordinate b = pts.get(i);
            double ex = b.x - a.x;
            double ey = b.y - a.y;
            double den = dir[0] * ey - dir[1] * ex;
            if (Math.abs(den) < 1e-9) {
                continue;
            }
            double dx = a.x - from.x;
            double dy = a.y - from.y;
            double d = (dx * ey - dy * ex) / den;
            double u = (dx * dir[1] - dy * dir[0]) / den;
            if (d > 0 && u >= 0 && u <= 1 && d < best) {
                best = d;
            }
        }
        return best;
    }

    /** Луч подходит к трубе положе минимального угла: ввод шёл бы вдоль неё. */
    private boolean alongPipe(double[] dir, List<Coordinate> pts, Coordinate at) {
        double m = Geo.measure(pts, at);
        Coordinate a = Geo.along(pts, Math.max(0, m - 0.5));
        Coordinate b = Geo.along(pts, m + 0.5);
        double len = a.distance(b);
        if (len < 1e-6) {
            return false;
        }
        double dot = Math.abs(dir[0] * (b.x - a.x) + dir[1] * (b.y - a.y)) / len;
        return Math.toDegrees(Math.acos(Math.min(1, dot))) < settings.tieInMinAngle;
    }

    /**
     * Запас к радиусу «не далее 10 м»: кандидаты присоединения стоят с шагом 10 м от узла,
     * и точка ровно в 10,00 м от камеры у нас и у чужой проверки считается по-разному из-за
     * проекции. Незначительное отклонение округляется в большую сторону (сессия 16.09), и
     * присоединение в камеру законно с любого расстояния, так что запас безопасен.
     */
    public static final double CHAMBER_RADIUS_TOLERANCE = 0.5;

    private boolean nearOpenChamber(List<Chamber> open, Coordinate at) {
        for (Chamber ch : open) {
            if (ch.at.distance(at) <= settings.tieInChamberRadius + CHAMBER_RADIUS_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Врезка в существующую камеру. Дополнительный расход идёт от камеры к источнику,
     * поэтому врезка привязывается к верхнему концу участка, который подходит к камере снизу.
     */
    private TieInCandidate chamberCandidate(Topology t, String node, Chamber chamber, Set<String> disconnected) {
        Segment below = null;
        Segment above = null;
        for (String id : t.segmentsAtNode.getOrDefault(node, new ArrayList<>())) {
            Segment s = t.byId.get(id);
            if (s == null || disconnected.contains(s.id)) {
                continue;
            }
            if (node.equals(s.upstreamNode)) {
                below = s;
            } else if (node.equals(s.downstreamNode)) {
                above = s;
            }
        }
        Segment s = below != null ? below : above;
        if (s == null) {
            return null;
        }
        TieInCandidate c = new TieInCandidate();
        c.id = "cand_ch_" + chamber.id;
        c.at = chamber.at;
        c.segmentId = s.id;
        c.measure = below != null ? s.length() : 0;
        c.chamberId = chamber.id;
        c.chamberBranches = t.branchesAt(node);
        c.existingDn = chamber.dn != null ? chamber.dn : s.dn;
        return c;
    }
}
