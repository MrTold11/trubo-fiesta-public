package ru.chmod777.trubofiesta.ingest;

import org.locationtech.jts.geom.Coordinate;
import ru.chmod777.trubofiesta.geo.Geo;
import ru.chmod777.trubofiesta.model.Chamber;
import ru.chmod777.trubofiesta.model.ConnectionPoint;
import ru.chmod777.trubofiesta.model.Diagnostic;
import ru.chmod777.trubofiesta.model.InputData;
import ru.chmod777.trubofiesta.model.ObjectType;
import ru.chmod777.trubofiesta.model.Restriction;
import ru.chmod777.trubofiesta.model.Segment;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Собирает доменную модель из объектов входного файла.
 * Отсутствующее поле — не повод падать: пишем замечание и берём значение по умолчанию
 * из параметров расчёта. Конкурсный набор беднее технического приложения, а проверочный
 * может оказаться полным.
 */
public class InputBuilder {

    private final Rules rules;
    private final CalcSettings settings;

    public InputBuilder(Rules rules, CalcSettings settings) {
        this.rules = rules;
        this.settings = settings;
    }

    public InputData build(List<RawFeature> features) {
        InputData data = new InputData();

        for (RawFeature f : features) {
            if (f.objectType == null) {
                data.diagnostics.add(Diagnostic.warning("no_object_type",
                        "У объекта нет поля object_type, объект пропущен", f.id));
                continue;
            }
            if (f.id != null) {
                data.rawIds.put(f.id, f.rawId);
            }
            try {
                switch (f.objectType) {
                    case ObjectType.SOURCE:
                        data.sourceId = f.id;
                        data.sourceName = f.str("name");
                        data.source = GeoJsonGeometry.point(f.geometry);
                        break;
                    case ObjectType.HEAT_NETWORK:
                        data.segments.add(segment(f, data));
                        break;
                    case ObjectType.HEAT_CHAMBER:
                        data.chambers.add(chamber(f));
                        break;
                    case ObjectType.OKS_CONNECTION_POINT:
                        data.points.add(point(f, data));
                        break;
                    case ObjectType.RESTRICTION:
                        Restriction r = restriction(f, data);
                        if (r != null) {
                            data.restrictions.add(r);
                        }
                        break;
                    default:
                        data.diagnostics.add(Diagnostic.info("unknown_object_type",
                                "Неизвестный object_type: " + f.objectType + ", объект пропущен", f.id));
                }
            } catch (RuntimeException e) {
                data.diagnostics.add(Diagnostic.error("bad_geometry",
                        "Не удалось разобрать геометрию: " + e.getMessage(), f.id));
            }
        }

        linkBuildings(data);
        mergePointsInSameBuilding(data);
        data.index();
        validate(data);
        return data;
    }

    private Segment segment(RawFeature f, InputData data) {
        Segment s = new Segment();
        s.id = f.id;
        s.pts = Geo.dedupe(GeoJsonGeometry.lineString(f.geometry));
        if (s.pts.size() < 2) {
            // Пустая или вырожденная линия из рабочей системы не должна ронять весь расчёт:
            // участок пропускается с ошибкой в диагностике.
            throw new IllegalArgumentException("участок сети из " + s.pts.size() + " точек");
        }
        Double dn = f.num("diameter");
        if (dn == null) {
            s.dn = rules.diameters.get(0).dn;
            data.diagnostics.add(Diagnostic.warning("no_diameter",
                    "У участка сети нет diameter, принят наименьший из справочника", f.id));
        } else {
            s.dn = dn.intValue();
        }
        return s;
    }

    private Chamber chamber(RawFeature f) {
        Chamber c = new Chamber();
        c.id = f.id;
        c.at = GeoJsonGeometry.point(f.geometry);
        Double dn = f.num("diameter");
        c.dn = dn == null ? null : dn.intValue();
        return c;
    }

    private ConnectionPoint point(RawFeature f, InputData data) {
        ConnectionPoint p = new ConnectionPoint();
        p.id = f.id;
        p.at = GeoJsonGeometry.point(f.geometry);
        Double flow = f.num("flow_tph");
        if (flow == null) {
            data.diagnostics.add(Diagnostic.error("no_flow",
                    "У точки подключения нет flow_tph, расход принят нулевым", f.id));
            p.flow = 0;
        } else if (flow < 0 || flow.isNaN()) {
            data.diagnostics.add(Diagnostic.error("bad_flow",
                    "У точки подключения flow_tph = " + flow + ", расход принят нулевым", f.id));
            p.flow = 0;
        } else {
            p.flow = flow;
        }
        return p;
    }

    /**
     * Пространственное ограничение. Обязательны типы таблицы 2; остальные поддерживаются
     * как расширение. Тип, которого нет в справочнике, по умолчанию игнорируется с
     * предупреждением: проверочные данные придут из рабочих систем, и чужой тип с правилом
     * запрета перекрыл бы всю территорию. Параметр unknownRestriction = FORBID включает запрет.
     */
    private Restriction restriction(RawFeature f, InputData data) {
        String type = f.str("restriction_type");
        if (type == null) {
            data.diagnostics.add(Diagnostic.warning("no_restriction_type",
                    "У ограничения нет restriction_type", f.id));
            type = "unknown";
        }
        if (!rules.restrictions.containsKey(type) || "unknown".equals(type)) {
            boolean forbid = "FORBID".equals(settings.unknownRestriction);
            data.diagnostics.add(Diagnostic.warning("unknown_restriction_type",
                    "Тип ограничения " + type + " отсутствует в справочнике, объект "
                            + (forbid ? "считается запретной зоной" : "не учитывается"), f.id));
            if (!forbid) {
                return null;
            }
            type = "unknown";
        }
        Restriction r = new Restriction();
        r.id = f.id;
        r.type = type;
        r.rule = rules.rule(type);
        r.address = f.str("address");
        String gt = f.geometryType();
        if (gt == null) {
            data.diagnostics.add(Diagnostic.warning("no_geometry", "У ограничения нет геометрии", f.id));
            return r;
        }
        if ("LineString".equals(gt) || "MultiLineString".equals(gt)) {
            r.geom = GeoJsonGeometry.linear(f.geometry);
        } else if ("Point".equals(gt)) {
            r.geom = Geo.point(GeoJsonGeometry.point(f.geometry));
        } else {
            r.geom = valid(GeoJsonGeometry.polygonal(f.geometry), f.id, data);
        }
        return r;
    }

    /**
     * Невалидная геометрия — ошибка входных данных, об этом сообщаем. Чтобы расчёт не упал
     * и объект не пропал из препятствий, дальше используется его исправленная копия.
     */
    private org.locationtech.jts.geom.Geometry valid(org.locationtech.jts.geom.Geometry g, String id, InputData data) {
        if (g == null || g.isValid()) {
            return g;
        }
        String reason = new org.locationtech.jts.operation.valid.IsValidOp(g).getValidationError().getMessage();
        data.diagnostics.add(Diagnostic.error("invalid_geometry",
                "Невалидная геометрия: " + reason + ". Для расчёта взята исправленная копия", id));
        return g.buffer(0);
    }

    /**
     * Привязывает точку подключения к её полигону ОКС по попаданию внутрь: отдельного
     * идентификатора связи в приложении нет (раздел 1.2).
     */
    private void linkBuildings(InputData data) {
        for (ConnectionPoint p : data.points) {
            for (Restriction r : data.restrictions) {
                if (r.geom != null && "oks".equals(r.type) && r.geom.covers(Geo.point(p.at))) {
                    p.buildingId = r.id;
                    r.futureOksBuilding = true;
                    break;
                }
            }
            if (p.buildingId == null) {
                data.diagnostics.add(Diagnostic.info("point_without_building",
                        "Точка подключения не попала ни в один полигон ОКС, трасса ведётся прямо к ней", p.id));
            }
        }
    }

    /** Несколько точек в одном здании: либо разные ОКС, либо один с суммой расходов. */
    private void mergePointsInSameBuilding(InputData data) {
        Map<String, List<ConnectionPoint>> byBuilding = new LinkedHashMap<>();
        for (ConnectionPoint p : data.points) {
            if (p.buildingId != null) {
                byBuilding.computeIfAbsent(p.buildingId, k -> new ArrayList<>()).add(p);
            }
        }
        List<ConnectionPoint> drop = new ArrayList<>();
        for (Map.Entry<String, List<ConnectionPoint>> e : byBuilding.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (ConnectionPoint p : e.getValue()) {
                ids.add(p.id);
            }
            data.diagnostics.add(Diagnostic.info("points_in_one_building",
                    "В здании несколько точек подключения: " + String.join(", ", ids)
                            + ". Режим: " + settings.pointsInSameBuilding, e.getKey()));
            if ("MERGE".equals(settings.pointsInSameBuilding)) {
                ConnectionPoint keep = e.getValue().get(0);
                for (int i = 1; i < e.getValue().size(); i++) {
                    ConnectionPoint extra = e.getValue().get(i);
                    keep.flow += extra.flow;
                    keep.merged.add(extra.id);
                    drop.add(extra);
                }
            }
        }
        data.points.removeAll(drop);
    }

    private void validate(InputData data) {
        if (data.source == null) {
            data.diagnostics.add(Diagnostic.error("no_source", "Во входных данных нет источника теплоснабжения", null));
        }
        if (data.segments.isEmpty()) {
            data.diagnostics.add(Diagnostic.error("no_network", "Во входных данных нет существующей тепловой сети", null));
        }
        if (data.points.isEmpty()) {
            data.diagnostics.add(Diagnostic.error("no_points", "Во входных данных нет точек подключения", null));
        }
        int small = 0;
        for (Restriction r : data.restrictions) {
            if (r.geom != null && r.geom.getDimension() == 2 && r.geom.getArea() < 5.0) {
                small++;
            }
        }
        if (small > 0) {
            data.diagnostics.add(Diagnostic.warning("tiny_restrictions",
                    "Полигонов ограничений мельче 5 м²: " + small
                            + ". С минимальным расстоянием они могут перекрывать проходы между зданиями", null));
        }
        for (ConnectionPoint p : data.points) {
            if (p.flow <= 0) {
                data.diagnostics.add(Diagnostic.warning("zero_flow",
                        "Расход точки подключения не положителен", p.id));
            }
        }
    }
}
