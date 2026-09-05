package com.cozentus.enrichment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CH-04: argument, then environment variable, then properties, then default. */
class KafkaConfigTest {

    private static KafkaConfig resolve(String[] args, Map<String, String> env) {
        return KafkaConfig.resolve(args, env::get, Map.of());
    }

    @Test
    @DisplayName("with nothing supplied, every value falls back to its documented default")
    void defaultsApply() {
        KafkaConfig config = resolve(new String[]{}, Map.of());

        assertThat(config.bootstrapServers()).isEqualTo("localhost:9092");
        assertThat(config.consumerGroup()).isEqualTo("city-enrichment");
        assertThat(config.topics().raw()).isEqualTo("booking.raw");
        assertThat(config.topics().enriched()).isEqualTo("booking.enriched");
        assertThat(config.topics().flagged()).isEqualTo("booking.flagged");
        assertThat(config.citiesSource()).isEqualTo("classpath:/cities.json");
        assertThat(config.readinessPort()).isEqualTo(8081);
    }

    @Test
    @DisplayName("an environment variable overrides the default")
    void environmentOverridesDefault() {
        KafkaConfig config = resolve(new String[]{}, Map.of(
                "KAFKA_BOOTSTRAP_SERVERS", "broker:19092",
                "TOPIC_RAW", "raw.v2",
                "CITIES_SOURCE", "inline:Mumbai,Delhi"));

        assertThat(config.bootstrapServers()).isEqualTo("broker:19092");
        assertThat(config.topics().raw()).isEqualTo("raw.v2");
        assertThat(config.citiesSource()).isEqualTo("inline:Mumbai,Delhi");
        assertThat(config.consumerGroup()).isEqualTo("city-enrichment");
    }

    @Test
    @DisplayName("a command-line argument overrides the environment")
    void argumentOverridesEnvironment() {
        KafkaConfig config = resolve(
                new String[]{"--kafka.bootstrap.servers=cli:9092", "--topic.raw=raw.cli"},
                Map.of("KAFKA_BOOTSTRAP_SERVERS", "env:9092", "TOPIC_RAW", "raw.env"));

        assertThat(config.bootstrapServers()).isEqualTo("cli:9092");
        assertThat(config.topics().raw()).isEqualTo("raw.cli");
    }

    @Test
    @DisplayName("a properties file sits below the environment but above the default")
    void propertiesSitBetweenEnvironmentAndDefault() {
        KafkaConfig fromProperties = KafkaConfig.resolve(
                new String[]{}, name -> null,
                Map.of("kafka.consumer.group", "from-properties",
                       "topic.enriched", "enriched.props"));

        assertThat(fromProperties.consumerGroup()).isEqualTo("from-properties");
        assertThat(fromProperties.topics().enriched()).isEqualTo("enriched.props");

        KafkaConfig environmentWins = KafkaConfig.resolve(
                new String[]{}, Map.of("KAFKA_CONSUMER_GROUP", "from-env")::get,
                Map.of("kafka.consumer.group", "from-properties"));

        assertThat(environmentWins.consumerGroup()).isEqualTo("from-env");
    }

    @Test
    @DisplayName("unique topic names per scenario are possible, which is what suite isolation needs")
    void topicNamesAreFullyOverridable() {
        KafkaConfig config = resolve(new String[]{
                "--topic.raw=booking.raw.abc123",
                "--topic.enriched=booking.enriched.abc123",
                "--topic.flagged=booking.flagged.abc123",
                "--kafka.consumer.group=group-abc123"}, Map.of());

        assertThat(config.topics().raw()).endsWith(".abc123");
        assertThat(config.topics().enriched()).endsWith(".abc123");
        assertThat(config.topics().flagged()).endsWith(".abc123");
        assertThat(config.consumerGroup()).isEqualTo("group-abc123");
    }

    @Test
    @DisplayName("a non-numeric readiness port is rejected at startup, not at first use")
    void invalidPortIsRejectedEarly() {
        assertThatThrownBy(() -> resolve(new String[]{"--readiness.port=not-a-number"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readiness.port");
    }

    @Test
    @DisplayName("port 0 is accepted: it means bind an ephemeral port")
    void ephemeralPortIsAllowed() {
        assertThat(resolve(new String[]{"--readiness.port=0"}, Map.of()).readinessPort()).isZero();
    }

    @Test
    @DisplayName("a port outside the valid range is rejected")
    void outOfRangePortIsRejected() {
        assertThatThrownBy(() -> resolve(new String[]{"--readiness.port=70000"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolve(new String[]{"--readiness.port=-1"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unknown --key=value argument is rejected rather than silently ignored")
    void unknownArgumentIsRejected() {
        assertThatThrownBy(() -> resolve(new String[]{"--kafka.bootstrp.servers=typo:9092"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kafka.bootstrp.servers");
    }

    @Test
    @DisplayName("the startup summary names the broker, topics, group and city source")
    void summaryIsDiagnosable() {
        String summary = resolve(new String[]{}, Map.of()).describe();

        assertThat(summary)
                .contains("localhost:9092")
                .contains("booking.raw")
                .contains("booking.enriched")
                .contains("booking.flagged")
                .contains("city-enrichment")
                .contains("classpath:/cities.json");
    }
}
