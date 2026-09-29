package ru.chmod777.trubofiesta.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Описание параметров расчёта: интерфейс рисует форму по этой схеме, поэтому новый
 * параметр нельзя добавить и забыть показать. Параметры с пометкой advanced скрыты
 * по умолчанию: они настраивают сам алгоритм, а не инженерные правила.
 */
public final class SettingsSchema {

    private SettingsSchema() {
    }

    public static List<Option> all() {
        List<Option> o = new ArrayList<>();

        o.add(choice("quality", "Качество поиска", "Поиск",
                "FAST", "Быстро",
                "NORMAL", "Обычно",
                "THOROUGH", "Тщательно"));
        o.add(number("variantCount", "Число вариантов", "Поиск", "", 1, 3, 1));
        o.add(flag("standardAnglesOnly", "Только отводы 45° и 90°", "Трасса"));
        o.add(number("shortcutMinGain", "Срезка с углом не 45/90 при выигрыше не меньше", "Трасса", "руб.",
                0, 20000000, 100000));
        o.add(number("beautyAllowance", "Доплата за вид трассы в одном месте, не больше", "Трасса", "руб.",
                0, 20000000, 100000));
        o.add(number("chamberMergeDistance", "Сливать камеры ближе", "Трасса", "м", 0, 50, 1));
        o.add(flag("alignChambers", "Камера на линии ствола напротив ветки", "Трасса"));
        o.add(flag("approachTieIns", "Кандидат присоединения напротив дома", "Существующая сеть"));

        o.add(choice("algorithm", "Алгоритм трассировки", "Поиск",
                "OCTOGRID", "Базовый: выход через любую годную стену",
                "OCTONORMAL", "Вход строго в точки подхода по нормали",
                "STEINERFIX", "Цена ствола уточняется итерациями",
                "TREELS", "Улучшение выемкой и пересборкой дерева"));
        o.add(advanced(number("steinerIterations", "Уточняющих итераций цены ствола", "Алгоритм", "", 0, 10, 1)));

        o.add(number("gridStep", "Шаг сетки", "Трасса", "м", 1, 10, 0.5));
        o.add(flag("diagonals", "Ходы под 45°", "Трасса"));
        o.add(number("turnPenalty", "Цена поворота", "Трасса", "м трубы", 0, 100, 1));
        o.add(number("jogPenalty", "Цена ступеньки", "Трасса", "м трубы", 0, 500, 5));
        o.add(advanced(number("minStraightRun", "Ступенька короче", "Алгоритм", "м", 0, 20, 0.5)));
        o.add(choice("ownBuildingScope", "Свой дом", "Трасса",
                "CORPUS", "Корпус с примыкающими частями",
                "FEATURE", "Весь объект с адресом",
                "PART", "Только полигон с точкой"));
        o.add(choice("exitWall", "Выход из здания", "Трасса",
                "NEAREST", "Через ближайшую стену",
                "ANY", "Через любую стену по стоимости"));
        o.add(flag("exitFallback", "Запасной выход через другую стену", "Трасса"));
        o.add(number("approachTolerance", "Стены считаются одинаково близкими в пределах", "Трасса", "м",
                0, 10, 0.5));
        o.add(number("minBranchAngle", "Расхождение веток в узле, не меньше", "Трасса", "°", 0, 90, 5));
        o.add(number("smallObjectArea", "Малое сооружение, а не здание, меньше", "Правила", "м²", 0, 100, 1));
        o.add(number("smallObjectClearance", "Отступ от малого сооружения", "Правила", "м", 0, 10, 0.5));
        o.add(number("minRestrictionArea", "Не учитывать объекты мельче", "Трасса", "м²", 0, 100, 1));

        o.add(number("tieInMinAngle", "Отход от трубы при присоединении, не положе", "Правила", "°", 10, 90, 5));
        o.add(choice("lengthLimitMode", "Предельная длина одного Ду", "Правила",
                "PATH", "Отдельно по каждому пути",
                "SUM", "Сумма с ветками"));
        o.add(choice("lengthLimitAction", "При превышении длины", "Правила",
                "UPSIZE", "Поднять Ду до подходящего",
                "WARN", "Только предупредить"));
        o.add(choice("pointsInSameBuilding", "Две точки в одном доме", "Правила",
                "SEPARATE", "Разные цели подключения",
                "MERGE", "Одна цель с суммой расходов"));
        o.add(choice("unknownRestriction", "Тип ограничения вне справочника", "Правила",
                "IGNORE", "Не учитывать",
                "FORBID", "Считать запретной зоной"));

        o.add(number("tieInChamberRadius", "Врезка в существующую камеру ближе",
                "Существующая сеть", "м", 0, 100, 1));
        o.add(number("chamberMaxBranches", "Участков к одной камере, не больше",
                "Существующая сеть", "", 2, 8, 1));

        o.add(advanced(number("snapTolerance", "Допуск склейки узлов", "Алгоритм", "м", 0.1, 10, 0.1)));
        o.add(advanced(number("tieInStep", "Шаг кандидатов присоединения", "Алгоритм", "м", 1, 50, 1)));
        o.add(advanced(number("rayLength", "Длина луча от дома и врезки", "Алгоритм", "м", 10, 500, 10)));
        o.add(advanced(number("exitSnapDeg", "Допуск стены к оси сетки", "Алгоритм", "°", 0, 20, 0.5)));
        o.add(advanced(number("obstacleSimplify", "Упрощение границ", "Алгоритм", "м", 0, 2, 0.1)));
        o.add(advanced(choice("planMode", "Режим подбора", "Алгоритм",
                "MULTISTART", "Много запусков",
                "GREEDY", "Один проход")));
        o.add(advanced(number("starts", "Число запусков вручную", "Алгоритм", "", 0, 5000, 1)));
        o.add(advanced(flag("relink", "Улучшать перестановками", "Алгоритм")));
        o.add(advanced(number("seed", "Зерно случайности", "Алгоритм", "", 0, 1000000000, 1)));
        o.add(advanced(number("variantMinDifference", "Порог различия вариантов", "Алгоритм", "м", 0, 200, 5)));
        o.add(advanced(flag("selfCheck", "Проверять результат", "Алгоритм")));
        return o;
    }

    public static List<String> groups() {
        return Arrays.asList("Поиск", "Трасса", "Правила", "Существующая сеть", "Алгоритм");
    }

    private static Option advanced(Option o) {
        o.advanced = true;
        return o;
    }

    private static Option choice(String key, String title, String group, String... pairs) {
        Option o = base(key, title, group);
        o.type = "choice";
        o.values = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            o.values.add(new Value(pairs[i], pairs[i + 1]));
        }
        return o;
    }

    private static Option number(String key, String title, String group, String unit,
                                 double min, double max, double step) {
        Option o = base(key, title, group);
        o.type = "number";
        o.unit = unit;
        o.min = min;
        o.max = max;
        o.step = step;
        return o;
    }

    private static Option flag(String key, String title, String group) {
        Option o = base(key, title, group);
        o.type = "flag";
        return o;
    }

    private static Option base(String key, String title, String group) {
        Option o = new Option();
        o.key = key;
        o.title = title;
        o.group = group;
        return o;
    }

    public static class Option {
        public String key;
        public String title;
        public String group;
        public String type;
        public String unit;
        public boolean advanced;
        public Double min;
        public Double max;
        public Double step;
        public List<Value> values;
    }

    public static class Value {
        public String key;
        public String title;

        public Value(String key, String title) {
            this.key = key;
            this.title = title;
        }
    }
}
