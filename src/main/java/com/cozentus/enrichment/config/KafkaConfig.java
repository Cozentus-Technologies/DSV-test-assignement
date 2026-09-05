package com.cozentus.enrichment.config;

import com.cozentus.enrichment.processor.Topics;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Service configuration (CH-04).
 *
 * <p>Resolution order, highest first: command-line argument, environment
 * variable, {@code application.properties}, built-in default.
 *
 * <p>Topic names are configurable because the test suite provisions unique topics
 * per scenario for isolation; hard-coded names would make scenarios contaminate
 * each other and rule out parallel runs.
 */
public final class KafkaConfig {

    /** Property key, environment variable, and default. */
    private enum Setting {
        BOOTSTRAP_SERVERS("kafka.bootstrap.servers", "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
        CONSUMER_GROUP("kafka.consumer.group", "KAFKA_CONSUMER_GROUP", "city-enrichment"),
        TOPIC_RAW("topic.raw", "TOPIC_RAW", Topics.RAW),
        TOPIC_ENRICHED("topic.enriched", "TOPIC_ENRICHED", Topics.ENRICHED),
        TOPIC_FLAGGED("topic.flagged", "TOPIC_FLAGGED", Topics.FLAGGED),
        CITIES_SOURCE("cities.source", "CITIES_SOURCE", "classpath:/cities.json"),
        READINESS_PORT("readiness.port", "READINESS_PORT", "8081");

        private final String key;
        private final String environmentVariable;
        private final String fallback;

        Setting(String key, String environmentVariable, String fallback) {
            this.key = key;
            this.environmentVariable = environmentVariable;
            this.fallback = fallback;
        }
    }

    private static final String PROPERTIES_RESOURCE = "/application.properties";

    private final Map<Setting, String> values;

    private KafkaConfig(Map<Setting, String> values) {
        this.values = values;
    }

    /** The entry point a running service uses. */
    public static KafkaConfig fromEnvironment(String[] args) {
        return resolve(args, System::getenv, propertiesFromClasspath());
    }

    public static KafkaConfig resolve(String[] args,
                                      Function<String, String> environment,
                                      Map<String, String> properties) {
        Map<String, String> arguments = parseArguments(args);

        Map<Setting, String> resolved = new LinkedHashMap<>();
        for (Setting setting : Setting.values()) {
            String value = arguments.get(setting.key);
            if (value == null) {
                value = environment.apply(setting.environmentVariable);
            }
            if (value == null) {
                value = properties.get(setting.key);
            }
            if (value == null) {
                value = setting.fallback;
            }
            resolved.put(setting, value);
        }

        KafkaConfig config = new KafkaConfig(resolved);
        config.validate();
        return config;
    }

    /**
     * Rejects an unrecognised key rather than ignoring it. A typo in
     * {@code --kafka.bootstrp.servers} would otherwise leave the service quietly
     * pointed at the default broker.
     */
    private static Map<String, String> parseArguments(String[] args) {
        List<String> known = java.util.Arrays.stream(Setting.values()).map(s -> s.key).toList();
        Map<String, String> parsed = new HashMap<>();

        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            int equals = arg.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException("Argument needs --key=value: " + arg);
            }
            String key = arg.substring(2, equals);
            if (!known.contains(key)) {
                throw new IllegalArgumentException(
                        "Unknown configuration key: " + key + ". Known keys: " + known);
            }
            parsed.put(key, arg.substring(equals + 1));
        }
        return parsed;
    }

    private static Map<String, String> propertiesFromClasspath() {
        try (InputStream in = KafkaConfig.class.getResourceAsStream(PROPERTIES_RESOURCE)) {
            if (in == null) {
                return Map.of();
            }
            Properties properties = new Properties();
            properties.load(in);
            Map<String, String> asMap = new LinkedHashMap<>();
            properties.stringPropertyNames().forEach(name -> asMap.put(name, properties.getProperty(name)));
            return asMap;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fails at startup rather than at first use. */
    private void validate() {
        String port = values.get(Setting.READINESS_PORT);
        try {
            int parsed = Integer.parseInt(port.trim());
            if (parsed < 1 || parsed > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "readiness.port must be a port number between 1 and 65535, got: " + port);
        }
    }

    public String bootstrapServers() {
        return values.get(Setting.BOOTSTRAP_SERVERS);
    }

    public String consumerGroup() {
        return values.get(Setting.CONSUMER_GROUP);
    }

    public TopicNames topics() {
        return new TopicNames(values.get(Setting.TOPIC_RAW),
                              values.get(Setting.TOPIC_ENRICHED),
                              values.get(Setting.TOPIC_FLAGGED));
    }

    public String citiesSource() {
        return values.get(Setting.CITIES_SOURCE);
    }

    public int readinessPort() {
        return Integer.parseInt(values.get(Setting.READINESS_PORT).trim());
    }

    /**
     * The startup log line. A failing test run should be diagnosable from logs
     * alone, which means the broker, topics, group and city source must all appear.
     */
    public String describe() {
        return """
                broker      %s
                topics      %s | %s | %s
                group       %s
                cities      %s
                readiness   http://localhost:%d/ready""".formatted(
                bootstrapServers(),
                topics().raw(), topics().enriched(), topics().flagged(),
                consumerGroup(),
                citiesSource(),
                readinessPort());
    }
}
