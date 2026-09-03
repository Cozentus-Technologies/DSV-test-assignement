package com.cozentus.enrichment.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.bus.InMemoryMessageBus;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.CityReference;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.FlagReason;
import com.cozentus.enrichment.model.FlaggedBooking;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Routing per SPEC 7, including the "never throws" invariant. */
class EnrichmentProcessorTest {

    private MessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new InMemoryMessageBus();
        new EnrichmentProcessor(bus, new BookingEnricher(new CityMatcher(CityReference.fromClasspath())))
                .start();
    }

    private void publishRaw(String bookingId, String origin, String destination) {
        bus.publish(Topics.RAW, bookingId,
                JsonSupport.write(new Booking(bookingId, "ABC Logistics",
                        origin, destination, "ROAD", "2026-09-05")));
    }

    // --- the assignment's core assertion --------------------------------

    @Test
    @DisplayName("a correctable booking reaches booking.enriched with both cities corrected")
    void correctableBookingIsForwardedCorrected() {
        publishRaw("BKG-12345", "Mumbi", "now delhi");

        assertThat(bus.consume(Topics.ENRICHED)).singleElement().satisfies(m -> {
            EnrichedBooking enriched = JsonSupport.read(m.payload(), EnrichedBooking.class);
            assertThat(enriched.origin()).isEqualTo("Mumbai");
            assertThat(enriched.destination()).isEqualTo("New Delhi");
            assertThat(enriched.enrichment().originalOrigin()).isEqualTo("Mumbi");
        });
        assertThat(bus.consume(Topics.FLAGGED)).isEmpty();
    }

    @Test
    @DisplayName("an unmatchable city is flagged rather than forwarded as-is")
    void unmatchableCityIsFlagged() {
        publishRaw("BKG-9", "Warsaw", "Mumbai");

        assertThat(bus.consume(Topics.FLAGGED)).singleElement().satisfies(m -> {
            FlaggedBooking flagged = JsonSupport.read(m.payload(), FlaggedBooking.class);
            assertThat(flagged.reasons()).containsExactly(FlagReason.UNMATCHED_ORIGIN_CITY);
        });
        assertThat(bus.consume(Topics.ENRICHED)).isEmpty();
    }

    // --- one topic per booking ------------------------------------------

    @Test
    @DisplayName("a bookingId never appears on both output topics")
    void aBookingLandsOnExactlyOneTopic() {
        publishRaw("BKG-1", "Mumbi", "now delhi");
        publishRaw("BKG-2", "Warsaw", "Mumbai");
        publishRaw("BKG-3", "Mumbai", "Lisbon");

        List<String> enriched = bus.consume(Topics.ENRICHED).stream().map(Message::key).toList();
        List<String> flagged = bus.consume(Topics.FLAGGED).stream().map(Message::key).toList();

        assertThat(enriched).containsExactly("BKG-1");
        assertThat(flagged).containsExactly("BKG-2", "BKG-3");
        assertThat(enriched).doesNotContainAnyElementsOf(flagged);
        assertThat(enriched.size() + flagged.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("the message key is the bookingId on both output topics")
    void keyIsTheBookingIdOnEveryTopic() {
        publishRaw("BKG-1", "Mumbi", "Pune");
        publishRaw("BKG-2", "Warsaw", "Pune");

        assertThat(bus.consume(Topics.ENRICHED)).singleElement()
                .extracting(Message::key).isEqualTo("BKG-1");
        assertThat(bus.consume(Topics.FLAGGED)).singleElement()
                .extracting(Message::key).isEqualTo("BKG-2");
    }

    @Test
    @DisplayName("a duplicate bookingId is processed each time it arrives")
    void duplicatesAreProcessedEachTime() {
        publishRaw("BKG-19", "Mumbai", "Pune");
        publishRaw("BKG-19", "Mumbai", "Pune");

        assertThat(bus.consume(Topics.ENRICHED)).hasSize(2);
    }

    @Test
    @DisplayName("the processor does not consume its own output")
    void outputTopicsAreNotReprocessed() {
        publishRaw("BKG-1", "Mumbi", "now delhi");

        assertThat(bus.consume(Topics.ENRICHED)).hasSize(1);
        assertThat(bus.consume(Topics.RAW)).hasSize(1);
    }

    // --- malformed input, and the "never throws" invariant --------------

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{not json", "", "   ", "[1,2,3]", "\"a string\"", "null", "{\"bookingId\":"})
    @DisplayName("unparseable payloads are flagged MALFORMED_MESSAGE, never thrown")
    void unparseablePayloadIsFlagged(String payload) {
        assertThatCode(() -> bus.publish(Topics.RAW, "BKG-16", payload)).doesNotThrowAnyException();

        assertThat(bus.consume(Topics.FLAGGED)).singleElement().satisfies(m -> {
            FlaggedBooking flagged = JsonSupport.read(m.payload(), FlaggedBooking.class);
            assertThat(flagged.reasons()).containsExactly(FlagReason.MALFORMED_MESSAGE);
            assertThat(flagged.fields()).isEmpty();
        });
        assertThat(bus.consume(Topics.ENRICHED)).isEmpty();
    }

    @Test
    @DisplayName("a malformed message keeps its key so it can be traced")
    void malformedKeepsItsKey() {
        bus.publish(Topics.RAW, "BKG-16", "{not json");

        assertThat(bus.consume(Topics.FLAGGED)).singleElement()
                .extracting(Message::key).isEqualTo("BKG-16");
    }

    @Test
    @DisplayName("a malformed message with no key falls back to UNKNOWN")
    void malformedWithoutKeyFallsBackToUnknown() {
        bus.publish(Topics.RAW, null, "{not json");

        assertThat(bus.consume(Topics.FLAGGED)).singleElement()
                .extracting(Message::key).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("one bad message does not stop the next one being processed")
    void processingContinuesAfterMalformed() {
        bus.publish(Topics.RAW, "BKG-16", "{not json");
        publishRaw("BKG-17", "Mumbai", "Pune");

        assertThat(bus.consume(Topics.FLAGGED)).singleElement()
                .extracting(Message::key).isEqualTo("BKG-16");
        assertThat(bus.consume(Topics.ENRICHED)).singleElement()
                .extracting(Message::key).isEqualTo("BKG-17");
    }

    @Test
    @DisplayName("a run of bad messages never breaks the pipeline")
    void pipelineSurvivesARunOfBadMessages() {
        for (int i = 0; i < 20; i++) {
            bus.publish(Topics.RAW, "BAD-" + i, "{not json");
        }
        publishRaw("BKG-17", "Mumbai", "Pune");

        assertThat(bus.consume(Topics.FLAGGED)).hasSize(20);
        assertThat(bus.consume(Topics.ENRICHED)).hasSize(1);
    }

    // --- topics -----------------------------------------------------------

    @Test
    @DisplayName("topic names are exactly those in SPEC 2")
    void topicNamesMatchTheSpec() {
        assertThat(Topics.RAW).isEqualTo("booking.raw");
        assertThat(Topics.ENRICHED).isEqualTo("booking.enriched");
        assertThat(Topics.FLAGGED).isEqualTo("booking.flagged");
    }
}
