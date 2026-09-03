package com.cozentus.enrichment.model;

/**
 * The two possible outcomes of enriching a booking — the Java expression of
 * SPEC 7's {@code Booking -> EnrichedBooking | FlaggedBooking}.
 *
 * <p>Sealed so that routing in the processor is exhaustive: a third outcome
 * would fail to compile rather than silently fall through.
 */
public sealed interface EnrichmentResult permits EnrichedBooking, FlaggedBooking {
}
