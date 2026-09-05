package com.cozentus.enrichment.matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CH-07: the suite cannot call CityReference.of, so the list must be configurable. */
class CityReferenceSourceTest {

    @TempDir
    Path dir;

    private static final List<String> BASE = List.of(
            "Mumbai", "New Delhi", "Bangalore", "Chennai",
            "Kolkata", "Pune", "Hyderabad", "Ahmedabad");

    @Test
    @DisplayName("classpath: is the default and matches fromClasspath")
    void classpathSource() {
        assertThat(CityReference.fromSource("classpath:/cities.json").names())
                .containsExactlyElementsOf(BASE)
                .containsExactlyElementsOf(CityReference.fromClasspath().names());
    }

    @Test
    @DisplayName("file: reads an external list, preserving order")
    void fileSource() throws IOException {
        Path file = dir.resolve("cities.json");
        Files.writeString(file, "[\"Mumbai\",\"New Delhi\",\"Delhi\"]");

        assertThat(CityReference.fromSource("file:" + file).names())
                .containsExactly("Mumbai", "New Delhi", "Delhi");
    }

    @Test
    @DisplayName("inline: takes a comma-separated list, preserving order")
    void inlineSource() {
        assertThat(CityReference.fromSource("inline:Mumbai,New Delhi,Delhi").names())
                .containsExactly("Mumbai", "New Delhi", "Delhi");
    }

    @Test
    @DisplayName("inline: trims surrounding whitespace but keeps names intact")
    void inlineTrimsAroundNames() {
        assertThat(CityReference.fromSource("inline: Mumbai , New Delhi ,Delhi").names())
                .containsExactly("Mumbai", "New Delhi", "Delhi");
    }

    @Test
    @DisplayName("the ambiguity case the suite needs: an inline list extended with Delhi")
    void inlineSupportsTheAmbiguityScenario() {
        CityMatcher matcher = new CityMatcher(CityReference.fromSource(
                "inline:Mumbai,New Delhi,Bangalore,Chennai,Kolkata,Pune,Hyderabad,Ahmedabad,Delhi"));

        assertThat(matcher.match("Delh"))
                .isEqualTo(new MatchResult.Ambiguous(List.of("New Delhi", "Delhi")));
    }

    @Test
    @DisplayName("an unknown scheme is rejected by name")
    void unknownSchemeIsRejected() {
        assertThatThrownBy(() -> CityReference.fromSource("ftp:/cities.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ftp");
    }

    @Test
    @DisplayName("a missing file is reported with its path, not as a null pointer")
    void missingFileIsReportedClearly() {
        assertThatThrownBy(() -> CityReference.fromSource("file:" + dir.resolve("absent.json")))
                .hasMessageContaining("absent.json");
    }

    @Test
    @DisplayName("an empty inline list is rejected: a matcher with no references is useless")
    void emptyInlineIsRejected() {
        assertThatThrownBy(() -> CityReference.fromSource("inline:"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
