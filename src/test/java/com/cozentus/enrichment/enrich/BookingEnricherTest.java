package com.cozentus.enrichment.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.CityReference;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.FlagReason;
import com.cozentus.enrichment.model.FlaggedBooking;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Covers every reason in SPEC 6, including the two-reason case. */
class BookingEnricherTest {

    private static final BookingEnricher ENRICHER =
            new BookingEnricher(new CityMatcher(CityReference.fromClasspath()));

    private static final BookingEnricher WITH_DELHI = new BookingEnricher(new CityMatcher(
            CityReference.of("Mumbai", "New Delhi", "Bangalore", "Chennai",
                             "Kolkata", "Pune", "Hyderabad", "Ahmedabad", "Delhi")));

    private static Booking booking(String origin, String destination) {
        return new Booking("BKG-1", "ABC Logistics", origin, destination, "ROAD", "2026-09-05");
    }

    // --- enriched path --------------------------------------------------

    @Test
    @DisplayName("both cities correctable yields an enriched booking with canonical names")
    void correctableCitiesAreEnriched() {
        var result = ENRICHER.enrich(booking("Mumbi", "now delhi"));

        assertThat(result).isInstanceOfSatisfying(EnrichedBooking.class, e -> {
            assertThat(e.origin()).isEqualTo("Mumbai");
            assertThat(e.destination()).isEqualTo("New Delhi");
        });
    }

    @Test
    @DisplayName("enrichment metadata retains the values that actually arrived")
    void enrichmentRetainsOriginalValues() {
        var enriched = (EnrichedBooking) ENRICHER.enrich(booking("Mumbi", "now delhi"));

        assertThat(enriched.enrichment().originalOrigin()).isEqualTo("Mumbi");
        assertThat(enriched.enrichment().originalDestination()).isEqualTo("now delhi");
        assertThat(enriched.enrichment().originConfidence()).isBetween(0.85, 1.0);
        assertThat(enriched.enrichment().destinationConfidence()).isBetween(0.85, 1.0);
    }

    @Test
    @DisplayName("shipper, mode and requestedDate pass through untouched")
    void nonCityFieldsAreUnchanged() {
        Booking raw = booking("Mumbi", "now delhi");

        var enriched = (EnrichedBooking) ENRICHER.enrich(raw);

        assertThat(enriched.bookingId()).isEqualTo(raw.bookingId());
        assertThat(enriched.shipper()).isEqualTo(raw.shipper());
        assertThat(enriched.mode()).isEqualTo(raw.mode());
        assertThat(enriched.requestedDate()).isEqualTo(raw.requestedDate());
    }

    @Test
    @DisplayName("an already-canonical city scores 1.0")
    void exactMatchScoresOne() {
        var enriched = (EnrichedBooking) ENRICHER.enrich(booking("Mumbai", "Pune"));

        assertThat(enriched.enrichment().originConfidence()).isEqualTo(1.0);
        assertThat(enriched.enrichment().destinationConfidence()).isEqualTo(1.0);
    }

    // --- every reason in SPEC 6 -----------------------------------------

    static Stream<Arguments> singleReasonCases() {
        return Stream.of(
                Arguments.of("Warsaw", "Mumbai", FlagReason.UNMATCHED_ORIGIN_CITY, "origin"),
                Arguments.of("Mumbai", "Lisbon", FlagReason.UNMATCHED_DESTINATION_CITY, "destination"),
                Arguments.of("", "Mumbai", FlagReason.MISSING_ORIGIN_CITY, "origin"),
                Arguments.of("Mumbai", "", FlagReason.MISSING_DESTINATION_CITY, "destination"),
                Arguments.of(null, "Mumbai", FlagReason.MISSING_ORIGIN_CITY, "origin"),
                Arguments.of("Mumbai", "   ", FlagReason.MISSING_DESTINATION_CITY, "destination"));
    }

    @ParameterizedTest(name = "[{index}] {0}/{1} -> {2}")
    @MethodSource("singleReasonCases")
    @DisplayName("one failing field yields exactly one reason, on that field")
    void singleFailingFieldYieldsOneReason(String origin, String destination,
                                           FlagReason expected, String field) {
        var result = ENRICHER.enrich(booking(origin, destination));

        assertThat(result).isInstanceOfSatisfying(FlaggedBooking.class, f -> {
            assertThat(f.reasons()).containsExactly(expected);
            assertThat(f.fields()).singleElement().satisfies(flag -> {
                assertThat(flag.field()).isEqualTo(field);
                assertThat(flag.reason()).isEqualTo(expected);
            });
        });
    }

    @Test
    @DisplayName("ambiguous origin is flagged with its candidates in reference order")
    void ambiguousOriginCarriesCandidates() {
        var flagged = (FlaggedBooking) WITH_DELHI.enrich(booking("Delh", "Mumbai"));

        assertThat(flagged.reasons()).containsExactly(FlagReason.AMBIGUOUS_ORIGIN_CITY);
        assertThat(flagged.fields()).singleElement().satisfies(flag -> {
            assertThat(flag.field()).isEqualTo("origin");
            assertThat(flag.value()).isEqualTo("Delh");
            assertThat(flag.candidates()).containsExactly("New Delhi", "Delhi");
        });
    }

    @Test
    @DisplayName("ambiguous destination uses the destination-specific reason")
    void ambiguousDestinationUsesItsOwnReason() {
        var flagged = (FlaggedBooking) WITH_DELHI.enrich(booking("Mumbai", "Delh"));

        assertThat(flagged.reasons()).containsExactly(FlagReason.AMBIGUOUS_DESTINATION_CITY);
    }

    @Test
    @DisplayName("an unmatched field carries the arriving value and no candidates")
    void unmatchedFieldHasValueAndNoCandidates() {
        var flagged = (FlaggedBooking) ENRICHER.enrich(booking("Warsaw", "Mumbai"));

        assertThat(flagged.fields()).singleElement().satisfies(flag -> {
            assertThat(flag.value()).isEqualTo("Warsaw");
            assertThat(flag.candidates()).isEmpty();
        });
    }

    // --- the two-reason case --------------------------------------------

    @Test
    @DisplayName("both cities failing yields two reasons, ordered origin then destination")
    void bothFailingYieldsTwoReasonsOriginFirst() {
        var flagged = (FlaggedBooking) ENRICHER.enrich(booking("Warsaw", "Lisbon"));

        assertThat(flagged.reasons()).containsExactly(
                FlagReason.UNMATCHED_ORIGIN_CITY, FlagReason.UNMATCHED_DESTINATION_CITY);
        assertThat(flagged.fields()).extracting("field").containsExactly("origin", "destination");
    }

    @Test
    @DisplayName("two different failure kinds keep origin-then-destination order")
    void mixedFailureKindsKeepOrder() {
        var flagged = (FlaggedBooking) WITH_DELHI.enrich(booking("Warsaw", "Delh"));

        assertThat(flagged.reasons()).containsExactly(
                FlagReason.UNMATCHED_ORIGIN_CITY, FlagReason.AMBIGUOUS_DESTINATION_CITY);
    }

    // --- whole-booking rule ---------------------------------------------

    @Test
    @DisplayName("one valid city does not rescue an invalid one")
    void oneValidCityDoesNotRescueTheOther() {
        assertThat(ENRICHER.enrich(booking("Mumbai", "Warsaw"))).isInstanceOf(FlaggedBooking.class);
        assertThat(ENRICHER.enrich(booking("Warsaw", "Mumbai"))).isInstanceOf(FlaggedBooking.class);
    }

    @Test
    @DisplayName("a flagged booking carries the raw booking untouched")
    void flaggedCarriesTheOriginalBooking() {
        Booking raw = booking("Warsaw", "Mumbai");

        var flagged = (FlaggedBooking) ENRICHER.enrich(raw);

        assertThat(flagged.bookingId()).isEqualTo("BKG-1");
        assertThat(flagged.original()).isEqualTo(raw);
    }

    // --- payload shapes are locked by SPEC 4 ----------------------------

    @Test
    @DisplayName("enriched JSON nests the enrichment block exactly as SPEC 4 shows")
    void enrichedJsonMatchesSpecShape() {
        var enriched = ENRICHER.enrich(booking("Mumbi", "now delhi"));

        JsonNode node = JsonSupport.mapper().valueToTree(enriched);

        assertThat(node.fieldNames()).toIterable().containsExactly(
                "bookingId", "shipper", "origin", "destination", "mode", "requestedDate", "enrichment");
        assertThat(node.get("enrichment").fieldNames()).toIterable().containsExactly(
                "originalOrigin", "originalDestination", "originConfidence", "destinationConfidence");
        assertThat(node.get("origin").asText()).isEqualTo("Mumbai");
        assertThat(node.get("enrichment").get("originalOrigin").asText()).isEqualTo("Mumbi");
    }

    @Test
    @DisplayName("flagged JSON carries reasons, fields and the original as SPEC 4 shows")
    void flaggedJsonMatchesSpecShape() {
        var flagged = WITH_DELHI.enrich(booking("Warsaw", "Delh"));

        JsonNode node = JsonSupport.mapper().valueToTree(flagged);

        assertThat(node.fieldNames()).toIterable().containsExactly(
                "bookingId", "reasons", "fields", "original");
        assertThat(node.get("reasons")).hasSize(2);
        assertThat(node.get("fields").get(0).fieldNames()).toIterable().containsExactly(
                "field", "reason", "value", "candidates");
        assertThat(node.get("fields").get(0).get("candidates")).isEmpty();
        assertThat(node.get("fields").get(1).get("candidates")).hasSize(2);
        assertThat(node.get("original").get("bookingId").asText()).isEqualTo("BKG-1");
    }

    // --- MALFORMED_MESSAGE shape (produced by the processor in task 3) ---

    @Test
    @DisplayName("a malformed flag has no fields, a null bookingId and the raw string")
    void malformedShapeIsDefinedOnce() {
        FlaggedBooking flagged = FlaggedBooking.malformed("{not json");

        assertThat(flagged.bookingId()).isNull();
        assertThat(flagged.reasons()).containsExactly(FlagReason.MALFORMED_MESSAGE);
        assertThat(flagged.fields()).isEmpty();
        assertThat(flagged.original()).isEqualTo("{not json");
    }

    @Test
    @DisplayName("every SPEC 6 reason is reachable")
    void allReasonsAreCovered() {
        assertThat(FlagReason.values()).containsExactlyInAnyOrder(
                FlagReason.UNMATCHED_ORIGIN_CITY, FlagReason.UNMATCHED_DESTINATION_CITY,
                FlagReason.AMBIGUOUS_ORIGIN_CITY, FlagReason.AMBIGUOUS_DESTINATION_CITY,
                FlagReason.MISSING_ORIGIN_CITY, FlagReason.MISSING_DESTINATION_CITY,
                FlagReason.MALFORMED_MESSAGE);
    }
}
