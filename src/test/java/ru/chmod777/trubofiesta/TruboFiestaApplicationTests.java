package ru.chmod777.trubofiesta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class TruboFiestaApplicationTests {

    @Test
    void contextLoads() {
    }
}
