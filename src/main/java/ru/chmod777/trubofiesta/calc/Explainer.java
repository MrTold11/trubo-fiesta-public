package ru.chmod777.trubofiesta.calc;

import ru.chmod777.trubofiesta.model.InputData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Короткое объяснение варианта словами и цифрами. Текст собирается по шаблону из
 * посчитанных величин: никаких догадок, только то, что есть в результате.
 */
public final class Explainer {

    private Explainer() {
    }

    public static void explain(VariantResult v, VariantResult best, InputData data) {
        // data не используется напрямую: весь текст собирается из посчитанных величин.
        StringBuilder b = new StringBuilder();
        b.append(geometry(v));

        int newChambers = 0;
        int onPipe = 0;
        for (VariantResult.Cham c : v.chambers) {
            newChambers++;
            if (c.onExistingPipe) {
                onPipe++;
            }
        }
        b.append("Присоединений к существующей сети ").append(v.attachments.size())
                .append(" (новых камер на трубе ").append(onPipe)
                .append(", врезок в существующие камеры ").append(v.existingChamberTieInCount).append("), ");
        b.append("всего новых камер ").append(newChambers);
        b.append(", новая сеть ").append(Math.round(v.newNetworkLength)).append(" м");

        Map<Integer, Double> byDn = new LinkedHashMap<>();
        for (VariantResult.Seg s : v.segments) {
            byDn.merge(s.dn, s.length, Double::sum);
        }
        if (!byDn.isEmpty()) {
            b.append(" (");
            boolean first = true;
            for (Map.Entry<Integer, Double> e : byDn.entrySet()) {
                if (!first) {
                    b.append(", ");
                }
                b.append("Ду").append(e.getKey()).append(" ").append(Math.round(e.getValue())).append(" м");
                first = false;
            }
            b.append(")");
        }
        b.append(". ");

        int turns = 0;
        for (VariantResult.Seg s : v.segments) {
            turns += s.turns;
        }
        b.append("Поворотов ").append(turns);
        b.append(v.oddTurns == 0 ? ", все на 45° или 90°. "
                : ", из них не на 45° и не на 90°: " + v.oddTurns + ". ");

        if (!v.unconnected.isEmpty()) {
            b.append("Маршрут не найден для точек: ").append(String.join(", ", v.unconnected))
                    .append(", штраф ").append(money(v.unconnectedPenalty)).append(" руб. ");
        }

        if (best != null && best != v) {
            double delta = v.calculatedCost - best.calculatedCost;
            b.append("Относительно первого варианта ")
                    .append(delta >= 0 ? "дороже на " : "дешевле на ")
                    .append(money(Math.abs(delta))).append(" руб., ");
            double dl = v.newNetworkLength - best.newNetworkLength;
            b.append(dl >= 0 ? "длиннее на " : "короче на ").append(Math.round(Math.abs(dl))).append(" м.");
        }
        v.explanation = b.toString().trim();
    }

    /**
     * Что сделала доводка с геометрией. Варианты различаются деревом подключения —
     * местами присоединения и тем, какие дома питаются от какой ветки, — а доводка у всех
     * одна: трасса вдоль застройки, выход из зданий по нормали, срезки там, где это законно
     * и дешевле.
     */
    private static String geometry(VariantResult v) {
        if (v.shortcuts == 0) {
            return "Трассировка вдоль застройки, выход из зданий по нормали, срезок под произвольным "
                    + "углом не понадобилось. ";
        }
        return "Трассировка вдоль застройки, выход из зданий по нормали; " + v.shortcuts
                + tail(v.shortcuts, " срезка", " срезки", " срезок") + " под произвольным углом там, где это "
                + "законно, вместе они экономят " + money(v.shortcutGain) + " руб. с учётом длины. ";
    }

    private static String tail(int n, String one, String few, String many) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) {
            return few;
        }
        return many;
    }

    private static String money(double v) {
        return String.format(java.util.Locale.US, "%,.0f", v).replace(',', ' ');
    }
}
