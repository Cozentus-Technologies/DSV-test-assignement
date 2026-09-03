# CLAUDE.md — City Enrichment BDD Harness

Read `SPEC.md` first. It is the contract. If code and SPEC disagree, SPEC wins; if a change to SPEC is needed, propose it before coding.

## Stack (fixed)

Java 21 · Maven · JUnit 5 · Cucumber-JVM 7 · Jackson · Apache Commons Text · AssertJ.
No Spring. No Docker. No Testcontainers. No embedded Kafka. No new dependencies without asking.

## Non-negotiables

1. **Feature files are the spec.** Never edit a scenario to make it pass. If a scenario is wrong, stop and say so.
2. **Thresholds are locked** (SPEC §5). Changing a threshold requires changing the feature file in the same commit and explaining why.
3. **Layering** (SPEC §7): `matcher/` and `enrich/` must not import `bus/` or `processor/`. `processor/` depends only on the `MessageBus` interface.
4. **One topic per booking.** A `bookingId` never appears on both `booking.enriched` and `booking.flagged`.
5. **The processor never throws.** Bad input → `booking.flagged` with `MALFORMED_MESSAGE`; the next message is still processed.
6. **Generator labels via `CityMatcher`**, never via the corruption name.
7. **Determinism.** Fixed seeds, synchronous bus, no sleeps, no Awaitility, no timeouts.

## Gate

A task is done only when `mvn -q verify` passes. Run it before reporting. For task 6 also run `mvn -q verify -Pbulk`.

## Task order

Work one task at a time. After each, run the gate, summarise what changed, and stop for review.

1. **Scaffold + matcher** — `pom.xml`, package layout, `cities.json`, `model/`, `CityReference`, `CityMatcher`, `MatchResult`. Parameterised `CityMatcherTest` covering every row of SPEC §5 sanity table.
2. **Enricher** — `BookingEnricher`, `FlagReason`, `FieldFlag`, `EnrichedBooking`, `FlaggedBooking`. `BookingEnricherTest` covering every reason in SPEC §6 including the two-reason case.
3. **Bus + processor** — `MessageBus`, `Message`, `InMemoryMessageBus` (ConcurrentLinkedQueue per topic, synchronous subscribers, subscriber exceptions isolated), `Topics`, `EnrichmentProcessor`. Unit tests for bus semantics and processor routing including malformed input.
4. **BDD** — `city_enrichment.feature` exactly as in SPEC §8.2, step definitions, hooks, Cucumber runner, HTML + JSON reports to `target/cucumber`.
5. **Sample data** — `BookingDataGenerator`, `BookingLoader`, committed 200-row `bookings-sample.jsonl` + oracle, data-driven scenario.
6. **Bulk profile** — `@Tag("bulk")` test per SPEC §8.4, Maven profile `bulk`, `-Dbulk.count` / `-Dbulk.seed`, throughput logged. Verify at 20 000 and 30 000.
7. **README** — run, regenerate data, swap `MessageBus` for real Kafka.

## Conventions

- Package root `com.cozentus.enrichment`.
- Records for models; sealed interface for `MatchResult`.
- JSON via one shared `ObjectMapper` in `JsonSupport`; `FAIL_ON_UNKNOWN_PROPERTIES = false`.
- Tests named `<Class>Test`; Cucumber steps in `steps/`, hooks in `support/`.
- Commit per task: `task-N: <summary>`.

## When unsure

Ask. Do not guess topic names, reason names, thresholds, or payload shapes — they are all in SPEC.
