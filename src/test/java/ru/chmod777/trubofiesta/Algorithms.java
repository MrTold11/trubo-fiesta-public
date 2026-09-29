package ru.chmod777.trubofiesta;

import ru.chmod777.trubofiesta.algo.AlgorithmRegistry;
import ru.chmod777.trubofiesta.algo.RoutingAlgorithm;
import ru.chmod777.trubofiesta.algo.octogrid.OctoGridAlgorithm;
import ru.chmod777.trubofiesta.algo.octonormal.OctoNormalAlgorithm;
import ru.chmod777.trubofiesta.algo.steinerfix.SteinerFixAlgorithm;
import ru.chmod777.trubofiesta.algo.treels.TreeLocalSearchAlgorithm;

import java.util.Arrays;
import java.util.List;

/** Реестр алгоритмов для тестов: в приложении его собирает Spring по бинам. */
public final class Algorithms {

    private Algorithms() {
    }

    public static AlgorithmRegistry registry() {
        List<RoutingAlgorithm> all = Arrays.asList(new OctoGridAlgorithm(), new OctoNormalAlgorithm(),
                new SteinerFixAlgorithm(), new TreeLocalSearchAlgorithm());
        return new AlgorithmRegistry(all);
    }
}
