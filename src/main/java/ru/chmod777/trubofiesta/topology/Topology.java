package ru.chmod777.trubofiesta.topology;

import org.locationtech.jts.geom.Coordinate;
import ru.chmod777.trubofiesta.model.Chamber;
import ru.chmod777.trubofiesta.model.Segment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Существующая сеть как граф: узлы, участки с направлением к источнику и кандидаты врезки.
 * Геометрия каждого участка развёрнута так, что pts[0] — дальний от источника конец.
 */
public class Topology {

    public String sourceNode;
    public Map<String, Coordinate> nodes = new LinkedHashMap<>();
    public Map<String, List<String>> segmentsAtNode = new LinkedHashMap<>();
    public Map<String, Chamber> chamberAtNode = new LinkedHashMap<>();
    public List<Segment> segments = new ArrayList<>();
    public Map<String, Segment> byId = new LinkedHashMap<>();
    public List<TieInCandidate> tieIns = new ArrayList<>();
    /** Участки, до которых от источника не дойти. Врезаться в них нельзя. */
    public List<String> disconnected = new ArrayList<>();

    /** Цепочка участков от данного к источнику, включая его самого. */
    public List<Segment> pathToSource(String segmentId) {
        List<Segment> path = new ArrayList<>();
        Segment current = byId.get(segmentId);
        int guard = 0;
        while (current != null && guard++ < 10000) {
            path.add(current);
            String node = current.upstreamNode;
            if (node == null || node.equals(sourceNode)) {
                break;
            }
            Segment next = null;
            for (String id : segmentsAtNode.getOrDefault(node, new ArrayList<>())) {
                Segment s = byId.get(id);
                if (s != null && s != current && node.equals(s.downstreamNode)) {
                    next = s;
                    break;
                }
            }
            current = next;
        }
        return path;
    }

    /** Направления существующих труб в точке: от врезки новая сеть уходит под углом к ним. */
    public List<double[]> pipeDirections(Coordinate at) {
        List<double[]> out = new ArrayList<>();
        org.locationtech.jts.geom.Point point = ru.chmod777.trubofiesta.geo.Geo.point(at);
        for (Segment s : segments) {
            for (int i = 1; i < s.pts.size(); i++) {
                Coordinate a = s.pts.get(i - 1);
                Coordinate b = s.pts.get(i);
                double len = a.distance(b);
                if (len < 1e-6 || ru.chmod777.trubofiesta.geo.Geo.line(java.util.Arrays.asList(a, b)).distance(point) > 1.0) {
                    continue;
                }
                out.add(new double[]{(b.x - a.x) / len, (b.y - a.y) / len});
            }
        }
        return out;
    }

    public int branchesAt(String node) {
        return segmentsAtNode.getOrDefault(node, new ArrayList<>()).size();
    }
}
