package com.yourapp.drama.provider.volcengine;

import com.yourapp.drama.model.ProviderException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class ProviderInputs {
    private ProviderInputs() {}
    static void prompt(String value) {
        if (value == null || value.isBlank()) throw ProviderException.invalid("PROMPT_REQUIRED", "生成提示词不能为空");
    }
    static Map<String, Object> options(Map<String, Object> options, Set<String> allowed) {
        for (String key : options.keySet())
            if (!allowed.contains(key)) throw ProviderException.invalid("OPTION_NOT_ALLOWED", "禁止覆盖或不支持的模型参数：" + key);
        return new LinkedHashMap<>(options);
    }
    static void imageUrl(String value, boolean allowAsset) {
        if (allowAsset && value != null && value.matches("asset://asset-[A-Za-z0-9_-]+")) return;
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0").contains(uri.getHost())
                    || uri.getHost().endsWith(".localhost")) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw ProviderException.invalid("INVALID_MEDIA_REFERENCE", allowAsset
                    ? "模型参考必须为原始 HTTP(S) URL 或完整的 asset://asset-..."
                    : "Seedream/视觉质检图片参考必须为原始 HTTP(S) URL，不能使用 asset://、本地路径或归档路径");
        }
    }
    static void taskId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,200}"))
            throw ProviderException.invalid("INVALID_TASK_ID", "模型任务 ID 格式无效");
    }
    /** Archived asset references may be embedded; video first-frame URLs still use imageUrl. */
    static void imageReference(String value) {
        if(value!=null&&value.startsWith("data:")){
            if(!value.matches("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+")||value.length()>28_000_000)
                throw ProviderException.invalid("INVALID_MEDIA_REFERENCE","归档参考图格式无效或超过 20 MB");
            try { if(java.util.Base64.getDecoder().decode(value.substring(value.indexOf(',')+1)).length>20_000_000)throw new IllegalArgumentException(); }
            catch(IllegalArgumentException error){throw ProviderException.invalid("INVALID_MEDIA_REFERENCE","归档参考图编码无效或超过 20 MB");}
            return;
        }
        imageUrl(value,false);
    }
}
