package ru.chmod777.trubofiesta.model;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Разобранный вход одного проекта, всё в метрах EPSG:32637. */
public class InputData {

    public String sourceId;
    public String sourceName;
    public Coordinate source;

    public List<Segment> segments = new ArrayList<>();
    public List<Chamber> chambers = new ArrayList<>();
    public List<ConnectionPoint> points = new ArrayList<>();
    public List<Restriction> restrictions = new ArrayList<>();
    public List<Diagnostic> diagnostics = new ArrayList<>();

    /**
     * Исходные идентификаторы входных объектов: строка, под которой объект известен расчёту,
     * и то же значение с типом из JSON. В выходе ссылки должны сохранять тип, раздел 7.
     */
    public Map<String, Object> rawIds = new LinkedHashMap<>();

    public Map<String, Segment> segmentById = new LinkedHashMap<>();
    public Map<String, Chamber> chamberById = new LinkedHashMap<>();
    public Map<String, Restriction> restrictionById = new LinkedHashMap<>();

    public void index() {
        segmentById.clear();
        chamberById.clear();
        restrictionById.clear();
        for (Segment s : segments) {
            segmentById.put(s.id, s);
        }
        for (Chamber c : chambers) {
            chamberById.put(c.id, c);
        }
        for (Restriction r : restrictions) {
            restrictionById.put(r.id, r);
        }
    }

    public double totalFlow() {
        double sum = 0;
        for (ConnectionPoint p : points) {
            sum += p.flow;
        }
        return sum;
    }
}
