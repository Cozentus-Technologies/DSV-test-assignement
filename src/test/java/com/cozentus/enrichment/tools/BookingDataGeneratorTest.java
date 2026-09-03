package com.cozentus.enrichment.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.CityReference;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.FlagReason;
import com.cozentus.enrichment.model.FlaggedBooking;
import com.cozentus.enrichment.processor.Topics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC 9. The oracle must be produced by the matcher, never guessed. */
class BookingDataGeneratorTest {

    @TempDir
    Path dir;

    private static BookingDataGenerator generator(long seed) {
        return new BookingDataGenerator(CityReference.fromClasspath(), seed,
                BookingDataGenerator.Mix.defaultMix());
    }

    private List<String> rows(Path file) throws IOException {
        return Files.readAllLines(file);
    }

    // --- determinism ----------------------------------------------------

    @Test
    @DisplayName("the same seed produces byte-identical output")
    void sameSeedIsReproducible() throws IOException {
        Path a = dir.resolve("a.jsonl");
        Path b = dir.resolve("b.jsonl");

        generator(42).generateTo(a, 200);
        generator(42).generateTo(b, 200);

        assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
    }

    @Test
    @DisplayName("the same seed produces an identical oracle")
    void sameSeedProducesTheSameOracle() throws IOException {
        Path a = dir.resolve("a.jsonl");
        Path b = dir.resolve("b.jsonl");

        var first = generator(42).generateTo(a, 200);
        var second = generator(42).generateTo(b, 200);

        assertThat(JsonSupport.write(first)).isEqualTo(JsonSupport.write(second));
    }

    @Test
    @DisplayName("a different seed produces different output")
    void differentSeedsDiffer() throws IOException {
        Path a = dir.resolve("a.jsonl");
        Path b = dir.resolve("b.jsonl");

        generator(42).generateTo(a, 200);
        generator(43).generateTo(b, 200);

        assertThat(Files.readAllBytes(a)).isNotEqualTo(Files.readAllBytes(b));
    }

    // --- row shape ------------------------------------------------------

    @Test
    @DisplayName("one JSON Lines row is written per booking")
    void oneRowPerBooking() throws IOException {
        Path out = dir.resolve("out.jsonl");

        generator(42).generateTo(out, 200);

        assertThat(rows(out)).hasSize(200);
    }

    @Test
    @DisplayName("bookingIds are BKG-%05d and unique")
    void bookingIdsAreFormattedAndUnique() throws IOException {
        Path out = dir.resolve("out.jsonl");
        var oracle = generator(42).generateTo(out, 200);

        assertThat(oracle.byId()).hasSize(200);
        assertThat(oracle.byId().keySet()).allMatch(id -> id.matches("BKG-\\d{5}"));
        assertThat(oracle.byId()).containsKey("BKG-00000").containsKey("BKG-00199");
    }

    @Test
    @DisplayName("non-city fields stay inside their allowed values")
    void nonCityFieldsAreWellFormed() throws IOException {
        Path out = dir.resolve("out.jsonl");
        generator(42).generateTo(out, 200);
        LocalDate base = BookingDataGenerator.BASE_DATE;

        for (String row : rows(out)) {
            Booking booking;
            try {
                booking = JsonSupport.read(row, Booking.class);
            } catch (UncheckedIOException malformed) {
                continue; // malformed rows are deliberately unparseable
            }
            assertThat(booking.mode()).isIn("ROAD", "RAIL", "AIR", "OCEAN");
            assertThat(booking.shipper()).isNotBlank();
            assertThat(ChronoUnit.DAYS.between(base, LocalDate.parse(booking.requestedDate())))
                    .isBetween(-90L, 90L);
        }
    }

    // --- the oracle is derived from the matcher, not guessed -------------

    @Test
    @DisplayName("every oracle entry agrees with running the real enricher on that row")
    void oracleAgreesWithTheEnricher() throws IOException {
        Path out = dir.resolve("out.jsonl");
        var oracle = generator(42).generateTo(out, 200);
        var enricher = new BookingEnricher(new CityMatcher(CityReference.fromClasspath()));

        for (String row : rows(out)) {
            Booking booking;
            try {
                booking = JsonSupport.read(row, Booking.class);
            } catch (UncheckedIOException malformed) {
                continue;
            }
            var expected = oracle.byId().get(booking.bookingId());
            var actual = enricher.enrich(booking);

            if (actual instanceof EnrichedBooking enriched) {
                assertThat(expected.topic()).isEqualTo(Topics.ENRICHED);
                assertThat(expected.origin()).isEqualTo(enriched.origin());
                assertThat(expected.destination()).isEqualTo(enriched.destination());
                assertThat(expected.reasons()).isEmpty();
            } else {
                var flagged = (FlaggedBooking) actual;
                assertThat(expected.topic()).isEqualTo(Topics.FLAGGED);
                assertThat(expected.reasons()).isEqualTo(flagged.reasons());
            }
        }
    }

    @Test
    @DisplayName("totals add up to the row count and match the per-id entries")
    void totalsAreConsistent() throws IOException {
        Path out = dir.resolve("out.jsonl");
        var oracle = generator(42).generateTo(out, 200);

        assertThat(oracle.totals().enriched() + oracle.totals().flagged()).isEqualTo(200);
        assertThat(oracle.byId().values().stream()
                .filter(e -> e.topic().equals(Topics.ENRICHED)).count())
                .isEqualTo(oracle.totals().enriched());
        assertThat(oracle.byId().values().stream()
                .filter(e -> e.topic().equals(Topics.FLAGGED)).count())
                .isEqualTo(oracle.totals().flagged());
    }

    @Test
    @DisplayName("byReason totals match the reasons listed per id")
    void reasonTotalsAreConsistent() throws IOException {
        Path out = dir.resolve("out.jsonl");
        var oracle = generator(42).generateTo(out, 200);

        for (FlagReason reason : FlagReason.values()) {
            long counted = oracle.byId().values().stream()
                    .filter(e -> e.reasons().contains(reason)).count();
            assertThat(oracle.totals().byReason().getOrDefault(reason, 0))
                    .as("count of %s", reason)
                    .isEqualTo((int) counted);
        }
    }

    // --- mix ------------------------------------------------------------

    @Test
    @DisplayName("an all-malformed mix produces unparseable rows flagged MALFORMED_MESSAGE")
    void allMalformedMix() throws IOException {
        Path out = dir.resolve("out.jsonl");
        var oracle = new BookingDataGenerator(CityReference.fromClasspath(), 42,
                new BookingDataGenerator.Mix(0, 0, 100)).generateTo(out, 50);

        assertThat(oracle.totals().flagged()).isEqualTo(50);
        assertThat(oracle.totals().byReason().get(FlagReason.MALFORMED_MESSAGE)).isEqualTo(50);
        assertThat(rows(out)).allSatisfy(row ->
                assertThat(catchParse(row)).as("row should not parse: %s", row).isNotNull());
    }

    @Test
    @DisplayName("an all-flaggable mix produces no enriched rows")
    void allFlaggableMix() throws IOException {
        Path out = dir.resolve("out.jsonl");
        var oracle = new BookingDataGenerator(CityReference.fromClasspath(), 42,
                new BookingDataGenerator.Mix(0, 100, 0)).generateTo(out, 50);

        assertThat(oracle.totals().enriched()).isZero();
        assertThat(oracle.totals().flagged()).isEqualTo(50);
    }

    @Test
    @DisplayName("a malformed row still carries a recoverable bookingId")
    void malformedRowsKeepTheirId() throws IOException {
        Path out = dir.resolve("out.jsonl");
        var oracle = new BookingDataGenerator(CityReference.fromClasspath(), 42,
                new BookingDataGenerator.Mix(0, 0, 100)).generateTo(out, 10);

        assertThat(oracle.byId()).hasSize(10);
        for (String row : rows(out)) {
            assertThat(BookingLoader.bookingIdOf(row)).matches("BKG-\\d{5}");
        }
    }

    private static Exception catchParse(String row) {
        try {
            JsonSupport.read(row, Booking.class);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
