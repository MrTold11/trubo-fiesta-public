package ru.chmod777.trubofiesta.model;

import org.locationtech.jts.geom.Coordinate;

/** Существующая тепловая камера. */
public class Chamber {
    public String id;
    public Coordinate at;
    public Integer dn;
    /** Узел сети, к которому камера привязана. */
    public String node;
    /** Сколько участков существующей сети уже примыкает. */
    public int existingBranches;
}
