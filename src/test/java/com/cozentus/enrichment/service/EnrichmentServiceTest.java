package com.cozentus.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.bus.KafkaMessageBus;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.config.KafkaConfig;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.FlaggedBooking;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CH-02, and the APP_CHANGE_SPEC 4 acceptance criteria.
 * Needs a broker: docker compose up -d, then mvn verify -Pkafka.
 */
@Tag("kafka")
class EnrichmentServiceTest {

    private static final String BROKER = "localhost:9092";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private EnrichmentService service;
    private KafkaMessageBus client;
    private String raw;
    private String enriched;
    private String flagged;

    private KafkaConfig startService(String citiesSource) {
        String suffix = UUID.randomUUID().toString();
        raw = "booking.raw." + suffix;
        enriched = "booking.enriched." + suffix;
        flagged = "booking.flagged." + suffix;
        provision(raw, enriched, flagged);

        KafkaConfig config = KafkaConfig.resolve(new String[]{
                "--topic.raw=" + raw,
                "--topic.enriched=" + enriched,
                "--topic.flagged=" + flagged,
                "--kafka.consumer.group=svc-" + suffix,
                "--cities.source=" + citiesSource,
                "--readiness.port=0"}, name -> null, Map.of());

        service = EnrichmentService.start(config);
        awaitReady(service.readinessPort());

        client = new KafkaMessageBus(KafkaConfig.resolve(
                new String[]{"--kafka.consumer.group=probe-" + suffix}, name -> null, Map.of()));
        return config;
    }

    private static void provision(String... topics) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        try (Admin admin = Admin.create(properties)) {
            admin.createTopics(java.util.Arrays.stream(topics)
                    .map(t -> new NewTopic(t, 3, (short) 1)).toList()).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not provision topics", e);
        }
    }

    private static void awaitReady(int port) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        HttpClient http = HttpClient.newHttpClient();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/ready"))
                        .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception retry) {
                // not up yet
            }
        }
        throw new AssertionError("Service never became ready on port " + port);
    }

    private Message awaitOne(String topic) {
        List<Message> received = new CopyOnWriteArrayList<>();
        client.subscribe(topic, received::add);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && received.isEmpty()) {
            Thread.onSpinWait();
        }
        assertThat(received).as("a message on %s", topic).hasSize(1);
        return received.get(0);
    }

    private void publishBooking(String id, String origin, String destination,
                                Map<String, String> headers) {
        client.publish(raw, id, """
                {"bookingId":"%s","shipper":"ABC Logistics","origin":"%s",
                 "destination":"%s","mode":"ROAD","requestedDate":"2026-09-05"}"""
                .formatted(id, origin, destination), headers);
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (service != null) {
            service.close();
        }
        deleteProvisionedTopics();
    }

    /** Best effort: a leftover topic is untidy, not a test failure. */
    private void deleteProvisionedTopics() {
        if (raw == null) {
            return;
        }
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        try (Admin admin = Admin.create(properties)) {
            admin.deleteTopics(List.of(raw, enriched, flagged)).all().get();
        } catch (Exception ignored) {
            // The broker accumulating a stray topic must not fail a green run.
        }
    }

    @Test
    @DisplayName("a booking published to the raw topic comes back enriched")
    void bookingIsEnrichedEndToEnd() {
        startService("classpath:/cities.json");

        publishBooking("BKG-12345", "Mumbi", "now delhi", Map.of());

        EnrichedBooking booking = JsonSupport.read(awaitOne(enriched).payload(), EnrichedBooking.class);
        assertThat(booking.origin()).isEqualTo("Mumbai");
        assertThat(booking.destination()).isEqualTo("New Delhi");
    }

    @Test
    @DisplayName("an unmatchable city is flagged, and the key is the bookingId")
    void unmatchableCityIsFlagged() {
        startService("classpath:/cities.json");

        publishBooking("BKG-9", "Warsaw", "Mumbai", Map.of());

        Message message = awaitOne(flagged);
        assertThat(message.key()).isEqualTo("BKG-9");
        assertThat(JsonSupport.read(message.payload(), FlaggedBooking.class).reasons())
                .containsExactly(com.cozentus.enrichment.model.FlagReason.UNMATCHED_ORIGIN_CITY);
    }

    @Test
    @DisplayName("a correlation-id set by the publisher survives to the output message")
    void correlationIdSurvivesTheProcessBoundary() {
        startService("classpath:/cities.json");

        publishBooking("BKG-7", "Mumbi", "Pune", Map.of("correlation-id", "trace-abc-123"));

        assertThat(awaitOne(enriched).headers())
                .containsEntry("correlation-id", "trace-abc-123")
                .containsEntry("x-enrichment-status", "ENRICHED");
    }

    @Test
    @DisplayName("a malformed message is flagged and the next valid booking still processes")
    void serviceSurvivesMalformedInput() {
        startService("classpath:/cities.json");

        client.publish(raw, "BKG-16", "{not json", Map.of());
        publishBooking("BKG-17", "Mumbai", "Pune", Map.of());

        assertThat(awaitOne(enriched).key()).isEqualTo("BKG-17");
    }

    @Test
    @DisplayName("an instance started with an extended city list reports Delh as ambiguous")
    void inlineCityListDrivesTheAmbiguityCase() {
        startService("inline:Mumbai,New Delhi,Bangalore,Chennai,Kolkata,Pune,Hyderabad,Ahmedabad,Delhi");

        publishBooking("BKG-13", "Delh", "Mumbai", Map.of());

        FlaggedBooking booking = JsonSupport.read(awaitOne(flagged).payload(), FlaggedBooking.class);
        assertThat(booking.reasons())
                .containsExactly(com.cozentus.enrichment.model.FlagReason.AMBIGUOUS_ORIGIN_CITY);
        assertThat(booking.fields().get(0).candidates()).containsExactly("New Delhi", "Delhi");
    }
}
