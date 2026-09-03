package com.cozentus.enrichment.matcher;

import java.util.List;

/**
 * Outcome of matching one city name against the reference list (SPEC 5).
 * Sealed so consumers must handle every outcome exhaustively.
 */
public sealed interface MatchResult {

    /** Exactly one candidate survived: the canonical name and its confidence. */
    record Match(String canonical, double confidence) implements MatchResult {}

    /** Two or more candidates survived, listed in reference-file order. */
    record Ambiguous(List<String> candidates) implements MatchResult {

        public Ambiguous {
            candidates = List.copyOf(candidates);
        }
    }

    /** No candidate fell within its allowed distance. */
    record NoMatch() implements MatchResult {}

    /** Input was null, empty or whitespace-only. */
    record Missing() implements MatchResult {}
}
