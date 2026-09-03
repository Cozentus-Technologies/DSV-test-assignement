package com.cozentus.enrichment.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.bus.InMemoryMessageBus;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.processor.Topics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC 9: streams a JSONL file into a MessageBus, keyed by bookingId. */
class BookingLoaderTest {

    @TempDir
    Path dir;

    private Path fileOf(String... lines) throws IOException {
        Path file = dir.resolve("in.jsonl");
        Files.write(file, List.of(lines));
        return file;
    }

    @Test
    @DisplayName("each line becomes one message on the target topic")
    void eachLineBecomesAMessage() throws IOException {
        Path file = fileOf(
                "{\"bookingId\":\"BKG-00001\",\"origin\":\"Mumbai\",\"destination\":\"Pune\"}",
                "{\"bookingId\":\"BKG-00002\",\"origin\":\"Warsaw\",\"destination\":\"Pune\"}");
        MessageBus bus = new InMemoryMessageBus();

        int loaded = BookingLoader.load(file, Topics.RAW, bus);

        assertThat(loaded).isEqualTo(2);
        assertThat(bus.consume(Topics.RAW)).hasSize(2);
    }

    @Test
    @DisplayName("the message key is the bookingId")
    void keyIsTheBookingId() throws IOException {
        Path file = fileOf("{\"bookingId\":\"BKG-00007\",\"origin\":\"Mumbai\",\"destination\":\"Pune\"}");
        MessageBus bus = new InMemoryMessageBus();

        BookingLoader.load(file, Topics.RAW, bus);

        assertThat(bus.consume(Topics.RAW)).singleElement()
                .extracting(Message::key).isEqualTo("BKG-00007");
    }

    @Test
    @DisplayName("the payload is the line verbatim, so malformed input stays malformed")
    void payloadIsTheLineVerbatim() throws IOException {
        String line = "{\"bookingId\":\"BKG-00003\",\"shipper\":\"ABC";
        Path file = fileOf(line);
        MessageBus bus = new InMemoryMessageBus();

        BookingLoader.load(file, Topics.RAW, bus);

        assertThat(bus.consume(Topics.RAW)).singleElement()
                .extracting(Message::payload).isEqualTo(line);
    }

    @Test
    @DisplayName("blank lines are skipped rather than published as empty messages")
    void blankLinesAreSkipped() throws IOException {
        Path file = fileOf("{\"bookingId\":\"BKG-00001\"}", "", "   ",
                           "{\"bookingId\":\"BKG-00002\"}");
        MessageBus bus = new InMemoryMessageBus();

        assertThat(BookingLoader.load(file, Topics.RAW, bus)).isEqualTo(2);
    }

    // --- key extraction, including from unparseable rows -----------------

    @ParameterizedTest(name = "[{index}] {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"bookingId\":\"BKG-00001\"}                          | BKG-00001",
            "{\"bookingId\": \"BKG-00002\" \\, \"shipper\":\"A\"}   | BKG-00002",
            "{\"bookingId\":\"BKG-00003\"\\, \"shipper\":\"ABC      | BKG-00003",
            "{\"shipper\":\"A\"\\, \"bookingId\":\"BKG-00004\"}     | BKG-00004"})
    @DisplayName("the bookingId is recoverable even when the row does not parse")
    void bookingIdIsRecoverable(String row, String expected) {
        assertThat(BookingLoader.bookingIdOf(row.trim())).isEqualTo(expected);
    }

    @Test
    @DisplayName("a row with no recoverable id falls back to null so the processor uses UNKNOWN")
    void unrecoverableIdIsNull() {
        assertThat(BookingLoader.bookingIdOf("{not json")).isNull();
    }
}
