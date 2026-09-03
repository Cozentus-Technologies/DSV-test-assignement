package com.cozentus.enrichment.matcher;

import com.cozentus.enrichment.JsonSupport;
import java.io.IOException;
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
        try (InputStream in = CityReference.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            return new CityReference(Arrays.asList(JsonSupport.mapper().readValue(in, String[].class)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static CityReference of(String... names) {
        return new CityReference(Arrays.asList(names));
    }

    /** Canonical names in reference-file order. */
    public List<String> names() {
        return names;
    }
}
