package ru.chmod777.trubofiesta.calc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.locationtech.jts.geom.Geometry;
import ru.chmod777.trubofiesta.model.Diagnostic;
import ru.chmod777.trubofiesta.constraints.ObstacleSet;

import java.util.Map;

import java.util.ArrayList;
import java.util.List;

/** Результат одного расчёта: варианты, замечания и краткий журнал шагов. */
public class CalcResult {

    public List<VariantResult> variants = new ArrayList<>();
    public List<Diagnostic> diagnostics = new ArrayList<>();
    public List<String> log = new ArrayList<>();
    public long millis;
    public int graphVertices;
    public int graphEdges;
    /** Шаг сетки трассировки, м. */
    public double gridStep;
    /** Поворот осей сетки относительно сторон света, градусы. */
    public double gridAngle;
    public int plansEvaluated;
    /** Когда записан последний шаг журнала: для времени между шагами. */
    @JsonIgnore
    public long lastStepMillis;

    /** Препятствия и зоны собственных зданий нужны для проверки результата. Наружу не отдаются. */
    @JsonIgnore
    public ObstacleSet obstacles;
    @JsonIgnore
    public Map<String, Geometry> ownBuildings;
}
