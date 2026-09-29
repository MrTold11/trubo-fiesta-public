package ru.chmod777.trubofiesta.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import ru.chmod777.trubofiesta.calc.CalcResult;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.geo.Crs;
import ru.chmod777.trubofiesta.model.ObjectType;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Пишет результат в GeoJSON потоком, без сборки всего дерева в памяти.
 * Состав — ровно раздел 7 технического приложения: новые участки, новые камеры,
 * технические узлы и сводка по варианту. Дополнительные свойства приложение допускает
 * и при проверке игнорирует, поэтому у участков пишутся ещё и углы поворотов.
 *
 * Ссылки на входные объекты сохраняют тип идентификатора: число во входе остаётся
 * числом в выходе. У сводки geometry равен null, у участков в двумерной задаче null
 * у глубин — это оговорено в разделе 7.
 */
public class GeoJsonWriter {

    private final ObjectMapper mapper = new ObjectMapper();

    public void write(CalcResult result, OutputStream out) throws IOException {
        try (JsonGenerator g = mapper.getFactory().createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeFieldName("crs");
            g.writeStartObject();
            g.writeStringField("type", "name");
            g.writeFieldName("properties");
            g.writeStartObject();
            g.writeStringField("name", "urn:ogc:def:crs:OGC:1.3:CRS84");
            g.writeEndObject();
            g.writeEndObject();
            g.writeArrayFieldStart("features");
            for (VariantResult v : result.variants) {
                writeVariant(g, v);
            }
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    private void writeVariant(JsonGenerator g, VariantResult v) throws IOException {
        Map<String, Object> raw = v.rawIds;
        for (VariantResult.Seg s : v.segments) {
            startFeature(g);
            line(g, s.pts);
            g.writeFieldName("properties");
            g.writeStartObject();
            g.writeStringField("id", s.id);
            g.writeStringField("object_type", ObjectType.HEAT_NETWORK);
            g.writeStringField("variant_id", v.variantId);
            id(g, "start_node_id", s.startNodeId, raw);
            id(g, "end_node_id", s.endNodeId, raw);
            g.writeNumberField("flow_tph", round(s.flow, 2));
            g.writeNumberField("diameter", s.dn);
            g.writeNumberField("length", round(s.length, 2));
            g.writeStringField("laying_method", s.laying);
            writeDepth(g, "depth_start", s.depthStart);
            writeDepth(g, "depth_end", s.depthEnd);
            g.writeNumberField("cost", round(s.cost, 0));
            // Дополнительные свойства: при проверке обязательной части игнорируются,
            // но по ним видно геометрию трассы без пересчёта.
            g.writeArrayFieldStart("turn_angles");
            for (Double angle : s.turnAngles) {
                g.writeNumber(round(angle, 1));
            }
            g.writeEndArray();
            if (s.nodeAngle != null) {
                g.writeNumberField("node_angle", round(s.nodeAngle, 1));
            }
            g.writeEndObject();
            endFeature(g);
        }
        for (VariantResult.Cham c : v.chambers) {
            startFeature(g);
            point(g, c.at);
            g.writeFieldName("properties");
            g.writeStartObject();
            g.writeStringField("id", c.id);
            g.writeStringField("object_type", ObjectType.HEAT_CHAMBER);
            g.writeStringField("variant_id", v.variantId);
            g.writeNumberField("diameter", c.dn);
            g.writeNumberField("cost", round(c.cost, 0));
            if (c.onExistingPipe) {
                g.writeBooleanField("on_existing_network", true);
                id(g, "existing_object_id", c.existingObjectId, raw);
            }
            g.writeEndObject();
            endFeature(g);
        }
        for (VariantResult.Node n : v.nodes) {
            startFeature(g);
            point(g, n.at);
            g.writeFieldName("properties");
            g.writeStartObject();
            g.writeStringField("id", n.id);
            g.writeStringField("object_type", ObjectType.TECHNICAL_NODE);
            g.writeStringField("variant_id", v.variantId);
            g.writeEndObject();
            endFeature(g);
        }

        startFeature(g);
        g.writeNullField("geometry");
        g.writeFieldName("properties");
        g.writeStartObject();
        g.writeStringField("id", "summary_" + v.variantId);
        g.writeStringField("object_type", ObjectType.VARIANT_SUMMARY);
        g.writeStringField("variant_id", v.variantId);
        g.writeNumberField("rank", v.rank);
        g.writeNumberField("construction_cost", round(v.constructionCost, 0));
        g.writeNumberField("chamber_construction_cost", round(v.chamberConstructionCost, 0));
        g.writeNumberField("existing_chamber_tie_in_count", v.existingChamberTieInCount);
        g.writeNumberField("existing_chamber_tie_in_cost", round(v.existingChamberTieInCost, 0));
        g.writeNumberField("unconnected_penalty", round(v.unconnectedPenalty, 0));
        g.writeNumberField("calculated_cost", round(v.calculatedCost, 0));
        g.writeNumberField("new_network_length", round(v.newNetworkLength, 2));
        g.writeNumberField("score", round(v.score, 4));
        g.writeArrayFieldStart("unconnected_oks_ids");
        for (String id : v.unconnected) {
            rawValue(g, id, raw);
        }
        g.writeEndArray();
        g.writeEndObject();
        endFeature(g);
    }

    /**
     * Ссылка на узел. Если узел — объект входного файла, пишем его идентификатор ровно
     * с тем типом, с которым он пришёл: раздел 7 этого требует.
     */
    private void id(JsonGenerator g, String field, String value, Map<String, Object> raw) throws IOException {
        if (value == null) {
            g.writeNullField(field);
            return;
        }
        Object original = raw == null ? null : raw.get(value);
        if (original instanceof Number) {
            g.writeNumberField(field, ((Number) original).longValue());
        } else {
            g.writeStringField(field, value);
        }
    }

    private void rawValue(JsonGenerator g, String value, Map<String, Object> raw) throws IOException {
        Object original = raw == null ? null : raw.get(value);
        if (original instanceof Number) {
            g.writeNumber(((Number) original).longValue());
        } else {
            g.writeString(value);
        }
    }

    private void writeDepth(JsonGenerator g, String field, Double value) throws IOException {
        if (value == null) {
            g.writeNullField(field);
        } else {
            g.writeNumberField(field, round(value, 2));
        }
    }

    private void startFeature(JsonGenerator g) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "Feature");
    }

    private void endFeature(JsonGenerator g) throws IOException {
        g.writeEndObject();
    }

    private void point(JsonGenerator g, Coordinate c) throws IOException {
        Coordinate d = Crs.toDegrees(c.x, c.y);
        g.writeFieldName("geometry");
        g.writeStartObject();
        g.writeStringField("type", "Point");
        g.writeArrayFieldStart("coordinates");
        g.writeNumber(round(d.x, 8));
        g.writeNumber(round(d.y, 8));
        g.writeEndArray();
        g.writeEndObject();
    }

    private void line(JsonGenerator g, List<Coordinate> pts) throws IOException {
        g.writeFieldName("geometry");
        g.writeStartObject();
        g.writeStringField("type", "LineString");
        g.writeArrayFieldStart("coordinates");
        for (Coordinate c : pts) {
            Coordinate d = Crs.toDegrees(c.x, c.y);
            g.writeStartArray();
            g.writeNumber(round(d.x, 8));
            g.writeNumber(round(d.y, 8));
            g.writeEndArray();
        }
        g.writeEndArray();
        g.writeEndObject();
    }

    private double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
