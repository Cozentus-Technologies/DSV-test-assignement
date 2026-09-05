package com.cozentus.enrichment.bus;

import java.time.Instant;
import java.util.Map;

/** One message on one topic (SPEC 7, headers added by CH-06). */
public record Message(String topic, String key, String payload,
                      long offset, Instant timestamp, Map<String, String> headers) {

    public Message {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Convenience for callers that carry no headers. */
    public Message(String topic, String key, String payload, long offset, Instant timestamp) {
        this(topic, key, payload, offset, timestamp, Map.of());
    }
}
