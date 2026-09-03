package com.cozentus.enrichment.model;

/** Why a booking was routed to {@code booking.flagged} (SPEC 6). */
public enum FlagReason {
    UNMATCHED_ORIGIN_CITY,
    UNMATCHED_DESTINATION_CITY,
    AMBIGUOUS_ORIGIN_CITY,
    AMBIGUOUS_DESTINATION_CITY,
    MISSING_ORIGIN_CITY,
    MISSING_DESTINATION_CITY,
    MALFORMED_MESSAGE
}
