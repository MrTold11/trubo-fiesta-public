package ru.chmod777.trubofiesta.algo;

import org.locationtech.jts.geom.Geometry;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;
import ru.chmod777.trubofiesta.model.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Что алгоритм вернул: доведённые кандидаты со всей посчитанной экономикой и диагностика
 * его работы. Отобрать из кандидатов до трёх содержательно разных вариантов, проверить их
 * по списку правил и проставить ранги — дело вызывающего, а не алгоритма: этот шаг должен
 * быть одинаков у всех реализаций, иначе их не сравнить.
 *
 * Кандидату алгоритм обязан проставить {@code treeIndex}: одно и то же дерево не выдаётся
 * двумя вариантами.
 */
public class AlgorithmResult {

    public final List<VariantResult> candidates = new ArrayList<>();
    /** Замечания к входным данным, найденные уже во время трассировки. */
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    /** Сколько деревьев подключения построено и оценено: цена поиска. */
    public int plansEvaluated;
    /** Сколько разных деревьев доведено до кандидатов. */
    public int treeCount;

    /** Препятствия, которыми посчитаны последние кандидаты: нужны карте и проверке результата. */
    public ObstacleSet obstacles;
    /** Зоны минимального расстояния вокруг своих зданий: нужны карте. */
    public Map<String, Geometry> ownZones = new LinkedHashMap<>();

    /**
     * Диагностика поискового пространства для интерфейса. У сеточного алгоритма это шаг
     * и поворот сетки и размер графа; алгоритм, который сетку не строит, оставляет нули.
     */
    public double gridStep;
    public double gridAngle;
    public int graphVertices;
    public int graphEdges;
}
