package com.cozentus.enrichment.tools;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.CityReference;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.FlagReason;
import com.cozentus.enrichment.model.FlaggedBooking;
import com.cozentus.enrichment.processor.Topics;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates reproducible booking data and the oracle that predicts where every
 * row will land (SPEC 9).
 *
 * <p>The oracle is produced by running the real {@link BookingEnricher} over each
 * generated row — never by inferring an outcome from the corruption that was
 * applied. A DROP_CHAR that happens to destroy a name is labelled flagged
 * because the matcher says so, so the data and the contract cannot drift apart.
 */
public final class BookingDataGenerator {

    public static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 5);

    private static final int DATE_SPREAD_DAYS = 90;
    private static final String ID_FORMAT = "BKG-%05d";

    private static final List<String> SHIPPERS =
            List.of("ABC Logistics", "Vertex Freight", "Northwind Cargo",
                    "Meridian Shipping", "Solaris Transport");
    private static final List<String> MODES = List.of("ROAD", "RAIL", "AIR", "OCEAN");
    private static final List<String> FOREIGN_CITIES =
            List.of("Warsaw", "Lisbon", "Dubai", "Singapore", "Berlin");

    /** SPEC 9: which corruption bucket a field is drawn from. */
    public record Mix(int recoverable, int flaggable, int malformed) {

        public static Mix defaultMix() {
            return new Mix(70, 25, 5);
        }

        public Mix {
            if (recoverable < 0 || flaggable < 0 || malformed < 0
                    || recoverable + flaggable + malformed == 0) {
                throw new IllegalArgumentException("Mix weights must be non-negative and not all zero");
            }
        }
    }

    /** Corruptions, grouped by the bucket that selects them. */
    private enum Corruption {
        NONE, UPPER, LOWER, TRIM_SPACES, DROP_CHAR, SWAP_CHARS, DOUBLE_CHAR, FOREIGN, EMPTY;

        static final List<Corruption> RECOVERABLE =
                List.of(NONE, UPPER, LOWER, TRIM_SPACES, DROP_CHAR, SWAP_CHARS, DOUBLE_CHAR);
        static final List<Corruption> FLAGGABLE = List.of(FOREIGN, EMPTY);
    }

    public record Expectation(String topic, String origin, String destination,
                              List<FlagReason> reasons) {
    }

    public record Totals(int enriched, int flagged, Map<FlagReason, Integer> byReason) {
    }

    public record Oracle(Totals totals, Map<String, Expectation> byId) {
    }

    private final CityReference reference;
    private final long seed;
    private final Mix mix;
    private final BookingEnricher enricher;

    public BookingDataGenerator(CityReference reference, long seed, Mix mix) {
        this.reference = reference;
        this.seed = seed;
        this.mix = mix;
        this.enricher = new BookingEnricher(new CityMatcher(reference));
    }

    /** Streams {@code count} rows to {@code out} and returns the matching oracle. */
    public Oracle generateTo(Path out, int count) throws IOException {
        Random random = new Random(seed);
        List<String> cities = reference.names();

        Map<String, Expectation> byId = new LinkedHashMap<>();
        Map<FlagReason, Integer> byReason = new EnumMap<>(FlagReason.class);
        int enriched = 0;
        int flagged = 0;

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < count; i++) {
                String bookingId = ID_FORMAT.formatted(i);
                Expectation expectation;

                if (pick(random, mix.malformed(),
                        mix.recoverable() + mix.flaggable() + mix.malformed())) {
                    writer.write(malformedRow(bookingId));
                    expectation = new Expectation(Topics.FLAGGED, null, null,
                            List.of(FlagReason.MALFORMED_MESSAGE));
                } else {
                    Booking booking = new Booking(
                            bookingId,
                            SHIPPERS.get(random.nextInt(SHIPPERS.size())),
                            corrupt(random, cities.get(random.nextInt(cities.size()))),
                            corrupt(random, cities.get(random.nextInt(cities.size()))),
                            MODES.get(random.nextInt(MODES.size())),
                            requestedDate(random));
                    writer.write(JsonSupport.write(booking));
                    expectation = expect(booking);
                }
                writer.newLine();

                byId.put(bookingId, expectation);
                if (expectation.topic().equals(Topics.ENRICHED)) {
                    enriched++;
                } else {
                    flagged++;
                }
                for (FlagReason reason : expectation.reasons()) {
                    byReason.merge(reason, 1, Integer::sum);
                }
            }
        }
        return new Oracle(new Totals(enriched, flagged, byReason), byId);
    }

    /** SPEC 9 step 4: the matcher decides, the corruption name never does. */
    private Expectation expect(Booking booking) {
        return switch (enricher.enrich(booking)) {
            case EnrichedBooking e ->
                    new Expectation(Topics.ENRICHED, e.origin(), e.destination(), List.of());
            case FlaggedBooking f ->
                    new Expectation(Topics.FLAGGED, null, null, f.reasons());
        };
    }

    /**
     * A truncated row: valid JSON up to the point it stops, so Jackson rejects it
     * while the bookingId is still textually recoverable for the message key.
     */
    private static String malformedRow(String bookingId) {
        return "{\"bookingId\":\"" + bookingId + "\",\"shipper\":\"ABC Logistics\",\"origin\":\"Mum";
    }

    private String corrupt(Random random, String city) {
        boolean flaggable = pick(random, mix.flaggable(), mix.recoverable() + mix.flaggable());
        List<Corruption> bucket = flaggable ? Corruption.FLAGGABLE : Corruption.RECOVERABLE;
        Corruption corruption = bucket.get(random.nextInt(bucket.size()));

        return switch (corruption) {
            case NONE -> city;
            case UPPER -> city.toUpperCase(Locale.ROOT);
            case LOWER -> city.toLowerCase(Locale.ROOT);
            case TRIM_SPACES -> "  " + city + "  ";
            case DROP_CHAR -> dropChar(random, city);
            case SWAP_CHARS -> swapChars(random, city);
            case DOUBLE_CHAR -> doubleChar(random, city);
            case FOREIGN -> FOREIGN_CITIES.get(random.nextInt(FOREIGN_CITIES.size()));
            case EMPTY -> "";
        };
    }

    private static String dropChar(Random random, String value) {
        int at = random.nextInt(value.length());
        return value.substring(0, at) + value.substring(at + 1);
    }

    private static String swapChars(Random random, String value) {
        if (value.length() < 2) {
            return value;
        }
        int at = random.nextInt(value.length() - 1);
        return value.substring(0, at) + value.charAt(at + 1) + value.charAt(at)
                + value.substring(at + 2);
    }

    private static String doubleChar(Random random, String value) {
        int at = random.nextInt(value.length());
        return value.substring(0, at + 1) + value.charAt(at) + value.substring(at + 1);
    }

    private static String requestedDate(Random random) {
        return BASE_DATE.plusDays(random.nextInt(2 * DATE_SPREAD_DAYS + 1) - DATE_SPREAD_DAYS)
                .toString();
    }

    /** True with probability {@code weight/total}, drawing exactly one number. */
    private static boolean pick(Random random, int weight, int total) {
        return total > 0 && random.nextInt(total) < weight;
    }

    public static void main(String[] args) throws IOException {
        int count = 20_000;
        long seed = 42L;
        Path out = Path.of("data/bookings-20k.jsonl");
        Mix mix = Mix.defaultMix();

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--count" -> count = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--mix" -> mix = parseMix(args[++i]);
                default -> { }
            }
        }

        BookingDataGenerator generator =
                new BookingDataGenerator(CityReference.fromClasspath(), seed, mix);
        long startedAt = System.nanoTime();
        Oracle oracle = generator.generateTo(out, count);
        Path oraclePath = Path.of(out + ".expected.json");
        Files.writeString(oraclePath,
                JsonSupport.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(oracle));

        System.out.printf("Wrote %,d rows to %s in %,d ms%n",
                count, out, (System.nanoTime() - startedAt) / 1_000_000);
        System.out.printf("Oracle: %s (enriched %,d, flagged %,d)%n",
                oraclePath, oracle.totals().enriched(), oracle.totals().flagged());
        oracle.totals().byReason().forEach((reason, n) -> System.out.printf("  %-28s %,d%n", reason, n));
    }

    /** Parses {@code recoverable=70,flaggable=25,malformed=5}. Package-private for testing. */
    static Mix parseMix(String spec) {
        int recoverable = 0;
        int flaggable = 0;
        int malformed = 0;
        for (String part : spec.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            int value = Integer.parseInt(kv[1].trim());
            switch (kv[0].trim()) {
                case "recoverable" -> recoverable = value;
                case "flaggable" -> flaggable = value;
                case "malformed" -> malformed = value;
                default -> throw new IllegalArgumentException("Unknown mix bucket: " + kv[0]);
            }
        }
        return new Mix(recoverable, flaggable, malformed);
    }
}
