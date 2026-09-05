package com.cozentus.enrichment.bus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Deterministic stand-in for Kafka (SPEC 7).
 *
 * <p>Subscribers are invoked <em>synchronously</em> inside {@link #publish}: by
 * the time publish returns, all downstream processing is complete. That is what
 * lets a negative assertion — "nothing appeared on booking.flagged" — be exact
 * rather than a race against a timeout, and it is why the suite needs no
 * Awaitility, sleeps or timeouts.
 */
public final class InMemoryMessageBus implements MessageBus {

    private static final System.Logger LOG = System.getLogger(InMemoryMessageBus.class.getName());

    private final Map<String, Queue<Message>> topics = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> offsets = new ConcurrentHashMap<>();
    private final Map<String, Queue<Consumer<Message>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void publish(String topic, String key, String payload, Map<String, String> headers) {
        long offset = offsets.computeIfAbsent(topic, t -> new AtomicLong()).getAndIncrement();
        Message message = new Message(topic, key, payload, offset, Instant.now(), headers);

        // Recorded before delivery, so a throwing subscriber cannot lose the message.
        topics.computeIfAbsent(topic, t -> new ConcurrentLinkedQueue<>()).add(message);

        Queue<Consumer<Message>> handlers = subscribers.get(topic);
        if (handlers == null) {
            return;
        }
        for (Consumer<Message> handler : handlers) {
            try {
                handler.accept(message);
            } catch (RuntimeException e) {
                // Isolated deliberately: one bad subscriber must not starve the
                // others, and must not surface out of publish into the producer.
                LOG.log(System.Logger.Level.WARNING,
                        "Subscriber on " + topic + " threw for key " + key, e);
            }
        }
    }

    @Override
    public List<Message> consume(String topic) {
        Queue<Message> messages = topics.get(topic);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    @Override
    public void subscribe(String topic, Consumer<Message> handler) {
        subscribers.computeIfAbsent(topic, t -> new ConcurrentLinkedQueue<>()).add(handler);
    }

    /** Returns the bus to its initial state: no messages, no offsets, no subscribers. */
    @Override
    public void reset() {
        topics.clear();
        offsets.clear();
        subscribers.clear();
    }
}
