package ru.chmod777.trubofiesta.calc;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Service;
import ru.chmod777.trubofiesta.approach.Approaches;
import ru.chmod777.trubofiesta.export.ResultChecker;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.ingest.GeoJsonGeometry;
import ru.chmod777.trubofiesta.ingest.GeoJsonReader;
import ru.chmod777.trubofiesta.ingest.InputBuilder;
import ru.chmod777.trubofiesta.ingest.RawFeature;
import ru.chmod777.trubofiesta.model.Chamber;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.model.ObjectType;
import ru.chmod777.trubofiesta.model.Segment;
import ru.chmod777.trubofiesta.constraints.LayingSplit;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.constraints.Obstacles;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.rules.RulesService;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.Topology;
import ru.chmod777.trubofiesta.topology.TopologyBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Оценка схемы, которую инженер начертил сам и принёс файлом.
 * Сеть разбирается на узлы и участки, расходы считаются по тем же правилам, что и в
 * автоматическом расчёте, подбираются диаметры, определяется реконструкция и стоимость.
 * Это даёт честное сравнение ручной разводки с расчётной: одна методика на обе.
 */
@Service
public class ImportedPlanService {

    private static final double TIE_IN_SNAP = 3.0;
    private static final double POINT_SNAP = 8.0;

    private final RulesService rulesService;

    public ImportedPlanService(RulesService rulesService) {
        this.rulesService = rulesService;
    }

    public VariantResult evaluate(List<RawFeature> projectFeatures, CalcSettings settings,
                                  InputStream uploaded) throws IOException {
        Rules rules = rulesService.get();
        InputData data = new InputBuilder(rules, settings).build(projectFeatures);
        Topology topology = new TopologyBuilder(settings).build(data);
        ObstacleSet obstacles = new Obstacles(data, rules, settings).forDn(rules.byFlow(data.totalFlow()).dn);

        List<List<Coordinate>> lines = readLines(uploaded);
        VariantResult v = new VariantResult();
        v.variantId = "imported";
        v.rank = 0;
        v.strategy = "Загруженная схема";
        v.rawIds = data.rawIds;
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("В файле нет ни одной линии новой сети");
        }

        Nodes nodes = new Nodes(Math.max(settings.snapTolerance, 1.0));
        List<int[]> ends = new ArrayList<>();
        for (List<Coordinate> line : lines) {
            ends.add(new int[]{nodes.of(line.get(0)), nodes.of(line.get(line.size() - 1))});
        }

        Map<Integer, String> oksAt = new LinkedHashMap<>();
        for (ConnectionPoint p : data.points) {
            int best = -1;
            double bestDist = POINT_SNAP;
            for (int i = 0; i < nodes.list.size(); i++) {
                double d = nodes.list.get(i).distance(p.at);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            if (best >= 0) {
                oksAt.put(best, p.id);
            }
        }

        Map<Integer, List<Integer>> adjacency = new HashMap<>();
        for (int i = 0; i < ends.size(); i++) {
            adjacency.computeIfAbsent(ends.get(i)[0], k -> new ArrayList<>()).add(i);
            adjacency.computeIfAbsent(ends.get(i)[1], k -> new ArrayList<>()).add(i);
        }

        // Каждая отдельная часть новой сети присоединяется к существующей в одной точке
        // (раздел 2.2 кейса), поэтому на компоненту связности берётся одна врезка —
        // тот её узел, который ближе всего к существующей сети.
        Map<Integer, TieIn> tieAt = new LinkedHashMap<>();
        Set<Integer> visited = new HashSet<>();
        for (int startNode : new ArrayList<>(adjacency.keySet())) {
            if (!visited.add(startNode)) {
                continue;
            }
            List<Integer> component = new ArrayList<>();
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(startNode);
            while (!stack.isEmpty()) {
                int node = stack.pop();
                component.add(node);
                for (int lineIndex : adjacency.getOrDefault(node, new ArrayList<>())) {
                    int other = ends.get(lineIndex)[0] == node ? ends.get(lineIndex)[1] : ends.get(lineIndex)[0];
                    if (visited.add(other)) {
                        stack.push(other);
                    }
                }
            }
            int bestNode = -1;
            Segment bestSegment = null;
            double bestDist = TIE_IN_SNAP;
            for (int node : component) {
                for (Segment seg : topology.segments) {
                    double d = Geo.point(nodes.list.get(node)).distance(Geo.line(seg.pts));
                    if (d < bestDist) {
                        bestDist = d;
                        bestNode = node;
                        bestSegment = seg;
                    }
                }
            }
            if (bestNode >= 0) {
                TieIn t = new TieIn();
                t.segment = bestSegment;
                t.measure = Geo.measure(bestSegment.pts, nodes.list.get(bestNode));
                t.chamber = nearestChamber(data, nodes.list.get(bestNode),
                        settings.tieInChamberRadius + ru.chmod777.trubofiesta.topology.TopologyBuilder.CHAMBER_RADIUS_TOLERANCE);
                tieAt.put(bestNode, t);
            }
        }
        if (tieAt.isEmpty()) {
            throw new IllegalArgumentException(
                    "Схема нигде не примыкает к существующей сети: врезку найти не удалось");
        }

        Map<Integer, Integer> parent = new HashMap<>();
        List<Integer> order = new ArrayList<>();
        Set<Integer> seen = new HashSet<>(tieAt.keySet());
        Deque<Integer> queue = new ArrayDeque<>(tieAt.keySet());
        while (!queue.isEmpty()) {
            int node = queue.poll();
            order.add(node);
            for (int line : adjacency.getOrDefault(node, new ArrayList<>())) {
                int other = ends.get(line)[0] == node ? ends.get(line)[1] : ends.get(line)[0];
                if (seen.add(other)) {
                    parent.put(other, node);
                    queue.add(other);
                }
            }
        }

        Map<Integer, Double> below = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            int node = order.get(i);
            double sum = 0;
            String oks = oksAt.get(node);
            if (oks != null) {
                for (ConnectionPoint p : data.points) {
                    if (p.id.equals(oks)) {
                        sum += p.flow;
                    }
                }
            }
            for (int line : adjacency.getOrDefault(node, new ArrayList<>())) {
                int other = ends.get(line)[0] == node ? ends.get(line)[1] : ends.get(line)[0];
                Integer p = parent.get(other);
                if (p != null && p == node) {
                    sum += below.getOrDefault(other, 0.0);
                }
            }
            below.put(node, sum);
        }

        // Диаметры подбираются тем же классом, что и в автоматическом расчёте: расход,
        // предельная длина пути и неубывание к месту присоединения.
        List<Diameters.Edge> edges = new ArrayList<>();
        Map<Integer, Diameters.Edge> edgeOfLine = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            int a = ends.get(i)[0];
            int b = ends.get(i)[1];
            Integer pa = parent.get(a);
            int child = pa != null && pa == b ? a : b;
            int up = child == a ? b : a;
            Diameters.Edge e = new Diameters.Edge(child, up, Geo.length(lines.get(i)),
                    below.getOrDefault(child, 0.0));
            edges.add(e);
            edgeOfLine.put(i, e);
        }
        v.warnings.addAll(Diameters.choose(rules, "PATH".equals(settings.lengthLimitMode),
                !"WARN".equals(settings.lengthLimitAction), edges));

        Map<Integer, Integer> dnAtNode = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            int a = ends.get(i)[0];
            int b = ends.get(i)[1];
            double flow = edgeOfLine.get(i).flow;
            int dn = edgeOfLine.get(i).dn;
            dnAtNode.merge(a, dn, Math::max);
            dnAtNode.merge(b, dn, Math::max);

            // Линия режется по зонам специального прохода той же функцией, что и в расчёте:
            // коэффициент относится только к пересекающей части, раздел 6.
            List<Coordinate> exempt = new ArrayList<>();
            if (tieAt.containsKey(a)) {
                exempt.add(nodes.list.get(a));
            }
            if (tieAt.containsKey(b)) {
                exempt.add(nodes.list.get(b));
            }
            int piece = 0;
            for (LayingSplit.Part part : LayingSplit.split(obstacles, lines.get(i), exempt)) {
                VariantResult.Seg s = new VariantResult.Seg();
                piece++;
                s.id = "man_" + (i + 1) + (piece > 1 || part.special() ? "_" + piece : "");
                s.pts = part.pts;
                s.length = Geo.roundedLength(part.pts);
                s.flow = flow;
                s.dn = dn;
                s.startNodeId = piece == 1 ? label(a, oksAt, tieAt) : "node_" + a + "_" + piece;
                s.endNodeId = label(b, oksAt, tieAt);
                s.laying = part.special() ? "special" : "base";
                s.specialType = part.type;
                s.k = part.k;
                s.turns = rules.countTurns(part.pts);
                s.cost = rules.segmentCost(s.dn, s.length, part.k);
                if (settings.writeNullDepth) {
                    s.depthStart = null;
                    s.depthEnd = null;
                }
                v.segments.add(s);
            }
        }

        NodeAngles.measure(v.segments, rules);

        for (Map.Entry<Integer, TieIn> e : tieAt.entrySet()) {
            TieIn t = e.getValue();
            VariantResult.Attachment a = new VariantResult.Attachment();
            a.nodeId = label(e.getKey(), oksAt, tieAt);
            a.at = nodes.list.get(e.getKey());
            a.existingChamber = t.chamber != null;
            a.existingObjectId = t.chamber != null ? t.chamber.id
                    : (t.segment.originId != null ? t.segment.originId : t.segment.id);
            a.existingDn = t.chamber != null && t.chamber.dn != null ? t.chamber.dn : t.segment.dn;
            a.branches = adjacency.getOrDefault(e.getKey(), new ArrayList<>()).size();
            if (a.existingChamber) {
                // Каждый новый участок, кончающийся в существующей камере, — отдельная врезка.
                a.cost = a.branches * rules.existingChamberTieInCost;
            } else {
                VariantResult.Cham ch = new VariantResult.Cham();
                ch.id = a.nodeId;
                ch.at = a.at;
                ch.onExistingPipe = true;
                ch.existingObjectId = a.existingObjectId;
                // Через камеру на трубе проходит и сама труба: она тоже примыкает.
                ch.dn = Math.max(dnAtNode.getOrDefault(e.getKey(), 0), existingDnAt(topology, a.at));
                ch.cost = rules.chamberCost(ch.dn);
                v.chambers.add(ch);
            }
            v.attachments.add(a);
        }
        for (int node : adjacency.keySet()) {
            if (tieAt.containsKey(node) || adjacency.get(node).size() < 3) {
                continue;
            }
            VariantResult.Cham ch = new VariantResult.Cham();
            ch.id = label(node, oksAt, tieAt);
            ch.at = nodes.list.get(node);
            ch.dn = dnAtNode.getOrDefault(node, 0);
            ch.cost = rules.chamberCost(ch.dn);
            v.chambers.add(ch);
        }

        Set<String> served = new HashSet<>(oksAt.values());
        for (ConnectionPoint p : data.points) {
            if (!served.contains(p.id)) {
                v.unconnected.add(p.id);
                v.unconnectedPenalty += rules.penalty(p.flow);
            }
        }

        totals(v, rules);
        Obstacles all = new Obstacles(data, rules, settings);
        Map<String, org.locationtech.jts.geom.Geometry> own = new LinkedHashMap<>();
        for (ConnectionPoint p : data.points) {
            org.locationtech.jts.geom.Geometry g = Approaches.ownBuilding(p, data, settings);
            if (g != null) {
                own.put(p.id, g);
            }
        }
        v.warnings.addAll(new ResultChecker(rules, settings).check(v, obstacles,
                new ru.chmod777.trubofiesta.constraints.Clearance(data, all, settings, obstacles.specials()), own, data));
        Explainer.explain(v, null, data);
        return v;
    }

    /**
     * Идентификатор узла. Конец участка в существующей камере называется её идентификатором:
     * так требует раздел 2.4, отдельного объекта присоединения нет.
     */
    private String label(int node, Map<Integer, String> oksAt, Map<Integer, TieIn> tieAt) {
        if (oksAt.containsKey(node)) {
            return oksAt.get(node);
        }
        TieIn t = tieAt.get(node);
        if (t != null) {
            return t.chamber != null ? t.chamber.id : "ch_" + node;
        }
        return "node_" + node;
    }

    /** Наибольший диаметр существующих труб в точке: врезка на стыке касается обеих. */
    private static int existingDnAt(Topology topology, Coordinate at) {
        int max = 0;
        for (Segment s : topology.segments) {
            if (Geo.point(at).distance(Geo.line(s.pts)) < 0.5) {
                max = Math.max(max, s.dn);
            }
        }
        return max;
    }

    private Chamber nearestChamber(InputData data, Coordinate c, double radius) {
        Chamber best = null;
        double bestDist = radius;
        for (Chamber ch : data.chambers) {
            double d = ch.at.distance(c);
            if (d <= bestDist) {
                bestDist = d;
                best = ch;
            }
        }
        return best;
    }

    private void totals(VariantResult v, Rules rules) {
        for (VariantResult.Seg s : v.segments) {
            v.segmentsCost += s.cost;
            v.newNetworkLength += s.length;
        }
        for (VariantResult.Cham c : v.chambers) {
            v.chamberConstructionCost += c.cost;
        }
        for (VariantResult.Attachment a : v.attachments) {
            if (a.existingChamber) {
                v.existingChamberTieInCount += a.branches;
                v.existingChamberTieInCost += a.cost;
            }
        }
        v.constructionCost = v.segmentsCost + v.chamberConstructionCost + v.existingChamberTieInCost;
        v.calculatedCost = v.constructionCost + v.unconnectedPenalty;
        v.score = rules.score(v.calculatedCost, v.newNetworkLength);
    }

    /** Из файла берутся только линии: полигоны и точки в схеме разводки не нужны. */
    private List<List<Coordinate>> readLines(InputStream in) throws IOException {
        List<List<Coordinate>> out = new ArrayList<>();
        new GeoJsonReader().read(in, f -> {
            String type = f.geometryType();
            if ("LineString".equals(type)) {
                out.add(Geo.dedupe(GeoJsonGeometry.lineString(f.geometry)));
            } else if ("MultiLineString".equals(type)) {
                for (JsonNode part : f.geometry.path("coordinates")) {
                    List<Coordinate> pts = new ArrayList<>();
                    for (JsonNode c : part) {
                        pts.add(ru.chmod777.trubofiesta.geo.Crs.toMeters(c.get(0).asDouble(), c.get(1).asDouble()));
                    }
                    out.add(Geo.dedupe(pts));
                }
            }
        });
        return out;
    }

    /** Склейка концов линий в общие узлы. */
    private static class Nodes {
        final List<Coordinate> list = new ArrayList<>();
        private final double tolerance;

        Nodes(double tolerance) {
            this.tolerance = tolerance;
        }

        int of(Coordinate c) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).distance(c) <= tolerance) {
                    return i;
                }
            }
            list.add(c);
            return list.size() - 1;
        }
    }

    private static class TieIn {
        Segment segment;
        double measure;
        Chamber chamber;
    }
}
