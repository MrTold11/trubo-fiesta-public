package ru.chmod777.trubofiesta.algo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Всё, что нужно знать о ломаной участка, кроме самих точек, чтобы её можно было довести:
 * спрямить лишние повороты или срезать угол. Оценка варианта собирает такую заявку для
 * каждой цепочки от узла до узла и передаёт её доводчику алгоритма.
 */
public class RefineRequest {

    /** Имя цепочки для сообщений об ошибках. */
    public String id;
    /** Условный диаметр цепочки: от него зависят отступы и стоимость метра. */
    public int dn;
    /** Точка присоединения к существующей сети, если ломаная начинается в ней. */
    public Coordinate tieIn;
    /** Направления существующих труб в точке присоединения: от них уходим не положе минимума. */
    public List<double[]> pipes = new ArrayList<>();
    /** Своё здание, если ломаная кончается в точке подключения ОКС. */
    public Geometry ownBuilding;
    /** Идентификатор ограничения, которым задано своё здание. */
    public String ownId;
    /** Ломаная начинается в камере на ветвлении: направление первого звена свободно. */
    public boolean startFree;
    /** Ломаная кончается в камере на ветвлении: направление последнего звена свободно. */
    public boolean endFree;
    /** Остальные участки сети: с ними нельзя пересекаться, по ним считаются углы в узлах. */
    public List<List<Coordinate>> others = new ArrayList<>();
}
