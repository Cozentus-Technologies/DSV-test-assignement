package com.cozentus.enrichment.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CH-02: the startup line must be diagnosable without attaching a debugger. */
class StartupSummaryTest {

    @Test
    @DisplayName("the summary contains no unexpanded format specifiers")
    void summaryHasNoFormatPlaceholders() {
        String summary = KafkaConfig.resolve(new String[]{}, name -> null, Map.of()).describe();

        assertThat(summary)
                .doesNotContain("%s").doesNotContain("%n").doesNotContain("%d")
                .doesNotContain("{0}").doesNotContain("{1}");
    }

    @Test
    @DisplayName("the summary reflects overridden values, not the defaults")
    void summaryReflectsOverrides() {
        String summary = KafkaConfig.resolve(new String[]{
                "--kafka.bootstrap.servers=broker:19092",
                "--topic.raw=raw.scenario-1",
                "--cities.source=inline:Mumbai,Delhi"}, name -> null, Map.of()).describe();

        assertThat(summary)
                .contains("broker:19092")
                .contains("raw.scenario-1")
                .contains("inline:Mumbai,Delhi")
                .doesNotContain("localhost:9092");
    }
}
