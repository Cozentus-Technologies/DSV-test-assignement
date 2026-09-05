package com.cozentus.enrichment.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.bus.InMemoryMessageBus;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.CityReference;
import com.cozentus.enrichment.model.Booking;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CH-06: a booking must stay traceable across systems. */
class HeaderPropagationTest {

    private MessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new InMemoryMessageBus();
        new EnrichmentProcessor(bus, new BookingEnricher(
                new CityMatcher(CityReference.fromClasspath()))).start();
    }

    private void publish(String id, String origin, String destination, Map<String, String> headers) {
        bus.publish(Topics.RAW, id,
                JsonSupport.write(new Booking(id, "ABC Logistics", origin, destination,
                        "ROAD", "2026-09-05")), headers);
    }

    private Message only(String topic) {
        assertThat(bus.consume(topic)).hasSize(1);
        return bus.consume(topic).get(0);
    }

    @Test
    @DisplayName("a publisher correlation-id survives to the enriched message unchanged")
    void correlationIdSurvivesOnEnriched() {
        publish("BKG-1", "Mumbi", "now delhi", Map.of("correlation-id", "abc-123"));

        assertThat(only(Topics.ENRICHED).headers())
                .containsEntry("correlation-id", "abc-123");
    }

    @Test
    @DisplayName("a publisher correlation-id survives to the flagged message unchanged")
    void correlationIdSurvivesOnFlagged() {
        publish("BKG-2", "Warsaw", "Mumbai", Map.of("correlation-id", "def-456"));

        assertThat(only(Topics.FLAGGED).headers())
                .containsEntry("correlation-id", "def-456");
    }

    @Test
    @DisplayName("every inbound header is carried over, not just the ones we know about")
    void allInboundHeadersAreCarried() {
        publish("BKG-3", "Mumbi", "Pune",
                Map.of("correlation-id", "abc", "trace-id", "t-1", "x-tenant", "dsv"));

        assertThat(only(Topics.ENRICHED).headers())
                .containsEntry("correlation-id", "abc")
                .containsEntry("trace-id", "t-1")
                .containsEntry("x-tenant", "dsv");
    }

    @Test
    @DisplayName("the enriched route is stamped ENRICHED")
    void enrichedRouteIsStamped() {
        publish("BKG-4", "Mumbi", "Pune", Map.of());

        assertThat(only(Topics.ENRICHED).headers())
                .containsEntry("x-enrichment-status", "ENRICHED")
                .containsKey("x-enrichment-version")
                .containsKey("x-processed-at");
    }

    @Test
    @DisplayName("the flagged route is stamped FLAGGED")
    void flaggedRouteIsStamped() {
        publish("BKG-5", "Warsaw", "Mumbai", Map.of());

        assertThat(only(Topics.FLAGGED).headers())
                .containsEntry("x-enrichment-status", "FLAGGED");
    }

    @Test
    @DisplayName("a malformed message keeps its inbound headers and is stamped FLAGGED")
    void malformedKeepsHeaders() {
        bus.publish(Topics.RAW, "BKG-6", "{not json", Map.of("correlation-id", "ghi-789"));

        assertThat(only(Topics.FLAGGED).headers())
                .containsEntry("correlation-id", "ghi-789")
                .containsEntry("x-enrichment-status", "FLAGGED");
    }

    @Test
    @DisplayName("a booking published with no headers still gets the service's own")
    void absentInboundHeadersAreFine() {
        publish("BKG-7", "Mumbi", "Pune", Map.of());

        assertThat(only(Topics.ENRICHED).headers())
                .containsKey("x-enrichment-status")
                .containsKey("x-processed-at");
    }
}
