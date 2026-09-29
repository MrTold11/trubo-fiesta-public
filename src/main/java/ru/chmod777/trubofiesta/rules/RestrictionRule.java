package ru.chmod777.trubofiesta.rules;

import java.util.List;

/** Правило по одному типу пространственного ограничения, таблица 5.1. */
public class RestrictionRule {

    /** Пересечение запрещено — обходим. */
    public static final String FORBIDDEN = "FORBIDDEN";
    /** Пересечение допускается специальным проходом. */
    public static final String SPECIAL = "SPECIAL";

    public String title;
    public String comment;
    public String crossing = FORBIDDEN;
    public Double clearance;
    public List<DnClearance> clearanceByDn;
    public double minAngle;
    public double specialBuffer;
    public double k = 1.0;
    public Depth depth;
    public Existing existing;

    public boolean isSpecial() {
        return SPECIAL.equals(crossing);
    }

    /** Минимальное горизонтальное расстояние для новой сети выбранного условного диаметра. */
    public double clearanceFor(int dn) {
        if (clearanceByDn != null && !clearanceByDn.isEmpty()) {
            for (DnClearance c : clearanceByDn) {
                if (dn <= c.maxDn) {
                    return c.clearance;
                }
            }
            return clearanceByDn.get(clearanceByDn.size() - 1).clearance;
        }
        return clearance == null ? 0 : clearance;
    }

    public static class DnClearance {
        public int maxDn;
        public double clearance;
    }

    /** Правило по глубине для дополнительной задачи. */
    public static class Depth {
        /** UNDER — только под объектом, EITHER — выше или ниже. */
        public String mode;
        /** Для UNDER: минимум от поверхности до верха габарита новой сети, м. */
        public double cover;
        /** Для EITHER: минимальный вертикальный просвет, м. */
        public double gap;
    }

    /** Условные габариты существующей коммуникации, раздел 4.3. */
    public static class Existing {
        public double width;
        public double height;
        public double top;
    }
}
