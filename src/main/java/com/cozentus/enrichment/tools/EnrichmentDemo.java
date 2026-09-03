package com.cozentus.enrichment.tools;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.bus.InMemoryMessageBus;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.CityReference;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.model.FlagReason;
import com.cozentus.enrichment.model.FlaggedBooking;
import com.cozentus.enrichment.processor.EnrichmentProcessor;
import com.cozentus.enrichment.processor.Topics;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Runs one booking, or a whole file, through a real {@link EnrichmentProcessor}
 * and prints where it landed.
 *
 * <p>Exists so the harness can be demonstrated without reading test output. It
 * is a developer tool: it asserts nothing, and the test suite remains the place
 * where behaviour is actually verified.
 *
 * <pre>
 *   --origin Mumbi --destination "now delhi"    one booking
 *   --file data/bookings-sample.jsonl           a whole file
 *   --cities "Mumbai,New Delhi,...,Delhi"       override the reference list
 * </pre>
 */
public final class EnrichmentDemo {

    private static final String DEMO_ID = "BKG-DEMO";

    private EnrichmentDemo() {
    }

    /**
     * @param out where the report goes, so a test can capture it
     * @return 0 on success, 2 when the arguments are unusable, 3 on an I/O failure
     */
    static int run(String[] args, PrintStream out) {
        String origin = null;
        String destination = null;
        String file = null;
        String cities = null;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--origin" -> origin = args[++i];
                case "--destination" -> destination = args[++i];
                case "--file" -> file = args[++i];
                case "--cities" -> cities = args[++i];
                default -> { }
            }
        }

        CityReference reference = cities == null
                ? CityReference.fromClasspath()
                : CityReference.of(Arrays.stream(cities.split(",")).map(String::trim).toArray(String[]::new));

        if (file != null) {
            return replayFile(Path.of(file), reference, out);
        }
        if (origin != null && destination != null) {
            return replayOne(origin, destination, reference, out);
        }
        return usage(out);
    }

    private static int replayOne(String origin, String destination,
                                 CityReference reference, PrintStream out) {
        MessageBus bus = startProcessor(reference);
        Booking booking = new Booking(DEMO_ID, "ABC Logistics", origin, destination,
                "ROAD", "2026-09-05");

        out.printf("in   origin=%s  destination=%s%n%n",
                quote(origin), quote(destination));
        bus.publish(Topics.RAW, DEMO_ID, JsonSupport.write(booking));

        for (String topic : List.of(Topics.ENRICHED, Topics.FLAGGED)) {
            for (Message message : bus.consume(topic)) {
                out.printf("out  %s%n%n%s%n", topic, pretty(message.payload()));
            }
        }
        return 0;
    }

    private static int replayFile(Path file, CityReference reference, PrintStream out) {
        if (!Files.exists(file)) {
            out.printf("No such file: %s%n", file);
            return 3;
        }
        // A sample file contains deliberately malformed rows. The processor logs
        // each one at WARNING with a stack trace, which is right for production
        // and useless here: it buries the summary under pages of noise that look
        // like failures. Quietened for the duration, and the count is reported
        // below instead, so nothing is hidden.
        java.util.logging.Logger enrichmentLog =
                java.util.logging.Logger.getLogger("com.cozentus.enrichment");
        java.util.logging.Level previous = enrichmentLog.getLevel();
        enrichmentLog.setLevel(java.util.logging.Level.SEVERE);

        MessageBus bus = startProcessor(reference);
        int loaded;
        try {
            loaded = BookingLoader.load(file, Topics.RAW, bus);
        } catch (IOException e) {
            out.printf("Could not read %s: %s%n", file, e.getMessage());
            return 3;
        } finally {
            enrichmentLog.setLevel(previous);
        }

        List<Message> enriched = bus.consume(Topics.ENRICHED);
        List<Message> flagged = bus.consume(Topics.FLAGGED);

        out.printf("file              %s%n", file);
        out.printf("rows published    %,d%n%n", loaded);
        out.printf("  %-18s %,d%n", Topics.ENRICHED, enriched.size());
        out.printf("  %-18s %,d%n%n", Topics.FLAGGED, flagged.size());

        java.util.Map<FlagReason, Integer> byReason =
                new java.util.EnumMap<>(FlagReason.class);
        for (Message message : flagged) {
            for (FlagReason reason : JsonSupport.read(message.payload(), FlaggedBooking.class).reasons()) {
                byReason.merge(reason, 1, Integer::sum);
            }
        }
        byReason.forEach((reason, n) -> out.printf("  %-28s %,d%n", reason, n));
        out.println();

        enriched.stream().limit(2).forEach(m ->
                out.printf("first enriched    %s%n%s%n%n", m.key(), pretty(m.payload())));
        flagged.stream().limit(2).forEach(m ->
                out.printf("first flagged     %s%n%s%n%n", m.key(), pretty(m.payload())));
        return 0;
    }

    private static MessageBus startProcessor(CityReference reference) {
        MessageBus bus = new InMemoryMessageBus();
        new EnrichmentProcessor(bus, new BookingEnricher(new CityMatcher(reference))).start();
        return bus;
    }

    private static String pretty(String json) {
        try {
            return JsonSupport.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(JsonSupport.mapper().readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return json;
        }
    }

    private static String quote(String value) {
        return value == null ? "<null>" : "\"" + value + "\"";
    }

    private static int usage(PrintStream out) {
        out.println("""
                Usage:
                  EnrichmentDemo --origin <city> --destination <city> [--cities "A,B,C"]
                  EnrichmentDemo --file <bookings.jsonl>              [--cities "A,B,C"]

                Examples:
                  --origin Mumbi --destination "now delhi"
                  --origin Warsaw --destination Mumbai
                  --file data/bookings-sample.jsonl""");
        return 2;
    }

    public static void main(String[] args) {
        int status = run(args, System.out);
        if (status != 0) {
            System.exit(status);
        }
    }
}
