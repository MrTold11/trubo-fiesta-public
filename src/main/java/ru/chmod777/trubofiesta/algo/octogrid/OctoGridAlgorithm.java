package ru.chmod777.trubofiesta.algo.octogrid;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;
import ru.chmod777.trubofiesta.algo.AlgorithmContext;
import ru.chmod777.trubofiesta.algo.AlgorithmResult;
import ru.chmod777.trubofiesta.algo.NewNetwork;
import ru.chmod777.trubofiesta.algo.ProgressLog;
import ru.chmod777.trubofiesta.algo.RoutingAlgorithm;
import ru.chmod777.trubofiesta.approach.Approach;
import ru.chmod777.trubofiesta.approach.Approaches;
import ru.chmod777.trubofiesta.approach.WallExits;
import ru.chmod777.trubofiesta.calc.Evaluator;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.geo.Frame;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.Diagnostic;
import ru.chmod777.trubofiesta.model.Restriction;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.TieInCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Трассировка по сетке в осях застройки с жадным деревом подключения.
 *
 * Идея в четырёх шагах.
 *
 * <ol>
 *   <li><b>Сетка.</b> Территория покрывается квадратной сеткой, повёрнутой на преобладающее
 *       направление стен: инженер ведёт сеть вдоль фасадов и улиц. Из узла можно идти
 *       в восемь сторон, поэтому любой поворот трассы — 45 или 90 градусов.</li>
 *   <li><b>Лучи.</b> Точки подключения и места присоединения на узлы сетки не попадают.
 *       К ним ведут лучи: из точки подключения — по нормали к стене, из места присоединения —
 *       в стороны от существующей трубы. На каждом пересечении луча с линией сетки ставится
 *       вершина, и с неё можно свернуть вдоль этой линии.</li>
 *   <li><b>Поля стоимости.</b> Для каждой точки подключения один раз считается, сколько
 *       стоит дотянуть до неё трубу из любой вершины сетки. После этого выбрать, откуда
 *       подключать дом — к уже построенной сети или новым присоединением, — значит сравнить
 *       числа в поле.</li>
 *   <li><b>Жадное дерево.</b> Точки подключения перебираются в разном порядке, каждая
 *       присоединяется туда, где это дешевле всего с учётом уже построенного. Лучшие деревья
 *       улучшаются перестановками порядка, у них сливаются близкие камеры и доводится
 *       геометрия. Три варианта — три лучших содержательно разных дерева.</li>
 * </ol>
 *
 * Цена всюду одна — показатель S раздела 6: так поиск сравнивает трубу, камеру и врезку
 * по той же формуле, по которой оценивается готовый вариант.
 *
 * Точка входа в дом — точка расширения: {@link #attachPoint} решает, откуда трасса может
 * подойти к точке подключения. Базовая реализация перебирает все годные стены здания и
 * оставляет выбор поиску по стоимости.
 */
@Component
public class OctoGridAlgorithm implements RoutingAlgorithm {

    /** Предел числа узлов сетки: больше — сетка укрупняется, чтобы хватило памяти. */
    protected static final int MAX_GRID_NODES = 3_000_000;
    /**
     * Узлов сетки на все точки подключения вместе: поле стоимости считается для каждой точки
     * по всей сетке, и на сотнях домов шаг 2 м даёт часы счёта. Двадцать миллионов узлов на
     * точку — это ЗИЛ без изменений (17 точек, полмиллиона узлов) и шаг около 4 м на 200 домах.
     */
    protected static final long NODES_PER_ALL_POINTS = 20_000_000L;
    /** Сколько лучших разных деревьев доводить перед окончательным отбором. */
    protected static final int REFINE_CANDIDATES = 12;
    /** Сколько лучших разных деревьев улучшать локальным поиском. */
    protected static final int IMPROVE_STARTS = 3;

    @Override
    public String key() {
        return "OCTOGRID";
    }

    @Override
    public String title() {
        return "Сетка в осях застройки, выход через любую годную стену";
    }

    @Override
    public AlgorithmResult plan(AlgorithmContext context, ProgressLog log) {
        AlgorithmResult out = new AlgorithmResult();
        Frame frame = frame(context);
        // Отступы зависят от диаметра участка. Сначала трассируем с отступами под самый толстый
        // отвод к одному дому: так проходят узкие места у домов. Каждый участок затем
        // проверяется по своему Ду. Если стволу где-то тесно, повторяем с отступами под ствол.
        List<VariantResult> all = new ArrayList<>();
        Map<VariantResult, GridSetup> setupOf = new IdentityHashMap<>();
        GridSetup last = null;
        int[] classes = context.branchDn < context.trunkDn
                ? new int[]{context.branchDn, context.trunkDn} : new int[]{context.trunkDn};
        for (int dn : classes) {
            log.step("Трассировка с отступами под Ду" + dn);
            last = setup(context, frame, dn, out, log, null);
            collect(all, setupOf, last, search(context, last, out, log));
            release(last);
            // Второй заход с удвоенной ценой поворота на той же сетке: жадное дерево
            // чувствительно к цене поворота, и лучшее из двух наборов стабильнее любого одного.
            // На больших наборах второй заход не окупается: он удваивает и поля, и поиск.
            if (!"FAST".equals(context.settings.quality)
                    && context.data.points.size() <= CalcSettings.FULL_BUDGET_POINTS) {
                CalcSettings other = context.settings.copy();
                other.turnPenalty = context.settings.turnPenalty * 2;
                log.step("Второй заход: цена поворота " + other.turnPenalty + " м");
                AlgorithmContext alt = context.with(other);
                GridSetup second = setup(alt, frame, dn, out, log, last);
                collect(all, setupOf, second, search(alt, second, out, log));
                release(second);
            }
            if (all.stream().anyMatch(v -> v.clearanceViolations == 0 && v.unconnected.isEmpty())) {
                break;
            }
        }

        long invalid = all.stream().filter(v -> v.clearanceViolations > 0).count();
        log.step("Построено деревьев " + all.size() + ", отброшено из-за отступов " + invalid);
        all.sort(java.util.Comparator
                .<VariantResult>comparingInt(v -> v.clearanceViolations > 0 ? 1 : 0)
                .thenComparingDouble(v -> v.score));
        // Доводка дорогая, поэтому только для лучших разных деревьев, а не для всех запусков.
        List<VariantResult> trees = distinct(all, context.settings, REFINE_CANDIDATES);
        log.step("Слияние камер, камеры на линию ствола и доводка трасс лучших деревьев: деревьев " + trees.size());
        for (int t = 0; t < trees.size(); t++) {
            VariantResult tree = trees.get(t);
            GridSetup s = setupOf.get(tree);
            // Слияние повторяется после переноса камер: две камеры в метре друг от друга на изломе
            // ствола не сливаются, пока ствол не выпрямлен через одну из них.
            NewNetwork net = s.mergeChambers(s.alignChambers(s.mergeChambers(tree.net)));
            VariantResult v = s.evaluator.evaluate(net, "cand", tree.obstacleSet, s.refiner());
            v.treeIndex = t;
            out.candidates.add(v);
        }
        out.treeCount = trees.size();
        if (last != null) {
            out.obstacles = last.set;
            out.ownZones = last.ownZones;
        }
        return out;
    }

    /**
     * Поля стоимости нужны только поиску дерева: на сотнях домов они занимают сотни мегабайт
     * на каждый набор отступов, а слиянию камер и доводке не нужны.
     */
    private static void release(GridSetup s) {
        s.fields = null;
        s.planner = null;
    }

    private void collect(List<VariantResult> all, Map<VariantResult, GridSetup> setupOf, GridSetup s,
                         List<VariantResult> found) {
        for (VariantResult v : found) {
            setupOf.put(v, s);
        }
        all.addAll(found);
    }

    /** Оси сетки: вдоль преобладающего направления стен или по сторонам света. */
    public Frame frame(AlgorithmContext context) {
        if ("NORTH".equals(context.settings.gridOrientation)) {
            return new Frame(0);
        }
        List<Geometry> buildings = new ArrayList<>();
        for (Restriction r : context.data.restrictions) {
            if (r.geom != null && r.geom.getDimension() == 2 && "oks".equals(r.type)
                    && context.area.intersects(r.geom.getEnvelopeInternal())) {
                buildings.add(r.geom);
            }
        }
        return Frame.dominant(buildings);
    }

    // --- сетка --------------------------------------------------------------------

    /**
     * Сетка, лучи, поля стоимости и планировщик для одного набора отступов.
     *
     * @param ready готовый набор с той же сеткой, если она уже построена для этих отступов:
     *              тогда заново считаются только поля стоимости
     */
    public GridSetup setup(AlgorithmContext context, Frame frame, int dn, AlgorithmResult out, ProgressLog log,
                           GridSetup ready) {
        CalcSettings settings = context.settings;
        GridSetup s = new GridSetup();
        s.context = context;
        s.dn = dn;
        s.set = context.obstacles.forDn(dn);
        s.ownZones = Approaches.ownZones(context.data, context.rules, settings, dn);
        if (ready == null) {
            int maxNodes = (int) Math.min(MAX_GRID_NODES,
                    NODES_PER_ALL_POINTS / Math.max(1, context.data.points.size()));
            s.grid = new OctoGrid(s.set, frame, context.area, settings.gridStep, settings.diagonals, maxNodes);
            out.gridStep = s.grid.step;
            out.gridAngle = Math.toDegrees(frame.angle);
            out.graphVertices = s.grid.nodeCount();
            log.step(String.format(java.util.Locale.US,
                    "Сетка трассировки: шаг %.1f м, оси повёрнуты на %.1f°, узлов %d, свободных %d",
                    s.grid.step, out.gridAngle, s.grid.nodeCount(), s.grid.freeNodes()));
            if (s.grid.step > settings.gridStep + 1e-9) {
                out.diagnostics.add(Diagnostic.warning("grid_coarsened",
                        "Территория слишком большая для шага " + settings.gridStep + " м, сетка укрупнена до "
                                + s.grid.step + " м", null));
            }

            log.step("Выходы из зданий и лучи от точек присоединения");
            tieIns(s);
            houses(s, frame, out);
            approachEdges(s);
            int crossings = s.grid.connectRayCrossings();
            out.graphEdges = s.grid.size() - s.grid.nodeCount();
            log.step("Узлов на пересечениях лучей: " + crossings);
        } else {
            s.grid = ready.grid;
            s.tieInVertex = ready.tieInVertex;
            s.houseVertex = ready.houseVertex;
        }

        fields(s, log);
        return s;
    }

    /**
     * Поля стоимости от каждого дома и планировщик по ним. Сетка и лучи при этом не трогаются,
     * поэтому алгоритм, уточняющий цену метра, может позвать этот шаг ещё раз на той же сетке.
     */
    protected void fields(GridSetup s, ProgressLog log) {
        AlgorithmContext context = s.context;
        CalcSettings settings = context.settings;
        log.step("Поля стоимости от каждого дома");
        s.router = new Router(s.grid, context.rules.turn);
        Planner pricing = new Planner(context.data, context.topology, context.rules, settings, s.grid, s.router,
                s.tieInVertex, s.houseVertex, Collections.emptyMap());
        s.fields = new ConcurrentHashMap<>();
        // Поле одного дома занимает сорок чисел на вершину сетки. Считаем их параллельно,
        // но не больше, чем помещается в половину свободной памяти.
        long perField = (long) s.grid.size() * (OctoGrid.STATES * 4 + 1) * 4L;
        int threads = (int) Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / 2 / Math.max(1, perField)));
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(threads);
        try {
            pool.submit(() -> context.data.points.parallelStream().forEach(point -> {
                Integer v = s.houseVertex.get(point.id);
                if (v == null) {
                    return;
                }
                // Отказ от подключения ради показателя запрещён (раздел 2.5), поэтому поле
                // стоимости считается без предела: дорогая трасса всё равно лучше штрафа.
                s.fields.put(point.id, s.router.field(v, fieldPrice(s, pricing, point), Double.POSITIVE_INFINITY));
            })).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Расчёт прерван", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Ошибка при расчёте полей стоимости: "
                    + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdown();
        }
        s.planner = new Planner(context.data, context.topology, context.rules, settings, s.grid, s.router,
                s.tieInVertex, s.houseVertex, s.fields);
        s.evaluator = new Evaluator(context.data, context.topology, context.rules, settings, s.grid,
                context.clearance, context.ownBuildings);
    }

    /**
     * Цена трубы, по которой считается поле стоимости этой точки. Базово — по её собственному
     * расчётному расходу: дом ещё не знает, с кем разделит коридор.
     *
     * Это точка расширения: алгоритм, у которого есть оценка будущих расходов, может вернуть
     * цену, различающуюся от ребра к ребру.
     */
    protected Router.Price fieldPrice(GridSetup s, Planner pricing, ConnectionPoint p) {
        return pricing.price(p.flow);
    }

    /**
     * Кандидаты присоединения на сетке. От каждого идут лучи в стороны от существующей трубы:
     * вдоль самой трубы новая сеть не прокладывается.
     */
    protected void tieIns(GridSetup s) {
        CalcSettings settings = s.context.settings;
        for (TieInCandidate c : s.context.topology.tieIns) {
            if (s.set.blocked(c.at)) {
                continue;
            }
            List<double[]> pipes = s.context.topology.pipeDirections(c.at);
            int vertex = s.grid.addTerminal(c.at);
            int stops = 0;
            for (int d = 0; d < 8; d++) {
                if (!settings.diagonals && d % 2 == 1) {
                    continue;
                }
                double rad = Math.toRadians(45.0 * d);
                double[] dir = s.grid.frame.worldVector(Math.cos(rad), Math.sin(rad));
                if (alongPipe(dir, pipes, settings.tieInMinAngle)) {
                    continue;
                }
                stops += s.grid.ray(vertex, dir, 0.01, settings.rayLength, null, c.at);
            }
            // Кандидат напротив дома получит прямое ребро к нему, даже если лучи никуда не вышли.
            if (stops > 0 || c.approach != null) {
                s.tieInVertex.put(c.id, vertex);
            }
        }
    }

    /**
     * Прямые рёбра от кандидатов напротив домов. По нормали — сразу в точку подключения:
     * это и есть финальный участок раздела 2.2. Вбок — в угол за зоной отступа, из которого
     * ввод уходит к дому под прямым углом; угол ставится на нормали ровно напротив кандидата.
     */
    protected void approachEdges(GridSetup s) {
        for (TieInCandidate c : s.context.topology.tieIns) {
            Integer from = c.approach == null ? null : s.tieInVertex.get(c.id);
            Integer house = c.approach == null ? null : s.houseVertex.get(c.pointId);
            if (from == null || house == null) {
                continue;
            }
            Geometry ownZone = s.ownZones.get(c.pointId);
            Geometry ownWalls = s.context.ownBuildings.get(c.pointId);
            Approach a = c.approach;
            Coordinate wall = new Coordinate(a.wall.x + a.direction[0] * WallExits.EXIT_MARGIN,
                    a.wall.y + a.direction[1] * WallExits.EXIT_MARGIN);
            if (!c.lateral) {
                if (ownWalls == null || !ownWalls.intersects(Geo.line(java.util.Arrays.asList(wall, c.at)))) {
                    s.grid.connect(from, house, ownZone, c.at);
                }
                continue;
            }
            double along = (c.at.x - a.point.x) * a.direction[0] + (c.at.y - a.point.y) * a.direction[1];
            Coordinate corner = new Coordinate(a.point.x + a.direction[0] * along, a.point.y + a.direction[1] * along);
            if (s.set.blocked(corner) || corner.distance(c.at) < 1.0
                    || (ownWalls != null && ownWalls.intersects(Geo.line(java.util.Arrays.asList(wall, corner))))) {
                continue;
            }
            int turn = s.grid.addVertex(corner);
            if (s.grid.connect(turn, house, ownZone, null)) {
                s.grid.connect(from, turn, null, c.at);
            }
        }
    }

    private boolean alongPipe(double[] dir, List<double[]> pipes, double minAngle) {
        for (double[] p : pipes) {
            // Положе минимального угла отход шёл бы вдоль трубы ближе нормы.
            if (Math.abs(dir[0] * p[0] + dir[1] * p[1]) > Math.cos(Math.toRadians(minAngle - 0.5))) {
                return true;
            }
        }
        return false;
    }

    /** Точки подключения на сетке. Как именно труба выходит из дома, решает {@link #attachPoint}. */
    protected void houses(GridSetup s, Frame frame, AlgorithmResult out) {
        List<Diagnostic> notes = new ArrayList<>();
        for (ConnectionPoint p : s.context.data.points) {
            int vertex = s.grid.addTerminal(p.at);
            if (attachPoint(s, p, vertex, frame, notes) == 0) {
                notes.add(Diagnostic.warning("point_enclosed",
                        "От ближайшей к точке границы полигона выйти за зону минимального расстояния "
                                + "нельзя: допустимого маршрута нет", p.id));
                continue;
            }
            s.houseVertex.put(p.id, vertex);
        }
        for (Diagnostic d : notes) {
            boolean known = out.diagnostics.stream()
                    .anyMatch(x -> x.code.equals(d.code) && java.util.Objects.equals(x.objectId, d.objectId));
            if (!known) {
                out.diagnostics.add(d);
            }
        }
    }

    /**
     * Откуда трасса может подойти к точке подключения. Базовая реализация берёт все годные
     * стены здания и от каждой пускает лучи: какой выход выгоднее, решит поиск по стоимости.
     *
     * Это точка расширения. Альтернативная реализация может, наоборот, зафиксировать одну
     * точку подхода и заставить трассу прийти именно в неё.
     *
     * @param vertex вершина сетки, заведённая для самой точки подключения
     * @param notes  замечания к этой точке; сюда же можно дописать свои
     * @return сколько вершин получилось на лучах; ноль означает, что точка недостижима
     */
    protected int attachPoint(GridSetup s, ConnectionPoint p, int vertex, Frame frame, List<Diagnostic> notes) {
        CalcSettings settings = s.context.settings;
        Geometry ownZone = s.ownZones.get(p.id);
        double clearance = Approaches.clearance(s.context.rules, settings, s.dn) + 0.3;
        boolean nearest = "NEAREST".equals(settings.exitWall);
        List<WallExits.Exit> exits = WallExits.exits(p, s.context.data, frame, clearance, settings, nearest);
        Geometry ownWalls = s.context.ownBuildings.get(p.id);
        int stops = rays(s.grid, vertex, exits, settings, ownZone, ownWalls);
        // Раздел 2.2 допускает один прямой участок от ближайшей к точке границы полигона,
        // поэтому выход через другую стену — расширение, включаемое параметром.
        if (stops == 0 && nearest && settings.exitFallback) {
            exits = WallExits.exits(p, s.context.data, frame, clearance, settings, false);
            stops = rays(s.grid, vertex, exits, settings, ownZone, ownWalls);
            if (stops > 0) {
                p.exitFallback = true;
                notes.add(Diagnostic.warning("exit_not_nearest",
                        "Через ближайшую стену выйти нельзя, выход через другую стену здания", p.id));
            }
        }
        if (stops == 0) {
            return 0;
        }
        if (!exits.isEmpty() && !exits.get(0).normal) {
            notes.add(Diagnostic.warning("exit_not_normal",
                    "Ни через одну стену здания нельзя выйти по нормали, выход по осям сетки", p.id));
        } else if (!exits.isEmpty() && exits.get(0).courtyard) {
            notes.add(Diagnostic.info("exit_courtyard",
                    "Наружу по нормали выйти нельзя, выход через внутренний двор", p.id));
        }
        return stops;
    }

    /**
     * Лучи выхода из здания. Отвод идёт по нормали до конца зоны минимального расстояния,
     * там ставится точка поворота. Из неё луч продолжается прямо и расходится вдоль фасада
     * в обе стороны. Если в точке поворота нельзя (угол соседнего здания), луч идёт до
     * первой линии сетки.
     */
    protected int rays(OctoGrid grid, int vertex, List<WallExits.Exit> exits, CalcSettings settings,
                       Geometry ownZone, Geometry ownWalls) {
        int stops = 0;
        for (WallExits.Exit e : exits) {
            // Точка поворота нужна, только когда выход идёт вдоль осей сетки: иначе повороты
            // в ней были бы нестандартными.
            int turn = e.turnDistance > 0 && grid.alongAxis(e.direction)
                    ? grid.exitVertex(vertex, e.direction, e.turnDistance, ownZone) : -1;
            if (turn < 0) {
                stops += grid.ray(vertex, e.direction, e.turnDistance, e.turnDistance + settings.rayLength,
                        ownZone, ownWalls, null);
                continue;
            }
            stops++;
            stops += grid.ray(turn, e.direction, 0.01, settings.rayLength, null, null);
            double[] left = {-e.direction[1], e.direction[0]};
            double[] right = {e.direction[1], -e.direction[0]};
            stops += grid.ray(turn, left, 0.01, settings.rayLength, null, null);
            stops += grid.ray(turn, right, 0.01, settings.rayLength, null, null);
        }
        return stops;
    }

    // --- поиск дерева --------------------------------------------------------------

    /** Многократное построение дерева в разных порядках и улучшение лучшего. */
    protected List<VariantResult> search(AlgorithmContext context, GridSetup s, AlgorithmResult out, ProgressLog log) {
        CalcSettings settings = context.settings;
        log.step("Поиск дерева подключения");
        int starts = "GREEDY".equals(settings.planMode) ? 1
                : Math.max(1, settings.effectiveStarts(context.data.points.size()));
        List<List<ConnectionPoint>> orders = s.planner.orders(starts, settings.seed);
        List<VariantResult> all = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            NewNetwork net = s.planner.build(orders.get(i));
            all.add(s.evaluator.evaluate(net, "tmp" + i, strategyName(i), s.set));
            out.plansEvaluated++;
        }
        if (settings.relink && !all.isEmpty()) {
            log.step("Улучшение лучшего дерева перестановками");
            out.plansEvaluated += improve(s.planner, s.evaluator, s.set, orders, all, settings);
        }
        return all;
    }

    /**
     * Локальный поиск по порядку обхода. Сосед — тот же порядок, но один дом перенесён на
     * другое место: дерево перестраивается, и если показатель стал меньше, порядок принимается.
     * Соседи перебираются в случайном порядке с фиксированным зерном, пока не кончится бюджет
     * построений. Так дом, которому досталась невыгодная врезка или камера, получает шанс
     * присоединиться позже, когда ствол к его соседям уже построен, или раньше, задав ствол.
     */
    protected int improve(Planner planner, Evaluator evaluator, ObstacleSet set,
                          List<List<ConnectionPoint>> orders, List<VariantResult> all, CalcSettings settings) {
        // Стартуем не только с лучшего дерева: жадное дерево чувствительно к порядку,
        // и второе-третье по показателю дерево часто улучшается сильнее первого.
        List<VariantResult> ranked = new ArrayList<>(all);
        ranked.sort(java.util.Comparator.comparingDouble(v -> v.score));
        List<Integer> startIndexes = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (VariantResult v : ranked) {
            int index = all.indexOf(v);
            if (index < orders.size() && seen.add(v.signature(Math.max(1, settings.variantMinDifference)))) {
                startIndexes.add(index);
            }
            if (startIndexes.size() >= IMPROVE_STARTS) {
                break;
            }
        }
        int total = 0;
        int budget = Math.max(60, settings.effectiveStarts(orders.isEmpty() ? 0 : orders.get(0).size()) / 2);
        for (int k = 0; k < startIndexes.size(); k++) {
            int share = k == 0 ? budget - budget / 2 : (budget / 2) / Math.max(1, startIndexes.size() - 1);
            total += improve(planner, evaluator, set, orders.get(startIndexes.get(k)),
                    all.get(startIndexes.get(k)).score, share, all, settings, k);
        }
        return total;
    }

    private int improve(Planner planner, Evaluator evaluator, ObstacleSet set, List<ConnectionPoint> start,
                        double startScore, int budget, List<VariantResult> all, CalcSettings settings, int seedShift) {
        List<ConnectionPoint> order = new ArrayList<>(start);
        int n = order.size();
        if (n < 2) {
            return 0;
        }
        double bestScore = startScore;
        java.util.Random random = new java.util.Random(settings.seed + 1 + seedShift);
        int used = 0;
        boolean improved = true;
        while (improved && used < budget) {
            improved = false;
            List<int[]> moves = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i != j) {
                        moves.add(new int[]{i, j});
                    }
                }
            }
            Collections.shuffle(moves, random);
            for (int[] move : moves) {
                if (used >= budget) {
                    break;
                }
                List<ConnectionPoint> candidate = new ArrayList<>(order);
                ConnectionPoint moved = candidate.remove(move[0]);
                candidate.add(move[1], moved);
                VariantResult v = evaluator.evaluate(planner.build(candidate), "imp" + used, "Улучшение", set);
                used++;
                all.add(v);
                if (v.score < bestScore - 1e-9) {
                    bestScore = v.score;
                    order = candidate;
                    improved = true;
                    break;
                }
            }
        }
        return used;
    }

    /** Деревья должны отличаться содержательно, а не сдвигом той же трассы. */
    protected List<VariantResult> distinct(List<VariantResult> all, CalcSettings settings, int limit) {
        List<VariantResult> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (VariantResult v : all) {
            if (out.size() >= limit) {
                break;
            }
            if (seen.add(v.signature(Math.max(1, settings.variantMinDifference)))) {
                out.add(v);
            }
        }
        return out;
    }

    /** Как назван порядок обхода в журнале: по нему видно, из чего выросло дерево. */
    protected String strategyName(int index) {
        switch (index) {
            case 0:
                return "По убыванию расхода";
            case 1:
                return "По возрастанию расхода";
            case 2:
                return "От ближних к сети";
            case 3:
                return "От дальних к сети";
            default:
                return "Случайный порядок " + (index - 3);
        }
    }
}
