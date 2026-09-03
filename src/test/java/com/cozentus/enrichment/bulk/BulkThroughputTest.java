package com.cozentus.enrichment.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.bus.InMemoryMessageBus;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.CityReference;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.FlagReason;
import com.cozentus.enrichment.model.FlaggedBooking;
import com.cozentus.enrichment.processor.EnrichmentProcessor;
import com.cozentus.enrichment.processor.Topics;
import com.cozentus.enrichment.tools.BookingDataGenerator;
import com.cozentus.enrichment.tools.BookingLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC 8.4. Excluded from the default build; run with {@code -Pbulk}.
 *
 * <p>Override with {@code -Dbulk.count=30000} and {@code -Dbulk.seed=42}.
 */
@Tag("bulk")
class BulkThroughputTest {

    private static final int SAMPLE_SIZE = 500;
    private static final long SAMPLE_SEED = 7L;

    @TempDir
    Path dir;

    @Test
    @DisplayName("a bulk run lands every booking exactly where the oracle predicts")
    void bulkRunMatchesTheOracle() throws IOException {
        int count = Integer.getInteger("bulk.count", 20_000);
        long seed = Long.getLong("bulk.seed", 42L);

        CityReference reference = CityReference.fromClasspath();
        Path file = dir.resolve("bulk.jsonl");

        long generateStart = System.nanoTime();
        BookingDataGenerator.Oracle oracle =
                new BookingDataGenerator(reference, seed, BookingDataGenerator.Mix.defaultMix())
                        .generateTo(file, count);
        long generateMillis = millisSince(generateStart);

        MessageBus bus = new InMemoryMessageBus();
        new EnrichmentProcessor(bus, new BookingEnricher(new CityMatcher(reference))).start();

        // Delivery is synchronous, so when load returns every message is processed.
        long processStart = System.nanoTime();
        int loaded = BookingLoader.load(file, Topics.RAW, bus);
        long processMillis = millisSince(processStart);

        List<Message> enriched = bus.consume(Topics.ENRICHED);
        List<Message> flagged = bus.consume(Topics.FLAGGED);

        report(count, seed, generateMillis, processMillis, enriched.size(), flagged.size());

        assertThat(loaded).as("rows loaded").isEqualTo(count);

        // 1. Nothing lost, nothing duplicated.
        assertThat(enriched.size() + flagged.size())
                .as("every booking reached exactly one output topic")
                .isEqualTo(count);

        // 2. No bookingId on both topics.
        Set<String> enrichedKeys = new HashSet<>(keys(enriched));
        Set<String> flaggedKeys = new HashSet<>(keys(flagged));
        assertThat(enrichedKeys).as("no bookingId on both topics")
                .doesNotContainAnyElementsOf(flaggedKeys);
        assertThat(enrichedKeys).hasSize(enriched.size());
        assertThat(flaggedKeys).hasSize(flagged.size());

        // 3. Per-topic counts equal the oracle totals.
        assertThat(enriched).as("enriched total").hasSize(oracle.totals().enriched());
        assertThat(flagged).as("flagged total").hasSize(oracle.totals().flagged());

        // 4. Per-reason counts equal the oracle totals.
        Map<FlagReason, Integer> actualByReason = new EnumMap<>(FlagReason.class);
        for (Message message : flagged) {
            for (FlagReason reason : JsonSupport.read(message.payload(), FlaggedBooking.class).reasons()) {
                actualByReason.merge(reason, 1, Integer::sum);
            }
        }
        for (FlagReason reason : FlagReason.values()) {
            assertThat(actualByReason.getOrDefault(reason, 0))
                    .as("count of %s", reason)
                    .isEqualTo(oracle.totals().byReason().getOrDefault(reason, 0));
        }

        // 5. A random sample matches the oracle field by field.
        Map<String, Message> enrichedByKey = byKey(enriched);
        Map<String, Message> flaggedByKey = byKey(flagged);
        for (String bookingId : sampleIds(oracle, count)) {
            var expected = oracle.byId().get(bookingId);
            if (expected.topic().equals(Topics.ENRICHED)) {
                Message message = enrichedByKey.get(bookingId);
                assertThat(message).as("enriched message for %s", bookingId).isNotNull();
                EnrichedBooking booking = JsonSupport.read(message.payload(), EnrichedBooking.class);
                assertThat(booking.origin()).as("origin of %s", bookingId).isEqualTo(expected.origin());
                assertThat(booking.destination()).as("destination of %s", bookingId)
                        .isEqualTo(expected.destination());
            } else {
                Message message = flaggedByKey.get(bookingId);
                assertThat(message).as("flagged message for %s", bookingId).isNotNull();
                FlaggedBooking booking = JsonSupport.read(message.payload(), FlaggedBooking.class);
                assertThat(booking.reasons()).as("reasons of %s", bookingId)
                        .isEqualTo(expected.reasons());
            }
        }

        // 6. Every enriched city is a canonical reference name.
        List<String> canonical = reference.names();
        for (Message message : enriched) {
            EnrichedBooking booking = JsonSupport.read(message.payload(), EnrichedBooking.class);
            assertThat(booking.origin()).as("origin of %s", message.key()).isIn(canonical);
            assertThat(booking.destination()).as("destination of %s", message.key()).isIn(canonical);
        }
    }

    /** Seeded, so a failure names the same ids on a rerun. */
    private static List<String> sampleIds(BookingDataGenerator.Oracle oracle, int count) {
        List<String> all = new ArrayList<>(oracle.byId().keySet());
        Random random = new Random(SAMPLE_SEED);
        int size = Math.min(SAMPLE_SIZE, count);
        List<String> sample = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            sample.add(all.get(random.nextInt(all.size())));
        }
        return sample;
    }

    private static Map<String, Message> byKey(List<Message> messages) {
        Map<String, Message> byKey = new java.util.HashMap<>(messages.size());
        messages.forEach(message -> byKey.put(message.key(), message));
        return byKey;
    }

    private static List<String> keys(List<Message> messages) {
        return messages.stream().map(Message::key).toList();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** SPEC 8.4 step 4. */
    private static void report(int count, long seed, long generateMillis, long processMillis,
                               int enriched, int flagged) {
        double perSecond = processMillis == 0 ? count : count * 1000.0 / processMillis;
        System.out.printf("%n=== bulk run =================================%n");
        System.out.printf("  messages          %,d (seed %d)%n", count, seed);
        System.out.printf("  generate + oracle %,d ms%n", generateMillis);
        System.out.printf("  load + process    %,d ms%n", processMillis);
        System.out.printf("  throughput        %,.0f msg/sec%n", perSecond);
        System.out.printf("  enriched          %,d%n", enriched);
        System.out.printf("  flagged           %,d%n", flagged);
        System.out.printf("==============================================%n%n");
    }
}
