package com.cozentus.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

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
    /** Bytes are decoded as UTF-8 explicitly; a platform default makes a
     *  developer machine and a Linux CI runner disagree over diacritics. */
    public static <T> T read(byte[] json, Class<T> type) {
        return read(new String(json, StandardCharsets.UTF_8), type);
    }

    public static byte[] writeBytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

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
