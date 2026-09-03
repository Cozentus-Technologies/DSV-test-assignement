package com.cozentus.enrichment.matcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.apache.commons.text.similarity.LevenshteinDistance;

/**
 * Applies the SPEC 5 matching contract to a single city name.
 *
 * <p>Field-agnostic by design: it reports <em>what</em> happened
 * ({@link MatchResult}), and the enricher maps that onto the field-specific
 * reasons of SPEC 6. Depends on nothing from {@code bus/} or {@code processor/}.
 */
public final class CityMatcher {

    /** SPEC 5 step 4: a candidate must also score at least this. */
    private static final double MIN_CONFIDENCE = 0.85;

    /** SPEC 5 step 4: allowed edit distance, by length of the comparison string. */
    private static final int SHORT_NAME_LENGTH = 6;
    private static final int SHORT_NAME_MAX_DISTANCE = 1;
    private static final int LONG_NAME_MAX_DISTANCE = 2;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final LevenshteinDistance DISTANCE = LevenshteinDistance.getDefaultInstance();
    private static final JaroWinklerSimilarity SIMILARITY = new JaroWinklerSimilarity();

    /** In reference-file order, so surviving candidates come out in that order. */
    private final List<Reference> references;

    public CityMatcher(CityReference reference) {
        this.references = reference.names().stream().map(Reference::of).toList();
    }

    public MatchResult match(String input) {
        // Step 1 — missing. Checked before normalising, which would throw on null.
        if (input == null || input.isBlank()) {
            return new MatchResult.Missing();
        }

        // Step 2 — normalise.
        String normalised = normalise(input);

        // Step 3 — exact wins outright; steps 4-5 are not reached.
        for (Reference reference : references) {
            if (reference.normalised().equals(normalised)) {
                return new MatchResult.Match(reference.canonical(), 1.0);
            }
        }

        // Step 4 — fuzzy. Distance and similarity are independent gates; both must pass.
        List<Scored> candidates = new ArrayList<>();
        for (Reference reference : references) {
            boolean withinDistance = false;
            double confidence = 0.0;
            for (String comparison : reference.comparisonSet()) {
                if (DISTANCE.apply(normalised, comparison) <= maxDistance(comparison)) {
                    withinDistance = true;
                }
                confidence = Math.max(confidence, SIMILARITY.apply(normalised, comparison));
            }
            if (withinDistance && confidence >= MIN_CONFIDENCE) {
                candidates.add(new Scored(reference.canonical(), confidence));
            }
        }

        // Steps 5-7.
        if (candidates.size() > 1) {
            return new MatchResult.Ambiguous(candidates.stream().map(Scored::canonical).toList());
        }
        if (candidates.isEmpty()) {
            return new MatchResult.NoMatch();
        }
        Scored only = candidates.get(0);
        return new MatchResult.Match(only.canonical(), only.confidence());
    }

    /** SPEC 5 step 2: trim, collapse internal whitespace to one space, case-fold. */
    private static String normalise(String value) {
        return WHITESPACE.matcher(value.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static int maxDistance(String comparison) {
        return comparison.length() <= SHORT_NAME_LENGTH
                ? SHORT_NAME_MAX_DISTANCE
                : LONG_NAME_MAX_DISTANCE;
    }

    /** A reference name with its normalised form and comparison set, computed once. */
    private record Reference(String canonical, String normalised, List<String> comparisonSet) {

        static Reference of(String canonical) {
            String normalised = normalise(canonical);
            return new Reference(canonical, normalised, comparisonSetOf(normalised));
        }

        /**
         * SPEC 5 step 4: the normalised full name, plus its tokens when multi-word.
         * Token matching is what lets a short input reach a multi-word reference —
         * "delh" is 5 edits from "new delhi" but 1 from its token "delhi".
         */
        private static List<String> comparisonSetOf(String normalised) {
            String[] tokens = normalised.split(" ");
            if (tokens.length == 1) {
                return List.of(normalised);
            }
            List<String> set = new ArrayList<>(tokens.length + 1);
            set.add(normalised);
            set.addAll(Arrays.asList(tokens));
            return List.copyOf(set);
        }
    }

    private record Scored(String canonical, double confidence) {
    }
}
