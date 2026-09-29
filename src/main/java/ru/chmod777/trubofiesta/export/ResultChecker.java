package ru.chmod777.trubofiesta.export;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import ru.chmod777.trubofiesta.calc.Evaluator;
import ru.chmod777.trubofiesta.approach.Approaches;
import ru.chmod777.trubofiesta.approach.WallExits;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.constraints.Clearance;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.constraints.SpecialZone;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;

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
 * Проверка готового варианта по правилам технического приложения от 19.09.2026.
 * Считает стоимость заново и сверяет, проверяет геометрию, ограничения и устройство сети.
 * Проверка идёт по списку правил, а не по тому, что умеет расчёт: так она ловит и ошибки
 * самого расчёта, и нарушения в загруженной схеме инженера.
 *
 * Это не замена независимой проверке: расчёт и проверка делят одни препятствия и одни
 * трактовки, поэтому результат обязательно прогоняется ещё и сторонним скриптом.
 */
public class ResultChecker {

    private final Rules rules;
    private final CalcSettings settings;

    public ResultChecker(Rules rules, CalcSettings settings) {
        this.rules = rules;
        this.settings = settings;
    }

    public List<String> check(VariantResult v, ObstacleSet obstacles, Clearance clearance,
                              Map<String, Geometry> ownBuildings, InputData data) {
        List<String> problems = new ArrayList<>();
        checkCosts(v, problems);
        checkDiameters(v, data, problems);
        checkLengthLimit(v, problems);
        checkGeometry(v, clearance, ownBuildings == null ? new HashMap<>() : ownBuildings, data, problems);
        checkIds(v, problems);
        checkStructure(v, data, problems);
        checkCrossings(v, problems);
        problems.addAll(Evaluator.overlappingBranches(v, settings.minBranchAngle));
        checkExits(v, data, problems);
        checkSpecialAngles(v, obstacles, problems);
        return problems;
    }

    /** Стоимость по разделу 6: участки, камеры, врезки в существующие камеры и штраф. */
    private void checkCosts(VariantResult v, List<String> problems) {
        double segments = 0;
        for (VariantResult.Seg s : v.segments) {
            double k = "special".equals(s.laying) && s.specialType != null ? rules.rule(s.specialType).k : 1.0;
            double expected = rules.segmentCost(s.dn, s.length, k);
            if (relativeGap(expected, s.cost) > 0.01) {
                problems.add("Стоимость участка " + s.id + " не сходится с формулой раздела 6");
            }
            segments += s.cost;
        }
        double chambers = 0;
        for (VariantResult.Cham c : v.chambers) {
            if (Math.abs(rules.chamberCost(c.dn) - c.cost) > 1) {
                problems.add("Стоимость камеры " + c.id + " не по шкале раздела 3.2");
            }
            chambers += c.cost;
        }
        int tieInCount = 0;
        double tieInCost = 0;
        for (VariantResult.Attachment a : v.attachments) {
            if (!a.existingChamber) {
                continue;
            }
            tieInCount += a.branches;
            tieInCost += a.cost;
            if (Math.abs(a.branches * rules.existingChamberTieInCost - a.cost) > 1) {
                problems.add("Врезки в камеру " + a.existingObjectId + " стоят не по 5 млн за участок");
            }
        }
        if (relativeGap(chambers, v.chamberConstructionCost) > 0.001) {
            problems.add("Сумма стоимостей камер не равна chamber_construction_cost");
        }
        if (tieInCount != v.existingChamberTieInCount
                || relativeGap(tieInCost, v.existingChamberTieInCost) > 0.001) {
            problems.add("Число или стоимость врезок в существующие камеры не сходится");
        }
        if (relativeGap(segments + chambers + tieInCost, v.constructionCost) > 0.001) {
            problems.add("Стоимость строительства не равна сумме участков, камер и врезок");
        }
        if (relativeGap(v.constructionCost + v.unconnectedPenalty, v.calculatedCost) > 0.001) {
            problems.add("Итоговая стоимость не равна стоимости строительства плюс штраф");
        }
        if (Math.abs(rules.score(v.calculatedCost, v.newNetworkLength) - v.score) > 0.002) {
            problems.add("Показатель ранжирования не сходится с формулой раздела 6");
        }
    }

    /**
     * Условный диаметр: хватает на расход, не завышен без причины и не убывает по направлению
     * к месту присоединения, раздел 2.3.
     */
    private void checkDiameters(VariantResult v, InputData data, List<String> problems) {
        for (VariantResult.Seg s : v.segments) {
            if (rules.byDn(s.dn).capacity < s.flow - 1e-6) {
                problems.add("Участок " + s.id + ": пропускной способности Ду" + s.dn + " не хватает на расход");
            }
        }
        Map<String, List<VariantResult.Seg>> out = new LinkedHashMap<>();
        for (VariantResult.Seg s : v.segments) {
            out.computeIfAbsent(s.startNodeId, k -> new ArrayList<>()).add(s);
        }
        for (VariantResult.Seg s : v.segments) {
            for (VariantResult.Seg child : out.getOrDefault(s.endNodeId, new ArrayList<>())) {
                if (child.dn > s.dn) {
                    problems.add("Ду убывает к месту присоединения: у " + child.id + " Ду" + child.dn
                            + ", а у вышестоящего " + s.id + " Ду" + s.dn);
                }
            }
        }
        // Расходы: участок несёт сумму расходов всех точек ниже по дереву.
        Map<String, Double> pointFlow = new HashMap<>();
        if (data != null) {
            for (ConnectionPoint p : data.points) {
                pointFlow.put(p.id, p.flow);
            }
        }
        for (VariantResult.Seg s : v.segments) {
            double below = below(s, out, pointFlow, new HashSet<>());
            if (Math.abs(below - s.flow) > 0.01) {
                problems.add("Участок " + s.id + ": расход " + s.flow + " т/ч не равен сумме точек ниже "
                        + Math.round(below * 100) / 100.0);
            }
        }
    }

    private double below(VariantResult.Seg s, Map<String, List<VariantResult.Seg>> out,
                         Map<String, Double> pointFlow, Set<String> seen) {
        if (!seen.add(s.id)) {
            return 0;
        }
        double sum = pointFlow.getOrDefault(s.endNodeId, 0.0);
        for (VariantResult.Seg child : out.getOrDefault(s.endNodeId, new ArrayList<>())) {
            sum += below(child, out, pointFlow, seen);
        }
        return sum;
    }

    /**
     * Предельная длина по каждому непрерывному пути одного Ду, ответ 2. Общий участок
     * входит в каждый путь, параллельные ветви не суммируются.
     */
    private void checkLengthLimit(VariantResult v, List<String> problems) {
        Map<String, List<VariantResult.Seg>> out = new LinkedHashMap<>();
        Set<String> hasParent = new HashSet<>();
        for (VariantResult.Seg s : v.segments) {
            out.computeIfAbsent(s.startNodeId, k -> new ArrayList<>()).add(s);
            hasParent.add(s.endNodeId);
        }
        for (VariantResult.Seg s : v.segments) {
            double limit = rules.byDn(s.dn).maxLength;
            double path = longestPath(s, out, new HashSet<>());
            if (path > limit + rules.roundingTolerance) {
                problems.add("Ду" + s.dn + ": непрерывный путь " + Math.round(path)
                        + " м длиннее предельной длины " + Math.round(limit) + " м");
                return;
            }
        }
    }

    /** Самый длинный путь того же Ду, начинающийся этим участком, вместе с ним самим. */
    private double longestPath(VariantResult.Seg s, Map<String, List<VariantResult.Seg>> out, Set<String> seen) {
        if (!seen.add(s.id)) {
            return 0;
        }
        double best = 0;
        for (VariantResult.Seg child : out.getOrDefault(s.endNodeId, new ArrayList<>())) {
            if (child.dn == s.dn) {
                best = Math.max(best, longestPath(child, out, seen));
            }
        }
        return s.length + best;
    }

    /** Геометрия и минимальные расстояния: каждый участок по своему диаметру, по исходным полигонам. */
    private void checkGeometry(VariantResult v, Clearance clearance, Map<String, Geometry> ownBuildings,
                               InputData data, List<String> problems) {
        Map<String, ConnectionPoint> points = new HashMap<>();
        if (data != null) {
            data.points.forEach(p -> points.put(p.id, p));
        }
        for (VariantResult.Seg s : v.segments) {
            if (s.pts == null || s.pts.size() < 2) {
                problems.add("Участок " + s.id + " имеет вырожденную геометрию");
                return;
            }
            if (Math.abs(Geo.length(s.pts) - s.length) > 0.02) {
                problems.add("Длина участка " + s.id + " не совпадает с геометрией");
            }
            for (int i = 1; i < s.pts.size() - 1; i++) {
                double turn = Geo.turnDegrees(s.pts.get(i - 1), s.pts.get(i), s.pts.get(i + 1));
                if (turn > rules.turn.maxDeg + rules.turn.toleranceDeg) {
                    problems.add("Участок " + s.id + ": поворот " + Math.round(turn) + "° круче "
                            + Math.round(rules.turn.maxDeg) + "°");
                }
            }
        }
        if (clearance != null) {
            problems.addAll(Evaluator.clearanceProblems(v, clearance, ownBuildings, points));
        }
    }

    private void checkIds(VariantResult v, List<String> problems) {
        Set<String> ids = new HashSet<>();
        for (VariantResult.Seg s : v.segments) {
            if (!ids.add(s.id)) {
                problems.add("Повторяющийся идентификатор участка " + s.id);
            }
            if (s.startNodeId == null || s.endNodeId == null) {
                problems.add("У участка " + s.id + " не заполнены узлы");
            }
        }
        List<String> objects = new ArrayList<>();
        v.nodes.forEach(n -> objects.add(n.id));
        v.chambers.forEach(c -> objects.add(c.id));
        for (String id : objects) {
            if (!ids.add(id)) {
                problems.add("Повторяющийся идентификатор объекта " + id);
            }
        }
    }

    /**
     * Устройство сети: точка подключения — конец ровно одного участка, ветвления только
     * в камерах, технический узел соединяет ровно два участка, к камере примыкает не больше
     * четырёх участков, каждая часть сети — дерево с одним местом присоединения.
     */
    private void checkStructure(VariantResult v, InputData data, List<String> problems) {
        Map<String, Integer> degree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (VariantResult.Seg s : v.segments) {
            degree.merge(s.startNodeId, 1, Integer::sum);
            degree.merge(s.endNodeId, 1, Integer::sum);
            adjacency.computeIfAbsent(s.startNodeId, k -> new ArrayList<>()).add(s.endNodeId);
            adjacency.computeIfAbsent(s.endNodeId, k -> new ArrayList<>()).add(s.startNodeId);
        }
        Set<String> points = new HashSet<>();
        if (data != null) {
            for (ConnectionPoint p : data.points) {
                points.add(p.id);
            }
        }
        Map<String, VariantResult.Cham> chambers = new HashMap<>();
        v.chambers.forEach(c -> chambers.put(c.id, c));
        Map<String, VariantResult.Attachment> attachments = new HashMap<>();
        v.attachments.forEach(a -> attachments.put(a.nodeId, a));
        Set<String> nodes = new HashSet<>();
        v.nodes.forEach(n -> nodes.add(n.id));

        for (Map.Entry<String, Integer> e : degree.entrySet()) {
            String id = e.getKey();
            int d = e.getValue();
            VariantResult.Attachment a = attachments.get(id);
            // Существующая труба через новую камеру занимает два примыкания, ответ 12.
            int occupied = a == null ? 0 : (a.existingChamber ? existingBranches(a, data) : 2);
            if (points.contains(id) && d != 1) {
                problems.add("Через точку подключения " + id + " проходит " + d
                        + " участка: трасса не должна идти транзитом через дом или ветвиться в нём");
            } else if (nodes.contains(id) && d != 2) {
                problems.add("Технический узел " + id + " соединяет " + d
                        + " участков, ветвление допускается только в камере");
            } else if (d >= 3 && !chambers.containsKey(id) && a == null) {
                problems.add("Ветвление в узле " + id + " не в тепловой камере");
            }
            if ((chambers.containsKey(id) || a != null) && d + occupied > settings.chamberMaxBranches) {
                problems.add("К камере " + id + " примыкает " + (d + occupied) + " участков");
            }
        }
        for (VariantResult.Cham c : v.chambers) {
            int max = 0;
            for (VariantResult.Seg s : v.segments) {
                if (c.id.equals(s.startNodeId) || c.id.equals(s.endNodeId)) {
                    max = Math.max(max, s.dn);
                }
            }
            if (c.dn < max) {
                problems.add("Камера " + c.id + ": Ду" + c.dn + " меньше наибольшего Ду примыкающих " + max);
            }
        }

        Set<String> seen = new HashSet<>();
        for (String startId : adjacency.keySet()) {
            if (!seen.add(startId)) {
                continue;
            }
            int vertices = 0;
            int edgeEnds = 0;
            int tieCount = 0;
            Deque<String> queue = new ArrayDeque<>();
            queue.add(startId);
            while (!queue.isEmpty()) {
                String id = queue.poll();
                vertices++;
                edgeEnds += adjacency.get(id).size();
                if (attachments.containsKey(id)) {
                    tieCount++;
                }
                for (String n : adjacency.get(id)) {
                    if (seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
            if (edgeEnds / 2 != vertices - 1) {
                problems.add("Часть новой сети содержит замкнутый контур: к дому должен вести один путь");
            }
            if (tieCount != 1) {
                problems.add("Часть новой сети присоединена к существующей в " + tieCount
                        + " точках, должна ровно в одной");
            }
        }
    }

    private int existingBranches(VariantResult.Attachment a, InputData data) {
        if (data == null || a.existingObjectId == null) {
            return 0;
        }
        ru.chmod777.trubofiesta.model.Chamber c = data.chamberById.get(a.existingObjectId);
        return c == null ? 0 : c.existingBranches;
    }

    /** Новые участки не пересекаются между собой вне общего узла, раздел 2.1. */
    private void checkCrossings(VariantResult v, List<String> problems) {
        List<LineString> lines = new ArrayList<>();
        for (VariantResult.Seg s : v.segments) {
            lines.add(s.pts == null || s.pts.size() < 2 ? null : Geo.line(s.pts));
        }
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {
                LineString a = lines.get(i);
                LineString b = lines.get(j);
                if (a == null || b == null || !a.getEnvelopeInternal().intersects(b.getEnvelopeInternal())) {
                    continue;
                }
                // Проверяем не только пересечение, но и касание: две трассы ближе сантиметра
                // друг к другу — это одна траншея, а не две.
                if (a.distance(b) > 0.01) {
                    continue;
                }
                Geometry hit = a.intersects(b) ? a.intersection(b) : nearest(a, b);
                boolean bad = false;
                for (Coordinate c : hit.getCoordinates()) {
                    if (!isEnd(c, a) || !isEnd(c, b)) {
                        bad = true;
                    }
                }
                if (bad) {
                    problems.add("Участки " + v.segments.get(i).id + " и " + v.segments.get(j).id
                            + " пересекаются или накладываются вне общего узла");
                }
            }
        }
    }

    private Geometry nearest(LineString a, LineString b) {
        Coordinate[] pair = org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(a, b);
        return Geo.point(pair[0]);
    }

    private boolean isEnd(Coordinate c, LineString line) {
        return c.distance(line.getStartPoint().getCoordinate()) < 0.02
                || c.distance(line.getEndPoint().getCoordinate()) < 0.02;
    }

    /**
     * Подход к точке подключения, раздел 2.2: один финальный прямой участок от ближайшей
     * к точке границы полигона до самой точки, отступ к своему полигону на него не действует.
     */
    private void checkExits(VariantResult v, InputData data, List<String> problems) {
        if (data == null) {
            return;
        }
        Map<String, ConnectionPoint> points = new HashMap<>();
        data.points.forEach(p -> points.put(p.id, p));
        for (VariantResult.Seg s : v.segments) {
            ConnectionPoint p = points.get(s.endNodeId);
            if (p == null || p.buildingId == null || data.restrictionById.get(p.buildingId) == null) {
                continue;
            }
            Polygon wall = "PART".equals(settings.ownBuildingScope)
                    ? Approaches.partContaining(data.restrictionById.get(p.buildingId).geom, p.at)
                    : Approaches.corpus(p, data);
            if (wall == null) {
                continue;
            }
            List<Coordinate> pts = s.pts;
            Coordinate last = pts.get(pts.size() - 1);
            Coordinate bend = pts.get(pts.size() - 2);
            if (last.distance(p.at) > 0.5 || !wall.covers(Geo.point(p.at))) {
                continue;
            }
            LineString run = Geo.line(java.util.Arrays.asList(bend, last));
            int walls = wall.getBoundary().intersection(run).getNumPoints();
            if (walls != 1) {
                problems.add("Отвод к точке подключения " + p.id + " пересекает стены здания " + walls
                        + " раза: труба проходит сквозь здание");
                continue;
            }
            Geometry inside = wall.intersection(run);
            double outside = run.getLength() - inside.getLength();
            double clearance = rules.rule("oks").clearanceFor(s.dn);
            if (outside + 0.3 < clearance) {
                problems.add("От точки подключения " + p.id + " трасса поворачивает раньше, чем выходит за зону "
                        + "минимального расстояния от здания");
            }
            Coordinate exit = exitPoint(wall, last, bend);
            if (exit == null) {
                continue;
            }
            double angle = wallAngle(wall, exit, bend, last);
            if (angle < 90 - settings.exitSnapDeg - 1) {
                problems.add("От точки подключения " + p.id + " трасса выходит из здания не по нормали к стене ("
                        + Math.round(angle) + "°)");
            }
            if ("NEAREST".equals(settings.exitWall) && !p.exitFallback) {
                double clearanceAxis = Approaches.clearance(rules, settings, s.dn) + 0.3;
                List<WallExits.Exit> nearest = WallExits.exits(p, data, new ru.chmod777.trubofiesta.geo.Frame(0),
                        clearanceAxis, settings, true);
                double through = exit.distance(p.at);
                if (!nearest.isEmpty()
                        && through > nearest.get(0).wallDistance / Math.cos(Math.toRadians(settings.exitSnapDeg)) + 0.5) {
                    problems.add("От точки подключения " + p.id + " трасса выходит не через ближайшую стену: до неё "
                            + String.format(java.util.Locale.US, "%.1f", nearest.get(0).footDistance)
                            + " м, до места выхода " + String.format(java.util.Locale.US, "%.1f", through) + " м");
                }
            }
        }
    }

    private Coordinate exitPoint(Polygon wall, Coordinate inside, Coordinate outside) {
        Geometry hit = wall.getBoundary().intersection(Geo.line(java.util.Arrays.asList(inside, outside)));
        Coordinate best = null;
        for (Coordinate c : hit.getCoordinates()) {
            if (best == null || c.distance(inside) < best.distance(inside)) {
                best = c;
            }
        }
        return best;
    }

    /** Угол между первым отрезком трассы и стеной, через которую она выходит, градусы. */
    private double wallAngle(Polygon wall, Coordinate exit, Coordinate a, Coordinate b) {
        Coordinate[] ring = wall.getBoundary().getCoordinates();
        double best = Double.MAX_VALUE;
        double wallDir = 0;
        for (int i = 1; i < ring.length; i++) {
            LineString edge = Geo.line(java.util.Arrays.asList(ring[i - 1], ring[i]));
            double d = edge.distance(Geo.point(exit));
            if (d < best) {
                best = d;
                wallDir = Math.atan2(ring[i].y - ring[i - 1].y, ring[i].x - ring[i - 1].x);
            }
        }
        return ObstacleSet.crossingAngle(Math.atan2(b.y - a.y, b.x - a.x), wallDir);
    }

    /**
     * Специальный проход: выполняется одним прямым участком и пересекает объект под углом
     * не меньше заданного, раздел 4 и ответ 6.
     */
    private void checkSpecialAngles(VariantResult v, ObstacleSet obstacles, List<String> problems) {
        if (obstacles == null) {
            return;
        }
        for (VariantResult.Seg s : v.segments) {
            if (!"special".equals(s.laying) || s.specialType == null) {
                continue;
            }
            if (s.pts.size() > 2) {
                problems.add("Специальный проход " + s.id + " выполнен ломаной из " + (s.pts.size() - 1)
                        + " отрезков, нужен один прямой участок");
            }
            double minAngle = rules.rule(s.specialType).minAngle;
            if (minAngle <= 0) {
                continue;
            }
            LineString line = Geo.line(s.pts);
            for (SpecialZone z : obstacles.zonesNear(line.getEnvelopeInternal())) {
                if (!s.specialType.equals(z.type) || !line.intersects(z.object)) {
                    continue;
                }
                Coordinate at = line.intersection(z.object).getCentroid().getCoordinate();
                for (int i = 1; i < s.pts.size(); i++) {
                    LineString piece = Geo.line(java.util.Arrays.asList(s.pts.get(i - 1), s.pts.get(i)));
                    if (!piece.intersects(z.object)) {
                        continue;
                    }
                    double angle = ObstacleSet.crossingAngle(Math.atan2(s.pts.get(i).y - s.pts.get(i - 1).y,
                            s.pts.get(i).x - s.pts.get(i - 1).x), z.axisAngle(at));
                    if (angle < minAngle - 1) {
                        problems.add("Участок " + s.id + " пересекает " + rules.rule(s.specialType).title
                                + " под углом " + Math.round(angle) + "°, меньше " + Math.round(minAngle) + "°");
                    }
                }
            }
        }
    }

    private double relativeGap(double expected, double actual) {
        double base = Math.max(1, Math.abs(expected));
        return Math.abs(expected - actual) / base;
    }
}
