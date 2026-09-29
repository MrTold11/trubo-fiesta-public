package ru.chmod777.trubofiesta.model;

import org.locationtech.jts.geom.Geometry;
import ru.chmod777.trubofiesta.rules.RestrictionRule;

/** Пространственное ограничение: полигон или линия в метрах плюс правило из справочника. */
public class Restriction {
    public String id;
    public String type;
    public Geometry geom;
    public RestrictionRule rule;
    public String address;
    /** Здание, в котором лежит точка подключения перспективного ОКС. */
    public boolean futureOksBuilding;
}
