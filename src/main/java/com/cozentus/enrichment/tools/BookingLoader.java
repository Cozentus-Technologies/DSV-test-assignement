package com.cozentus.enrichment.tools;

import com.cozentus.enrichment.bus.InMemoryMessageBus;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.processor.Topics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Streams a JSON Lines file into a {@link MessageBus} (SPEC 9).
 *
 * <p>Targets the interface, so pointing it at a real Kafka producer needs no
 * change here. Lines are streamed rather than read into memory, so a 30 000-row
 * file costs no more than a 200-row one.
 */
public final class BookingLoader {

    /**
     * Finds the bookingId textually rather than by parsing, because a
     * deliberately malformed row still has to reach booking.flagged under its
     * own key. Tolerates whitespace around the colon.
     */
    private static final Pattern BOOKING_ID =
            Pattern.compile("\"bookingId\"\\s*:\\s*\"([^\"]+)\"");

    private BookingLoader() {
    }

    /** @return the number of non-blank lines published. */
    public static int load(Path file, String topic, MessageBus bus) throws IOException {
        int published = 0;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isBlank()) {
                    continue;
                }
                bus.publish(topic, bookingIdOf(line), line);
                published++;
            }
        }
        return published;
    }

    /** The row's bookingId, or {@code null} if none can be recovered. */
    public static String bookingIdOf(String row) {
        Matcher matcher = BOOKING_ID.matcher(row);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static void main(String[] args) throws IOException {
        int status = run(args, new InMemoryMessageBus());
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * The body of {@link #main}, taking the bus so it can be driven from a test.
     * Returns a process status rather than calling {@code System.exit}, which
     * would take the test JVM down with it.
     *
     * @return 0 on success, 2 when the arguments are unusable
     */
    static int run(String[] args, MessageBus bus) throws IOException {
        Path file = null;
        String topic = Topics.RAW;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--file" -> file = Path.of(args[++i]);
                case "--topic" -> topic = args[++i];
                default -> { }
            }
        }
        if (file == null) {
            System.err.println("Usage: BookingLoader --file <jsonl> [--topic booking.raw]");
            return 2;
        }
        int published = load(file, topic, bus);
        System.out.printf("Published %,d rows to %s%n", published, topic);
        return 0;
    }
}
