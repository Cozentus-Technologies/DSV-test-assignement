package com.cozentus.enrichment.config;

/** The three topic names, resolved from configuration (CH-04). */
public record TopicNames(String raw, String enriched, String flagged) {
}
