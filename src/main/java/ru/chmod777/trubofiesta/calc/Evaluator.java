package ru.chmod777.trubofiesta.calc;

import org.locationtech.jts.geom.Coordinate;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.model.Segment;
import ru.chmod777.trubofiesta.algo.GeometryRefiner;
import ru.chmod777.trubofiesta.algo.NewNetwork;
import ru.chmod777.trubofiesta.algo.RefineRequest;
import ru.chmod777.trubofiesta.algo.VertexSpace;
import ru.chmod777.trubofiesta.constraints.LayingSplit;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.TieInCandidate;
import ru.chmod777.trubofiesta.topology.Topology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Превращает построенное дерево в посчитанный вариант: расходы, условные диаметры,
 * предельная длина, технические узлы, камеры, присоединения и стоимость.
 * Порядок действий здесь ровно такой, как в разделах 2, 3 и 6 технического приложения.
 */
public class Evaluator {

    /** Изломы положе этого угла — просто вершины прямой линии сетки, их убираем. */
    private static final double STRAIGHT_DEG = 0.3;

    private final InputData data;
    private final Topology topology;
    private final Rules rules;
    private final CalcSettings settings;
    private final VertexSpace grid;
    private final ru.chmod777.trubofiesta.constraints.Clearance clearance;
    private final Map<String, org.locationtech.jts.geom.Geometry> ownBuildings;
    private final Map<String, TieInCandidate> tieInById = new HashMap<>();
    private final Map<String, ConnectionPoint> pointById = new HashMap<>();

    public Evaluator(InputData data, Topology topology, Rules rules, CalcSettings settings, VertexSpace grid,
                     ru.chmod777.trubofiesta.constraints.Clearance clearance,
                     Map<String, org.locationtech.jts.geom.Geometry> ownBuildings) {
        this.clearance = clearance;
        this.ownBuildings = ownBuildings;
        this.data = data;
        this.topology = topology;
        this.rules = rules;
        this.settings = settings;
        this.grid = grid;
        for (TieInCandidate c : topology.tieIns) {
            tieInById.put(c.id, c);
        }
        for (ConnectionPoint p : data.points) {
            pointById.put(p.id, p);
        }
    }

    /** Оценка дерева при подборе: без доводки трассы, быстро. Подпись — откуда дерево взялось. */
    public VariantResult evaluate(NewNetwork net, String variantId, String label, ObstacleSet obstacles) {
        VariantResult r = evaluate(net, variantId, obstacles, null);
        r.strategy = label;
        return r;
    }

    /**
     * @param refiner доводчик ломаных от алгоритма; null — геометрия берётся как есть.
     *                Доводка дорогая, поэтому её включают только для лучших деревьев
     *                перед окончательным отбором вариантов
     */
    public VariantResult evaluate(NewNetwork net, String variantId, ObstacleSet obstacles, GeometryRefiner refiner) {
        VariantResult r = new VariantResult();
        r.net = net;
        r.obstacleSet = obstacles;
        r.evaluator = this;
        r.variantId = variantId;
        r.rawIds = data.rawIds;
        r.unconnected.addAll(net.unconnected);

        Tree tree = root(net);
        Map<Long, Double> flow = flows(net, tree);
        Map<Long, Integer> dn = diameters(net, tree, flow, r);

        Ids ids = new Ids(data.rawIds.keySet());
        Map<Integer, String> nodeIds = assignNodeIds(net, ids, r);
        chambers(net, dn, nodeIds, r);
        buildSegments(net, tree, flow, dn, nodeIds, obstacles, ids, r, refiner);
        penalties(r);
        groups(net, r);
        totals(r);
        checkGeometry(r);
        for (VariantResult.Seg seg : r.segments) {
            r.oddTurns += seg.oddTurns;
        }
        r.standardViolated = settings.standardAnglesOnly && r.oddTurns > 0;
        r.planWarnings.addAll(r.warnings);
        return r;
    }

    /**
     * Расчётный расход каждого ребра дерева. Нужен алгоритмам, которые уточняют цену метра:
     * по ребру, где уже пойдёт чужой расход, следующему дому нужна не новая труба, а разница
     * между диаметрами.
     */
    public Map<Long, Double> linkFlows(NewNetwork net) {
        Tree tree = root(net);
        return flows(net, tree);
    }

    /** Условный диаметр каждого ребра дерева: нужен слиянию камер до полной оценки. */
    public Map<Long, Integer> linkDiameters(NewNetwork net) {
        Tree tree = root(net);
        return diameters(net, tree, flows(net, tree), new VariantResult());
    }

    /** Отступы каждого участка по его собственному диаметру и расхождение веток в узлах. */
    private void checkGeometry(VariantResult r) {
        if (clearance != null) {
            r.clearanceViolations = clearanceProblems(r, clearance, ownBuildings, pointById).size();
        }
        r.clearanceViolations += overlappingBranches(r, settings.minBranchAngle).size();
    }

    /**
     * Две ветки, уходящие из одного узла почти в одну сторону, физически лежат в одной
     * траншее и накладываются друг на друга. В приложении такого правила нет, но новые
     * участки не должны пересекаться вне общего узла, а наложение — это худший случай
     * пересечения. Порог — параметр minBranchAngle.
     */
    public static List<String> overlappingBranches(VariantResult r, double minAngle) {
        List<String> problems = new ArrayList<>();
        if (minAngle <= 0) {
            return problems;
        }
        Map<String, List<VariantResult.Seg>> atNode = new LinkedHashMap<>();
        for (VariantResult.Seg s : r.segments) {
            if (s.pts == null || s.pts.size() < 2) {
                continue;
            }
            atNode.computeIfAbsent(s.startNodeId, k -> new ArrayList<>()).add(s);
            atNode.computeIfAbsent(s.endNodeId, k -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<String, List<VariantResult.Seg>> e : atNode.entrySet()) {
            List<VariantResult.Seg> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    double[] a = NodeAngles.awayFrom(list.get(i), e.getKey());
                    double[] b = NodeAngles.awayFrom(list.get(j), e.getKey());
                    if (a == null || b == null) {
                        continue;
                    }
                    double angle = NodeAngles.rayAngle(a, b);
                    if (angle < minAngle) {
                        problems.add("Участки " + list.get(i).id + " и " + list.get(j).id + " уходят из узла "
                                + e.getKey() + " под " + Math.round(angle) + "°: ветки идут по одному коридору");
                    }
                }
            }
        }
        return problems;
    }

    /**
     * Ближе этого к месту присоединения пересечение существующей теплосети — не пересечение,
     * а разводка у своей камеры: длина спецучастка 2 м плюс полтора метра на саму камеру.
     */
    private static final double NEAR_ATTACHMENT = 3.5;

    /** Все нарушения отступов варианта. Та же функция используется в проверке результата. */
    public static List<String> clearanceProblems(VariantResult r, ru.chmod777.trubofiesta.constraints.Clearance clearance,
                                                 Map<String, org.locationtech.jts.geom.Geometry> ownBuildings,
                                                 Map<String, ConnectionPoint> pointById) {
        List<String> problems = new ArrayList<>();
        List<List<Coordinate>> lines = new ArrayList<>();
        Map<String, Coordinate> tieAt = new HashMap<>();
        for (VariantResult.Attachment a : r.attachments) {
            tieAt.put(a.nodeId, a.at);
        }
        for (VariantResult.Seg s : r.segments) {
            lines.add(s.pts);
        }
        List<Coordinate> crossings = clearance.crossings(lines);
        crossings.removeIf(c -> tieAt.values().stream().anyMatch(t -> t != null && t.distance(c) < 0.5));
        // Пересечение существующей теплосети в нескольких метрах от места присоединения — не
        // спецпроход, а обход собственной камеры: инженер провёл бы ветку через камеру, а
        // независимая проверка такое пересечение спецпроходом не признаёт. Полметра — сама
        // точка присоединения, до неё труба доходит законно.
        for (Coordinate c : clearance.crossings(lines, "heat_network")) {
            for (Coordinate t : tieAt.values()) {
                double d = t == null ? Double.MAX_VALUE : t.distance(c);
                if (d >= 0.5 && d < NEAR_ATTACHMENT) {
                    problems.add(String.format(java.util.Locale.US,
                            "Новый участок пересекает существующую теплосеть в %.1f м от места присоединения", d));
                }
            }
        }
        for (VariantResult.Seg s : r.segments) {
            ConnectionPoint p = pointById.get(s.endNodeId);
            String problem = clearance.violation(s.id, s.pts, s.dn,
                    p == null ? null : ownBuildings.get(p.id), p == null ? null : p.buildingId);
            if (problem == null && !"special".equals(s.laying)) {
                problem = clearance.sideViolation(s.id, s.pts, s.dn, crossings, tieAt.get(s.startNodeId));
            }
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }

    // --- дерево -------------------------------------------------------------------

    /** Корни — вершины присоединения; для каждой вершины известен родитель в сторону сети. */
    private Tree root(NewNetwork net) {
        Tree t = new Tree();
        Deque<Integer> queue = new ArrayDeque<>();
        for (int v : net.tieInAt.keySet()) {
            t.parent.put(v, null);
            queue.add(v);
        }
        Set<Integer> seen = new HashSet<>(net.tieInAt.keySet());
        while (!queue.isEmpty()) {
            int v = queue.poll();
            t.order.add(v);
            for (int n : net.neighbours(v)) {
                if (seen.add(n)) {
                    t.parent.put(n, v);
                    queue.add(n);
                }
            }
        }
        return t;
    }

    private boolean isChildOf(Tree tree, int child, int parent) {
        Integer p = tree.parent.get(child);
        return p != null && p == parent;
    }

    /** Расход участка — сумма расходов всех точек подключения, которые через него питаются. */
    private Map<Long, Double> flows(NewNetwork net, Tree tree) {
        Map<Integer, Double> below = new HashMap<>();
        for (int i = tree.order.size() - 1; i >= 0; i--) {
            int v = tree.order.get(i);
            double sum = 0;
            String oks = net.oksAt.get(v);
            if (oks != null && pointById.containsKey(oks)) {
                sum += pointById.get(oks).flow;
            }
            for (int n : net.neighbours(v)) {
                if (isChildOf(tree, n, v)) {
                    sum += below.getOrDefault(n, 0.0);
                }
            }
            below.put(v, sum);
        }
        Map<Long, Double> flow = new LinkedHashMap<>();
        for (NewNetwork.Link l : net.links.values()) {
            int child = isChildOf(tree, l.a, l.b) ? l.a : l.b;
            flow.put(NewNetwork.key(l.a, l.b), below.getOrDefault(child, 0.0));
        }
        return flow;
    }

    /** Условный диаметр каждого ребра дерева. Правила подбора — в классе Diameters. */
    private Map<Long, Integer> diameters(NewNetwork net, Tree tree, Map<Long, Double> flow, VariantResult r) {
        List<Diameters.Edge> edges = new ArrayList<>();
        Map<Long, Diameters.Edge> byKey = new LinkedHashMap<>();
        for (int v : tree.order) {
            Integer parent = tree.parent.get(v);
            NewNetwork.Link link = parent == null ? null : net.link(v, parent);
            if (link == null) {
                continue;
            }
            long key = NewNetwork.key(v, parent);
            Diameters.Edge e = new Diameters.Edge(v, parent, link.length, flow.getOrDefault(key, 0.0));
            edges.add(e);
            byKey.put(key, e);
        }
        if (byKey.size() != net.links.size()) {
            // Дерево обходится от места присоединения, поэтому ребро вне обхода означает
            // кольцо или оторванный кусок: молча считать его Ду наименьшим нельзя.
            r.warnings.add("Часть новой сети не образует дерева: рёбер " + net.links.size()
                    + ", в обходе от присоединения " + byKey.size());
        }
        r.warnings.addAll(Diameters.choose(rules, "PATH".equals(settings.lengthLimitMode),
                !"WARN".equals(settings.lengthLimitAction), edges));
        Map<Long, Integer> dn = new LinkedHashMap<>();
        for (Map.Entry<Long, Diameters.Edge> e : byKey.entrySet()) {
            dn.put(e.getKey(), e.getValue().dn);
        }
        return dn;
    }

    // --- узлы, присоединения и участки ----------------------------------------------

    /**
     * Идентификаторы узлов: точки подключения, места присоединения и камеры на ветвлениях.
     * Присоединение — либо конец участка прямо в существующей камере (её id и остаётся
     * идентификатором узла), либо новая камера в выбранной точке трубы, раздел 2.4.
     */
    private Map<Integer, String> assignNodeIds(NewNetwork net, Ids ids, VariantResult r) {
        Map<Integer, String> nodeIds = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : net.oksAt.entrySet()) {
            nodeIds.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<Integer, String> e : net.tieInAt.entrySet()) {
            int vertex = e.getKey();
            TieInCandidate c = tieInById.get(e.getValue());
            if (c == null) {
                continue;
            }
            int branches = net.degree(vertex);
            VariantResult.Attachment a = new VariantResult.Attachment();
            a.at = grid.vertex(vertex);
            a.vertex = vertex;
            a.branches = branches;
            a.candidateId = c.id;
            if (intoExistingChamber(c, branches)) {
                a.existingChamber = true;
                a.nodeId = c.chamberId;
                a.existingObjectId = c.chamberId;
                a.existingDn = c.existingDn;
                a.cost = branches * rules.existingChamberTieInCost;
            } else {
                if (c.chamberId != null) {
                    r.warnings.add("К камере " + c.chamberId + " примыкало бы больше "
                            + settings.chamberMaxBranches + " участков, построена новая камера на трубе");
                }
                a.existingChamber = false;
                a.nodeId = ids.next("ch");
                a.existingObjectId = originOf(c.segmentId);
                a.existingDn = segmentDn(c.segmentId, c.existingDn);
            }
            nodeIds.put(vertex, a.nodeId);
            r.attachments.add(a);
        }
        for (int v : net.vertices) {
            if (!nodeIds.containsKey(v) && net.degree(v) >= 3) {
                nodeIds.put(v, ids.next("ch"));
            }
        }
        return nodeIds;
    }

    private boolean intoExistingChamber(TieInCandidate c, int newBranches) {
        return c.chamberId != null && c.chamberBranches + newBranches <= settings.chamberMaxBranches;
    }

    private int segmentDn(String segmentId, int fallback) {
        Segment s = topology.byId.get(segmentId);
        return s == null ? fallback : s.dn;
    }

    private String originOf(String segmentId) {
        Segment s = topology.byId.get(segmentId);
        return s == null ? segmentId : (s.originId != null ? s.originId : s.id);
    }

    /**
     * Новые камеры: на ветвлениях и в точках присоединения к существующей трубе, раздел 3.2.
     * Условный диаметр и стоимость камеры — по наибольшему Ду всех примыкающих участков;
     * существующая труба, проходящая через камеру присоединения, тоже примыкает (ответ 12).
     */
    private void chambers(NewNetwork net, Map<Long, Integer> dn, Map<Integer, String> nodeIds, VariantResult r) {
        Map<Integer, VariantResult.Attachment> attachmentAt = new HashMap<>();
        for (VariantResult.Attachment a : r.attachments) {
            attachmentAt.put(a.vertex, a);
        }
        for (Map.Entry<Integer, String> e : nodeIds.entrySet()) {
            int vertex = e.getKey();
            if (net.oksAt.containsKey(vertex)) {
                continue;
            }
            VariantResult.Attachment a = attachmentAt.get(vertex);
            if (a != null && a.existingChamber) {
                // Существующая камера не строится: оплачиваются только врезки в неё.
                if (existingBranches(a) + net.degree(vertex) > settings.chamberMaxBranches) {
                    r.warnings.add("К существующей камере " + a.existingObjectId + " примыкает больше "
                            + settings.chamberMaxBranches + " участков");
                }
                continue;
            }
            VariantResult.Cham ch = new VariantResult.Cham();
            ch.id = e.getValue();
            ch.at = grid.vertex(vertex);
            int max = maxDnAt(net, dn, vertex);
            // Существующая труба проходит через камеру присоединения и занимает два примыкания.
            int occupied = a == null ? 0 : 2;
            if (a != null) {
                ch.onExistingPipe = true;
                ch.existingObjectId = a.existingObjectId;
                max = Math.max(max, existingDnAt(a.at));
            }
            ch.dn = max;
            ch.cost = rules.chamberCost(ch.dn);
            r.chambers.add(ch);
            if (net.degree(vertex) + occupied > settings.chamberMaxBranches) {
                r.warnings.add("К камере " + ch.id + " примыкает " + (net.degree(vertex) + occupied) + " участков");
            }
        }
    }

    /** Сколько участков существующей сети уже примыкает к камере присоединения. */
    private int existingBranches(VariantResult.Attachment a) {
        TieInCandidate c = tieInById.get(a.candidateId);
        return c == null ? 0 : c.chamberBranches;
    }

    /**
     * Наибольший диаметр существующих труб в точке присоединения. Труба, проходящая через
     * новую камеру, примыкает к ней двумя участками, раздел 2.1 и ответ 12.
     */
    private int existingDnAt(Coordinate at) {
        int max = 0;
        for (Segment s : topology.segments) {
            if (Geo.point(at).distance(Geo.line(s.pts)) < 0.5) {
                max = Math.max(max, s.dn);
            }
        }
        return max;
    }

    private int maxDnAt(NewNetwork net, Map<Long, Integer> dn, int vertex) {
        int max = 0;
        for (int n : net.neighbours(vertex)) {
            Integer d = dn.get(NewNetwork.key(vertex, n));
            if (d != null) {
                max = Math.max(max, d);
            }
        }
        return max;
    }

    /**
     * Участки между узлами. Цепочка рёбер сетки от узла до узла становится одной ломаной,
     * лишние вершины на прямых убираются, затем ломаная делится на обычные и специальные
     * участки. Направление участка — от места присоединения к дому.
     */
    private void buildSegments(NewNetwork net, Tree tree, Map<Long, Double> flow, Map<Long, Integer> dn,
                               Map<Integer, String> nodeIds, ObstacleSet obstacles, Ids ids, VariantResult r,
                               GeometryRefiner refiner) {
        Set<Long> used = new HashSet<>();
        List<List<Integer>> chains = new ArrayList<>();
        for (int start : new ArrayList<>(nodeIds.keySet())) {
            for (int neighbour : net.neighbours(start)) {
                if (used.contains(NewNetwork.key(start, neighbour))) {
                    continue;
                }
                List<Integer> chain = new ArrayList<>();
                chain.add(start);
                int prev = start;
                int current = neighbour;
                used.add(NewNetwork.key(start, neighbour));
                chain.add(current);
                while (!nodeIds.containsKey(current)) {
                    int next = -1;
                    for (int n : net.neighbours(current)) {
                        if (n != prev) {
                            next = n;
                            break;
                        }
                    }
                    if (next < 0) {
                        break;
                    }
                    used.add(NewNetwork.key(current, next));
                    chain.add(next);
                    prev = current;
                    current = next;
                }
                if (isChildOf(tree, chain.get(0), chain.get(1))) {
                    Collections.reverse(chain);
                }
                chains.add(chain);
            }
        }

        List<List<Coordinate>> geometry = new ArrayList<>();
        for (List<Integer> chain : chains) {
            List<Coordinate> pts = new ArrayList<>();
            for (int v : chain) {
                pts.add(grid.vertex(v));
            }
            geometry.add(Geo.simplify(Geo.dedupe(pts), STRAIGHT_DEG));
        }
        for (int n = 0; refiner != null && n < chains.size(); n++) {
            List<Integer> chain = chains.get(n);
            int first = chain.get(0);
            int last = chain.get(chain.size() - 1);
            ConnectionPoint point = pointById.get(net.oksAt.get(last));
            RefineRequest c = new RefineRequest();
            c.id = "chain_" + n;
            c.dn = dn.getOrDefault(NewNetwork.key(first, chain.get(1)), rules.diameters.get(0).dn);
            if (net.tieInAt.containsKey(first)) {
                c.tieIn = grid.vertex(first);
                c.pipes = topology.pipeDirections(c.tieIn);
            }
            c.ownBuilding = point == null || ownBuildings == null ? null : ownBuildings.get(point.id);
            c.ownId = point == null ? null : point.buildingId;
            c.startFree = isChamber(net, nodeIds, first);
            c.endFree = isChamber(net, nodeIds, last);
            c.others = new ArrayList<>(geometry);
            c.others.remove(n);
            geometry.set(n, refiner.refine(c, geometry.get(n)));
        }
        if (refiner != null) {
            r.shortcuts = refiner.shortcuts();
            r.shortcutGain = refiner.shortcutGainRubles();
        }
        for (int n = 0; n < chains.size(); n++) {
            emitChain(net, chains.get(n), geometry.get(n), flow, dn, nodeIds, obstacles, ids, r);
        }
        NodeAngles.measure(r.segments, rules);
    }

    /** Камера на ветвлении: не место присоединения и не точка подключения. */
    private static boolean isChamber(NewNetwork net, Map<Integer, String> nodeIds, int vertex) {
        return nodeIds.containsKey(vertex) && !net.oksAt.containsKey(vertex) && !net.tieInAt.containsKey(vertex);
    }

    private void emitChain(NewNetwork net, List<Integer> chain, List<Coordinate> pts, Map<Long, Double> flow,
                           Map<Long, Integer> dn, Map<Integer, String> nodeIds, ObstacleSet obstacles,
                           Ids ids, VariantResult r) {
        int first = chain.get(0);
        int second = chain.get(1);
        int last = chain.get(chain.size() - 1);
        long firstLink = NewNetwork.key(first, second);
        double f = flow.getOrDefault(firstLink, 0.0);
        int d = dn.getOrDefault(firstLink, rules.diameters.get(0).dn);

        String startId = endpointId(first, nodeIds, ids, r);
        String endId = endpointId(last, nodeIds, ids, r);

        List<Coordinate> tieIns = new ArrayList<>();
        if (net.tieInAt.containsKey(first)) {
            tieIns.add(grid.vertex(first));
        }
        if (net.tieInAt.containsKey(last)) {
            tieIns.add(grid.vertex(last));
        }
        List<LayingSplit.Part> parts = LayingSplit.split(obstacles, pts, tieIns);
        String currentStart = startId;
        for (int i = 0; i < parts.size(); i++) {
            LayingSplit.Part part = parts.get(i);
            String partEnd = endId;
            if (i < parts.size() - 1) {
                VariantResult.Node node = new VariantResult.Node();
                node.id = ids.next("node");
                node.at = part.pts.get(part.pts.size() - 1);
                r.nodes.add(node);
                partEnd = node.id;
            }
            r.segments.add(segment(part, currentStart, partEnd, f, d, r));
            currentStart = partEnd;
        }
    }

    private String endpointId(int vertex, Map<Integer, String> nodeIds, Ids ids, VariantResult r) {
        String id = nodeIds.get(vertex);
        if (id == null) {
            // Обрыв цепочки без узла: ставим технический узел, чтобы участок не висел.
            id = ids.next("node");
            nodeIds.put(vertex, id);
            VariantResult.Node node = new VariantResult.Node();
            node.id = id;
            node.at = grid.vertex(vertex);
            r.nodes.add(node);
        }
        return id;
    }

    private VariantResult.Seg segment(LayingSplit.Part part, String startId, String endId, double flow, int dn,
                                      VariantResult r) {
        VariantResult.Seg s = new VariantResult.Seg();
        s.id = "new_" + (r.segments.size() + 1);
        s.startNodeId = startId;
        s.endNodeId = endId;
        s.flow = flow;
        s.dn = dn;
        s.laying = part.special() ? "special" : "base";
        s.specialType = part.type;
        s.pts = part.pts;
        s.length = Geo.roundedLength(part.pts);
        s.k = part.k;
        s.turns = rules.countTurns(part.pts);
        s.cost = rules.segmentCost(dn, s.length, part.k);
        if (settings.writeNullDepth) {
            s.depthStart = null;
            s.depthEnd = null;
        }
        return s;
    }

    private void penalties(VariantResult r) {
        Set<String> connected = new HashSet<>();
        for (VariantResult.Seg s : r.segments) {
            connected.add(s.endNodeId);
            connected.add(s.startNodeId);
        }
        for (ConnectionPoint p : data.points) {
            if (!connected.contains(p.id) && !r.unconnected.contains(p.id)) {
                r.unconnected.add(p.id);
            }
        }
        for (String id : r.unconnected) {
            ConnectionPoint p = pointById.get(id);
            r.unconnectedPenalty += rules.penalty(p == null ? 0 : p.flow);
        }
    }

    /**
     * Какие дома питаются от какого присоединения: по этому варианты отличаются содержательно.
     * Считается по месту присоединения целиком, а не по каждой ветке из него: два дерева с
     * теми же местами присоединения и теми же домами за каждым из них, где дом лишь
     * переехал с одной ветки на соседнюю, инженер назовёт одним вариантом.
     */
    private void groups(NewNetwork net, VariantResult r) {
        for (Map.Entry<Integer, String> e : net.tieInAt.entrySet()) {
            List<String> served = new ArrayList<>();
            Deque<Integer> queue = new ArrayDeque<>();
            Set<Integer> seen = new HashSet<>();
            seen.add(e.getKey());
            queue.add(e.getKey());
            while (!queue.isEmpty()) {
                int v = queue.poll();
                if (net.oksAt.containsKey(v)) {
                    served.add(net.oksAt.get(v));
                }
                for (int n : net.neighbours(v)) {
                    if (seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
            Collections.sort(served);
            Coordinate at = grid.vertex(e.getKey());
            r.groups.add(Math.round(at.x) + ":" + Math.round(at.y) + "=" + String.join(",", served));
        }
        Collections.sort(r.groups);
    }

    /** Стоимость варианта, раздел 6. */
    private void totals(VariantResult r) {
        for (VariantResult.Seg s : r.segments) {
            r.segmentsCost += s.cost;
            r.newNetworkLength += s.length;
        }
        for (VariantResult.Cham c : r.chambers) {
            r.chamberConstructionCost += c.cost;
        }
        for (VariantResult.Attachment a : r.attachments) {
            if (a.existingChamber) {
                r.existingChamberTieInCount += a.branches;
                r.existingChamberTieInCost += a.cost;
            }
        }
        r.constructionCost = r.segmentsCost + r.chamberConstructionCost + r.existingChamberTieInCost;
        r.calculatedCost = r.constructionCost + r.unconnectedPenalty;
        r.score = rules.score(r.calculatedCost, r.newNetworkLength);
    }

    private static class Tree {
        Map<Integer, Integer> parent = new HashMap<>();
        List<Integer> order = new ArrayList<>();
    }

    /** Идентификаторы новых объектов: не повторяют ни друг друга, ни идентификаторы входа. */
    private static class Ids {
        private final Map<String, Integer> counters = new HashMap<>();
        private final Set<String> taken;

        Ids(Set<String> taken) {
            this.taken = taken == null ? Collections.emptySet() : taken;
        }

        String next(String prefix) {
            while (true) {
                int n = counters.merge(prefix, 1, Integer::sum);
                String id = prefix + "_" + n;
                if (!taken.contains(id)) {
                    return id;
                }
            }
        }
    }
}
