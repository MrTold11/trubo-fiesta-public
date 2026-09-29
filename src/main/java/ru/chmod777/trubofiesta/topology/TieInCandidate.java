package ru.chmod777.trubofiesta.topology;

import org.locationtech.jts.geom.Coordinate;

/** Место, куда можно присоединить новую сеть: точка на существующем участке или камера. */
public class TieInCandidate {

    public String id;
    public Coordinate at;
    /** Участок существующей сети, в который выполняется врезка. */
    public String segmentId;
    /** Расстояние от дальнего от источника конца участка, м. */
    public double measure;
    /** Существующая камера, в которой выполняется врезка, если она в радиусе. */
    public String chamberId;
    /** Сколько участков уже примыкает к этой камере. */
    public int chamberBranches;
    /** Условный диаметр существующего объекта в точке врезки. */
    public int existingDn;
    /** Врезка непосредственно в источник. */
    public boolean atSource;
    /**
     * Точка подключения, напротив которой стоит кандидат: луч из её подхода пересёк здесь
     * существующую трубу. Такой кандидат, кроме обычных лучей, получает прямое ребро к дому.
     */
    public String pointId;
    /** Подход к точке, из которого пришёл луч. */
    public ru.chmod777.trubofiesta.approach.Approach approach;
    /** Луч шёл вбок от точки подхода, а не по нормали: трасса придёт в угол у дома и повернёт к нему. */
    public boolean lateral;
}
