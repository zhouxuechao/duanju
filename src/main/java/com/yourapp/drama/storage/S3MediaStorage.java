package com.yourapp.drama.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.nio.file.*;

@Component
@ConditionalOnProperty(name="drama.storage.mode", havingValue="s3")
public class S3MediaStorage implements MediaStorage {
    private final S3Client client;
    private final String bucket;
    public S3MediaStorage(@Value("${drama.storage.s3.endpoint}") String endpoint,
                          @Value("${drama.storage.s3.region:us-east-1}") String region,
                          @Value("${drama.storage.s3.bucket:drama}") String bucket,
                          @Value("${drama.storage.s3.access-key}") String access,
                          @Value("${drama.storage.s3.secret-key}") String secret) {
        this.bucket = bucket;
        this.client = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
            .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).build();
    }
    @Override public String put(String key, InputStream source, String contentType) {
        MediaStorage.safeKey(key);
        Path temp = null;
        try {
            temp = Files.createTempFile("drama-archive-", ".bin");
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            client.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromFile(temp));
            return "/api/media/" + key;
        } catch (IOException e) { throw new UncheckedIOException(e); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
    }
    @Override public InputStream open(String key) { return client.getObject(b -> b.bucket(bucket).key(MediaStorage.safeKey(key))); }
    @PreDestroy public void close() { client.close(); }
}
