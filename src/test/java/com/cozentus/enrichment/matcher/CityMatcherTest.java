package com.cozentus.enrichment.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Covers every row of the SPEC 5 sanity table. */
class CityMatcherTest {

    private static CityMatcher baseMatcher() {
        return new CityMatcher(CityReference.fromClasspath());
    }

    private static CityMatcher matcherWithDelhi() {
        return new CityMatcher(
                CityReference.of("Mumbai", "New Delhi", "Bangalore", "Chennai",
                                 "Kolkata", "Pune", "Hyderabad", "Ahmedabad", "Delhi"));
    }

    // --- reference data ------------------------------------------------

    @Test
    @DisplayName("cities.json supplies the eight canonical names in file order")
    void referenceIsLoadedInFileOrder() {
        assertThat(CityReference.fromClasspath().names())
                .containsExactly("Mumbai", "New Delhi", "Bangalore", "Chennai",
                                 "Kolkata", "Pune", "Hyderabad", "Ahmedabad");
    }

    // --- SPEC 5 step 3: exact after normalise --------------------------

    static Stream<Arguments> exactAfterNormalise() {
        return Stream.of(
                Arguments.of("Mumbai", "Mumbai"),
                Arguments.of("MUMBAI", "Mumbai"),                 // sanity table
                Arguments.of("  New   Delhi ", "New Delhi"),      // sanity table
                Arguments.of("chennai", "Chennai"),
                Arguments.of("  pune  ", "Pune"));                // trim coverage (SPEC 8.2 note)
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1} @ 1.0")
    @MethodSource("exactAfterNormalise")
    @DisplayName("exact match after normalising scores 1.0")
    void exactMatchScoresOne(String input, String expected) {
        assertThat(baseMatcher().match(input))
                .isEqualTo(new MatchResult.Match(expected, 1.0));
    }

    // --- SPEC 5 step 4: fuzzy ------------------------------------------

    static Stream<Arguments> fuzzyMatches() {
        return Stream.of(
                Arguments.of("Mumbi", "Mumbai"),            // sanity table, d=1
                Arguments.of("now delhi", "New Delhi"),     // sanity table, d=1
                Arguments.of("Bangalor", "Bangalore"),      // sanity table, d=1
                Arguments.of("Pne", "Pune"),                // sanity table, d=1 on a short name
                Arguments.of("Delh", "New Delhi"),          // sanity table, token match
                Arguments.of("kolkatta", "Kolkata"),        // feature BKG-5
                Arguments.of("Ahmedabd", "Ahmedabad"));     // feature BKG-4

    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @MethodSource("fuzzyMatches")
    @DisplayName("a single candidate within distance is corrected to its canonical name")
    void fuzzyMatchCorrectsToCanonical(String input, String expected) {
        assertThat(baseMatcher().match(input))
                .isInstanceOfSatisfying(MatchResult.Match.class, m -> {
                    assertThat(m.canonical()).isEqualTo(expected);
                    assertThat(m.confidence()).isBetween(0.85, 1.0);
                });
    }

    // --- SPEC 5 step 5: ambiguity --------------------------------------

    @Test
    @DisplayName("\"Delh\" is ambiguous once Delhi joins the reference list, in file order")
    void ambiguousListsCandidatesInReferenceOrder() {
        assertThat(matcherWithDelhi().match("Delh"))
                .isEqualTo(new MatchResult.Ambiguous(List.of("New Delhi", "Delhi")));
    }

    @Test
    @DisplayName("an exact match short-circuits and is never ambiguous")
    void exactMatchShortCircuitsAmbiguity() {
        assertThat(matcherWithDelhi().match("delhi"))
                .isEqualTo(new MatchResult.Match("Delhi", 1.0));
    }

    // --- SPEC 5 step 6: no match ---------------------------------------

    @ParameterizedTest(name = "[{index}] \"{0}\" -> NoMatch")
    @ValueSource(strings = {
            "Pn",         // sanity table: d=2 exceeds the cap of 1 for a 4-char name
            "Warsaw",     // sanity table: genuine foreign city
            "Lisbon",     // feature BKG-10
            "Singapore",
            "Berlin"})
    @DisplayName("zero candidates within distance is a no-match")
    void unmatchedInputYieldsNoMatch(String input) {
        assertThat(baseMatcher().match(input)).isEqualTo(new MatchResult.NoMatch());
    }

    // --- SPEC 5 step 1: missing ----------------------------------------

    @ParameterizedTest(name = "[{index}] missing")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    @DisplayName("null, empty or whitespace-only input is missing, not unmatched")
    void blankInputYieldsMissing(String input) {
        assertThat(baseMatcher().match(input)).isEqualTo(new MatchResult.Missing());
    }
}
