package com.cozentus.enrichment.model;

import java.util.List;

/** Per-field detail for a flagged booking (SPEC 4). */
public record FieldFlag(String field, FlagReason reason, String value, List<String> candidates) {

    public FieldFlag {
        candidates = List.copyOf(candidates);
    }
}
