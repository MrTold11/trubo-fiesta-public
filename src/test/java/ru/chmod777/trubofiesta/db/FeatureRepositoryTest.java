package ru.chmod777.trubofiesta.db;

import org.junit.jupiter.api.Test;
import ru.chmod777.trubofiesta.ingest.RawFeature;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Тип идентификатора должен пережить путь через базу: число остаётся числом. */
class FeatureRepositoryTest {

    @Test
    void числовой_id_возвращается_из_базы_числом() {
        RawFeature f = FeatureRepository.fromRow("17", "oks_connection_point",
                "{\"id\":17,\"object_type\":\"oks_connection_point\",\"flow_tph\":4.1}",
                "{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}");
        assertEquals("17", f.id);
        assertEquals(17, f.rawId);
        assertEquals(Integer.class, f.rawId.getClass());
    }

    @Test
    void строковый_id_остаётся_строкой() {
        RawFeature f = FeatureRepository.fromRow("K108", "heat_chamber",
                "{\"id\":\"K108\",\"object_type\":\"heat_chamber\"}", null);
        assertEquals("K108", f.rawId);
    }
}
