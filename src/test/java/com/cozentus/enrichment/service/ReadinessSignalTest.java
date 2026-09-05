package com.cozentus.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CH-05: the suite must not publish before the service has subscribed. */
class ReadinessSignalTest {

    private ReadinessSignal signal;

    @AfterEach
    void stop() {
        if (signal != null) {
            signal.close();
        }
    }

    private int statusOf(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + signal.port() + path))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("/ready is 503 until the processor has subscribed, then 200")
    void readyFlipsOnlyWhenMarked() throws Exception {
        signal = ReadinessSignal.start(0);

        assertThat(statusOf("/ready")).isEqualTo(503);

        signal.markReady();

        assertThat(statusOf("/ready")).isEqualTo(200);
    }

    @Test
    @DisplayName("/health is 200 while the server is alive, regardless of readiness")
    void healthIsIndependentOfReadiness() throws Exception {
        signal = ReadinessSignal.start(0);

        assertThat(statusOf("/health")).isEqualTo(200);
        assertThat(statusOf("/ready")).isEqualTo(503);
    }

    @Test
    @DisplayName("an unknown path is 404, not a stack trace")
    void unknownPathIs404() throws Exception {
        signal = ReadinessSignal.start(0);

        assertThat(statusOf("/nope")).isEqualTo(404);
    }

    @Test
    @DisplayName("port 0 binds an ephemeral port and reports the real one")
    void ephemeralPortIsReported() {
        signal = ReadinessSignal.start(0);

        assertThat(signal.port()).isPositive().isNotEqualTo(0);
    }

    @Test
    @DisplayName("close is idempotent, so a shutdown hook running twice is harmless")
    void closeIsIdempotent() {
        signal = ReadinessSignal.start(0);
        signal.close();
        signal.close();
        signal = null;
    }
}
