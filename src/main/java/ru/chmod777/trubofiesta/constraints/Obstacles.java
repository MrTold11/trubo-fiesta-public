package ru.chmod777.trubofiesta.constraints;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.model.Restriction;
import ru.chmod777.trubofiesta.model.Segment;
import ru.chmod777.trubofiesta.rules.RestrictionRule;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Готовит препятствия. Минимальное расстояние до зданий и ширина пары труб зависят от
 * условного диаметра новой сети, поэтому наборов несколько — по одному на диаметр.
 * Наборы строятся лениво и переиспользуются всеми запусками расчёта.
 */
public class Obstacles {

    /** Тип ограничения, по правилу которого пересекается существующая теплосеть. */
    public static final String HEAT_NETWORK = "heat_network";

    private final InputData data;
    private final Rules rules;
    private final CalcSettings settings;
    private final Map<Integer, ObstacleSet> cache = new HashMap<>();

    public Obstacles(InputData data, Rules rules, CalcSettings settings) {
        this.data = data;
        this.rules = rules;
        this.settings = settings;
    }

    /** Препятствия для трубы этого диаметра: и отступ от зданий, и ширина пары зависят от Ду. */
    public synchronized ObstacleSet forDn(int dn) {
        return cache.computeIfAbsent(dn, this::build);
    }

    private ObstacleSet build(int dnClass) {
        List<Geometry> forbidden = new ArrayList<>();
        List<SpecialZone> specials = new ArrayList<>();
        double half = halfWidth(dnClass);

        for (Restriction r : data.restrictions) {
            if (r.geom == null || r.geom.isEmpty()) {
                continue;
            }
            if (r.geom.getDimension() == 2 && r.geom.getArea() < settings.minRestrictionArea) {
                continue;
            }
            if (r.futureOksBuilding && !settings.futureOksAsObstacle) {
                continue;
            }
            if (r.rule.isSpecial()) {
                double existingHalf = r.rule.existing == null ? 0 : r.rule.existing.width / 2;
                specials.add(zone(r.id, r.type, r.geom, r.rule, half, existingHalf));
                continue;
            }
            // Упрощение границ может срезать до obstacleSimplify метров,
            // поэтому закладываем этот запас в буфер: норматив должен выдерживаться.
            double simplify = Math.max(0, settings.obstacleSimplify);
            for (int i = 0; i < r.geom.getNumGeometries(); i++) {
                Geometry part = r.geom.getGeometryN(i);
                double buffer = clearance(r, part, dnClass) + half + simplify;
                forbidden.add(buffer > 0 ? part.buffer(buffer) : part);
            }
        }

        // Существующую теплосеть без врезки пересекаем специальным проходом, таблица 5.1.
        RestrictionRule heat = rules.restrictions.get(HEAT_NETWORK);
        double heatRadius = 0;
        if (heat != null) {
            for (Segment s : data.segments) {
                if (s.pts == null || s.pts.size() < 2) {
                    continue;
                }
                specials.add(zone(s.id, HEAT_NETWORK, Geo.line(s.pts), heat, half, rules.byDn(s.dn).width / 2));
            }
            heatRadius = heat.specialBuffer + half + 0.5;
        }

        Geometry union = forbidden.isEmpty() ? Geo.emptyPolygon() : UnaryUnionOp.union(forbidden);
        if (settings.obstacleSimplify > 0 && !union.isEmpty()) {
            Geometry simplified = org.locationtech.jts.simplify.TopologyPreservingSimplifier
                    .simplify(union, settings.obstacleSimplify);
            if (simplified != null && !simplified.isEmpty() && simplified.isValid()) {
                union = simplified;
            }
        }
        return new ObstacleSet(dnClass, union, specials, heatRadius);
    }

    /**
     * Зона специального прохода. Отступ сбоку считается между наружными границами габаритов:
     * новой пары труб и существующей коммуникации, таблица 4.3.
     */
    private SpecialZone zone(String id, String type, Geometry object, RestrictionRule rule, double half,
                             double existingHalf) {
        SpecialZone z = new SpecialZone();
        z.restrictionId = id;
        z.type = type;
        z.object = object;
        z.zone = object.buffer(rule.specialBuffer + half + existingHalf);
        double side = rule.clearance == null ? 0 : rule.clearance;
        z.band = object.buffer(side + half + existingHalf);
        z.existingHalf = existingHalf;
        z.rule = rule;
        z.collectAxis();
        return z;
    }

    /**
     * Минимальное расстояние до части объекта. Полигон здания меньше smallObjectArea — не здание,
     * а малое сооружение: опора, приямок, выступ. Отступ 5 метров от него перекрыл бы проходы
     * вдоль фасадов, поэтому его обходим с отступом как у прочих запретных объектов.
     */
    public double clearance(Restriction r, Geometry part, int dn) {
        if (part.getDimension() == 2 && part.getArea() < settings.smallObjectArea
                && r.rule.clearanceByDn != null && !r.rule.clearanceByDn.isEmpty()) {
            return settings.smallObjectClearance;
        }
        return r.rule.clearanceFor(dn);
    }

    /** Половина расчётной ширины пары труб: от неё отсчитываются минимальные расстояния. */
    public double halfWidth(int dn) {
        return settings.clearanceFromGabarit ? rules.byDn(dn).width / 2 : 0;
    }
}
