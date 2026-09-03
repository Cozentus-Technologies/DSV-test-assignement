package com.cozentus.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;

/**
 * The one shared {@link ObjectMapper} for the whole harness.
 *
 * <p>Unknown properties are tolerated so that a payload gaining a field upstream
 * does not turn every booking into a MALFORMED_MESSAGE.
 */
public final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonSupport() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * @throws UncheckedIOException if the payload cannot be bound; the processor
     *                              catches this and flags MALFORMED_MESSAGE.
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
