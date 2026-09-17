package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.drama.model.ProviderException;
import jakarta.validation.Validator;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** The documented schema subset is validated before network and after parsing, including JsonNode targets. */
public final class StructuredJson {
    private static final Set<String> KEYWORDS = Set.of("$schema", "title", "description", "default", "examples",
            "type", "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern",
            "enum", "const", "anyOf", "allOf", "oneOf");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "integer", "number", "boolean", "null");
    private final ObjectMapper mapper;
    private final Validator validator;

    public StructuredJson(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }
    public JsonNode schema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) throw ProviderException.invalid("SCHEMA_REQUIRED", "必须提供结构化输出 Schema");
        JsonNode node = mapper.valueToTree(schema);
        checkSchema(node, "$");
        return node;
    }
    public <T> T parse(String text, JsonNode schema, Class<T> type, String requestId) {
        try {
            JsonNode node=mapper.readTree(text);
            if (node == null) throw new IllegalArgumentException("empty JSON");
            validate(node, schema, "$");
            T result = mapper.treeToValue(node, type);
            var violations = validator.validate(result);
            if (!violations.isEmpty()) throw new IllegalArgumentException(violations.iterator().next().getPropertyPath() + " 不符合约束");
            return result;
        } catch (Exception e) {
            throw new ProviderException("INVALID_STRUCTURED_OUTPUT", "模型输出未通过结构化校验：" + safeMessage(e), requestId, 200, false, false).withRawOutput(text);
        }
    }
    private String safeMessage(Exception e) {
        // Jackson errors can contain the full model response; expose only validation paths.
        if (e.getClass() == IllegalArgumentException.class) return e.getMessage();
        return "JSON 格式、字段或目标类型不匹配";
    }
    private void checkSchema(JsonNode s, String path) {
        if (s.isBoolean()) return;
        if (!s.isObject()) throw ProviderException.invalid("INVALID_SCHEMA", path + " 必须是 JSON Schema 对象");
        s.fieldNames().forEachRemaining(k -> {
            if (!KEYWORDS.contains(k)) throw ProviderException.invalid("UNSUPPORTED_SCHEMA", "尚不支持 Schema 关键字：" + k);
        });
        JsonNode type = s.path("type");
        if (!type.isMissingNode()) {
            if (type.isTextual()) checkType(type.asText());
            else if (type.isArray() && !type.isEmpty()) type.forEach(t -> checkType(t.asText()));
            else throw ProviderException.invalid("INVALID_SCHEMA", path + ".type 格式无效");
        }
        if (s.has("properties")) {
            if (!s.path("properties").isObject()) throw ProviderException.invalid("INVALID_SCHEMA", path + ".properties 必须是对象");
            s.path("properties").fields().forEachRemaining(e -> checkSchema(e.getValue(), path + "." + e.getKey()));
        }
        if (s.has("required") && (!s.path("required").isArray()))
            throw ProviderException.invalid("INVALID_SCHEMA", path + ".required 必须是数组");
        for (String key : Set.of("items", "additionalProperties")) if (s.has(key)) checkSchema(s.get(key), path + "." + key);
        for (String key : Set.of("allOf", "anyOf", "oneOf")) if (s.has(key)) {
            if (!s.get(key).isArray() || s.get(key).isEmpty()) throw ProviderException.invalid("INVALID_SCHEMA", key + " 必须为非空数组");
            s.get(key).forEach(sub -> checkSchema(sub, path));
        }
        if (s.has("pattern")) {
            try { java.util.regex.Pattern.compile(s.path("pattern").asText()); }
            catch (Exception e) { throw ProviderException.invalid("INVALID_SCHEMA", "pattern 正则表达式无效"); }
        }
    }
    private void checkType(String value) {
        if (!TYPES.contains(value)) throw ProviderException.invalid("INVALID_SCHEMA", "不支持的 JSON 类型：" + value);
    }
    private void validate(JsonNode value, JsonNode s, String path) {
        if (s.isBoolean()) { require(s.asBoolean(), path + " 不被 Schema 允许"); return; }
        if (s.has("type")) {
            JsonNode type = s.get("type");
            boolean valid = type.isArray() ? java.util.stream.StreamSupport.stream(type.spliterator(), false)
                    .anyMatch(t -> matchesType(value, t.asText())) : matchesType(value, type.asText());
            require(valid, path + " 类型错误");
        }
        if (s.has("const")) require(value.equals(s.get("const")), path + " 必须匹配固定值");
        if (s.has("enum")) {
            boolean found = false; for (JsonNode option : s.get("enum")) found |= option.equals(value);
            require(found, path + " 不在允许值中");
        }
        if (value.isObject()) {
            for (JsonNode required : s.path("required")) require(value.has(required.asText()), path + "." + required.asText() + " 缺失");
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next(); JsonNode rule = s.path("properties").get(field.getKey());
                if (rule != null) validate(field.getValue(), rule, path + "." + field.getKey());
                else if (s.has("additionalProperties")) validate(field.getValue(), s.get("additionalProperties"), path + "." + field.getKey());
            }
        }
        if (value.isArray()) {
            bounds(value.size(), s, "minItems", "maxItems", path);
            if (s.has("items")) for (int i = 0; i < value.size(); i++) validate(value.get(i), s.get("items"), path + "[" + i + "]");
        }
        if (value.isNumber()) {
            bounds(value.doubleValue(), s, "minimum", "maximum", path);
            if (s.has("exclusiveMinimum")) require(value.doubleValue() > s.get("exclusiveMinimum").doubleValue(), path + " 未大于下界");
            if (s.has("exclusiveMaximum")) require(value.doubleValue() < s.get("exclusiveMaximum").doubleValue(), path + " 未小于上界");
        }
        if (value.isTextual()) {
            bounds(value.asText().codePointCount(0, value.asText().length()), s, "minLength", "maxLength", path);
            if (s.has("pattern")) require(java.util.regex.Pattern.compile(s.get("pattern").asText()).matcher(value.asText()).find(), path + " 格式错误");
        }
        for (JsonNode rule : s.path("allOf")) validate(value, rule, path);
        for (String key : Set.of("anyOf", "oneOf")) if (s.has(key)) {
            int matches = 0;
            for (JsonNode rule : s.get(key)) try { validate(value, rule, path); matches++; } catch (IllegalArgumentException ignored) {}
            require(key.equals("anyOf") ? matches > 0 : matches == 1, path + " 不满足 " + key);
        }
    }
    private boolean matchesType(JsonNode value, String type) {
        return switch(type) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "number" -> value.isNumber(); case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean(); case "null" -> value.isNull(); default -> false;
        };
    }
    private void bounds(double value, JsonNode s, String low, String high, String path) {
        if (s.has(low)) require(value >= s.get(low).doubleValue(), path + " 低于 " + low);
        if (s.has(high)) require(value <= s.get(high).doubleValue(), path + " 超过 " + high);
    }
    private void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
