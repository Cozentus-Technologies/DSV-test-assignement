package com.cozentus.enrichment.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cozentus.enrichment.config.KafkaConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
 * CH-01. Needs a broker: {@code docker compose up -d} then {@code mvn verify -Pkafka}.
 * Excluded from the default build.
 */
@Tag("kafka")
class KafkaMessageBusTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private KafkaMessageBus bus;

    private KafkaMessageBus busFor(String topic) {
        bus = new KafkaMessageBus(KafkaConfig.resolve(
                new String[]{"--topic.raw=" + topic,
                             "--kafka.consumer.group=test-" + UUID.randomUUID()},
                name -> null, Map.of()));
        return bus;
    }

    private static final String BROKER = "localhost:9092";

    private final Set<String> provisioned = new java.util.HashSet<>();

    /**
     * Auto-creation is off on the broker (CH-03), so a topic is provisioned
     * explicitly. This is what the suite's TopicProvisioner will do per scenario.
     */
    private String uniqueTopic() {
        String topic = "test.topic." + UUID.randomUUID();
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        try (Admin admin = Admin.create(properties)) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not provision " + topic, e);
        }
        provisioned.add(topic);
        return topic;
    }

    private void deleteProvisionedTopics() {
        if (provisioned.isEmpty()) {
            return;
        }
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        try (Admin admin = Admin.create(properties)) {
            admin.deleteTopics(provisioned).all().get();
        } catch (Exception ignored) {
            // Best effort: a leftover test topic is noise, not a failure.
        }
        provisioned.clear();
    }

    @AfterEach
    void closeBus() {
        if (bus != null) {
            bus.close();
        }
        deleteProvisionedTopics();
    }

    /** Polls rather than sleeping; the suite has no fixed waits anywhere. */
    private static void awaitSize(List<?> received, int expected) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && received.size() < expected) {
            Thread.onSpinWait();
        }
        assertThat(received).hasSize(expected);
    }

    @Test
    @DisplayName("a published message reaches a subscriber with key, payload and headers intact")
    void roundTripsAMessage() {
        String topic = uniqueTopic();
        List<Message> received = new CopyOnWriteArrayList<>();

        KafkaMessageBus kafka = busFor(topic);
        kafka.subscribe(topic, received::add);
        kafka.publish(topic, "BKG-1", "{\"bookingId\":\"BKG-1\"}", Map.of("correlation-id", "abc-123"));

        awaitSize(received, 1);
        assertThat(received.get(0).key()).isEqualTo("BKG-1");
        assertThat(received.get(0).payload()).contains("BKG-1");
        assertThat(received.get(0).headers()).containsEntry("correlation-id", "abc-123");
    }

    @Test
    @DisplayName("messages sharing a key keep their relative order")
    void orderIsPreservedPerKey() {
        String topic = uniqueTopic();
        List<Message> received = new CopyOnWriteArrayList<>();

        KafkaMessageBus kafka = busFor(topic);
        kafka.subscribe(topic, received::add);
        for (int i = 0; i < 10; i++) {
            kafka.publish(topic, "BKG-SAME", String.valueOf(i), Map.of());
        }

        awaitSize(received, 10);
        assertThat(received).extracting(Message::payload)
                .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    @DisplayName("a throwing handler neither stops the poll loop nor loses later messages")
    void handlerExceptionIsIsolated() {
        String topic = uniqueTopic();
        List<Message> received = new CopyOnWriteArrayList<>();

        KafkaMessageBus kafka = busFor(topic);
        kafka.subscribe(topic, message -> {
            received.add(message);
            throw new IllegalStateException("boom");
        });
        kafka.publish(topic, "BKG-1", "first", Map.of());
        kafka.publish(topic, "BKG-2", "second", Map.of());

        awaitSize(received, 2);
    }

    @Test
    @DisplayName("a publish failure surfaces rather than being lost silently")
    void publishFailureSurfaces() {
        KafkaMessageBus unreachable = new KafkaMessageBus(KafkaConfig.resolve(
                new String[]{"--kafka.bootstrap.servers=localhost:1"}, name -> null, Map.of()));

        assertThatThrownBy(() -> unreachable.publish("any.topic", "K", "payload", Map.of()))
                .isInstanceOf(RuntimeException.class);
        unreachable.close();
    }

    @Test
    @DisplayName("consume is refused, because a consumer group has a position not a history")
    void consumeIsUnsupported() {
        assertThatThrownBy(() -> busFor(uniqueTopic()).consume("any.topic"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("position");
    }

    @Test
    @DisplayName("close is idempotent, so a shutdown hook running twice is harmless")
    void closeIsIdempotent() {
        KafkaMessageBus kafka = busFor(uniqueTopic());
        kafka.close();
        assertThatCode(kafka::close).doesNotThrowAnyException();
        bus = null;
    }
}
