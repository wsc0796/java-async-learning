package com.cms.flow.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 叶子节点下游配置。
 * Spec 只声明配置名（urlKey），完整绝对 URL 写在 {@code cms.flow.http.urls.<name>}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cms.flow.http")
public class CmsFlowHttpProperties {

    /**
     * urlKey → 绝对 URL。
     * 例如 {@code urls.content-batch: http://localhost:8081/stub/content/batch}。
     */
    private Map<String, String> urls = new LinkedHashMap<>();

    /** 连接/读取超时（毫秒）。 */
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 3000;

    public void setUrls(Map<String, String> urls) {
        this.urls = urls != null ? urls : new LinkedHashMap<>();
    }

    /**
     * 按 Spec 中的 urlKey 解析绝对 URL。
     *
     * @throws IllegalArgumentException 未配置或值为空
     */
    public String requireUrl(String urlKey) {
        if (urlKey == null || urlKey.isBlank()) {
            throw new IllegalArgumentException("http urlKey must not be blank");
        }
        String url = urls.get(urlKey);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "missing cms.flow.http.urls." + urlKey + " (absolute URL required)");
        }
        return url.trim();
    }
}
