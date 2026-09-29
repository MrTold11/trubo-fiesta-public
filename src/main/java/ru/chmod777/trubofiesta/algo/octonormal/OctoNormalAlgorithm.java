package ru.chmod777.trubofiesta.algo.octonormal;

import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;
import ru.chmod777.trubofiesta.algo.octogrid.GridSetup;
import ru.chmod777.trubofiesta.algo.octogrid.OctoGrid;
import ru.chmod777.trubofiesta.algo.octogrid.OctoGridAlgorithm;
import ru.chmod777.trubofiesta.approach.Approach;
import ru.chmod777.trubofiesta.approach.Approaches;
import ru.chmod777.trubofiesta.geo.Frame;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.Diagnostic;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Та же сетка и то же жадное дерево, что у {@code OCTOGRID}, но вход в дом не ищется
 * в процессе трассировки, а назначается заранее.
 *
 * Нормализация уже посчитала для каждой точки подключения нормали к стенам её полигона и
 * точки за зоной минимального расстояния, куда труба может подойти (раздел 2.2: один финальный
 * прямой участок от ближайшей к точке границы полигона). Здесь эти точки становятся жёсткими
 * вершинами сетки: трасса приходит ровно в одну из них и только там поворачивает.
 *
 * Подходов берётся несколько, а не один:
 *
 * <ul>
 *   <li>ближайшая граница полигона бывает не одна — две стены отстоят от точки одинаково
 *       или почти одинаково, и обе законны;</li>
 *   <li>за ближайшей стеной может вплотную стоять соседний ОКС, и финальный участок не
 *       помещается в его минимальное расстояние.</li>
 * </ul>
 *
 * Вход заводится с каждого годного подхода сразу, а какой из них выгоднее, решает поле
 * стоимости: подход, от которого до сети дешевле, выигрывает сам.
 *
 * Чем это отличается от {@code OCTOGRID} по существу:
 *
 * <ul>
 *   <li>нормаль берётся сырая, без подгонки под оси сетки, поэтому поворот в точке подхода
 *       произвольный. Приложение это разрешает (раздел 2.1: угол до 90° включительно любой),
 *       но инженерной геометрии 45°/90° такой отвод не даёт;</li>
 *   <li>подход, не помещающийся в минимальные расстояния до чужих ОКС, не используется
 *       вовсе. {@code OCTOGRID} в таком месте может протиснуться: луч выхода из дома
 *       игнорирует всю зону своего здания целиком, а она в узком проходе перекрывает и
 *       зону соседа;</li>
 *   <li>поисковое пространство меньше, значит расчёт быстрее.</li>
 * </ul>
 */
@Component
public class OctoNormalAlgorithm extends OctoGridAlgorithm {

    @Override
    public String key() {
        return "OCTONORMAL";
    }

    @Override
    public String title() {
        return "Сетка в осях застройки, вход в назначенные точки подхода по нормали";
    }

    /**
     * Заводит вход с каждого годного подхода. Сначала через ближайшие стены, как требует
     * раздел 2.2; если все они перекрыты — через стены подальше, с замечанием. Перекрытые
     * подходы не используются никогда: трасса по ним была бы недопустимой, а раздел 2.5
     * разрешает не подключать точку именно тогда, когда допустимого маршрута нет.
     */
    @Override
    protected int attachPoint(GridSetup s, ConnectionPoint p, int vertex, Frame frame, List<Diagnostic> notes) {
        if (!s.context.ownBuildings.containsKey(p.id)) {
            // У точки нет своего полигона ОКС: выходить не из чего, трасса ведётся прямо к ней.
            return super.attachPoint(s, p, vertex, frame, notes);
        }
        List<Approach> free = new ArrayList<>();
        List<Approach> blocked = new ArrayList<>();
        for (Approach a : s.context.approaches.getOrDefault(p.id, Collections.emptyList())) {
            // Запасные направления по осям — это уже перебор, ему место в базовой реализации.
            if (!a.normal) {
                continue;
            }
            (a.free ? free : blocked).add(a);
        }
        if (free.isEmpty()) {
            if (!blocked.isEmpty()) {
                notes.add(Diagnostic.warning("approach_blocked",
                        "Подход к точке не помещается в минимальные расстояния: " + blocked.get(0).blockedBy,
                        p.id));
            } else {
                notes.add(Diagnostic.warning("approach_not_normal",
                        "Нормаль к стене построить не удалось, вход подобран перебором стен", p.id));
                return super.attachPoint(s, p, vertex, frame, notes);
            }
            return 0;
        }

        // Ближайшие стены — основной случай раздела 2.2. Стены подальше берём, только если
        // все ближайшие перекрыты: иначе поиск выбирал бы дальнюю стену просто потому,
        // что от неё дешевле идти к сети.
        List<Approach> use = new ArrayList<>();
        for (Approach a : free) {
            if (a.nearest) {
                use.add(a);
            }
        }
        boolean fallback = use.isEmpty();
        if (fallback) {
            use = free;
        }

        int stops = 0;
        List<Approach> used = new ArrayList<>();
        for (Approach a : use) {
            int added = attach(s, p, vertex, a);
            if (added > 0) {
                used.add(a);
                stops += added;
            }
        }
        if (stops == 0) {
            notes.add(Diagnostic.warning("approach_dead_end",
                    "Из точек подхода на сетку не выйти, вход подобран перебором стен", p.id));
            return super.attachPoint(s, p, vertex, frame, notes);
        }
        if (fallback) {
            notes.add(Diagnostic.warning("approach_not_nearest",
                    "Подходы через ближайшие стены перекрыты, вход через стену в "
                            + Math.round(used.get(0).footDistance) + " м от точки", p.id));
        }
        if (used.size() > 1) {
            notes.add(Diagnostic.info("approach_several",
                    "Точек подхода несколько (" + used.size() + "), выбор между ними делает поиск", p.id));
        }
        if (used.get(0).courtyard) {
            notes.add(Diagnostic.info("approach_courtyard",
                    "Наружу по нормали выйти нельзя, подход во внутреннем дворе", p.id));
        }
        return stops;
    }

    /**
     * Один подход на сетке: отвод по нормали до точки подхода, поворот в ней и три луча
     * из неё — прямо и вдоль фасада в обе стороны.
     *
     * @return сколько вершин получилось; ноль означает, что этот подход не годится
     */
    private int attach(GridSetup s, ConnectionPoint p, int vertex, Approach a) {
        CalcSettings settings = s.context.settings;
        OctoGrid grid = s.grid;
        Geometry ownZone = s.ownZones.get(p.id);
        // Отступ берётся по классу Ду этой сетки, а не по наименьшему диаметру нормализации:
        // труба толще отходит от стены дальше.
        double clearance = Approaches.clearance(s.context.rules, settings, s.dn) + 0.3;
        int turn = grid.exitVertex(vertex, a.direction, a.wallDistance + clearance, ownZone);
        if (turn < 0) {
            return 0;
        }
        double[] forward = a.direction;
        double[] left = {-forward[1], forward[0]};
        double[] right = {forward[1], -forward[0]};
        int stops = grid.ray(turn, forward, 0.01, settings.rayLength, null, null)
                + grid.ray(turn, left, 0.01, settings.rayLength, null, null)
                + grid.ray(turn, right, 0.01, settings.rayLength, null, null);
        // Из точки подхода некуда идти: ни один луч не дошёл до линии сетки, и сама она
        // остаётся тупиком. Такой подход не годится.
        return stops == 0 ? 0 : stops + 1;
    }
}
