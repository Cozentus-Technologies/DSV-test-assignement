package com.cozentus.enrichment.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The demo command documented in the README. */
class EnrichmentDemoTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    private int run(String... args) {
        return EnrichmentDemo.run(args, new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a correctable booking is reported as enriched with both cities fixed")
    void correctableBookingIsShownAsEnriched() {
        int status = run("--origin", "Mumbi", "--destination", "now delhi");

        assertThat(status).isZero();
        assertThat(output())
                .contains("booking.enriched")
                .contains("\"origin\" : \"Mumbai\"")
                .contains("\"destination\" : \"New Delhi\"")
                .contains("\"originalOrigin\" : \"Mumbi\"");
    }

    @Test
    @DisplayName("an unmatchable city is reported as flagged, with the reason")
    void unmatchableCityIsShownAsFlagged() {
        int status = run("--origin", "Warsaw", "--destination", "Mumbai");

        assertThat(status).isZero();
        assertThat(output())
                .contains("booking.flagged")
                .contains("UNMATCHED_ORIGIN_CITY")
                .doesNotContain("booking.enriched");
    }

    @Test
    @DisplayName("a missing city is reported as missing, not as unmatched")
    void missingCityIsDistinguished() {
        run("--origin", "", "--destination", "Mumbai");

        assertThat(output()).contains("MISSING_ORIGIN_CITY");
    }

    @Test
    @DisplayName("an ambiguous city lists its candidates")
    void ambiguousCityListsCandidates() {
        run("--origin", "Delh", "--destination", "Mumbai", "--cities",
            "Mumbai,New Delhi,Bangalore,Chennai,Kolkata,Pune,Hyderabad,Ahmedabad,Delhi");

        assertThat(output())
                .contains("AMBIGUOUS_ORIGIN_CITY")
                .contains("New Delhi")
                .contains("Delhi");
    }

    @Test
    @DisplayName("replaying a file reports the split and totals")
    void fileReplayReportsTheSplit() {
        int status = run("--file", "data/bookings-sample.jsonl");

        assertThat(status).isZero();
        assertThat(output())
                .contains("200")
                .contains("booking.enriched")
                .contains("booking.flagged")
                .contains("93")
                .contains("107");
    }

    @Test
    @DisplayName("replaying a file summarises malformed rows rather than hiding them")
    void fileReplaySummarisesMalformedRows() {
        run("--file", "data/bookings-sample.jsonl");

        assertThat(output())
                .contains("MALFORMED_MESSAGE")
                .contains("10");
    }

    @Test
    @DisplayName("no usable arguments prints usage and reports a non-zero status")
    void noArgumentsPrintsUsage() {
        assertThat(run()).isNotZero();
        assertThat(output()).contains("Usage").contains("--origin").contains("--file");
    }

    @Test
    @DisplayName("an origin without a destination is rejected rather than half-run")
    void partialArgumentsAreRejected() {
        assertThat(run("--origin", "Mumbai")).isNotZero();
        assertThat(output()).contains("Usage");
    }

    @Test
    @DisplayName("a file that does not exist is reported clearly, not as a stack trace")
    void missingFileIsReportedClearly() {
        int status = run("--file", "data/does-not-exist.jsonl");

        assertThat(status).isNotZero();
        assertThat(output()).contains("does-not-exist.jsonl");
    }
}
