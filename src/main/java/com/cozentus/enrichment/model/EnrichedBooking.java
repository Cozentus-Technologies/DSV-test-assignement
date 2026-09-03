package com.cozentus.enrichment.model;

/**
 * A booking with both cities corrected, bound for TMS on {@code booking.enriched}.
 * Component order is the JSON field order of SPEC 4.
 */
public record EnrichedBooking(String bookingId,
                              String shipper,
                              String origin,
                              String destination,
                              String mode,
                              String requestedDate,
                              Enrichment enrichment) implements EnrichmentResult {

    /** The audit block: what arrived, and how sure the matcher was. */
    public record Enrichment(String originalOrigin,
                             String originalDestination,
                             double originConfidence,
                             double destinationConfidence) {
    }
}
