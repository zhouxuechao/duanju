package com.yourapp.drama.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;

@Component
@ConditionalOnProperty(name="drama.storage.mode", havingValue="local", matchIfMissing=true)
public class LocalMediaStorage implements MediaStorage {
    private final Path root;
    public LocalMediaStorage(@Value("${drama.storage.root:./data/media}") String root) { this.root = Path.of(root).toAbsolutePath().normalize(); }
    public Path resolve(String key) {
        Path result = root.resolve(MediaStorage.safeKey(key)).normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("文件超出存储目录");
        return result;
    }
    @Override public String put(String key, InputStream source, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, target); }
            } finally { Files.deleteIfExists(temp); }
            return "/api/media/" + key;
        } catch (IOException e) { throw new UncheckedIOException("保存媒体文件失败", e); }
    }
    @Override public InputStream open(String key) {
        try { return Files.newInputStream(resolve(key)); }
        catch (IOException e) { throw new UncheckedIOException("媒体文件不存在或无法读取", e); }
    }
}
