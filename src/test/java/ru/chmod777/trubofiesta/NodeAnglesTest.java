package ru.chmod777.trubofiesta;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.chmod777.trubofiesta.calc.NodeAngles;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.rules.RulesService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Углы между лучами в узле. Стоимости у них нет (раздел 2.1), но по ним отбирается
 * инженерный вариант, и они пишутся в выгрузку дополнительными свойствами.
 */
class NodeAnglesTest {

    private final Rules rules;

    NodeAnglesTest() throws IOException {
        rules = new RulesService().get();
    }

    private static VariantResult.Seg seg(String id, String from, String to, int dn, double... xy) {
        VariantResult.Seg s = new VariantResult.Seg();
        s.id = id;
        s.startNodeId = from;
        s.endNodeId = to;
        s.dn = dn;
        s.pts = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            s.pts.add(new Coordinate(xy[i], xy[i + 1]));
        }
        return s;
    }

    private static VariantResult.Seg find(List<VariantResult.Seg> segs, String id) {
        return segs.stream().filter(s -> s.id.equals(id)).findFirst().orElseThrow(IllegalStateException::new);
    }

    @Test
    void отвод_в_камере_под_30_градусов_нестандартный_а_под_45_и_90_стандартный() {
        List<VariantResult.Seg> segs = Arrays.asList(
                seg("trunk", "ch_0", "ch_1", 200, 0, 0, 100, 0),
                seg("branch30", "ch_1", "1", 100, 100, 0,
                        100 + 50 * Math.cos(Math.toRadians(30)), 50 * Math.sin(Math.toRadians(30))),
                seg("branch45", "ch_1", "2", 100, 100, 0, 150, 50),
                seg("branch90", "ch_1", "3", 100, 100, 0, 100, 60),
                seg("straight", "ch_1", "4", 100, 100, 0, 160, 0));
        NodeAngles.measure(segs, rules);
        assertNull(find(segs, "trunk").nodeAngle, "в начале сети опорного направления нет");
        assertEquals(30.0, find(segs, "branch30").nodeAngle, 0.1);
        assertEquals(1, find(segs, "branch30").oddTurns);
        assertEquals(0, find(segs, "branch45").oddTurns);
        assertEquals(0, find(segs, "branch90").oddTurns);
        assertEquals(0, find(segs, "straight").oddTurns);
    }

    @Test
    void в_точке_присоединения_опорное_направление_у_самой_толстой_ветки() {
        List<VariantResult.Seg> segs = Arrays.asList(
                seg("thick", "ch_1", "ch_2", 200, 0, 0, 100, 0),
                seg("thin", "ch_1", "1", 100, 0, 0, 30, 40));
        NodeAngles.measure(segs, rules);
        assertEquals(0, find(segs, "thick").oddTurns, "толстая ветка задаёт направление");
        assertEquals(1, find(segs, "thin").oddTurns, "тонкая ветка уходит под 53° к толстой");
    }

    @Test
    void в_техническом_узле_допуск_угла_жёстче_чем_в_камере() {
        double deg = 3.0;
        List<VariantResult.Seg> segs = Arrays.asList(
                seg("trunk", "ch_0", "ch_1", 200, 0, 0, 100, 0),
                seg("inChamber", "ch_1", "node_1", 100, 100, 0,
                        100 + 50 * Math.cos(Math.toRadians(deg)), 50 * Math.sin(Math.toRadians(deg))),
                seg("afterNode", "node_1", "1", 100,
                        100 + 50 * Math.cos(Math.toRadians(deg)), 50 * Math.sin(Math.toRadians(deg)),
                        200, 30));
        NodeAngles.measure(segs, rules);
        assertEquals(0, find(segs, "inChamber").oddTurns, "в камере отклонение 3° в пределах допуска");
        assertEquals(1, find(segs, "afterNode").oddTurns, "в техническом узле допуск только на округление");
    }

    @Test
    void углы_поворотов_внутри_участка_пишутся_в_свойства() {
        List<VariantResult.Seg> segs = Arrays.asList(
                seg("s", "ch_1", "1", 100, 0, 0, 50, 0, 50, 50));
        NodeAngles.measure(segs, rules);
        assertEquals(1, find(segs, "s").turnAngles.size());
        assertEquals(90.0, find(segs, "s").turnAngles.get(0), 0.01);
    }
}
