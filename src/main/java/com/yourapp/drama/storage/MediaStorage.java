package com.yourapp.drama.storage;

import java.io.InputStream;

public interface MediaStorage {
    String put(String key, InputStream source, String contentType);
    InputStream open(String key);
    static String safeKey(String key) {
        if (key == null || !key.matches("[a-zA-Z0-9][a-zA-Z0-9/_.-]{0,240}") || key.contains("..") || key.contains("//"))
            throw new IllegalArgumentException("文件标识无效");
        return key;
    }
}
