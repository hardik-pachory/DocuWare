package com.docuware.storage;

import com.docuware.exception.ApiException;
import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LocalFileStorageService implements StorageService {
    private final Path root;
    public LocalFileStorageService(@Value("${app.storage-root}") String directory) throws IOException {
        root = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
    }
    private Path path(String key) {
        if (!key.matches("[0-9a-fA-F-]{36}\\.[a-z]{2,4}")) throw new IllegalArgumentException("Invalid storage key");
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Invalid storage path");
        return target;
    }
    public void store(String key, InputStream input) {
        Path target = path(key);
        try (InputStream source = input) { Files.copy(source, target); }
        catch (IOException e) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw failure();
        }
    }
    public Resource load(String key) {
        Path target = path(key);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw failure();
        return new FileSystemResource(target);
    }
    public void delete(String key) {
        try { Files.deleteIfExists(path(key)); } catch (IOException e) { throw failure(); }
    }
    private ApiException failure() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Document storage is unavailable");
    }
}
