package com.cozentus.enrichment.model;

import java.util.List;

/**
 * A booking that could not be confidently enriched (SPEC 4).
 *
 * <p>{@code original} is the untouched raw booking, so it can be replayed once
 * corrected — or the raw payload string when the message never parsed.
 */
public record FlaggedBooking(String bookingId,
                             List<FlagReason> reasons,
                             List<FieldFlag> fields,
                             Object original) implements EnrichmentResult {

    public FlaggedBooking {
        reasons = List.copyOf(reasons);
        fields = List.copyOf(fields);
    }

    /**
     * SPEC 4: for MALFORMED_MESSAGE the fields list is empty, {@code original}
     * holds the raw string rather than a booking, and there is no bookingId to
     * report because the payload never parsed.
     */
    public static FlaggedBooking malformed(String rawPayload) {
        return new FlaggedBooking(null, List.of(FlagReason.MALFORMED_MESSAGE), List.of(), rawPayload);
    }
}
