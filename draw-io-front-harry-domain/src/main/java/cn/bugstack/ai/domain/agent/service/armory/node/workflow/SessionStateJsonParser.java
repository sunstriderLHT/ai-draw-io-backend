package cn.bugstack.ai.domain.agent.service.armory.node.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

final class SessionStateJsonParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SessionStateJsonParser() {
    }

    static Optional<JsonNode> parse(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String)) {
            JsonNode structured = OBJECT_MAPPER.valueToTree(value);
            return structured != null && structured.isObject()
                    ? Optional.of(structured)
                    : Optional.empty();
        }

        String json = extractJson(((String) value).trim());
        if (json.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(json);
            return parsed != null && parsed.isObject()
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
        }
    }

    private static String extractJson(String value) {
        int fenceStart = value.indexOf("```");
        if (fenceStart < 0) {
            return value;
        }
        int firstLineEnd = value.indexOf('\n', fenceStart + 3);
        if (firstLineEnd < 0) {
            return "";
        }
        String language = value.substring(fenceStart + 3, firstLineEnd).trim();
        if (!language.isEmpty() && !"json".equalsIgnoreCase(language)) {
            return "";
        }
        int fenceEnd = value.indexOf("```", firstLineEnd + 1);
        if (fenceEnd < 0 || value.indexOf("```", fenceEnd + 3) >= 0) {
            return "";
        }
        return value.substring(firstLineEnd + 1, fenceEnd).trim();
    }
}
