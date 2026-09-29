package ru.chmod777.trubofiesta.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Настройки хранилища из application.yaml, секция app.storage. */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    private String rootDir;
    private String tmpDir;
    private String maxUploadSize;
    private String maxDownloadSize;

}
