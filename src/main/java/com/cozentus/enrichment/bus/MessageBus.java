package com.cozentus.enrichment.bus;

import java.util.List;
import java.util.function.Consumer;

/**
 * The seam between the harness and messaging (SPEC 7).
 *
 * <p>The processor depends on this interface alone, so swapping the in-memory
 * fake for real Kafka needs no change above this line.
 */
public interface MessageBus {

    void publish(String topic, String key, String payload);

    /** Snapshot of every message published to the topic so far. */
    List<Message> consume(String topic);

    /** Handlers see only messages published after subscribing; there is no replay. */
    void subscribe(String topic, Consumer<Message> handler);

    void reset();
}
