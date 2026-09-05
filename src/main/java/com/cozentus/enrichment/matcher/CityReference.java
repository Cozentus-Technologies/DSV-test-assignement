package com.cozentus.enrichment.matcher;

import com.cozentus.enrichment.JsonSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

/**
 * The canonical city master data (SPEC 4).
 *
 * <p>Order is significant: it is the order ambiguous candidates are reported in
 * (SPEC 5 step 5), so it must follow {@code cities.json} exactly.
 */
public final class CityReference {

    private static final String RESOURCE = "/cities.json";

    private final List<String> names;

    private CityReference(List<String> names) {
        this.names = List.copyOf(names);
    }

    public static CityReference fromClasspath() {
        return fromClasspathResource(RESOURCE);
    }

    private static CityReference fromClasspathResource(String resource) {
        try (InputStream in = CityReference.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return new CityReference(Arrays.asList(JsonSupport.mapper().readValue(in, String[].class)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Loads the reference list from a configured source (CH-07).
     *
     * <p>A black-box suite cannot call {@link #of}, so the ambiguity scenario
     * needs the list extended from outside the process. Reference-file order
     * stays significant: it determines candidate ordering (SPEC 5 step 5).
     *
     * <ul>
     *   <li>{@code classpath:/cities.json}
     *   <li>{@code file:/path/to/cities.json}
     *   <li>{@code inline:Mumbai,New Delhi,Delhi}
     * </ul>
     */
    public static CityReference fromSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Cities source must not be blank");
        }
        if (source.startsWith("inline:")) {
            List<String> names = Arrays.stream(source.substring("inline:".length()).split(","))
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .toList();
            if (names.isEmpty()) {
                throw new IllegalArgumentException("inline: needs at least one city, got: " + source);
            }
            return new CityReference(names);
        }
        if (source.startsWith("classpath:")) {
            return fromClasspathResource(source.substring("classpath:".length()));
        }
        if (source.startsWith("file:")) {
            Path path = Path.of(source.substring("file:".length()));
            try {
                return new CityReference(Arrays.asList(
                        JsonSupport.mapper().readValue(Files.readString(path, StandardCharsets.UTF_8),
                                String[].class)));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read cities from " + path, e);
            }
        }
        throw new IllegalArgumentException(
                "Unknown cities source scheme in \"" + source + "\". Use classpath:, file: or inline:");
    }

    public static CityReference of(String... names) {
        return new CityReference(Arrays.asList(names));
    }

    /** Canonical names in reference-file order. */
    public List<String> names() {
        return names;
    }
}
