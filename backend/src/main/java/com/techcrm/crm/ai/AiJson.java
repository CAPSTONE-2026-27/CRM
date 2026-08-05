package com.techcrm.crm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls a JSON object out of a model reply.
 *
 * Instruction-tuned models frequently wrap JSON in ```fences``` or pad it with
 * a sentence of prose despite being told not to, so the first {@code {} to the
 * last is extracted rather than trusting the whole reply to parse.
 */
public final class AiJson {

    private static final Logger log = LoggerFactory.getLogger(AiJson.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiJson() {
    }

    /** Parses a payload known to be exactly one JSON document, such as a
     *  streamed chunk. Unlike {@link #extractObject}, it does not go hunting
     *  for braces inside prose. */
    public static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the parsed object, or null if the reply held no usable JSON. */
    public static JsonNode extractObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("Model reply contained no JSON object: {}", truncate(raw));
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            log.warn("Model reply was not parseable as JSON: {}", truncate(raw));
            return null;
        }
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public static Integer clampedScore(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber() && !value.isTextual()) return null;
        int score = value.isNumber() ? value.asInt() : (int) Math.round(parseOrNaN(value.asText()));
        return Math.max(0, Math.min(100, score));
    }

    private static double parseOrNaN(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
