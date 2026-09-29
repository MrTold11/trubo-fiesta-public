package ru.chmod777.trubofiesta.model;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;

/** Точка подключения перспективного ОКС. */
public class ConnectionPoint {

    public String id;
    public Coordinate at;
    public double flow;
    /** ID здания, внутри которого лежит точка, если такое нашлось. */
    public String buildingId;
    /** Через ближайшую стену выйти нельзя, выход через другую: так отмечает расчёт. */
    public boolean exitFallback;
    /** ID точек, слитых в эту при режиме MERGE. */
    public List<String> merged = new ArrayList<>();
}
