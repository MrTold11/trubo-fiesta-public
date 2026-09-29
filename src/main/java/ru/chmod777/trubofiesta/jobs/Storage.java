package ru.chmod777.trubofiesta.jobs;

import org.springframework.stereotype.Service;
import ru.chmod777.trubofiesta.config.StorageProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Файлы проектов на диске. Загруженный файл и результат никогда не держатся
 * в памяти целиком: и запись, и отдача идут потоком.
 */
@Service
public class Storage {

    private final Path root;
    private final Path tmp;

    public Storage(StorageProperties properties) throws IOException {
        this.root = Paths.get(properties.getRootDir() != null ? properties.getRootDir()
                : System.getProperty("java.io.tmpdir") + "/trubo-fiesta/store");
        this.tmp = Paths.get(properties.getTmpDir() != null ? properties.getTmpDir()
                : System.getProperty("java.io.tmpdir") + "/trubo-fiesta/tmp");
        Files.createDirectories(root);
        Files.createDirectories(tmp);
    }

    public Path projectDir(UUID projectId) throws IOException {
        Path dir = root.resolve("projects").resolve(projectId.toString());
        Files.createDirectories(dir);
        return dir;
    }

    /** Кладёт загруженный файл рядом с будущими результатами. */
    public Path saveInput(UUID projectId, InputStream in) throws IOException {
        Path target = projectDir(projectId).resolve("input.geojson");
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public Path resultPath(UUID projectId, UUID jobId) throws IOException {
        return projectDir(projectId).resolve("result-" + jobId + ".geojson");
    }

    public OutputStream write(Path path) throws IOException {
        return Files.newOutputStream(path);
    }

    public InputStream read(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    public Path tmpDir() {
        return tmp;
    }
}
