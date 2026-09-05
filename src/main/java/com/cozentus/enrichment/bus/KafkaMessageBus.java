package com.cozentus.enrichment.bus;

import com.cozentus.enrichment.config.KafkaConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * The {@link MessageBus} backed by a real broker (CH-01).
 *
 * <p>Confined to this package: nothing in {@code matcher/}, {@code enrich/},
 * {@code model/} or {@code processor/} changes to accommodate it.
 */
public final class KafkaMessageBus implements MessageBus, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(KafkaMessageBus.class.getName());
    private static final Duration POLL = Duration.ofMillis(200);

    private final KafkaConfig config;
    private final KafkaProducer<String, String> producer;
    private final List<PollLoop> loops = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KafkaMessageBus(KafkaConfig config) {
        this.config = config;
        this.producer = new KafkaProducer<>(producerProperties());
    }

    private Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        properties.put(ProducerConfig.RETRIES_CONFIG, 3);
        // Bounded, so an unreachable broker fails the publish rather than hanging.
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        return properties;
    }

    private Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, config.consumerGroup());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // CH-08: commit only after the handler has succeeded.
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return properties;
    }

    /** Synchronous send: a publish failure surfaces here rather than being lost. */
    @Override
    public void publish(String topic, String key, String payload, Map<String, String> headers) {
        List<Header> recordHeaders = new ArrayList<>();
        headers.forEach((name, value) -> recordHeaders.add(
                new RecordHeader(name, value == null ? null : value.getBytes(StandardCharsets.UTF_8))));
        try {
            producer.send(new ProducerRecord<>(topic, null, key, payload, recordHeaders)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing to " + topic, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed publishing to " + topic + " key " + key, e.getCause());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed publishing to " + topic + " key " + key, e);
        }
    }

    /**
     * Kafka has no snapshot of a topic's history. A consumer group has a
     * position, not a history, so this cannot be implemented honestly and is
     * refused rather than approximated. The test suite must not depend on it.
     */
    @Override
    public List<Message> consume(String topic) {
        throw new UnsupportedOperationException(
                "KafkaMessageBus has no history to return: a consumer group has a position, "
                        + "not a history. Subscribe and collect, or use the test harness.");
    }

    @Override
    public void subscribe(String topic, Consumer<Message> handler) {
        PollLoop loop = new PollLoop(topic, handler);
        loops.add(loop);
        loop.start();
    }

    /** Not meaningful against a real broker; topics are provisioned externally. */
    @Override
    public void reset() {
        throw new UnsupportedOperationException(
                "Reset a real broker by recreating topics, not through the bus.");
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        loops.forEach(PollLoop::stop);
        loops.clear();
        producer.close(Duration.ofSeconds(5));
    }

    /** One consumer on one topic, polled on a virtual thread. */
    private final class PollLoop {

        private final String topic;
        private final Consumer<Message> handler;
        private final KafkaConsumer<String, String> consumer;
        private volatile Thread thread;

        PollLoop(String topic, Consumer<Message> handler) {
            this.topic = topic;
            this.handler = handler;
            this.consumer = new KafkaConsumer<>(consumerProperties());
        }

        void start() {
            consumer.subscribe(List.of(topic));
            thread = Thread.ofVirtual().name("kafka-poll-" + topic).start(this::run);
        }

        private void run() {
            try {
                while (running.get()) {
                    ConsumerRecords<String, String> records = consumer.poll(POLL);
                    Map<TopicPartition, OffsetAndMetadata> committable = new HashMap<>();

                    for (ConsumerRecord<String, String> record : records) {
                        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                        try {
                            handler.accept(toMessage(record));
                        } catch (RuntimeException e) {
                            // Isolated: one bad message must not stop the loop or
                            // starve later ones. CH-08 means its offset is not
                            // committed, so it is redelivered on the next poll.
                            LOG.log(System.Logger.Level.WARNING,
                                    "Handler on " + topic + " threw for key " + record.key(), e);
                            continue;
                        }
                        committable.put(partition, new OffsetAndMetadata(record.offset() + 1));
                    }
                    if (!committable.isEmpty()) {
                        consumer.commitSync(committable);
                    }
                }
            } catch (WakeupException expected) {
                // close() was called.
            } finally {
                consumer.close(Duration.ofSeconds(5));
            }
        }

        void stop() {
            consumer.wakeup();
            Thread poller = thread;
            if (poller != null) {
                try {
                    poller.join(Duration.ofSeconds(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static Message toMessage(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new HashMap<>();
        record.headers().forEach(header -> headers.put(
                header.key(),
                header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8)));

        return new Message(record.topic(), record.key(), record.value(),
                record.offset(), Instant.ofEpochMilli(record.timestamp()), headers);
    }
}
