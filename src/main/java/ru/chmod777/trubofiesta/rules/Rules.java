package ru.chmod777.trubofiesta.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Всё содержимое rules.yaml. Цифр технического приложения в коде нет, они только здесь. */
public class Rules {

    public List<Diameter> diameters = new ArrayList<>();
    public Map<String, RestrictionRule> restrictions = new LinkedHashMap<>();
    public List<ChamberCost> chamberCost = new ArrayList<>();
    /** Врезка в существующую камеру: за каждый новый участок, кончающийся в ней, раздел 3.2. */
    public double existingChamberTieInCost;
    public Penalty penalty = new Penalty();
    public Ranking ranking = new Ranking();
    public DepthRules depth = new DepthRules();
    public TieInRules tieIn = new TieInRules();
    public TurnRules turn = new TurnRules();
    /** Допуск округления расстояний в большую сторону, м. */
    public double roundingTolerance = 0.01;

    /** Минимальный диаметр, пропускной способности которого хватает на такой расход. */
    public Diameter byFlow(double flowTph) {
        for (Diameter d : diameters) {
            if (d.capacity >= flowTph) {
                return d;
            }
        }
        return diameters.get(diameters.size() - 1);
    }

    /**
     * Минимальный диаметр, который тянет расход и укладывается в предельную длину пути,
     * раздел 2.3 и ответ 1. Поднимаем на столько ступеней, сколько нужно, но только до
     * первого подходящего: произвольное завышение запрещено.
     */
    public Diameter byFlowAndLength(double flowTph, double pathLength) {
        for (Diameter d : diameters) {
            if (d.capacity >= flowTph && d.maxLength >= pathLength - roundingTolerance) {
                return d;
            }
        }
        return diameters.get(diameters.size() - 1);
    }

    public Diameter byDn(int dn) {
        for (Diameter d : diameters) {
            if (d.dn == dn) {
                return d;
            }
        }
        return byFlow(0);
    }

    /** Следующий диаметр по возрастанию; для самого большого — он сам. */
    public Diameter next(int dn) {
        for (int i = 0; i < diameters.size(); i++) {
            if (diameters.get(i).dn == dn) {
                return diameters.get(Math.min(i + 1, diameters.size() - 1));
            }
        }
        return byDn(dn);
    }

    public RestrictionRule rule(String type) {
        RestrictionRule r = restrictions.get(type);
        return r != null ? r : restrictions.get("unknown");
    }

    /**
     * Стоимость участка новой сети, раздел 6: длина, ставка по диаметру и коэффициент
     * специального прохода. Удорожания за угол поворота нет (раздел 2.1). Формула одна
     * на все пути расчёта: автоматический подбор и загруженная схема должны давать на
     * одинаковой геометрии одинаковую цену.
     */
    public double segmentCost(int dn, double length, double k) {
        return length * byDn(dn).newCost * k;
    }

    /** Сколько поворотов ломаной выполнены не на 45° и не на 90°: показатель инженерности. */
    public int countNonStandardTurns(java.util.List<org.locationtech.jts.geom.Coordinate> pts) {
        int count = 0;
        for (int i = 1; i < pts.size() - 1; i++) {
            if (!turn.standard(ru.chmod777.trubofiesta.geo.Geo.turnDegrees(
                    pts.get(i - 1), pts.get(i), pts.get(i + 1)))) {
                count++;
            }
        }
        return count;
    }

    /** Сколько всего поворотов у ломаной. */
    public int countTurns(java.util.List<org.locationtech.jts.geom.Coordinate> pts) {
        int count = 0;
        for (int i = 1; i < pts.size() - 1; i++) {
            if (ru.chmod777.trubofiesta.geo.Geo.turnDegrees(pts.get(i - 1), pts.get(i), pts.get(i + 1))
                    > turn.toleranceDeg) {
                count++;
            }
        }
        return count;
    }

    /** Стоимость новой камеры по наибольшему условному диаметру примыкающих участков. */
    public double chamberCost(int maxDn) {
        for (ChamberCost c : chamberCost) {
            if (maxDn <= c.maxDn) {
                return c.cost;
            }
        }
        return chamberCost.get(chamberCost.size() - 1).cost;
    }

    /** Штраф за одну неподключённую точку подключения, раздел 6. */
    public double penalty(double flowTph) {
        return penalty.fixed + penalty.perTph * flowTph;
    }

    /** Показатель ранжирования S, раздел 6. Чем меньше, тем выше вариант. */
    public double score(double cost, double length) {
        return ranking.costWeight * cost / ranking.costBase
                + ranking.lengthWeight * length / ranking.lengthBase;
    }

    /**
     * Во сколько единиц S обходится один рубль. Нужно, чтобы поиск трассы мог
     * сравнивать деньги и метры в одной величине — по той же формуле, по которой
     * нас будет оценивать жюри.
     */
    public double scorePerRuble() {
        return ranking.costWeight / ranking.costBase;
    }

    public double scorePerMeter() {
        return ranking.lengthWeight / ranking.lengthBase;
    }

    public static class ChamberCost {
        public int maxDn;
        public double cost;
    }

    public static class Penalty {
        public double fixed;
        public double perTph;
    }

    public static class Ranking {
        public double costWeight = 0.7;
        public double costBase = 25000000;
        public double lengthWeight = 0.3;
        public double lengthBase = 100;
    }

    /** Правила присоединения к существующей сети, раздел 2.4. */
    public static class TieInRules {
        public double chamberRadius = 10.0;
        public int maxBranches = 4;
    }

    /**
     * Углы поворота. Удорожания за угол нет, но 45° и 90° остаются признаком инженерной
     * геометрии: по ним отбирается инженерный вариант и считается показатель для интерфейса.
     */
    public static class TurnRules {
        public List<Double> standardAngles = new ArrayList<>(java.util.Arrays.asList(45.0, 90.0));
        /** Поворот круче этого угла запрещён, раздел 2.1. */
        public double maxDeg = 90.0;
        public double toleranceDeg = 1.0;
        /** Допуск на угол между лучами в камере: отвода там нет, труба входит в стенку. */
        public double chamberToleranceDeg = 5.0;

        /** Поворот считается стандартным, если он близок к 45° или 90°. */
        public boolean standard(double deflectionDeg) {
            return standard(deflectionDeg, toleranceDeg);
        }

        public boolean standard(double deflectionDeg, double tolerance) {
            if (deflectionDeg <= tolerance) {
                return true;
            }
            for (double angle : standardAngles) {
                if (Math.abs(deflectionDeg - angle) <= tolerance) {
                    return true;
                }
            }
            return false;
        }
    }

    public static class DepthRules {
        public double normal = 3.0;
        public double min = 0.7;
        public double free = 3.0;
        public double extraCostPerMeter = 0.10;
        public double maxSlope = 0.10;

        /** Коэффициент стоимости по глубине, раздел 5. */
        public double k(double depth) {
            return depth <= free ? 1.0 : 1.0 + extraCostPerMeter * (depth - free);
        }
    }
}
