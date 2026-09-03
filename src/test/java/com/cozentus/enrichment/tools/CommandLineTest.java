package com.cozentus.enrichment.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cozentus.enrichment.bus.InMemoryMessageBus;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.processor.Topics;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CLI surface documented in the README. A broken --mix parser would
 * silently produce data with the wrong distribution, so it is worth pinning.
 */
class CommandLineTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    /** These are console tools; capturing keeps the `mvn -q verify` gate silent. */
    @BeforeEach
    void captureConsole() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreConsole() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    // --- BookingDataGenerator -------------------------------------------

    @Test
    @DisplayName("--mix parses all three buckets regardless of order")
    void mixParsesAllBuckets() {
        assertThat(BookingDataGenerator.parseMix("recoverable=70,flaggable=25,malformed=5"))
                .isEqualTo(new BookingDataGenerator.Mix(70, 25, 5));
        assertThat(BookingDataGenerator.parseMix("malformed=5,recoverable=70,flaggable=25"))
                .isEqualTo(new BookingDataGenerator.Mix(70, 25, 5));
    }

    @Test
    @DisplayName("--mix tolerates spaces around the values")
    void mixToleratesSpaces() {
        assertThat(BookingDataGenerator.parseMix(" recoverable = 70 , flaggable = 25 , malformed = 5 "))
                .isEqualTo(new BookingDataGenerator.Mix(70, 25, 5));
    }

    @Test
    @DisplayName("an unknown mix bucket is rejected rather than silently ignored")
    void unknownMixBucketIsRejected() {
        assertThatThrownBy(() -> BookingDataGenerator.parseMix("recoverable=70,nonsense=30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonsense");
    }

    @Test
    @DisplayName("a mix with every weight zero is rejected")
    void allZeroMixIsRejected() {
        assertThatThrownBy(() -> new BookingDataGenerator.Mix(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative mix weight is rejected")
    void negativeMixWeightIsRejected() {
        assertThatThrownBy(() -> new BookingDataGenerator.Mix(-1, 50, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the documented generator command writes both the rows and the oracle")
    void generatorMainWritesRowsAndOracle() throws Exception {
        Path rows = dir.resolve("gen.jsonl");

        BookingDataGenerator.main(new String[]{
                "--count", "50", "--seed", "42", "--out", rows.toString(),
                "--mix", "recoverable=70,flaggable=25,malformed=5"});

        assertThat(rows).exists();
        assertThat(Files.readAllLines(rows)).hasSize(50);
        assertThat(Path.of(rows + ".expected.json")).exists();
        assertThat(stdout()).contains("Wrote 50 rows").contains("Oracle:");
    }

    @Test
    @DisplayName("the generator main is reproducible for a given seed")
    void generatorMainIsReproducible() throws Exception {
        Path first = dir.resolve("first.jsonl");
        Path second = dir.resolve("second.jsonl");

        BookingDataGenerator.main(new String[]{"--count", "50", "--seed", "42", "--out", first.toString()});
        BookingDataGenerator.main(new String[]{"--count", "50", "--seed", "42", "--out", second.toString()});

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
    }

    // --- BookingLoader ---------------------------------------------------

    @Test
    @DisplayName("the loader publishes every row of the file it is given")
    void loaderRunPublishesEveryRow() throws IOException {
        Path file = dir.resolve("in.jsonl");
        Files.write(file, List.of(
                "{\"bookingId\":\"BKG-00001\",\"origin\":\"Mumbai\",\"destination\":\"Pune\"}",
                "{\"bookingId\":\"BKG-00002\",\"origin\":\"Warsaw\",\"destination\":\"Pune\"}"));
        MessageBus bus = new InMemoryMessageBus();

        int status = BookingLoader.run(new String[]{"--file", file.toString()}, bus);

        assertThat(status).isZero();
        assertThat(bus.consume(Topics.RAW)).hasSize(2);
        assertThat(stdout()).contains("Published 2 rows to booking.raw");
    }

    @Test
    @DisplayName("the loader honours an explicit --topic")
    void loaderHonoursExplicitTopic() throws IOException {
        Path file = dir.resolve("in.jsonl");
        Files.write(file, List.of("{\"bookingId\":\"BKG-00001\"}"));
        MessageBus bus = new InMemoryMessageBus();

        BookingLoader.run(new String[]{"--file", file.toString(), "--topic", "other.topic"}, bus);

        assertThat(bus.consume("other.topic")).hasSize(1);
        assertThat(bus.consume(Topics.RAW)).isEmpty();
    }

    @Test
    @DisplayName("the loader reports a non-zero status when --file is missing")
    void loaderRejectsMissingFileArgument() throws IOException {
        assertThat(BookingLoader.run(new String[]{}, new InMemoryMessageBus())).isNotZero();
        assertThat(BookingLoader.run(new String[]{"--topic", "booking.raw"}, new InMemoryMessageBus()))
                .isNotZero();
        assertThat(stderr()).contains("Usage: BookingLoader --file");
    }
}
