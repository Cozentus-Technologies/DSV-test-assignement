package com.cozentus.enrichment.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bus semantics per SPEC 7. */
class InMemoryMessageBusTest {

    private static final String TOPIC = "topic.a";
    private static final String OTHER = "topic.b";

    private MessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new InMemoryMessageBus();
    }

    // --- publish / consume ----------------------------------------------

    @Test
    @DisplayName("a published message is readable with its key, payload and topic")
    void publishedMessageIsReadable() {
        bus.publish(TOPIC, "K1", "payload");

        assertThat(bus.consume(TOPIC)).singleElement().satisfies(m -> {
            assertThat(m.topic()).isEqualTo(TOPIC);
            assertThat(m.key()).isEqualTo("K1");
            assertThat(m.payload()).isEqualTo("payload");
            assertThat(m.timestamp()).isNotNull();
        });
    }

    @Test
    @DisplayName("consuming a topic that was never published to yields nothing")
    void unknownTopicIsEmpty() {
        assertThat(bus.consume("never.used")).isEmpty();
    }

    @Test
    @DisplayName("messages come back in publication order")
    void orderIsPreserved() {
        bus.publish(TOPIC, "K1", "first");
        bus.publish(TOPIC, "K2", "second");
        bus.publish(TOPIC, "K3", "third");

        assertThat(bus.consume(TOPIC)).extracting(Message::payload)
                .containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("offsets start at zero and increment per topic independently")
    void offsetsArePerTopic() {
        bus.publish(TOPIC, "K1", "a");
        bus.publish(TOPIC, "K2", "b");
        bus.publish(OTHER, "K3", "c");

        assertThat(bus.consume(TOPIC)).extracting(Message::offset).containsExactly(0L, 1L);
        assertThat(bus.consume(OTHER)).extracting(Message::offset).containsExactly(0L);
    }

    @Test
    @DisplayName("consume returns a snapshot that later publishes do not mutate")
    void consumeReturnsASnapshot() {
        bus.publish(TOPIC, "K1", "first");
        List<Message> snapshot = bus.consume(TOPIC);

        bus.publish(TOPIC, "K2", "second");

        assertThat(snapshot).hasSize(1);
        assertThat(bus.consume(TOPIC)).hasSize(2);
    }

    // --- subscribers ----------------------------------------------------

    @Test
    @DisplayName("subscribers run synchronously inside publish, so no waiting is needed")
    void deliveryIsSynchronous() {
        List<Message> received = new ArrayList<>();
        bus.subscribe(TOPIC, received::add);

        bus.publish(TOPIC, "K1", "payload");

        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("subscribing does not replay messages published beforehand")
    void subscribeDoesNotReplayHistory() {
        bus.publish(TOPIC, "K1", "before");

        List<Message> received = new ArrayList<>();
        bus.subscribe(TOPIC, received::add);

        assertThat(received).isEmpty();
        assertThat(bus.consume(TOPIC)).hasSize(1);
    }

    @Test
    @DisplayName("a subscriber only hears its own topic")
    void subscribersAreScopedToTheirTopic() {
        List<Message> received = new ArrayList<>();
        bus.subscribe(TOPIC, received::add);

        bus.publish(OTHER, "K1", "elsewhere");

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("every subscriber on a topic receives the message")
    void allSubscribersReceive() {
        List<Message> first = new ArrayList<>();
        List<Message> second = new ArrayList<>();
        bus.subscribe(TOPIC, first::add);
        bus.subscribe(TOPIC, second::add);

        bus.publish(TOPIC, "K1", "payload");

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
    }

    // --- subscriber exceptions are isolated -----------------------------

    @Test
    @DisplayName("a throwing subscriber does not stop the ones after it")
    void throwingSubscriberDoesNotBlockOthers() {
        List<Message> received = new ArrayList<>();
        bus.subscribe(TOPIC, m -> { throw new IllegalStateException("boom"); });
        bus.subscribe(TOPIC, received::add);

        bus.publish(TOPIC, "K1", "payload");

        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("a throwing subscriber does not surface out of publish")
    void throwingSubscriberDoesNotEscapePublish() {
        bus.subscribe(TOPIC, m -> { throw new IllegalStateException("boom"); });

        assertThatCode(() -> bus.publish(TOPIC, "K1", "payload")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a throwing subscriber still sees the next message")
    void deliveryContinuesAfterAFailure() {
        List<Message> received = new ArrayList<>();
        bus.subscribe(TOPIC, m -> {
            received.add(m);
            throw new IllegalStateException("boom");
        });

        bus.publish(TOPIC, "K1", "first");
        bus.publish(TOPIC, "K2", "second");

        assertThat(received).hasSize(2);
    }

    @Test
    @DisplayName("a message is still recorded even when every subscriber throws")
    void messageIsRecordedDespiteSubscriberFailure() {
        bus.subscribe(TOPIC, m -> { throw new IllegalStateException("boom"); });

        bus.publish(TOPIC, "K1", "payload");

        assertThat(bus.consume(TOPIC)).hasSize(1);
    }

    // --- reset ----------------------------------------------------------

    @Test
    @DisplayName("reset clears every topic and restarts offsets")
    void resetClearsEverything() {
        bus.publish(TOPIC, "K1", "a");
        bus.publish(OTHER, "K2", "b");

        bus.reset();

        assertThat(bus.consume(TOPIC)).isEmpty();
        assertThat(bus.consume(OTHER)).isEmpty();

        bus.publish(TOPIC, "K3", "c");
        assertThat(bus.consume(TOPIC)).extracting(Message::offset).containsExactly(0L);
    }

    @Test
    @DisplayName("reset also drops subscribers, so a fresh scenario starts clean")
    void resetDropsSubscribers() {
        List<Message> received = new ArrayList<>();
        bus.subscribe(TOPIC, received::add);

        bus.reset();
        bus.publish(TOPIC, "K1", "payload");

        assertThat(received).isEmpty();
    }
}
