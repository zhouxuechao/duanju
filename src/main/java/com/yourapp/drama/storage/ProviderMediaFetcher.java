package com.yourapp.drama.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Used only by the archive worker after the provider has accepted a video task. */
@Component
public class ProviderMediaFetcher {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<String> hosts;
    public ProviderMediaFetcher(@Value("${drama.storage.provider-hosts:.volces.com,.volcengine.com,.byteimg.com,.bytecdn.cn,.ibytedtos.com,.bytedance.net}") String hosts) {
        this.hosts = Arrays.stream(hosts.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
    public InputStream open(String originalUrl) {
        try {
            URI uri = URI.create(originalUrl);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443) ||
                hosts.stream().noneMatch(h -> h.startsWith(".") ? host.endsWith(h) : host.equals(h)))
                throw new IllegalArgumentException("归档 URL 不属于已配置的模型媒体域名");
            for (InetAddress addr : InetAddress.getAllByName(host))
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress())
                    throw new IllegalArgumentException("不能归档内部网络地址");
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) { response.body().close(); throw new IOException("模型媒体下载失败，HTTP " + response.statusCode()); }
            return response.body();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("归档已中断", e); }
        catch (IOException e) { throw new UncheckedIOException("归档下载失败", e); }
    }
}
