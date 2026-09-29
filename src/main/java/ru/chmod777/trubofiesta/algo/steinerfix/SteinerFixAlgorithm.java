package ru.chmod777.trubofiesta.algo.steinerfix;

import org.springframework.stereotype.Component;
import ru.chmod777.trubofiesta.algo.AlgorithmContext;
import ru.chmod777.trubofiesta.algo.AlgorithmResult;
import ru.chmod777.trubofiesta.algo.ProgressLog;
import ru.chmod777.trubofiesta.algo.octogrid.GridSetup;
import ru.chmod777.trubofiesta.algo.octogrid.OctoGridAlgorithm;
import ru.chmod777.trubofiesta.algo.octogrid.Planner;
import ru.chmod777.trubofiesta.algo.octogrid.Router;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Та же сетка и то же жадное дерево, что у {@code OCTOGRID}, но цена метра трубы уточняется
 * итерациями до неподвижной точки.
 *
 * Что чинится. Поле стоимости дома считается по цене трубы его собственного расчётного
 * расхода, потому что в момент расчёта поля ещё не известно, с кем он разделит коридор.
 * Из-за этого жадное дерево систематически ошибается в обе стороны сразу:
 *
 * <ul>
 *   <li>не видит, что подсесть на чужой ствол почти бесплатно — труба там уже будет,
 *       и ни метра длины, ни рублей за неё второй раз не платят;</li>
 *   <li>не видит, что от его расхода ствол может потолстеть на всю длину до места
 *       присоединения, и это настоящие деньги, которых в оценке нет.</li>
 * </ul>
 *
 * Как чинится. Сначала обычный заход: цена по собственному расходу. Потом по лучшему
 * найденному дереву замораживаются расходы рёбер, поля пересчитываются с маржинальной ценой
 * ({@link MarginalPrice}) и дерево строится заново. И так несколько раз, пока показатель
 * улучшается. Сетка и лучи при этом не перестраиваются — пересчитываются только поля.
 *
 * Оценка варианта не меняется ни на шаг: стоимость по-прежнему считает {@code Evaluator}
 * по фактической геометрии и фактическим диаметрам. Итерации влияют только на то, куда
 * поиск смотрит, поэтому испортить результат они не могут — деревья всех итераций попадают
 * в общий отбор, и побеждает лучшее.
 */
@Component
public class SteinerFixAlgorithm extends OctoGridAlgorithm {

    /** Меньше этого улучшения показателя считаем, что итерации сошлись. */
    private static final double EPSILON = 1e-6;
    /** Сколько стартов оставлять на уточняющих итерациях: поля уже осмысленные. */
    private static final int REFINED_STARTS = 8;

    @Override
    public String key() {
        return "STEINERFIX";
    }

    @Override
    public String title() {
        return "Сетка в осях застройки, цена ствола уточняется итерациями";
    }

    @Override
    protected Router.Price fieldPrice(GridSetup s, Planner pricing, ConnectionPoint p) {
        Router.Price price = pricing.price(p.flow);
        if (s.busy != null && !s.busy.isEmpty()) {
            price.edgeMoney = marginal(s, p.flow);
        }
        return price;
    }

    /**
     * Ту же маржинальную цену получает и планировщик. Иначе поле считало бы, что по чужому
     * стволу ехать дёшево, а восстановление трассы — что дорого, и трасса шла бы мимо ствола.
     */
    @Override
    protected void fields(GridSetup s, ProgressLog log) {
        super.fields(s, log);
        if (s.busy != null && !s.busy.isEmpty()) {
            s.planner.edgeMoney(flow -> marginal(s, flow));
        }
    }

    /**
     * Полная цена метра считается здесь, а не берётся у планировщика: планировщик сам
     * спрашивает эту цену, и обращение к нему было бы бесконечной рекурсией.
     */
    private Router.Price.EdgeMoney marginal(GridSetup s, double flow) {
        Rules rules = s.context.rules;
        double own = rules.byFlow(flow).newCost * rules.scorePerRuble();
        return new MarginalPrice(rules, flow, s.busy, own, rules.scorePerMeter());
    }

    /**
     * Обычный заход, затем уточняющие: расходы берутся из лучшего дерева, поля пересчитываются
     * маржинальной ценой, дерево строится заново. Останавливаемся, когда очередная итерация
     * перестаёт улучшать показатель.
     */
    @Override
    protected List<VariantResult> search(AlgorithmContext context, GridSetup s, AlgorithmResult out, ProgressLog log) {
        List<VariantResult> all = new ArrayList<>(super.search(context, s, out, log));
        int iterations = Math.max(0, context.settings.steinerIterations);
        double best = bestScore(all);
        // Уточняющие итерации идут на меньшем числе стартов: поля уже знают про будущие
        // стволы, и перебирать сотни порядков вставки больше незачем.
        AlgorithmContext refined = context;
        if (iterations > 0) {
            CalcSettings fewer = context.settings.copy();
            fewer.starts = Math.max(REFINED_STARTS, context.settings.effectiveStarts(context.data.points.size()) / 4);
            refined = context.with(fewer);
        }
        for (int i = 1; i <= iterations; i++) {
            VariantResult from = bestOf(all);
            if (from == null || from.net == null) {
                break;
            }
            Map<Long, Double> busy = s.evaluator.linkFlows(from.net);
            if (busy.isEmpty()) {
                break;
            }
            log.step("Уточнение цены ствола, итерация " + i + " из " + iterations
                    + ": занятых рёбер " + busy.size());
            s.busy = busy;
            // Планировщик и поля пересчитываются на той же сетке: перестраивать её незачем.
            fields(s, log);
            List<VariantResult> more = super.search(refined, s, out, log);
            all.addAll(more);
            double now = bestScore(more);
            if (now >= best - EPSILON) {
                log.step("Итерации сошлись: показатель больше не улучшается");
                break;
            }
            best = now;
        }
        // Дальше этим набором пользуются слияние камер и доводка, а им маржинальная цена
        // не нужна: они считают настоящую стоимость.
        s.busy = null;
        s.planner.edgeMoney(null);
        return all;
    }

    private static VariantResult bestOf(List<VariantResult> all) {
        VariantResult best = null;
        for (VariantResult v : all) {
            if (best == null || v.score < best.score) {
                best = v;
            }
        }
        return best;
    }

    private static double bestScore(List<VariantResult> all) {
        VariantResult best = bestOf(all);
        return best == null ? Double.POSITIVE_INFINITY : best.score;
    }
}
