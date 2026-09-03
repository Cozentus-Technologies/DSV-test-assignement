package com.cozentus.enrichment.bus;

import java.time.Instant;

/** One message on one topic (SPEC 7). */
public record Message(String topic, String key, String payload, long offset, Instant timestamp) {
}
