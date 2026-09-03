package com.cozentus.enrichment.enrich;

import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.MatchResult;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.EnrichmentResult;
import com.cozentus.enrichment.model.FieldFlag;
import com.cozentus.enrichment.model.FlagReason;
import com.cozentus.enrichment.model.FlaggedBooking;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure: {@code Booking -> EnrichedBooking | FlaggedBooking}.
 *
 * <p>Knows nothing of topics or the bus — it reports an outcome and lets the
 * processor route it. Depends only on {@link CityMatcher} and {@code model/}.
 */
public final class BookingEnricher {

    private final CityMatcher matcher;

    public BookingEnricher(CityMatcher matcher) {
        this.matcher = matcher;
    }

    public EnrichmentResult enrich(Booking booking) {
        MatchResult origin = matcher.match(booking.origin());
        MatchResult destination = matcher.match(booking.destination());

        FieldFlag originFlag = flagFor(Field.ORIGIN, booking.origin(), origin);
        FieldFlag destinationFlag = flagFor(Field.DESTINATION, booking.destination(), destination);

        if (originFlag == null && destinationFlag == null) {
            // Both matched, so both casts are safe: flagFor returns null only for Match.
            MatchResult.Match matchedOrigin = (MatchResult.Match) origin;
            MatchResult.Match matchedDestination = (MatchResult.Match) destination;
            return new EnrichedBooking(
                    booking.bookingId(),
                    booking.shipper(),
                    matchedOrigin.canonical(),
                    matchedDestination.canonical(),
                    booking.mode(),
                    booking.requestedDate(),
                    new EnrichedBooking.Enrichment(
                            booking.origin(),
                            booking.destination(),
                            matchedOrigin.confidence(),
                            matchedDestination.confidence()));
        }

        // Whole-booking rule: either city failing flags the lot, so TMS never
        // receives a half-enriched booking. SPEC 6 fixes the order as origin
        // then destination, which lets tests assert list equality.
        List<FieldFlag> fields = new ArrayList<>(2);
        if (originFlag != null) {
            fields.add(originFlag);
        }
        if (destinationFlag != null) {
            fields.add(destinationFlag);
        }
        return new FlaggedBooking(
                booking.bookingId(),
                fields.stream().map(FieldFlag::reason).toList(),
                fields,
                booking);
    }

    /** The flag for one field, or {@code null} when it matched cleanly. */
    private static FieldFlag flagFor(Field field, String value, MatchResult result) {
        return switch (result) {
            case MatchResult.Match ignored -> null;
            case MatchResult.Ambiguous ambiguous ->
                    new FieldFlag(field.name, field.ambiguous, value, ambiguous.candidates());
            case MatchResult.NoMatch ignored ->
                    new FieldFlag(field.name, field.unmatched, value, List.of());
            case MatchResult.Missing ignored ->
                    new FieldFlag(field.name, field.missing, value, List.of());
        };
    }

    /**
     * Pairs a field name with its three SPEC 6 reasons in one place, so an
     * origin outcome cannot be reported under a destination reason.
     */
    private enum Field {
        ORIGIN("origin",
               FlagReason.UNMATCHED_ORIGIN_CITY,
               FlagReason.AMBIGUOUS_ORIGIN_CITY,
               FlagReason.MISSING_ORIGIN_CITY),
        DESTINATION("destination",
                    FlagReason.UNMATCHED_DESTINATION_CITY,
                    FlagReason.AMBIGUOUS_DESTINATION_CITY,
                    FlagReason.MISSING_DESTINATION_CITY);

        private final String name;
        private final FlagReason unmatched;
        private final FlagReason ambiguous;
        private final FlagReason missing;

        Field(String name, FlagReason unmatched, FlagReason ambiguous, FlagReason missing) {
            this.name = name;
            this.unmatched = unmatched;
            this.ambiguous = ambiguous;
            this.missing = missing;
        }
    }
}
