package com.cozentus.enrichment.bus;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The seam between the harness and messaging (SPEC 7).
 *
 * <p>The processor depends on this interface alone, so swapping the in-memory
 * fake for real Kafka needs no change above this line.
 */
public interface MessageBus {

    void publish(String topic, String key, String payload, Map<String, String> headers);

    /** Publish with no headers. */
    default void publish(String topic, String key, String payload) {
        publish(topic, key, payload, Map.of());
    }

    /**
     * Snapshot of every message published to the topic so far.
     *
     * @throws UnsupportedOperationException on implementations backed by a real
     *         broker, where a consumer group has a position rather than a history
     */
    List<Message> consume(String topic);

    /** Handlers see only messages published after subscribing; there is no replay. */
    void subscribe(String topic, Consumer<Message> handler);

    void reset();
}
