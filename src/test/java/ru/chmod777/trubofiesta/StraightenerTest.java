package ru.chmod777.trubofiesta;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.chmod777.trubofiesta.algo.RefineRequest;
import ru.chmod777.trubofiesta.algo.octogrid.Straightener;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.rules.RulesService;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Доводка ломаной на чистом поле: без препятствий видно, что именно она меняет. */
class StraightenerTest {

    private final Rules rules = new RulesService().get();

    StraightenerTest() throws IOException {
    }

    private Straightener straightener(CalcSettings settings) {
        ObstacleSet empty = new ObstacleSet(100, Geo.emptyPolygon(), new ArrayList<>(), 0);
        return new Straightener(rules, settings, null, empty);
    }

    private static List<Coordinate> line(double... xy) {
        List<Coordinate> out = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            out.add(new Coordinate(xy[i], xy[i + 1]));
        }
        return out;
    }

    /**
     * Запрос доводки, у которого ломаная кончается вводом в дом: последний отрезок — отвод по
     * нормали, и срезка до самой точки запрещена. Иначе на пустом поле любая ломаная
     * схлопнулась бы в прямую, и замену ступеньки было бы не увидеть. Сам дом стоит далеко,
     * чтобы не мешать отступами.
     */
    private static RefineRequest toHouse(String id) {
        RefineRequest c = new RefineRequest();
        c.id = id;
        c.dn = 100;
        c.ownBuilding = Geo.F.createPolygon(new Coordinate[]{
                new Coordinate(1000, 1000), new Coordinate(1010, 1000), new Coordinate(1010, 1010),
                new Coordinate(1000, 1010), new Coordinate(1000, 1000)});
        return c;
    }

    @Test
    void ступенька_становится_двумя_отводами_по_45() {
        CalcSettings settings = new CalcSettings();
        settings.standardAnglesOnly = true;
        // Срезки с произвольным углом выключены; здесь смотрим только на замену ступеньки.
        settings.shortcutMinGain = 1e12;
        RefineRequest c = toHouse("z");
        // Ступенька: 20 м на восток, 10 м на север, 20 м на восток.
        List<Coordinate> out = straightener(settings).refine(c, line(0, 0, 20, 0, 20, 10, 40, 10));
        assertEquals(4, out.size(), "одна вершина заменяется: " + out);
        List<Double> turns = new ArrayList<>();
        for (int i = 1; i < out.size() - 1; i++) {
            turns.add((double) Math.round(Geo.turnDegrees(out.get(i - 1), out.get(i), out.get(i + 1))));
        }
        assertEquals(Arrays.asList(45.0, 45.0), turns, "два отвода по 45°: " + out);
        assertEquals(20 - 10 + 10 * Math.sqrt(2) + 20, Geo.length(out), 1e-6, "отрезок перед ступенькой короче на L, диагональ L·√2");
    }

    @Test
    void разворот_ступенькой_не_считается() {
        CalcSettings settings = new CalcSettings();
        settings.standardAnglesOnly = true;
        settings.shortcutMinGain = 1e12;
        RefineRequest c = toHouse("u");
        // Два поворота в одну сторону: трасса проходит мимо и возвращается. Диагональю не заменить.
        List<Coordinate> pts = line(0, 0, 20, 0, 20, 10, 0, 10);
        List<Coordinate> out = straightener(settings).refine(c, pts);
        assertEquals(pts, out, "разворот остаётся как есть");
    }

    @Test
    void ступенька_шире_предела_остаётся() {
        CalcSettings settings = new CalcSettings();
        settings.standardAnglesOnly = true;
        settings.shortcutMinGain = 1e12;
        RefineRequest c = toHouse("wide");
        List<Coordinate> pts = line(0, 0, 30, 0, 30, 20, 60, 20);
        List<Coordinate> out = straightener(settings).refine(c, pts);
        assertTrue(out.size() == 4 && Geo.turnDegrees(out.get(0), out.get(1), out.get(2)) > 89,
                "смещение 20 м — два поворота, а не ступенька: " + out);
    }

    /**
     * Точка присоединения в паре сантиметров от линии сетки: луч прилипает к узлу первым же
     * звеном, и получается звено 0,15 м с изломом в 17°. Это стык, а не поворот, и доводка
     * его убирает при любой политике срезок.
     */
    @Test
    void звено_короче_метра_у_узла_убирается() {
        CalcSettings settings = new CalcSettings();
        settings.standardAnglesOnly = true;
        settings.shortcutMinGain = 1e12;
        RefineRequest c = toHouse("stub");
        List<Coordinate> out = straightener(settings).refine(c, line(0, 0, 0.15, 0.046, 23, 0.046, 23, 20));
        assertEquals(3, out.size(), "заглушка 0,15 м уходит, остальные вершины на месте: " + out);
        assertEquals(new Coordinate(0, 0), out.get(0));
        assertEquals(new Coordinate(23, 0.046), out.get(1));
    }

    /**
     * Срезка с нестандартным углом: при выигрыше меньше порога она не делается, а если она
     * убирает дефект (звено короче предельного между поворотами) — делается при любом выигрыше.
     */
    @Test
    void срезка_под_произвольным_углом_только_ради_дефекта_или_большого_выигрыша() {
        CalcSettings settings = new CalcSettings();
        settings.shortcutMinGain = 1e12;
        // Без доплаты за вид: иначе зигзаг заменился бы прямым углом ещё до срезок.
        settings.beautyAllowance = 0;
        // Два поворота через 20 м: не дефект, срезка дала бы угол 26°, выигрыш меньше порога.
        List<Coordinate> kept = straightener(settings).refine(toHouse("keep"), line(0, 0, 20, 0, 40, 20, 40, 60));
        assertEquals(4, kept.size(), "без дефекта срезки под произвольным углом нет: " + kept);
        // Те же повороты через 5 м: зигзаг, срезается несмотря на порог.
        List<Coordinate> cut = straightener(settings).refine(toHouse("cut"), line(0, 0, 20, 0, 25, 5, 25, 60));
        assertEquals(3, cut.size(), "зигзаг через 5 м срезается: " + cut);
    }
}
