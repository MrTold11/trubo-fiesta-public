package ru.chmod777.trubofiesta.export;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.chmod777.trubofiesta.calc.VariantResult;
import ru.chmod777.trubofiesta.rules.Rules;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Выгрузка в Excel того, что инженер реально уносит в работу: сравнение вариантов,
 * ведомость объёмов по диаметрам, перечень участков, камер и присоединений к сети.
 * Никаких служебных полей расчёта — только то, что идёт в смету и в пояснительную записку.
 */
public class ExcelExporter {

    private final Rules rules;

    public ExcelExporter(Rules rules) {
        this.rules = rules;
    }

    public void write(List<VariantResult> variants, OutputStream out) throws IOException {
        try (Workbook book = new XSSFWorkbook()) {
            Styles styles = new Styles(book);
            summary(book, styles, variants);
            volumes(book, styles, variants);
            segments(book, styles, variants);
            attachments(book, styles, variants);
            chambers(book, styles, variants);
            unconnected(book, styles, variants);
            book.write(out);
        }
    }

    private void summary(Workbook book, Styles s, List<VariantResult> variants) {
        Sheet sheet = book.createSheet("Сравнение вариантов");
        header(sheet, s, "Показатель", names(variants));
        int row = 1;
        row = line(sheet, s, row, "Стоимость новых участков, руб", variants, v -> v.segmentsCost, s.money);
        row = line(sheet, s, row, "Стоимость новых камер, руб", variants, v -> v.chamberConstructionCost, s.money);
        row = line(sheet, s, row, "Врезки в существующие камеры, руб", variants,
                v -> v.existingChamberTieInCost, s.money);
        row = line(sheet, s, row, "Стоимость строительства, руб", variants, v -> v.constructionCost, s.money);
        row = line(sheet, s, row, "Штраф за неподключённые, руб", variants, v -> v.unconnectedPenalty, s.money);
        row = line(sheet, s, row, "Итого стоимость, руб", variants, v -> v.calculatedCost, s.moneyBold);
        row = line(sheet, s, row, "Длина новой сети, м", variants, v -> v.newNetworkLength, s.number1);
        row = line(sheet, s, row, "Присоединений к сети", variants, v -> (double) v.attachments.size(), s.number0);
        row = line(sheet, s, row, "Врезок в существующие камеры", variants,
                v -> (double) v.existingChamberTieInCount, s.number0);
        row = line(sheet, s, row, "Число новых камер", variants, v -> (double) v.chambers.size(), s.number0);
        row = line(sheet, s, row, "Не подключено объектов", variants, v -> (double) v.unconnected.size(), s.number0);
        row = line(sheet, s, row, "Показатель ранжирования", variants, v -> v.score, s.number3);

        Row note = sheet.createRow(row + 1);
        note.createCell(0).setCellValue("Показатель = "
                + rules.ranking.costWeight + " × стоимость / " + (long) rules.ranking.costBase
                + " + " + rules.ranking.lengthWeight + " × длина / " + (long) rules.ranking.lengthBase
                + ". Чем меньше, тем выгоднее вариант.");
        autoSize(sheet, variants.size() + 1);
    }

    private void volumes(Workbook book, Styles s, List<VariantResult> variants) {
        Sheet sheet = book.createSheet("Ведомость объёмов");
        header(sheet, s, "Вариант", new String[]{"Условный диаметр, мм", "Длина, м",
                "Стоимость 1 м, руб", "Стоимость, руб", "Предельная длина, м"});
        int row = 1;
        for (VariantResult v : variants) {
            Map<Integer, double[]> byDn = new LinkedHashMap<>();
            for (VariantResult.Seg seg : v.segments) {
                double[] acc = byDn.computeIfAbsent(seg.dn, k -> new double[2]);
                acc[0] += seg.length;
                acc[1] += seg.cost;
            }
            for (Map.Entry<Integer, double[]> e : byDn.entrySet()) {
                Row r = sheet.createRow(row++);
                text(r, 0, v.strategy == null ? v.variantId : v.variantId + " · " + v.strategy, s.text);
                number(r, 1, e.getKey(), s.number0);
                number(r, 2, e.getValue()[0], s.number1);
                number(r, 3, rules.byDn(e.getKey()).newCost, s.money);
                number(r, 4, e.getValue()[1], s.money);
                number(r, 5, rules.byDn(e.getKey()).maxLength, s.number0);
            }
        }
        autoSize(sheet, 6);
    }

    private void segments(Workbook book, Styles s, List<VariantResult> variants) {
        Sheet sheet = book.createSheet("Участки новой сети");
        header(sheet, s, "Вариант", new String[]{"Участок", "Начало", "Конец", "Расход, т/ч",
                "Ду, мм", "Длина, м", "Прокладка", "Стоимость, руб"});
        int row = 1;
        for (VariantResult v : variants) {
            for (VariantResult.Seg seg : v.segments) {
                Row r = sheet.createRow(row++);
                text(r, 0, v.variantId, s.text);
                text(r, 1, seg.id, s.text);
                text(r, 2, seg.startNodeId, s.text);
                text(r, 3, seg.endNodeId, s.text);
                number(r, 4, seg.flow, s.number2);
                number(r, 5, seg.dn, s.number0);
                number(r, 6, seg.length, s.number1);
                text(r, 7, "special".equals(seg.laying) ? "специальный проход" : "обычная", s.text);
                number(r, 8, seg.cost, s.money);
            }
        }
        autoSize(sheet, 9);
    }

    private void attachments(Workbook book, Styles s, List<VariantResult> variants) {
        Sheet sheet = book.createSheet("Присоединения к сети");
        header(sheet, s, "Вариант", new String[]{"Узел", "Способ", "Существующий объект",
                "Ду существующий, мм", "Новых участков", "Стоимость врезок, руб"});
        int row = 1;
        for (VariantResult v : variants) {
            for (VariantResult.Attachment a : v.attachments) {
                Row r = sheet.createRow(row++);
                text(r, 0, v.variantId, s.text);
                text(r, 1, a.nodeId, s.text);
                text(r, 2, a.existingChamber ? "врезка в существующую камеру" : "новая камера на трубе", s.text);
                text(r, 3, a.existingObjectId, s.text);
                number(r, 4, a.existingDn, s.number0);
                number(r, 5, a.branches, s.number0);
                number(r, 6, a.cost, s.money);
            }
        }
        autoSize(sheet, 7);
    }

    private void chambers(Workbook book, Styles s, List<VariantResult> variants) {
        Sheet sheet = book.createSheet("Камеры");
        header(sheet, s, "Вариант", new String[]{"Камера", "Расположение", "Ду, мм", "Стоимость, руб"});
        int row = 1;
        for (VariantResult v : variants) {
            for (VariantResult.Cham c : v.chambers) {
                Row r = sheet.createRow(row++);
                text(r, 0, v.variantId, s.text);
                text(r, 1, c.id, s.text);
                text(r, 2, c.onExistingPipe ? "на существующей сети" : "на ветвлении новой сети", s.text);
                number(r, 3, c.dn, s.number0);
                number(r, 4, c.cost, s.money);
            }
        }
        autoSize(sheet, 5);
    }

    private void unconnected(Workbook book, Styles s, List<VariantResult> variants) {
        Sheet sheet = book.createSheet("Не подключено");
        header(sheet, s, "Вариант", new String[]{"Объект", "Штраф, руб"});
        int row = 1;
        for (VariantResult v : variants) {
            for (String id : v.unconnected) {
                Row r = sheet.createRow(row++);
                text(r, 0, v.variantId, s.text);
                text(r, 1, id, s.text);
                number(r, 2, v.unconnected.isEmpty() ? 0
                        : v.unconnectedPenalty / v.unconnected.size(), s.money);
            }
        }
        if (row == 1) {
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("Все объекты подключены");
        }
        autoSize(sheet, 3);
    }

    // --- вспомогательное ---------------------------------------------------------

    private interface Value {
        double of(VariantResult v);
    }

    private int line(Sheet sheet, Styles s, int row, String title,
                     List<VariantResult> variants, Value value, CellStyle style) {
        Row r = sheet.createRow(row);
        text(r, 0, title, s.text);
        for (int i = 0; i < variants.size(); i++) {
            number(r, i + 1, value.of(variants.get(i)), style);
        }
        return row + 1;
    }

    private String[] names(List<VariantResult> variants) {
        String[] out = new String[variants.size()];
        for (int i = 0; i < variants.size(); i++) {
            VariantResult v = variants.get(i);
            out[i] = v.strategy == null || v.strategy.isEmpty()
                    ? "Вариант " + v.variantId : "Вариант " + v.variantId + " · " + v.strategy;
        }
        return out;
    }

    private void header(Sheet sheet, Styles s, String first, String[] rest) {
        Row r = sheet.createRow(0);
        text(r, 0, first, s.head);
        for (int i = 0; i < rest.length; i++) {
            text(r, i + 1, rest[i], s.head);
        }
    }

    private void text(Row row, int column, String value, CellStyle style) {
        Cell c = row.createCell(column);
        c.setCellValue(value == null ? "" : value);
        c.setCellStyle(style);
    }

    private void number(Row row, int column, double value, CellStyle style) {
        Cell c = row.createCell(column);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private void autoSize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
            int width = sheet.getColumnWidth(i);
            sheet.setColumnWidth(i, Math.min(Math.max(width + 600, 2600), 14000));
        }
        sheet.createFreezePane(1, 1);
    }

    private static class Styles {
        final CellStyle head;
        final CellStyle text;
        final CellStyle money;
        final CellStyle moneyBold;
        final CellStyle number0;
        final CellStyle number1;
        final CellStyle number2;
        final CellStyle number3;

        Styles(Workbook book) {
            Font bold = book.createFont();
            bold.setBold(true);
            head = book.createCellStyle();
            head.setFont(bold);
            head.setBorderBottom(BorderStyle.THIN);
            head.setAlignment(HorizontalAlignment.LEFT);
            text = book.createCellStyle();
            money = format(book, "# ##0");
            moneyBold = format(book, "# ##0");
            moneyBold.setFont(bold);
            number0 = format(book, "0");
            number1 = format(book, "0.0");
            number2 = format(book, "0.00");
            number3 = format(book, "0.000");
        }

        private CellStyle format(Workbook book, String pattern) {
            CellStyle style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat(pattern));
            return style;
        }
    }
}
