package ru.chmod777.trubofiesta.algo;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Какие алгоритмы трассировки есть в сборке и какой из них взять. Новая реализация
 * появляется здесь сама: достаточно объявить её бином {@link RoutingAlgorithm}.
 */
@Component
public class AlgorithmRegistry {

    private final Map<String, RoutingAlgorithm> byKey = new LinkedHashMap<>();
    private final RoutingAlgorithm fallback;

    public AlgorithmRegistry(List<RoutingAlgorithm> algorithms) {
        if (algorithms.isEmpty()) {
            throw new IllegalStateException("В сборке нет ни одного алгоритма трассировки");
        }
        for (RoutingAlgorithm a : algorithms) {
            RoutingAlgorithm clash = byKey.put(a.key(), a);
            if (clash != null) {
                throw new IllegalStateException("Два алгоритма с ключом " + a.key() + ": "
                        + clash.getClass().getName() + " и " + a.getClass().getName());
            }
        }
        this.fallback = byKey.containsKey("OCTOGRID") ? byKey.get("OCTOGRID") : algorithms.get(0);
    }

    /** Алгоритм по ключу; неизвестный ключ или его отсутствие дают алгоритм по умолчанию. */
    public RoutingAlgorithm get(String key) {
        RoutingAlgorithm a = key == null ? null : byKey.get(key);
        return a != null ? a : fallback;
    }

    /** Название алгоритма по ключу. Ключа нет в сборке — возвращается он сам. */
    public String title(String key) {
        RoutingAlgorithm a = key == null ? null : byKey.get(key);
        return a != null ? a.title() : String.valueOf(key);
    }

    /** Все алгоритмы сборки: ключ и название, для справочника интерфейса. */
    public List<Map<String, String>> list() {
        List<Map<String, String>> out = new ArrayList<>();
        for (RoutingAlgorithm a : byKey.values()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("key", a.key());
            item.put("title", a.title());
            item.put("default", String.valueOf(a == fallback));
            out.add(item);
        }
        return out;
    }
}
