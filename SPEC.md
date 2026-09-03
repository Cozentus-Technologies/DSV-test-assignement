# SPEC — City Enrichment Before Publishing to TMS

**Status:** agreed 2026-09-03 · **Owner:** Testing Lead · **Consumers:** Claude Code, reviewers

## 1. Problem

Bookings arrive on Kafka topic `booking.raw`. Their `origin` and `destination` city names may be misspelled or inconsistently cased. Before a booking is forwarded to TMS on `booking.enriched`, both cities must be corrected against a reference list. Any booking whose cities cannot be confidently matched must be routed to `booking.flagged` — never forwarded as-is, never silently dropped.

No enrichment service exists. This repository contains **both** a reference implementation of the enrichment logic **and** the BDD test harness that verifies it. The harness is the source of truth; the implementation exists to satisfy it and to be replaced later by the real service.

## 2. Scope and decisions

| Decision | Value |
|---|---|
| Messaging | In-memory fake bus. No Docker, no Testcontainers, no embedded Kafka. |
| Enrichment logic | Written here, in `src/main`, behind interfaces. |
| Flag routing | Separate topic `booking.flagged`. A booking appears on exactly one of `booking.enriched` / `booking.flagged`. |
| Flag reasons | Field-specific (see §6). |
| Whole-booking rule | If **either** city fails, the whole booking is flagged. TMS never receives a half-enriched booking. |
| Message key | `bookingId` on all three topics. |
| Test style | BDD — Cucumber-JVM feature files are the spec; unit tests cover the matcher; a bulk profile runs ≥20 000 messages. |

Out of scope: real Kafka, schema registry, retries, exactly-once, TMS itself.

## 3. Stack

Java 21 · Maven · JUnit 5 · Cucumber-JVM 7 · Jackson · Apache Commons Text · AssertJ. No Spring.

## 4. Domain

### Reference cities (master data)

`Mumbai, New Delhi, Bangalore, Chennai, Kolkata, Pune, Hyderabad, Ahmedabad`

Stored in `src/main/resources/cities.json` as a JSON array of canonical names. Canonical casing in this file is the casing that must appear on `booking.enriched`.

### Raw booking (`booking.raw`)

```json
{
  "bookingId": "BKG-12345",
  "shipper": "ABC Logistics",
  "origin": "Mumbi",
  "destination": "now delhi",
  "mode": "ROAD",
  "requestedDate": "2026-09-05"
}
```

### Enriched booking (`booking.enriched`)

All raw fields unchanged except `origin` / `destination`, plus an `enrichment` block:

```json
{
  "bookingId": "BKG-12345",
  "shipper": "ABC Logistics",
  "origin": "Mumbai",
  "destination": "New Delhi",
  "mode": "ROAD",
  "requestedDate": "2026-09-05",
  "enrichment": {
    "originalOrigin": "Mumbi",
    "originalDestination": "now delhi",
    "originConfidence": 0.97,
    "destinationConfidence": 0.933
  }
}
```

> Confidence values in this block are illustrative of the algorithm in §5, not a contract. No scenario asserts an exact confidence. (`JaroWinkler("mumbi","mumbai") = 0.967`; `JaroWinkler("now delhi","new delhi") = 0.933`.)

### Flagged booking (`booking.flagged`)

```json
{
  "bookingId": "BKG-12345",
  "reasons": ["UNMATCHED_ORIGIN_CITY", "AMBIGUOUS_DESTINATION_CITY"],
  "fields": [
    { "field": "origin",      "reason": "UNMATCHED_ORIGIN_CITY",      "value": "Warsaw", "candidates": [] },
    { "field": "destination", "reason": "AMBIGUOUS_DESTINATION_CITY", "value": "Delh",   "candidates": ["New Delhi", "Delhi"] }
  ],
  "original": { "...": "raw booking untouched" }
}
```

For `MALFORMED_MESSAGE`, `fields` is empty and `original` holds the raw string; `bookingId` is `null` and the message key is the Kafka key if present, else `"UNKNOWN"`.

## 5. Matching contract

Applied independently to `origin` and `destination`. Order matters.

1. **Missing** — `null`, empty, or whitespace-only → `MISSING_*_CITY`.
2. **Normalise** — trim, collapse internal whitespace to one space, case-fold.
3. **Exact** — normalised input equals normalised reference name → match, confidence `1.0`. An exact match short-circuits and wins outright: steps 4–5 are not reached, so an input equal to one reference name is never ambiguous with another.
4. **Fuzzy** — for each reference name, build its **comparison set**: the normalised full name, plus each whitespace-separated token if the name has more than one. For every comparison string `c` in that set:
   - Max allowed distance for `c`: `1` if `length(c) ≤ 6`, else `2`. All lengths are of the **normalised** string, spaces included.
   - The reference is a **candidate** if `LevenshteinDistance(normalisedInput, c)` is within the allowed distance for **any** `c` in its comparison set.
   - Confidence for that reference = the **highest** `JaroWinklerSimilarity(normalisedInput, c)` over its comparison set; a candidate must also score ≥ `0.85`.
   - Token matching is what lets a short input reach a multi-word reference: `Delh` is distance 5 from `new delhi` as a whole, but distance 1 from its token `delhi`.
5. **Ambiguity** — if two or more candidates survive step 4 → `AMBIGUOUS_*_CITY` with the candidate list. Candidates are listed in **reference-file order**, so the list is deterministic.
6. **No match** — zero candidates → `UNMATCHED_*_CITY`.
7. **Result** — exactly one candidate → match with its canonical name and confidence.

Sanity table (must hold):

| Input | Result |
|---|---|
| `Mumbi` | Mumbai (d=1) |
| `now delhi` | New Delhi (d=1) |
| `Bangalor` | Bangalore (d=1) |
| `MUMBAI` | Mumbai (exact after normalise) |
| `  New   Delhi ` | New Delhi (exact after normalise) |
| `Pne` | Pune (d=1 vs `pune`, length 4 ≤ 6 so d≤1 allowed; JW 0.925) |
| `Pn` | flagged (d=2 > 1) |
| `Warsaw` | flagged, no candidate within distance |
| `Delh` (base list) | New Delhi (token `delhi`, d=1) |
| `Delh` (list + `Delhi`) | flagged AMBIGUOUS, candidates `New Delhi`, `Delhi` |
| `""` | flagged, MISSING |

> Note: thresholds are deliberate and encoded in scenarios. Changing a threshold requires changing the feature file in the same commit.

## 6. Flag reasons

| Origin | Destination |
|---|---|
| `UNMATCHED_ORIGIN_CITY` | `UNMATCHED_DESTINATION_CITY` |
| `AMBIGUOUS_ORIGIN_CITY` | `AMBIGUOUS_DESTINATION_CITY` |
| `MISSING_ORIGIN_CITY` | `MISSING_DESTINATION_CITY` |

Plus `MALFORMED_MESSAGE` for unparseable input. `reasons` is a list; both cities failing yields two entries, ordered origin then destination.

## 7. Architecture

```
src/main/java/com/cozentus/enrichment/
├── model/        Booking, EnrichedBooking, FlaggedBooking, FlagReason, FieldFlag
├── matcher/      CityReference, CityMatcher, MatchResult (sealed: Match | Ambiguous | NoMatch | Missing)
├── enrich/       BookingEnricher                    ← pure: Booking -> EnrichedBooking | FlaggedBooking
├── bus/          MessageBus (interface), Message, InMemoryMessageBus
├── processor/    EnrichmentProcessor                ← only class that knows topic names
└── tools/        BookingDataGenerator, BookingLoader
```

Rules:
- `CityMatcher` and `BookingEnricher` have **no** dependency on `bus/` or `processor/`.
- `EnrichmentProcessor` depends only on the `MessageBus` interface.
- Topic names live in one constants class: `Topics.RAW`, `Topics.ENRICHED`, `Topics.FLAGGED`.

### MessageBus

```java
public interface MessageBus {
    void publish(String topic, String key, String payload);
    List<Message> consume(String topic);                 // snapshot of all messages so far
    void subscribe(String topic, Consumer<Message> handler);
    void reset();
}
record Message(String topic, String key, String payload, long offset, Instant timestamp) {}
```

`InMemoryMessageBus`: `ConcurrentHashMap<String, ConcurrentLinkedQueue<Message>>`, `AtomicLong` offset per topic, subscribers invoked **synchronously** inside `publish`. Synchronous delivery is deliberate: no timeouts, no Awaitility, deterministic negative assertions. A subscriber exception is caught, logged, and must not stop subsequent deliveries.

`subscribe` does **not** replay history: a subscriber receives only messages published after it subscribed. `consume` returns the full snapshot regardless. Consequence: `BookingLoader` must not publish to `booking.raw` until the `EnrichmentProcessor` has subscribed.

### EnrichmentProcessor

Subscribes to `booking.raw`. For each message: parse → `BookingEnricher` → publish to `booking.enriched` or `booking.flagged`. Parse failure → `MALFORMED_MESSAGE` to `booking.flagged`. Never throws out of the handler.

## 8. Test layers

### 8.1 Unit — `CityMatcherTest`, `BookingEnricherTest`

Parameterised tests covering every row of the sanity table in §5 and every reason in §6. Fast, no bus.

### 8.2 BDD — `src/test/resources/features/city_enrichment.feature`

```gherkin
Feature: City enrichment before publishing to TMS

  Background:
    Given the reference cities are Mumbai, New Delhi, Bangalore, Chennai, Kolkata, Pune, Hyderabad, Ahmedabad
    And the enrichment processor is consuming from "booking.raw"

  Scenario Outline: Correctable cities are enriched and forwarded
    When a raw booking "<id>" with origin "<origin>" and destination "<destination>" is published
    Then a booking "<id>" is received on "booking.enriched"
    And its origin is "<expectedOrigin>" and destination is "<expectedDestination>"
    And the enrichment metadata retains original values "<origin>" and "<destination>"
    And shipper, mode and requestedDate are unchanged
    And no booking "<id>" appears on "booking.flagged"
    Examples:
      | id    | origin        | destination | expectedOrigin | expectedDestination |
      | BKG-1 | Mumbi         | now delhi   | Mumbai         | New Delhi           |
      | BKG-2 | bangalor      | CHENNAI     | Bangalore      | Chennai             |
      | BKG-3 |   pune        | Hyderabad   | Pune           | Hyderabad           |
      | BKG-4 | New   Delhi   | Ahmedabd    | New Delhi      | Ahmedabad           |
      | BKG-5 | kolkatta      | Mumbai      | Kolkata        | Mumbai              |

  Scenario: Unmatched origin is flagged, not forwarded
    When a raw booking "BKG-9" with origin "Warsaw" and destination "Mumbai" is published
    Then a booking "BKG-9" is received on "booking.flagged"
    And its reasons are exactly "UNMATCHED_ORIGIN_CITY"
    And the flagged field "origin" has value "Warsaw" and no candidates
    And no booking "BKG-9" appears on "booking.enriched"

  Scenario: Unmatched destination is flagged, not forwarded
    When a raw booking "BKG-10" with origin "Mumbai" and destination "Lisbon" is published
    Then a booking "BKG-10" is received on "booking.flagged"
    And its reasons are exactly "UNMATCHED_DESTINATION_CITY"
    And no booking "BKG-10" appears on "booking.enriched"

  Scenario: Both cities unmatched produces both reasons
    When a raw booking "BKG-11" with origin "Warsaw" and destination "Lisbon" is published
    Then a booking "BKG-11" is received on "booking.flagged"
    And its reasons are exactly "UNMATCHED_ORIGIN_CITY", "UNMATCHED_DESTINATION_CITY"
    And no booking "BKG-11" appears on "booking.enriched"

  Scenario: One valid city does not rescue an invalid one
    When a raw booking "BKG-12" with origin "Mumbai" and destination "Warsaw" is published
    Then no booking "BKG-12" appears on "booking.enriched"

  Scenario: Ambiguous match is flagged with candidates
    Given the reference cities additionally include "Delhi"
    When a raw booking "BKG-13" with origin "Delh" and destination "Mumbai" is published
    Then a booking "BKG-13" is received on "booking.flagged"
    And its reasons are exactly "AMBIGUOUS_ORIGIN_CITY"
    And the flagged field "origin" has candidates "New Delhi", "Delhi"

  Scenario: Missing city is flagged
    When a raw booking "BKG-14" with origin "" and destination "Mumbai" is published
    Then a booking "BKG-14" is received on "booking.flagged"
    And its reasons are exactly "MISSING_ORIGIN_CITY"

  Scenario: Typo beyond threshold on a short name is flagged
    When a raw booking "BKG-15" with origin "Pn" and destination "Mumbai" is published
    Then a booking "BKG-15" is received on "booking.flagged"
    And its reasons are exactly "UNMATCHED_ORIGIN_CITY"

  Scenario: Malformed message is flagged and processing continues
    When the raw payload "{not json" with key "BKG-16" is published to "booking.raw"
    And a raw booking "BKG-17" with origin "Mumbai" and destination "Pune" is published
    Then a message with key "BKG-16" is received on "booking.flagged" with reason "MALFORMED_MESSAGE"
    And a booking "BKG-17" is received on "booking.enriched"

  Scenario: Message key is the bookingId on every topic
    When a raw booking "BKG-18" with origin "Mumbai" and destination "Warsaw" is published
    Then the message for "BKG-18" on "booking.flagged" has key "BKG-18"

  Scenario: Duplicate bookingId is processed each time it arrives
    When a raw booking "BKG-19" with origin "Mumbai" and destination "Pune" is published twice
    Then exactly 2 bookings "BKG-19" are received on "booking.enriched"
```

Step definitions in `src/test/java/.../steps/`. Hooks: `@Before` builds a fresh `InMemoryMessageBus` + `EnrichmentProcessor`; `@After` calls `reset()`.

> Gherkin trims leading and trailing whitespace from Examples cells, so BKG-3's `|   pune        |` reaches the step as `pune`. Internal whitespace survives, so BKG-4's `New   Delhi` does exercise the collapse rule. Leading/trailing-trim coverage therefore lives in `CityMatcherTest` (§8.1), not in the feature.

### 8.3 Data-driven sample — `bookings-sample.jsonl` (200 rows, committed)

A scenario reads the committed sample and its oracle and asserts every row landed on the expected topic with expected values.

### 8.4 Bulk — `@Tag("bulk")`, Maven profile `-Pbulk`, excluded from default `mvn verify`

1. Generate N bookings (default 20 000, override `-Dbulk.count=30000`, seed `-Dbulk.seed=42`).
2. Load into `booking.raw` via `BookingLoader`.
3. Assert aggregates:
   - `enriched.size() + flagged.size() == N`
   - no `bookingId` on both topics
   - per-topic counts equal the oracle totals
   - per-reason counts equal the oracle totals
   - random sample of 500 IDs matches oracle field-by-field
   - every enriched `origin`/`destination` is a canonical reference name
4. Log elapsed time and messages/sec.

> `byReason.AMBIGUOUS_ORIGIN_CITY` and `byReason.AMBIGUOUS_DESTINATION_CITY` are expected to be **0** in the bulk run, because §9's corruptions apply at most **one** edit to a canonical name and the closest pair of reference cities is 4 apart (`Hyderabad`/`Ahmedabad`), leaving a 1-edit corruption at least 3 from any second reference — outside the cap of 2. This is a property of the generator, not of the reference list: `ahderabad` is distance 2 from both and *would* be ambiguous, so a future generator that applies two edits could produce non-zero counts legitimately. Assertions compare per-reason counts to the oracle; they must not require any reason to be non-zero, nor assume ambiguity is unreachable.

## 9. Test-data generator

`BookingDataGenerator` (in `tools/`, runnable `main`):

```
--count 20000 --seed 42 --out data/bookings-20k.jsonl [--mix recoverable=70,flaggable=25,malformed=5]
```

Algorithm per row:
1. Pick a canonical city for origin and destination (seeded `Random`).
2. Pick a corruption per field from the configured mix:
   `NONE, UPPER, LOWER, TRIM_SPACES, DROP_CHAR, SWAP_CHARS, DOUBLE_CHAR, FOREIGN, EMPTY`.
   `FOREIGN` substitutes from a fixed list (`Warsaw, Lisbon, Dubai, Singapore, Berlin`).
3. Apply corruption to produce the raw value.
4. Label expected outcome by calling **the same `CityMatcher`** on the corrupted value — never by guessing from the corruption name. This guarantees data and contract cannot drift.
5. Write the raw booking to `<out>` (JSON Lines, streamed).
6. Write the oracle to `<out>.expected.json`: `{ totals: {enriched, flagged, byReason}, byId: { "BKG-...": {topic, origin, destination, reasons} } }`.

The `--mix` buckets select which corruption a field receives:

| Bucket | Corruptions |
|---|---|
| `recoverable` | `NONE, UPPER, LOWER, TRIM_SPACES, DROP_CHAR, SWAP_CHARS, DOUBLE_CHAR` |
| `flaggable` | `FOREIGN, EMPTY` |
| `malformed` | row emitted as broken JSON instead of a booking |

The mix biases corruption **selection** only. It never sets the expected outcome: step 4 above labels every row by running `CityMatcher` on the corrupted value, so a corruption from the `recoverable` bucket that happens to destroy the name still lands in the flagged oracle. Bucket names describe intent; the matcher decides truth.

Malformed rows: emit a deliberately broken JSON string with key `BKG-<n>`; oracle expects `MALFORMED_MESSAGE`.

`bookingId` format `BKG-%05d`; `shipper` from a small fixed list; `mode` ∈ `ROAD, RAIL, AIR, OCEAN`; `requestedDate` within ±90 days of a fixed base date.

Fixed seed ⇒ identical output every run. `data/*.jsonl` is git-ignored; the 200-row sample is committed.

`BookingLoader --file <jsonl> --topic booking.raw` streams a file into a `MessageBus`. Targets the interface so it can later push to real Kafka unchanged.

Optional, later: Mockaroo (REST API) or Tonic Fabricator for realistic shipper names / volume, and a thin MCP wrapper so Claude Code can call `generate_bookings`. Not part of the initial build.

## 10. Definition of done

- `mvn verify` green: unit tests + all Cucumber scenarios + 200-row sample.
- `mvn verify -Pbulk` green at 20 000 and 30 000 messages, runtime reported.
- Cucumber HTML + JSON reports in `target/cucumber`.
- README with: how to run, how to regenerate data, how to swap `MessageBus` for real Kafka.
