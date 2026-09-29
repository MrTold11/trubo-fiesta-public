package ru.chmod777.trubofiesta.calc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Один посчитанный вариант подключения — ровно то, что уходит в выходной GeoJSON. */
public class VariantResult {

    public String variantId;
    public int rank;
    /** Подпись варианта для интерфейса: откуда взялось дерево или «Загруженная схема». */
    public String strategy;
    public String explanation;

    public List<Seg> segments = new ArrayList<>();
    /** Новые тепловые камеры: на ветвлениях и в точках присоединения к существующей трубе. */
    public List<Cham> chambers = new ArrayList<>();
    /** Места присоединения новой сети к существующей, раздел 2.4. В выходной файл не идут. */
    public List<Attachment> attachments = new ArrayList<>();
    public List<Node> nodes = new ArrayList<>();
    public List<String> unconnected = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
    /** Замечания, найденные при расчёте варианта: предельная длина, заполненная камера, отступы. */
    @JsonIgnore
    public List<String> planWarnings = new ArrayList<>();
    /** Сколько срезок сделано и сколько они сэкономили, руб. по формуле показателя. */
    public int shortcuts;
    public double shortcutGain;
    /** Сколько поворотов не на 45° и не на 90°: показатель инженерности, на стоимость не влияет. */
    public int oddTurns;
    /** Включено «только отводы 45° и 90°», а в варианте есть другие углы: вариант не годится. */
    @JsonIgnore
    public boolean standardViolated;
    /** Исходные идентификаторы входных объектов: в выходе тип id сохраняется, раздел 7. */
    @JsonIgnore
    public Map<String, Object> rawIds = new LinkedHashMap<>();
    /** Дерево, из которого посчитан вариант, и чем его оценивать: нужно для слияния камер и доводки. */
    @JsonIgnore
    public ru.chmod777.trubofiesta.algo.NewNetwork net;
    @JsonIgnore
    public ru.chmod777.trubofiesta.constraints.ObstacleSet obstacleSet;
    @JsonIgnore
    public Evaluator evaluator;
    /** Номер дерева среди отобранных: один и тот же ствол не выдаётся двумя вариантами. */
    @JsonIgnore
    public int treeIndex = -1;
    /** Сколько участков ближе нормы по своему диаметру. Такой вариант выдаётся, только если нет другого. */
    @JsonIgnore
    public int clearanceViolations;
    /** Какие дома питаются от какого присоединения. Нужно, чтобы отличать варианты по сути. */
    @JsonIgnore
    public List<String> groups = new ArrayList<>();

    /** Стоимость новых участков, входит в construction_cost. */
    public double segmentsCost;
    public double chamberConstructionCost;
    public int existingChamberTieInCount;
    public double existingChamberTieInCost;
    public double constructionCost;
    public double unconnectedPenalty;
    public double calculatedCost;
    public double newNetworkLength;
    public double score;

    /** Участок новой тепловой сети, раздел 7. */
    public static class Seg {
        public String id;
        public String startNodeId;
        public String endNodeId;
        public double flow;
        public int dn;
        public double length;
        public String laying;
        public Double depthStart;
        public Double depthEnd;
        public double cost;
        @JsonIgnore
        public List<Coordinate> pts;
        public String specialType;
        /** Сколько поворотов на участке и сколько из них не на 45° и не на 90°. */
        public int turns;
        public int oddTurns;
        /** Углы поворотов внутри участка, градусы: дополнительное свойство выгрузки. */
        public List<Double> turnAngles = new ArrayList<>();
        /** Угол в начальном узле к пришедшему участку, градусы; null, если узел — начало сети. */
        public Double nodeAngle;
        /** Коэффициент специального прохода, по которому посчитана стоимость. */
        @JsonIgnore
        public double k = 1.0;
    }

    /** Новая тепловая камера, раздел 7. */
    public static class Cham {
        public String id;
        public int dn;
        public double cost;
        /** Камера построена прямо на существующей трубе: её стоимость включает присоединение. */
        public boolean onExistingPipe;
        /** Id существующего участка, на котором стоит камера присоединения. */
        public String existingObjectId;
        @JsonIgnore
        public Coordinate at;
    }

    /**
     * Присоединение части новой сети к существующей. Отдельным объектом в выходной файл
     * не выводится (раздел 2.4, ответ 13): это либо новая камера на трубе, либо концы
     * новых участков в существующей камере.
     */
    public static class Attachment {
        /** Узел выхода: id новой камеры на трубе или id существующей камеры. */
        public String nodeId;
        public boolean existingChamber;
        /** Id существующей камеры или существующего участка сети. */
        public String existingObjectId;
        public int existingDn;
        /** Сколько новых участков кончается в этом узле. */
        public int branches;
        /** Стоимость врезок в существующую камеру; для новой камеры 0, цена внутри камеры. */
        public double cost;
        @JsonIgnore
        public Coordinate at;
        @JsonIgnore
        public String candidateId;
        /** Вершина сетки трассировки, в которой выполнено присоединение. */
        @JsonIgnore
        public int vertex = -1;
    }

    /** Технический узел, раздел 7. */
    public static class Node {
        public String id;
        @JsonIgnore
        public Coordinate at;
    }

    /**
     * Ключ для сравнения вариантов. Варианты разные, если отличаются места присоединения
     * или то, какие дома от какого присоединения питаются. Сдвиг той же трассы — не вариант.
     */
    public String signature(double tolerance) {
        List<String> parts = new ArrayList<>();
        for (Attachment a : attachments) {
            parts.add(Math.round(a.at.x / tolerance) + ":" + Math.round(a.at.y / tolerance));
        }
        java.util.Collections.sort(parts);
        List<String> served = new ArrayList<>();
        for (String g : groups) {
            served.add(g.substring(g.indexOf('=') + 1));
        }
        java.util.Collections.sort(served);
        return String.join("|", parts) + "#" + String.join("|", served) + "#" + unconnected.size();
    }
}
