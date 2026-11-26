package com.techcrm.crm.marketing.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON helpers for the module's text-typed JSON columns and model payloads. */
public final class MarketingJson {

    // Values stored through this mapper carry no java.time types (dates are
    // ISO strings) because jackson-datatype-jsr310 is not on the classpath.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private MarketingJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise value to JSON", e);
        }
    }

    public static <T> T read(String json, Class<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            T value = MAPPER.readValue(json, type);
            return value != null ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            T value = MAPPER.readValue(json, type);
            return value != null ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static List<String> readStringList(String json) {
        return read(json, new TypeReference<List<String>>() { }, List.of());
    }

    public static Map<String, String> readStringMap(String json) {
        return read(json, new TypeReference<LinkedHashMap<String, String>>() { }, new LinkedHashMap<>());
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }
}
