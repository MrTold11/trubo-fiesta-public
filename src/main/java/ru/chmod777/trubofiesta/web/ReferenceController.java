package ru.chmod777.trubofiesta.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.chmod777.trubofiesta.algo.AlgorithmRegistry;
import ru.chmod777.trubofiesta.rules.Rules;
import ru.chmod777.trubofiesta.rules.RulesService;
import ru.chmod777.trubofiesta.settings.CalcSettings;
import ru.chmod777.trubofiesta.settings.SettingsSchema;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Справочники", description = "Таблицы техприложения и параметры расчёта")
public class ReferenceController {

    private final RulesService rules;
    private final AlgorithmRegistry algorithms;

    public ReferenceController(RulesService rules, AlgorithmRegistry algorithms) {
        this.rules = rules;
        this.algorithms = algorithms;
    }

    @GetMapping("/reference")
    @Operation(summary = "Справочники: диаметры, ограничения, стоимости")
    public Map<String, Object> reference() {
        Rules r = rules.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", rules.source());
        out.put("diameters", r.diameters);
        out.put("restrictions", r.restrictions);
        out.put("chamberCost", r.chamberCost);
        out.put("existingChamberTieInCost", r.existingChamberTieInCost);
        out.put("turn", r.turn);
        out.put("penalty", r.penalty);
        out.put("ranking", r.ranking);
        out.put("depth", r.depth);
        return out;
    }

    @GetMapping("/reference/reload")
    @Operation(summary = "Перечитать справочники без перезапуска")
    public Map<String, Object> reload() throws IOException {
        rules.reload();
        return reference();
    }

    @GetMapping("/settings/schema")
    @Operation(summary = "Описание параметров расчёта для интерфейса")
    public Map<String, Object> schema() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", SettingsSchema.groups());
        out.put("options", SettingsSchema.all());
        out.put("defaults", new CalcSettings());
        out.put("algorithms", algorithms.list());
        return out;
    }

    @GetMapping("/settings/default")
    @Operation(summary = "Параметры расчёта по умолчанию")
    public CalcSettings defaults() {
        return new CalcSettings();
    }
}
