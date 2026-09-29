package ru.chmod777.trubofiesta.algo.octogrid;

import ru.chmod777.trubofiesta.algo.NewNetwork;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.rules.Diameter;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.TieInCandidate;
import ru.chmod777.trubofiesta.topology.Topology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Строит дерево подключения. Каждая следующая точка подключения присоединяется туда, где
 * это обходится дешевле всего с учётом уже построенного: к стволу через новую камеру,
 * в уже существующую камеру ветвления или отдельным присоединением к существующей сети.
 * Труба, камера и врезка сравниваются в одной величине — показателе S.
 *
 * Отказ от подключения ради показателя запрещён (раздел 2.5): точка остаётся без трассы,
 * только если допустимого маршрута нет.
 */
public class Planner {

    private final InputData data;
    private final Topology topology;
    private final Rules rules;
    private final CalcSettings settings;
    private final OctoGrid grid;
    private final Router router;
    private final Map<String, Integer> houseVertex;
    private final Map<String, float[]> fields;
    private final Set<Integer> houses = new HashSet<>();
    private final Map<Integer, TieInCandidate> tieInAtVertex = new LinkedHashMap<>();
    /** Сколько способов подключения пробовать, если трассу перекрывает построенная сеть. */
    private static final int OPTIONS_TRIED = 8;
    /** Предел раскрытых состояний при восстановлении одной трассы. */
    private static final int PATH_BUDGET = 15_000;

    public Planner(InputData data, Topology topology, Rules rules, CalcSettings settings, OctoGrid grid,
                   Router router, Map<String, Integer> tieInVertex, Map<String, Integer> houseVertex,
                   Map<String, float[]> fields) {
        this.data = data;
        this.topology = topology;
        this.rules = rules;
        this.settings = settings;
        this.grid = grid;
        this.router = router;
        this.houseVertex = houseVertex;
        this.fields = fields;
        houses.addAll(houseVertex.values());
        Map<String, TieInCandidate> byId = new HashMap<>();
        for (TieInCandidate c : topology.tieIns) {
            byId.put(c.id, c);
        }
        for (Map.Entry<String, Integer> e : tieInVertex.entrySet()) {
            tieInAtVertex.put(e.getValue(), byId.get(e.getKey()));
        }
    }

    /**
     * Цена метра на ребре для дома с таким расходом; null — везде полная цена по его Ду.
     * Через неё алгоритм сообщает планировщику, что часть коридоров уже занята чужим расходом.
     */
    private java.util.function.DoubleFunction<Router.Price.EdgeMoney> edgeMoney;

    /** Ставит цену метра, различающуюся от ребра к ребру. Действует и на поле, и на трассу. */
    public void edgeMoney(java.util.function.DoubleFunction<Router.Price.EdgeMoney> money) {
        this.edgeMoney = money;
    }

    /** Цена трубы для конкретной точки: диаметр подбирается по её расходу. */
    public Router.Price price(double flow) {
        Diameter d = rules.byFlow(flow);
        Router.Price p = new Router.Price();
        p.money = d.newCost * rules.scorePerRuble();
        p.meter = rules.scorePerMeter();
        p.bend = settings.turnPenalty * (p.money + p.meter);
        p.jog = settings.jogPenalty * (p.money + p.meter);
        p.minRun = settings.minStraightRun;
        if (edgeMoney != null) {
            p.edgeMoney = edgeMoney.apply(flow);
        }
        return p;
    }

    /** Штраф за неподключение точки в единицах S. */
    public double penaltyScore(ConnectionPoint p) {
        return rules.penalty(p.flow) * rules.scorePerRuble();
    }

    /** Один жадный проход в заданном порядке точек подключения. */
    public NewNetwork build(List<ConnectionPoint> order) {
        NewNetwork net = new NewNetwork();
        for (ConnectionPoint p : order) {
            insert(net, p, null);
        }
        return net;
    }

    public boolean insert(NewNetwork net, ConnectionPoint p) {
        return insert(net, p, null);
    }

    /**
     * Присоединяет одну точку к уже построенной сети туда, где это обходится дешевле всего
     * с учётом построенного: к стволу через новую камеру, в уже существующую камеру
     * ветвления или отдельным присоединением к существующей сети.
     *
     * @param avoid вершины, к которым присоединяться нельзя: так точка ищет другое место,
     *              когда прежнее уже проверено. Null — ограничений нет
     * @return удалось ли присоединить
     */
    public boolean insert(NewNetwork net, ConnectionPoint p, Set<Integer> avoid) {
        Integer target = houseVertex.get(p.id);
        float[] field = fields.get(p.id);
        if (target == null || field == null) {
            unconnected(net, p);
            return false;
        }
        Diameter d = rules.byFlow(p.flow);

        // Все способы подключения с ценой; обычно берётся первый, но если его трассу
        // перекрывает уже построенная сеть, пробуем следующие.
        List<double[]> options = new ArrayList<>();
        for (int v : net.vertices) {
            if (houses.contains(v) || Float.isInfinite(field[v]) || (avoid != null && avoid.contains(v))) {
                continue;
            }
            double attach = tieInAtVertex.containsKey(v)
                    ? usedAttachmentCost(net, v) : branchCost(net, v, d.dn);
            if (!Double.isInfinite(attach)) {
                options.add(new double[]{field[v] + attach, v});
            }
        }
        for (Map.Entry<Integer, TieInCandidate> e : tieInAtVertex.entrySet()) {
            int v = e.getKey();
            if (net.vertices.contains(v) || Float.isInfinite(field[v]) || (avoid != null && avoid.contains(v))) {
                continue;
            }
            options.add(new double[]{field[v] + newAttachmentCost(e.getValue(), d.dn), v});
        }
        options.sort((x, y) -> Double.compare(x[0], y[0]));
        if (options.isEmpty()) {
            unconnected(net, p);
            return false;
        }

        List<Integer> path = new ArrayList<>();
        for (int k = 0; k < Math.min(OPTIONS_TRIED, options.size()) && path.size() < 2; k++) {
            int source = (int) options.get(k)[1];
            path = router.path(source, target, price(p.flow), field,
                    (a, b) -> net.link(a, b) == null && net.crosses(a, b, grid.vertex(a), grid.vertex(b)),
                    PATH_BUDGET);
        }
        if (path.size() < 2) {
            unconnected(net, p);
            return false;
        }
        attach(net, path, p);
        net.unconnected.remove(p.id);
        return true;
    }

    private void unconnected(NewNetwork net, ConnectionPoint p) {
        if (!net.unconnected.contains(p.id)) {
            net.unconnected.add(p.id);
        }
    }

    /**
     * Присоединяет трассу к дереву. Если она проходит через вершину, которая уже в дереве,
     * отсчёт начинается от неё: сеть остаётся деревом без замкнутых маршрутов.
     */
    private void attach(NewNetwork net, List<Integer> path, ConnectionPoint p) {
        int from = 0;
        for (int i = path.size() - 2; i >= 0; i--) {
            if (net.vertices.contains(path.get(i))) {
                from = i;
                break;
            }
        }
        for (int i = from + 1; i < path.size(); i++) {
            int a = path.get(i - 1);
            int b = path.get(i);
            net.addLink(a, b, grid.vertex(a).distance(grid.vertex(b)), grid.vertex(a), grid.vertex(b));
        }
        net.oksAt.put(path.get(path.size() - 1), p.id);

        int start = path.get(from);
        TieInCandidate c = tieInAtVertex.get(start);
        if (c != null) {
            net.tieInAt.put(start, c.id);
        }
    }

    /** Ветвление в построенной сети: новая камера, если её там ещё нет, и не больше четырёх участков. */
    private double branchCost(NewNetwork net, int vertex, int dn) {
        int degree = net.degree(vertex);
        if (degree + 1 > settings.chamberMaxBranches) {
            return Double.POSITIVE_INFINITY;
        }
        if (degree >= 3) {
            return 0;
        }
        return rules.chamberCost(dn) * rules.scorePerRuble();
    }

    /**
     * Ещё одна ветка из уже использованного места присоединения. В существующую камеру это
     * новая врезка за 5 млн (ответ 13). В новой камере на трубе — обычное ветвление внутри
     * камеры, отдельной платы нет.
     */
    private double usedAttachmentCost(NewNetwork net, int vertex) {
        TieInCandidate c = tieInAtVertex.get(vertex);
        int existing = c.chamberId != null ? c.chamberBranches : 2;
        if (existing + net.degree(vertex) + 1 > settings.chamberMaxBranches) {
            return Double.POSITIVE_INFINITY;
        }
        double cost = c.chamberId != null ? rules.existingChamberTieInCost : 0;
        return cost * rules.scorePerRuble();
    }

    /**
     * Новое присоединение, раздел 3.2. В существующую камеру — врезка за 5 млн, сама камера
     * не оплачивается. В трубу — новая камера по наибольшему Ду примыкающих, включая саму
     * трубу; её стоимость уже включает присоединение.
     */
    private double newAttachmentCost(TieInCandidate c, int dn) {
        double cost = c.chamberId != null && c.chamberBranches + 1 <= settings.chamberMaxBranches
                ? rules.existingChamberTieInCost
                : rules.chamberCost(Math.max(c.existingDn, dn));
        return cost * rules.scorePerRuble();
    }

    /** Порядки обхода точек подключения: разный порядок даёт разные деревья и разные варианты. */
    public List<List<ConnectionPoint>> orders(int count, long seed) {
        List<List<ConnectionPoint>> out = new ArrayList<>();
        List<ConnectionPoint> base = new ArrayList<>(data.points);

        List<ConnectionPoint> byFlowDesc = new ArrayList<>(base);
        byFlowDesc.sort((a, b) -> Double.compare(b.flow, a.flow));
        out.add(byFlowDesc);

        List<ConnectionPoint> byFlowAsc = new ArrayList<>(byFlowDesc);
        java.util.Collections.reverse(byFlowAsc);
        out.add(byFlowAsc);

        List<ConnectionPoint> byDistance = new ArrayList<>(base);
        byDistance.sort(java.util.Comparator.comparingDouble(this::distanceToNetwork));
        out.add(byDistance);

        List<ConnectionPoint> byDistanceDesc = new ArrayList<>(byDistance);
        java.util.Collections.reverse(byDistanceDesc);
        out.add(byDistanceDesc);

        java.util.Random random = new java.util.Random(seed);
        while (out.size() < Math.max(1, count)) {
            List<ConnectionPoint> shuffled = new ArrayList<>(base);
            java.util.Collections.shuffle(shuffled, random);
            out.add(shuffled);
        }
        return out;
    }

    private double distanceToNetwork(ConnectionPoint p) {
        double best = Double.MAX_VALUE;
        for (TieInCandidate c : topology.tieIns) {
            best = Math.min(best, c.at.distance(p.at));
        }
        return best;
    }
}
