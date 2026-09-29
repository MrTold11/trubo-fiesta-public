package ru.chmod777.trubofiesta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ru.chmod777.trubofiesta.calc.CalcResult;
import ru.chmod777.trubofiesta.calc.CalculationService;

import static ru.chmod777.trubofiesta.Algorithms.registry;
import ru.chmod777.trubofiesta.calc.ImportedPlanService;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.export.ExcelExporter;
import ru.chmod777.trubofiesta.export.GeoJsonWriter;
import ru.chmod777.trubofiesta.ingest.GeoJsonReader;
import ru.chmod777.trubofiesta.ingest.RawFeature;
import ru.chmod777.trubofiesta.rules.RulesService;
import ru.chmod777.trubofiesta.settings.CalcSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Своя схема разводки и выгрузка в Excel. */
class ImportAndExportTest {

    private final RulesService rules = new RulesService();
    private final CalculationService service = new CalculationService(rules, registry());

    ImportAndExportTest() throws IOException {
    }

    private List<RawFeature> sample() throws IOException {
        List<RawFeature> features = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/sample.geojson")) {
            new GeoJsonReader().read(in, features::add);
        }
        return features;
    }

    @Test
    void загруженная_схема_оценивается_теми_же_правилами() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 6;
        CalcResult auto = service.run(sample(), settings, null);
        VariantResult best = auto.variants.get(0);

        ByteArrayOutputStream geo = new ByteArrayOutputStream();
        new GeoJsonWriter().write(auto, geo);
        String own = onlyLines(geo.toString(StandardCharsets.UTF_8.name()), best.variantId);

        VariantResult imported = new ImportedPlanService(rules).evaluate(sample(), settings,
                new ByteArrayInputStream(own.getBytes(StandardCharsets.UTF_8)));

        assertTrue(imported.unconnected.isEmpty(), "своя схема должна подключать те же объекты");
        assertTrue(imported.attachments.size() > 0, "место присоединения должно найтись");
        assertEquals(best.newNetworkLength, imported.newNetworkLength, 1.0,
                "та же геометрия должна дать ту же длину");
        assertEquals(best.calculatedCost, imported.calculatedCost, best.calculatedCost * 0.002,
                "одна и та же схема должна стоить одинаково, кто бы её ни начертил");
    }

    @Test
    void схема_без_примыкания_к_сети_отклоняется() throws IOException {
        String far = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[30.0,50.0],[30.001,50.001]]},"
                + "\"properties\":{}}]}";
        try {
            new ImportedPlanService(rules).evaluate(sample(), new CalcSettings(),
                    new ByteArrayInputStream(far.getBytes(StandardCharsets.UTF_8)));
            throw new AssertionError("ожидалась понятная ошибка про отсутствие врезки");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("врезку"), e.getMessage());
        }
    }

    @Test
    void в_excel_попадает_всё_что_нужно_инженеру() throws IOException {
        CalcSettings settings = new CalcSettings();
        settings.starts = 4;
        CalcResult result = service.run(sample(), settings, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExcelExporter(rules.get()).write(result.variants, out);

        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            for (String sheet : new String[]{"Сравнение вариантов", "Ведомость объёмов", "Участки новой сети",
                    "Присоединения к сети", "Камеры", "Не подключено"}) {
                assertNotNull(book.getSheet(sheet), "нет листа: " + sheet);
            }
            assertTrue(book.getSheet("Участки новой сети").getLastRowNum() > 0, "лист участков пуст");
            assertEquals("Показатель", book.getSheet("Сравнение вариантов")
                    .getRow(0).getCell(0).getStringCellValue());
        }
    }

    /** Из выгрузки берём только линии нового строительства: так инженер и рисует свою схему. */
    private String onlyLines(String geojson, String variantId) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(geojson);
        com.fasterxml.jackson.databind.node.ArrayNode features = mapper.createArrayNode();
        for (com.fasterxml.jackson.databind.JsonNode f : root.path("features")) {
            com.fasterxml.jackson.databind.JsonNode p = f.path("properties");
            if ("heat_network".equals(p.path("object_type").asText())
                    && variantId.equals(p.path("variant_id").asText())) {
                com.fasterxml.jackson.databind.node.ObjectNode copy = mapper.createObjectNode();
                copy.put("type", "Feature");
                copy.set("geometry", f.path("geometry"));
                copy.set("properties", mapper.createObjectNode());
                features.add(copy);
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
        out.put("type", "FeatureCollection");
        out.set("features", features);
        return mapper.writeValueAsString(out);
    }
}
