package ru.chmod777.trubofiesta;

import org.junit.jupiter.api.Test;
import ru.chmod777.trubofiesta.calc.Diameters;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.rules.RulesService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Справочники и формулы разделов 2, 3 и 6 технического приложения. */
class RulesTest {

    private final Rules rules;

    RulesTest() throws IOException {
        rules = new RulesService().get();
    }

    @Test
    void подбирается_минимальный_подходящий_диаметр() {
        assertEquals(50, rules.byFlow(3.0).dn);
        assertEquals(65, rules.byFlow(3.6).dn);
        assertEquals(300, rules.byFlow(437.4).dn);
        assertEquals(400, rules.byFlow(437.5).dn);
        assertEquals(1400, rules.byFlow(999999).dn);
    }

    @Test
    void диаметр_поднимается_до_первого_подходящего_и_по_расходу_и_по_длине() {
        // Ду50 тянет 3 т/ч, но не длиннее 181 м. На 600 м подходит только Ду150 (696 м).
        assertEquals(50, rules.byFlowAndLength(3.0, 100).dn);
        assertEquals(125, rules.byFlowAndLength(3.0, 500).dn);
        assertEquals(150, rules.byFlowAndLength(3.0, 600).dn);
        assertEquals(200, rules.byFlowAndLength(150.0, 100).dn, "по расходу не хватает Ду150");
    }

    @Test
    void длины_параллельных_веток_не_суммируются() {
        // Две ветки по 300 и 200 м из одной камеры, обе Ду100 (предел 419 м).
        // По путям — 300 и 200 м, обе проходят. По сумме — 500 м, уже нет.
        List<Diameters.Edge> path = branches();
        Diameters.choose(rules, true, true, path);
        assertEquals(100, path.get(0).dn, "длинная ветка 300 м укладывается в предел Ду100");

        List<Diameters.Edge> sum = branches();
        Diameters.choose(rules, false, true, sum);
        assertEquals(125, sum.get(0).dn, "по сумме 500 м нужен Ду125");
    }

    @Test
    void общий_участок_входит_в_путь_и_считается_вместе_с_веткой() {
        // Цепочка 300 + 200 м одного Ду100: путь 500 м длиннее предела 419 м.
        List<Diameters.Edge> chain = new ArrayList<>(Arrays.asList(
                new Diameters.Edge(1, 0, 300, 20.0),
                new Diameters.Edge(2, 1, 200, 20.0)));
        Diameters.choose(rules, true, true, chain);
        assertEquals(125, chain.get(0).dn);
        assertEquals(125, chain.get(1).dn, "Ду постоянен на всей непрерывной части");
    }

    /** Две ветки 0→1 и 0→2 из одного узла, обе с расходом под Ду100. */
    private List<Diameters.Edge> branches() {
        return new ArrayList<>(Arrays.asList(
                new Diameters.Edge(1, 0, 300, 20.0),
                new Diameters.Edge(2, 0, 200, 20.0)));
    }

    @Test
    void диаметр_не_убывает_к_месту_присоединения() {
        // Ветка длинная и поднимается по предельной длине; ствол обязан подняться вместе с ней.
        List<Diameters.Edge> edges = new ArrayList<>(Arrays.asList(
                new Diameters.Edge(1, 0, 20, 3.0),
                new Diameters.Edge(2, 1, 600, 3.0)));
        Diameters.choose(rules, true, true, edges);
        assertTrue(edges.get(0).dn >= edges.get(1).dn,
                "Ду ствола " + edges.get(0).dn + " меньше Ду ветки " + edges.get(1).dn);
    }

    @Test
    void стоимость_камеры_по_шкале_раздела_3_2() {
        assertEquals(3000000, rules.chamberCost(50));
        assertEquals(3000000, rules.chamberCost(200));
        assertEquals(5000000, rules.chamberCost(250));
        assertEquals(5000000, rules.chamberCost(500));
        assertEquals(8000000, rules.chamberCost(600));
        assertEquals(12000000, rules.chamberCost(1400));
    }

    @Test
    void штраф_за_неподключённую_точку() {
        assertEquals(100000000 + 500000 * 24.0, rules.penalty(24.0));
    }

    @Test
    void пример_из_раздела_7_3_сходится_до_рубля() {
        double segment = rules.segmentCost(100, 100.0, 1.0);
        assertEquals(8974800, segment, 1.0);
        double construction = segment + rules.existingChamberTieInCost;
        assertEquals(13974800, construction, 1.0);
        assertEquals(0.6913, rules.score(construction, 100.0), 0.0001);
    }

    @Test
    void стоимость_специального_прохода_умножается_на_коэффициент() {
        double base = rules.segmentCost(200, 145.2, 1.0);
        assertEquals(1.6, rules.rule("road").k, 1e-9);
        assertEquals(base * 1.6, rules.segmentCost(200, 145.2, rules.rule("road").k), 1e-6);
    }

    @Test
    void удорожания_за_нестандартный_угол_нет() {
        assertEquals(rules.segmentCost(100, 100.0, 1.0), rules.segmentCost(100, 100.0, 1.0), 1e-9);
        assertTrue(rules.turn.standard(45.0) && rules.turn.standard(90.0) && !rules.turn.standard(30.0),
                "45° и 90° остаются признаком инженерной геометрии");
    }

    @Test
    void коэффициент_глубины_по_разделу_5() {
        assertEquals(1.0, rules.depth.k(2.0), 1e-9);
        assertEquals(1.0, rules.depth.k(3.0), 1e-9);
        assertEquals(1.2, rules.depth.k(5.0), 1e-9);
    }

    @Test
    void минимальное_расстояние_до_окс_зависит_от_диаметра() {
        assertEquals(5.0, rules.rule("oks").clearanceFor(400));
        assertEquals(7.0, rules.rule("oks").clearanceFor(500));
        assertEquals(7.0, rules.rule("oks").clearanceFor(800));
        assertEquals(9.0, rules.rule("oks").clearanceFor(900));
    }

    @Test
    void железная_дорога_это_запрет_а_не_специальный_проход() {
        assertEquals("FORBIDDEN", rules.rule("railway").crossing);
        assertEquals(1.0, rules.rule("railway").clearanceFor(200));
    }
}
