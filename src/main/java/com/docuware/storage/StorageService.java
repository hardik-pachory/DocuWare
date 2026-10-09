package com.docuware.storage;

import java.io.InputStream;
import org.springframework.core.io.Resource;

public interface StorageService {
    void store(String key, InputStream input);
    Resource load(String key);
    void delete(String key);
}
