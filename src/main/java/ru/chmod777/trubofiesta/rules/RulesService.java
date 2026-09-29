package ru.chmod777.trubofiesta.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * Читает справочники из rules.yaml. Файл можно заменить снаружи через переменную
 * APP_RULES_FILE, не пересобирая образ: у жюри может быть свой набор ставок.
 */
@Service
public class RulesService {

    private volatile Rules rules;
    private volatile String source;

    public RulesService() throws IOException {
        reload();
    }

    public Rules get() {
        return rules;
    }

    public String source() {
        return source;
    }

    public final void reload() throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        String external = System.getenv("APP_RULES_FILE");
        if (external != null && !external.isEmpty()) {
            rules = yaml.readValue(new java.io.File(external), Rules.class);
            source = external;
            return;
        }
        try (InputStream in = new ClassPathResource("rules.yaml").getInputStream()) {
            rules = yaml.readValue(in, Rules.class);
            source = "classpath:rules.yaml";
        }
    }
}
