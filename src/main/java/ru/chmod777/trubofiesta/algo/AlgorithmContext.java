package ru.chmod777.trubofiesta.algo;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import ru.chmod777.trubofiesta.approach.Approach;
import ru.chmod777.trubofiesta.constraints.Clearance;
import ru.chmod777.trubofiesta.constraints.Obstacles;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.topology.Topology;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Нормализованный мир, в котором работает алгоритм трассировки. Всё, что здесь лежит,
 * посчитано до него и одинаково для всех алгоритмов:
 *
 * <ol>
 *   <li>вход разобран и проверен ({@link #data});</li>
 *   <li>существующая сеть собрана в граф с направлением к источнику и списком мест,
 *       куда можно присоединиться ({@link #topology});</li>
 *   <li>ограничения превращены в препятствия с отступами по таблице 2 ({@link #obstacles});</li>
 *   <li>для каждой точки подключения ОКС посчитана нормаль к ближайшей стене и точка,
 *       куда труба должна к ней подойти ({@link #approaches}).</li>
 * </ol>
 *
 * Дальше начинается то, что у алгоритмов разное: где искать трассу, как соединять точки
 * подключения между собой и как доводить геометрию. Контекст менять нельзя, он общий.
 */
public class AlgorithmContext {

    /** Разобранные и проверенные входные объекты. */
    public InputData data;
    /** Существующая сеть как граф: узлы, направление к источнику, кандидаты присоединения. */
    public Topology topology;
    /** Справочники технического приложения. */
    public Rules rules;
    public CalcSettings settings;
    /** Препятствия с уже заложенными минимальными расстояниями, по классам условного диаметра. */
    public Obstacles obstacles;
    /** Точная проверка минимальных расстояний для готового участка по его собственному Ду. */
    public Clearance clearance;
    /** Своё здание каждой точки подключения: его пересекает только её собственный отвод. */
    public Map<String, Geometry> ownBuildings = new LinkedHashMap<>();
    /**
     * Куда труба может подойти к каждой точке подключения, раздел 2.2. Подходов у точки
     * несколько: ближайшая граница полигона бывает не одна, а ближайшую может перекрывать
     * соседний ОКС. Список отсортирован — сначала свободные подходы через ближайшие стены.
     * Какой взять и брать ли несколько сразу, решает алгоритм.
     */
    public Map<String, List<Approach>> approaches = new LinkedHashMap<>();
    /** Рабочая область: существующая сеть, точки подключения и запас вокруг них. */
    public Envelope area;
    /** Самый толстый отвод к одному дому: с таким отступом проходят узкие места у домов. */
    public int branchDn;
    /** Ствол на весь расход: самый широкий класс отступов, который может понадобиться. */
    public int trunkDn;

    /** Тот же мир с другими параметрами расчёта: для повторного захода с иной настройкой. */
    public AlgorithmContext with(CalcSettings other) {
        AlgorithmContext c = new AlgorithmContext();
        c.data = data;
        c.topology = topology;
        c.rules = rules;
        c.settings = other;
        c.obstacles = obstacles;
        c.clearance = clearance;
        c.ownBuildings = ownBuildings;
        c.approaches = approaches;
        c.area = area;
        c.branchDn = branchDn;
        c.trunkDn = trunkDn;
        return c;
    }
}
