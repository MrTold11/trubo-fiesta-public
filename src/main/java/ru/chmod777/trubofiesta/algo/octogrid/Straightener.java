package ru.chmod777.trubofiesta.algo.octogrid;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.algo.GeometryRefiner;
import ru.chmod777.trubofiesta.algo.RefineRequest;
import ru.chmod777.trubofiesta.approach.Approaches;
import ru.chmod777.trubofiesta.calc.NodeAngles;
import ru.chmod777.trubofiesta.constraints.Clearance;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Доводка готовой трассы. Сетка иногда даёт горб: трасса проскакивает нужную линию
 * и возвращается двумя поворотами там, где хватило бы одного, или делает ступеньку при
 * переходе с диагонали на линию выхода из дома. Два-три соседних поворота заменяются одним
 * стандартным на 45° или 90°, если это не нарушает правил. Направления остальных отрезков
 * не меняются. Исключение — конец в камере на ветвлении: в камеру можно прийти с любого
 * стандартного направления, поэтому последний отрезок перед ней может сменить направление.
 *
 * Вторая доводка — срезка: несколько вершин подряд заменяются одним прямым отрезком.
 * Удорожания за угол нет (раздел 2.1), но инженер чертит отводами 45° и 90°, поэтому срезка
 * принимается по трём правилам: все углы после неё стандартные — берётся всегда; она убирает
 * дефект (заглушку, зигзаг, горб) — берётся всегда; иначе только при выигрыше не меньше
 * {@code shortcutMinGain}. При {@code standardAnglesOnly} нестандартных углов не бывает вовсе.
 *
 * За вид трассы сервис готов немного доплатить: замена двух-трёх поворотов одним
 * стандартным допускается, даже если трасса чуть длиннее, в пределах {@code beautyAllowance}
 * на одну ломаную.
 *
 * Доводка одна для всех вариантов: варианты различаются деревом подключения, а не
 * геометрией доводки.
 */
public final class Straightener implements GeometryRefiner {

    private final Rules rules;
    private final CalcSettings settings;
    private final Clearance clearance;
    private final ObstacleSet obstacles;
    /** Только углы 45° и 90°, и внутри участка, и в узлах. */
    private final boolean standardOnly;
    /** Наименьший выигрыш одной срезки в рублях по формуле показателя. */
    private final double minGainRubles;

    /** Сколько срезок сделано и сколько они сэкономили в рублях по формуле показателя. */
    private int shortcuts;
    private double shortcutGainRubles;
    /** Звено у вершины короче этого — стык луча с узлом сетки, а не поворот трассы, м. */
    private static final double MICRO_STUB = 1.0;
    /**
     * Отрезок между поворотами короче этого — дефект геометрии: заглушка, зигзаг или горб.
     * Такой фрагмент можно срезать и под нестандартным углом: два лишних излома хуже одного
     * произвольного.
     */
    private static final double DEFECT_LEG = 8.0;
    /** Сколько рублей по формуле показателя уже доплачено за вид текущей ломаной. */
    private double spent;

    @Override
    public int shortcuts() {
        return shortcuts;
    }

    @Override
    public double shortcutGainRubles() {
        return shortcutGainRubles;
    }

    public Straightener(Rules rules, CalcSettings settings, Clearance clearance, ObstacleSet obstacles) {
        this.rules = rules;
        this.settings = settings;
        this.clearance = clearance;
        this.obstacles = obstacles;
        this.standardOnly = settings.standardAnglesOnly;
        this.minGainRubles = settings.shortcutMinGain;
    }

    /** Повороты, которыми можно прийти в камеру, градусы. */
    private static final double[] CHAMBER_TURNS = {45, -45, 90, -90};
    /** Ступенька шире этого — уже не ступенька, а два самостоятельных поворота, м. */
    private static final double CHAMFER_MAX = 15.0;

    @Override
    public List<Coordinate> refine(RefineRequest c, List<Coordinate> pts) {
        spent = 0;
        List<Coordinate> current = kinks(c, new ArrayList<>(pts));
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 30) {
            changed = false;
            search:
            for (int removed = 3; removed >= 1; removed--) {
                for (int i = 0; i + removed + 1 < current.size(); i++) {
                    int j = i + removed + 1;
                    // Одну вершину есть смысл менять только там, где направление звена свободно:
                    // в начале от камеры или врезки, в конце перед камерой.
                    boolean freeStart = i == 0 && (c.startFree || c.tieIn != null);
                    boolean freeEnd = j == current.size() - 1 && c.endFree;
                    if (removed == 1 && !freeStart && !freeEnd) {
                        continue;
                    }
                    for (List<Coordinate> candidate : replacements(current, i, j, c)) {
                        if (valid(c, candidate, current, i, i + 2, true)) {
                            spent += Math.max(0, money(c, candidate) - money(c, current));
                            current = candidate;
                            changed = true;
                            break search;
                        }
                    }
                }
            }
        }
        return shortcuts(c, chamfers(c, current));
    }

    /**
     * Ступенька: два поворота на 90° в разные стороны через короткий отрезок L — трасса
     * переходит на параллельную прямую. Инженер разводит такое двумя отводами по 45° с
     * диагональю L·√2: короче ступеньки на L·(2 − √2) и без двух прямых углов. Меняется
     * одна вершина: отрезок перед ступенькой укорачивается на L и диагональ приходит прямо
     * во второй поворот, либо наоборот — диагональ уходит из первого поворота, а отрезок
     * после ступеньки укорачивается. Оба варианта проверяются по всем правилам.
     */
    private List<Coordinate> chamfers(RefineRequest c, List<Coordinate> pts) {
        List<Coordinate> current = pts;
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 20) {
            changed = false;
            for (int i = 1; i + 2 < current.size() && !changed; i++) {
                Coordinate a = current.get(i - 1);
                Coordinate b = current.get(i);
                Coordinate d = current.get(i + 1);
                Coordinate e = current.get(i + 2);
                double tolerance = rules.turn.toleranceDeg;
                if (Math.abs(Geo.turnDegrees(a, b, d) - 90) > tolerance
                        || Math.abs(Geo.turnDegrees(b, d, e) - 90) > tolerance) {
                    continue;
                }
                double jog = b.distance(d);
                double[] u = unit(a, b);
                double[] w = unit(d, e);
                if (jog > CHAMFER_MAX || u == null || w == null
                        || u[0] * w[0] + u[1] * w[1] < Math.cos(Math.toRadians(tolerance))) {
                    // Повороты в одну сторону — это разворот, а не ступенька.
                    continue;
                }
                if (a.distance(b) - jog >= settings.minStraightRun) {
                    List<Coordinate> candidate = new ArrayList<>(current.subList(0, i));
                    candidate.add(new Coordinate(b.x - u[0] * jog, b.y - u[1] * jog));
                    candidate.addAll(current.subList(i + 1, current.size()));
                    if (valid(c, candidate, current, i - 1, i + 1, true)) {
                        current = candidate;
                        changed = true;
                        continue;
                    }
                }
                if (d.distance(e) - jog >= settings.minStraightRun) {
                    List<Coordinate> candidate = new ArrayList<>(current.subList(0, i + 1));
                    candidate.add(new Coordinate(d.x + w[0] * jog, d.y + w[1] * jog));
                    candidate.addAll(current.subList(i + 2, current.size()));
                    if (valid(c, candidate, current, i, i + 2, true)) {
                        current = candidate;
                        changed = true;
                    }
                }
            }
        }
        return current;
    }

    /**
     * Излом меньше допуска стандартного угла — не поворот, а стык луча с линией сетки,
     * разошедшихся на доли градуса. Такая вершина убирается, если прямая проходит все правила.
     *
     * То же со звеном короче метра у вершины: точка присоединения в паре сантиметров от линии
     * сетки даёт луч, который первым же звеном прилипает к узлу, и получается звено 0,2 м с
     * изломом в 17°. Это погрешность стыка, а не поворот трассы. Ввод в дом по нормали не
     * трогается.
     */
    private List<Coordinate> kinks(RefineRequest c, List<Coordinate> pts) {
        List<Coordinate> current = pts;
        for (int i = 1; i < current.size() - 1; ) {
            double turn = Geo.turnDegrees(current.get(i - 1), current.get(i), current.get(i + 1));
            boolean tiny = turn <= rules.turn.toleranceDeg && turn > 1e-6;
            boolean lead = c.ownBuilding != null && i + 1 == current.size() - 1;
            boolean stub = turn <= rules.turn.maxDeg + rules.turn.toleranceDeg
                    && (current.get(i - 1).distance(current.get(i)) < MICRO_STUB
                    || (!lead && current.get(i).distance(current.get(i + 1)) < MICRO_STUB));
            if (tiny || stub) {
                List<Coordinate> candidate = new ArrayList<>(current.subList(0, i));
                candidate.addAll(current.subList(i + 1, current.size()));
                if (valid(c, candidate, current, i - 1, i, false)) {
                    current = candidate;
                    continue;
                }
            }
            i++;
        }
        return current;
    }

    /**
     * Срезки. Каждый раз берётся самая выгодная из допустимых. Срезка со стандартными углами
     * или убирающая дефект берётся при любом выигрыше, срезка с произвольным углом — только
     * при выигрыше не меньше порога; среди допустимых стандартная предпочтительнее выгодной.
     * Последний отрезок к дому не трогается: это отвод по нормали к стене.
     */
    private List<Coordinate> shortcuts(RefineRequest c, List<Coordinate> pts) {
        List<Coordinate> current = pts;
        int guard = 0;
        while (guard++ < 30) {
            List<Coordinate> best = null;
            double bestGain = 0;
            boolean bestClean = false;
            int last = current.size() - 1;
            for (int i = 0; i + 2 <= last; i++) {
                for (int j = i + 2; j <= last; j++) {
                    List<Coordinate> candidate = new ArrayList<>(current.subList(0, i + 1));
                    candidate.addAll(current.subList(j, current.size()));
                    // Отвод к дому остаётся по нормали: срезка до самой точки допустима, только
                    // если ввод отклонится не больше, чем допускает выход по нормали.
                    if (j == last && c.ownBuilding != null && !keepsNormal(current, candidate)) {
                        continue;
                    }
                    if (!valid(c, candidate, current, i, i + 1, false)) {
                        continue;
                    }
                    double gain = gainRubles(c, current, candidate);
                    if (gain < 1.0) {
                        continue;
                    }
                    boolean clean = standardAngles(c, candidate) || defective(c, current, i, j);
                    if (!clean && (minGainRubles <= 0 || gain < minGainRubles)) {
                        continue;
                    }
                    if (best == null || (clean && !bestClean) || (clean == bestClean && gain > bestGain)) {
                        best = candidate;
                        bestGain = gain;
                        bestClean = clean;
                    }
                }
            }
            if (best == null) {
                break;
            }
            if (DEBUG) {
                System.err.printf(java.util.Locale.US, "shortcut %s: gain %.0f clean %s vertices %d -> %d length %.1f -> %.1f%n",
                        c.id, bestGain, bestClean, current.size(), best.size(), Geo.length(current), Geo.length(best));
            }
            shortcuts++;
            shortcutGainRubles += bestGain;
            current = best;
        }
        return current;
    }

    /** Отладочная печать принятых срезок в stderr: переменная окружения TRUBO_ALIGN_DEBUG. */
    private static final boolean DEBUG = System.getenv("TRUBO_ALIGN_DEBUG") != null;

    /** Все углы ломаной стандартные: повороты внутри неё и углы в узлах на концах. */
    private boolean standardAngles(RefineRequest c, List<Coordinate> pts) {
        for (int n = 1; n < pts.size() - 1; n++) {
            if (!rules.turn.standard(Geo.turnDegrees(pts.get(n - 1), pts.get(n), pts.get(n + 1)))) {
                return false;
            }
        }
        return nodeOdd(c, pts) == 0;
    }

    /**
     * Срезаемый фрагмент old[i..j] — дефект: в нём есть звено короче предельного. Заглушка у
     * камеры, ступенька, зигзаг и горб выглядят именно так. Ввод в дом по нормали не считается.
     * Срезка дефекта под произвольным углом — местная правка: новый отрезок не длиннее
     * {@link #DEFECT_SPAN}, иначе ради ступеньки посреди длинного ствола весь ствол лёг бы
     * наискось, а инженер такую ступеньку убрал бы сдвигом трассы.
     */
    private boolean defective(RefineRequest c, List<Coordinate> old, int i, int j) {
        if (old.get(i).distance(old.get(j)) > DEFECT_SPAN) {
            return false;
        }
        for (int n = i; n < j; n++) {
            if (c.ownBuilding != null && n + 1 == old.size() - 1) {
                continue;
            }
            if (old.get(n).distance(old.get(n + 1)) < DEFECT_LEG) {
                return true;
            }
        }
        return false;
    }

    /** Самый длинный отрезок, которым можно срезать дефект под произвольным углом, м. */
    private static final double DEFECT_SPAN = 30.0;

    /** Последнее звено после срезки отклоняется от нормали к стене не больше допуска выхода. */
    private boolean keepsNormal(List<Coordinate> old, List<Coordinate> candidate) {
        double[] was = NodeAngles.direction(old.get(old.size() - 2), old.get(old.size() - 1));
        double[] now = NodeAngles.direction(candidate.get(candidate.size() - 2), candidate.get(candidate.size() - 1));
        double dot = Math.max(-1, Math.min(1, was[0] * now[0] + was[1] * now[1]));
        return Math.toDegrees(Math.acos(dot)) <= settings.exitSnapDeg;
    }

    /** Выигрыш срезки в рублях: стоимость трубы и её длина по формуле показателя. */
    private double gainRubles(RefineRequest c, List<Coordinate> old, List<Coordinate> candidate) {
        return money(c, old) - money(c, candidate);
    }

    /** Стоимость ломаной вместе с длиной, переведённой в рубли по показателю S. */
    private double money(RefineRequest c, List<Coordinate> pts) {
        double length = Geo.length(pts);
        return rules.segmentCost(c.dn, length, 1.0) + length * rules.scorePerMeter() / rules.scorePerRuble();
    }

    /**
     * Нестандартные углы в узлах на концах ломаной: её первый отрезок против участка,
     * пришедшего в начальный узел, и первые отрезки участков, уходящих из конечного узла,
     * против её последнего отрезка. Стоимости у них нет, но при {@code standardAnglesOnly}
     * они не допускаются; отвод к дому по нормали здесь не считается.
     */
    private int nodeOdd(RefineRequest c, List<Coordinate> pts) {
        int odd = 0;
        Coordinate start = pts.get(0);
        Coordinate end = pts.get(pts.size() - 1);
        double[] first = NodeAngles.direction(pts.get(0), pts.get(1));
        double[] lastDir = NodeAngles.direction(pts.get(pts.size() - 2), end);
        for (List<Coordinate> other : c.others) {
            if (other.size() < 2) {
                continue;
            }
            Coordinate oStart = other.get(0);
            Coordinate oEnd = other.get(other.size() - 1);
            double tolerance = rules.turn.chamberToleranceDeg;
            if (oEnd.distance(start) < 1e-6) {
                // Родитель приходит в начальный узел.
                if (!NodeAngles.standardBetween(NodeAngles.direction(other.get(other.size() - 2), oEnd), first,
                        rules, tolerance)) {
                    odd++;
                }
            } else if (oStart.distance(start) < 1e-6 && c.tieIn != null) {
                // Соседняя ветка из того же узла: опорное направление у самой толстой, здесь считаем обе.
                if (!NodeAngles.standardBetween(NodeAngles.direction(oStart, other.get(1)), first, rules, tolerance)) {
                    odd++;
                }
            }
            if (c.ownBuilding == null && oStart.distance(end) < 1e-6) {
                if (!NodeAngles.standardBetween(lastDir, NodeAngles.direction(oStart, other.get(1)),
                        rules, tolerance)) {
                    odd++;
                }
            }
        }
        return odd;
    }

    /**
     * Две ветки, уходящие из одного узла почти в одну сторону, физически лягут в одну
     * траншею: новые участки не должны накладываться друг на друга.
     */
    private boolean branchesOverlap(RefineRequest c, List<Coordinate> pts) {
        if (settings.minBranchAngle <= 0 || pts.size() < 2) {
            return false;
        }
        Coordinate start = pts.get(0);
        Coordinate end = pts.get(pts.size() - 1);
        double[] ourStart = NodeAngles.direction(start, pts.get(1));
        double[] ourEnd = NodeAngles.direction(end, pts.get(pts.size() - 2));
        for (List<Coordinate> other : c.others) {
            if (other.size() < 2) {
                continue;
            }
            for (int side = 0; side < 2; side++) {
                Coordinate node = side == 0 ? start : end;
                double[] ours = side == 0 ? ourStart : ourEnd;
                double[] theirs = null;
                if (other.get(0).distance(node) < 1e-6) {
                    theirs = NodeAngles.direction(other.get(0), other.get(1));
                } else if (other.get(other.size() - 1).distance(node) < 1e-6) {
                    theirs = NodeAngles.direction(other.get(other.size() - 1), other.get(other.size() - 2));
                }
                if (theirs != null && NodeAngles.rayAngle(ours, theirs) < settings.minBranchAngle) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Варианты замены вершин между a = pts[i] и d = pts[j] одной вершиной X. Обычно X —
     * пересечение прямых a→b и c→d. Если d — камера, последний отрезок может прийти в неё
     * под другим стандартным углом; то же для a в начале ломаной.
     */
    private List<List<Coordinate>> replacements(List<Coordinate> pts, int i, int j, RefineRequest c) {
        List<List<Coordinate>> out = new ArrayList<>();
        double[] u = unit(pts.get(i), pts.get(i + 1));
        double[] w = unit(pts.get(j - 1), pts.get(j));
        if (u == null || w == null) {
            return out;
        }
        add(out, pts, i, j, u, w);
        if (c.endFree && j == pts.size() - 1) {
            for (double turn : CHAMBER_TURNS) {
                add(out, pts, i, j, u, rotate(u, turn));
            }
        }
        // В начале ломаной направление свободно и в камере, и во врезке: из врезки труба
        // уходит в любую сторону не положе 45° к существующей трубе, это проверяет valid.
        if ((c.startFree || c.tieIn != null) && i == 0) {
            for (double turn : CHAMBER_TURNS) {
                add(out, pts, i, j, rotate(w, turn), w);
            }
        }
        return out;
    }

    /** Ломаная a → X → d, где a→X идёт по направлению u, а X→d — по w. */
    private void add(List<List<Coordinate>> out, List<Coordinate> pts, int i, int j, double[] u, double[] w) {
        Coordinate a = pts.get(i);
        Coordinate d = pts.get(j);
        double den = u[0] * w[1] - u[1] * w[0];
        if (Math.abs(den) < 1e-9) {
            return;
        }
        double dx = d.x - a.x;
        double dy = d.y - a.y;
        double s = (dx * w[1] - dy * w[0]) / den;
        double t = (u[0] * dy - u[1] * dx) / den;
        if (s < 0.01 || t < 0.01) {
            return;
        }
        double turn = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, u[0] * w[0] + u[1] * w[1]))));
        if (turn <= rules.turn.toleranceDeg || turn > rules.turn.maxDeg + rules.turn.toleranceDeg
                || !rules.turn.standard(turn)) {
            return;
        }
        double minRun = settings.minStraightRun;
        if ((i > 0 && s < minRun) || (j < pts.size() - 1 && t < minRun)) {
            return;
        }
        Coordinate x = new Coordinate(a.x + u[0] * s, a.y + u[1] * s);
        if (j == i + 2 && x.distance(pts.get(i + 1)) < 0.01) {
            // Та же вершина: замена одной вершины имеет смысл только с другим направлением звена.
            return;
        }
        List<Coordinate> candidate = new ArrayList<>(pts.subList(0, i + 1));
        candidate.add(x);
        candidate.addAll(pts.subList(j, pts.size()));
        out.add(candidate);
    }

    private static double[] unit(Coordinate from, Coordinate to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double len = Math.hypot(dx, dy);
        return len < 1e-6 ? null : new double[]{dx / len, dy / len};
    }

    private static double[] rotate(double[] v, double deg) {
        double r = Math.toRadians(deg);
        return new double[]{v[0] * Math.cos(r) - v[1] * Math.sin(r), v[0] * Math.sin(r) + v[1] * Math.cos(r)};
    }

    /**
     * Проверка замены: новые отрезки candidate[i..k] против всех правил. Замена поворотов
     * (standardReplacement) может удлинить трассу только в пределах доплаты за вид
     * {@code beautyAllowance} на всю ломаную: удорожания за поворот нет, но два лишних отвода
     * инженер не начертит, если их можно убрать за несколько метров трубы. Для срезки
     * выигрыш считается отдельно.
     */
    private boolean valid(RefineRequest c, List<Coordinate> candidate, List<Coordinate> old, int i, int k,
                          boolean standardReplacement) {
        if (standardReplacement) {
            double extra = money(c, candidate) - money(c, old);
            if (extra > 1e-6 && spent + extra > Math.max(0, settings.beautyAllowance)) {
                return false;
            }
        }
        boolean last = k == candidate.size() - 1;
        for (int n = i + 1; n < k; n++) {
            if (obstacles.blocked(candidate.get(n))) {
                return false;
            }
        }
        // Отвод к дому идёт по нормали от стены. Точку поворота можно придвинуть к дому,
        // только если труба вдоль него всё равно проходит на нормативном расстоянии.
        if (last && c.ownBuilding != null && k >= 2) {
            Coordinate x = candidate.get(k - 1);
            Coordinate d = candidate.get(k);
            if (x.distance(d) < old.get(old.size() - 2).distance(d) - 0.01
                    && c.ownBuilding.distance(Geo.point(x)) < Approaches.clearance(rules, settings, c.dn)) {
                return false;
            }
        }
        for (int n = i; n < k; n++) {
            boolean lead = last && c.ownBuilding != null && n == k - 1;
            if (lead) {
                continue;
            }
            if (obstacles.edge(candidate.get(n), candidate.get(n + 1), null, n == 0 ? c.tieIn : null).blocked) {
                return false;
            }
        }
        if (i == 0 && c.tieIn != null && !c.pipes.isEmpty()) {
            double[] first = NodeAngles.direction(candidate.get(0), candidate.get(1));
            for (double[] pipe : c.pipes) {
                double along = Math.toDegrees(Math.acos(Math.min(1, Math.abs(first[0] * pipe[0] + first[1] * pipe[1]))));
                if (along < settings.tieInMinAngle - rules.turn.toleranceDeg) {
                    return false;
                }
            }
        }
        for (int n = 1; n < candidate.size() - 1; n++) {
            double turn = Geo.turnDegrees(candidate.get(n - 1), candidate.get(n), candidate.get(n + 1));
            if (turn > rules.turn.maxDeg + rules.turn.toleranceDeg) {
                return false;
            }
            if (standardOnly && !rules.turn.standard(turn)) {
                return false;
            }
            // Спецпроход — один прямой участок: поворот в зоне дороги или рядом с пересечением
            // газопровода, кабеля, существующей теплосети недопустим.
            Coordinate at = candidate.get(n);
            if (turn > rules.turn.toleranceDeg && (obstacles.inPolygonZone(at)
                    || obstacles.crossesLineNear(candidate.get(n - 1), at, at, 0.5)
                    || obstacles.crossesLineNear(at, candidate.get(n + 1), at, 0.5))) {
                return false;
            }
        }
        if (standardOnly && nodeOdd(c, candidate) > 0) {
            return false;
        }
        if (branchesOverlap(c, candidate)) {
            return false;
        }
        if (!standardReplacement && jog(candidate)) {
            return false;
        }
        if (clearance != null) {
            if (clearance.violation(c.id, candidate, c.dn, c.ownBuilding, c.ownId) != null) {
                return false;
            }
            List<List<Coordinate>> self = new ArrayList<>();
            self.add(candidate);
            if (clearance.sideViolation(c.id, candidate, c.dn, clearance.crossings(self), c.tieIn) != null) {
                return false;
            }
        }
        List<LineString> fresh = new ArrayList<>();
        for (int n = i; n < k; n++) {
            fresh.add(Geo.line(Arrays.asList(candidate.get(n), candidate.get(n + 1))));
        }
        // Сама с собой ломаная тоже не пересекается: соседние отрезки касаются только в вершинах.
        for (int n = 1; n < candidate.size(); n++) {
            if (n >= i && n <= k + 1) {
                continue;
            }
            LineString edge = Geo.line(Arrays.asList(candidate.get(n - 1), candidate.get(n)));
            for (LineString f : fresh) {
                if (edge.intersects(f)) {
                    return false;
                }
            }
        }
        Coordinate start = candidate.get(0);
        Coordinate end = candidate.get(candidate.size() - 1);
        for (List<Coordinate> other : c.others) {
            LineString line = Geo.line(other);
            for (LineString edge : fresh) {
                if (!edge.getEnvelopeInternal().intersects(line.getEnvelopeInternal()) || !edge.intersects(line)) {
                    continue;
                }
                for (Coordinate hit : edge.intersection(line).getCoordinates()) {
                    boolean shared = (hit.distance(start) < 1e-6 || hit.distance(end) < 1e-6)
                            && (hit.distance(other.get(0)) < 1e-6 || hit.distance(other.get(other.size() - 1)) < 1e-6);
                    if (!shared) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Ступенька: два поворота с отрезком короче минимального между ними. */
    private boolean jog(List<Coordinate> pts) {
        int previous = -1;
        for (int n = 1; n < pts.size() - 1; n++) {
            if (Geo.turnDegrees(pts.get(n - 1), pts.get(n), pts.get(n + 1)) > rules.turn.toleranceDeg) {
                if (previous >= 0 && Geo.length(pts.subList(previous, n + 1)) < settings.minStraightRun) {
                    return true;
                }
                previous = n;
            }
        }
        return false;
    }
}
