package ru.chmod777.trubofiesta.algo.steinerfix;

import ru.chmod777.trubofiesta.algo.NewNetwork;
import ru.chmod777.trubofiesta.algo.octogrid.Router;
import ru.chmod777.trubofiesta.rules.Rules;

import java.util.Map;

/**
 * Цена метра трубы для одного дома с поправкой на уже занятые коридоры.
 *
 * Обычная цена исходит из того, что дом кладёт трубу в одиночку: метр стоит по его
 * собственному расходу. На самом деле часть коридора он разделит с соседями, и там верны
 * совсем другие числа:
 *
 * <ul>
 *   <li>ребро свободно — метр стоит полную цену по Ду этого дома плюс вклад длины
 *       в показатель;</li>
 *   <li>по ребру уже идёт чужой расход, и от добавки условный диаметр не меняется — метр
 *       не стоит ничего. Труба там и так будет, и длина уже посчитана;</li>
 *   <li>от добавки условный диаметр вырастает — метр стоит разницу между ставками двух
 *       диаметров. Это и есть та цена утолщения ствола, которой жадное дерево не видит.</li>
 * </ul>
 *
 * Расходы берутся из дерева предыдущей итерации и на время расчёта полей заморожены,
 * поэтому оценка приблизительная: настоящее дерево строится заново и может разойтись
 * с прежним. Но саму стоимость варианта считает не эта формула, а {@code Evaluator}
 * по фактической геометрии — здесь только подсказка поиску, куда смотреть.
 */
final class MarginalPrice implements Router.Price.EdgeMoney {

    private final Rules rules;
    /** Расчётный расход точки, для которой считается поле, т/ч. */
    private final double flow;
    /** Расход рёбер по замороженному дереву, т/ч. */
    private final Map<Long, Double> busy;
    /** Цена метра свободного ребра в единицах S, без коэффициента спецпрохода. */
    private final double own;
    /** Вклад метра длины в показатель. */
    private final double meter;

    MarginalPrice(Rules rules, double flow, Map<Long, Double> busy, double own, double meter) {
        this.rules = rules;
        this.flow = flow;
        this.busy = busy;
        this.own = own;
        this.meter = meter;
    }

    @Override
    public double perMetre(int a, int b, double k) {
        Double taken = busy.get(NewNetwork.key(a, b));
        if (taken == null) {
            return own * k + meter;
        }
        int before = rules.byFlow(taken).dn;
        int after = rules.byFlow(taken + flow).dn;
        if (after <= before) {
            return 0;
        }
        double extra = rules.byDn(after).newCost - rules.byDn(before).newCost;
        return Math.max(0, extra) * rules.scorePerRuble() * k;
    }
}
