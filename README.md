# City Enrichment — BDD Test Harness

A supply-chain booking arrives on Kafka topic `booking.raw`. Its `origin` and
`destination` city names are sometimes misspelled or inconsistently cased —
`Mumbi`, `now delhi`, `Bangalor`. Before the booking is forwarded to TMS on
`booking.enriched`, each city must be corrected against a reference list. A city
that cannot be confidently matched must send the booking to `booking.flagged`
rather than pass through silently.

```
booking.raw ──▶ EnrichmentProcessor ──┬──▶ booking.enriched ──▶ TMS
                                      └──▶ booking.flagged
```

A `bookingId` lands on **exactly one** of the two output topics — never both,
never neither.

**`SPEC.md` is the contract.** This README explains how to run things;
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) explains how it fits together,
with diagrams. Where any of them disagree, `SPEC.md` wins.

---

## Quick start

Requires **JDK 21** and **Maven 3.9+**.

```bash
mvn -q verify
```

Silence means everything passed — 132 tests, and the gate prints nothing on
success so any output at all is a real signal. To see the counts:

```bash
mvn verify
```

<details>
<summary>If <code>mvn</code> picks the wrong JDK</summary>

Maven may resolve a newer JDK than 21 if several are installed. Pin it for the
command:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q verify   # macOS
```

On macOS with Homebrew, `brew install openjdk@21` is keg-only, so its path is
`/opt/homebrew/opt/openjdk@21` rather than the system JDK location.
</details>

---

## Seeing it work

`EnrichmentDemo` runs a booking through a real processor and prints where it
landed. It is a developer tool — it asserts nothing, and the test suite remains
where behaviour is actually verified.

```bash
mvn -q compile
mvn -q exec:java -Dexec.args='--origin Mumbi --destination "now delhi"'
```

```
in   origin="Mumbi"  destination="now delhi"

out  booking.enriched

{
  "bookingId" : "BKG-DEMO",
  "origin" : "Mumbai",
  "destination" : "New Delhi",
  ...
  "enrichment" : {
    "originalOrigin" : "Mumbi",
    "originalDestination" : "now delhi",
    "originConfidence" : 0.9666666666666667,
    "destinationConfidence" : 0.9333333333333333
  }
}
```

A city that cannot be matched goes the other way:

```bash
mvn -q exec:java -Dexec.args='--origin Warsaw --destination Mumbai'
```

```
out  booking.flagged

{ "reasons" : [ "UNMATCHED_ORIGIN_CITY" ],
  "fields"  : [ { "field" : "origin", "value" : "Warsaw", "candidates" : [ ] } ],
  "original": { ...the raw booking, untouched... } }
```

Replay a whole file. `data/bookings-demo.jsonl` is twelve hand-written rows, one
per outcome, meant to be read rather than generated:

```bash
mvn -q exec:java -Dexec.args='--file data/bookings-demo.jsonl'
```

| Row | origin / destination | Lands as |
|---|---|---|
| `BKG-D01` | `Mumbi` / `now delhi` | enriched — typo in both |
| `BKG-D02` | `MUMBAI` / `chennai` | enriched — casing only |
| `BKG-D03` | `  New   Delhi  ` / `Bangalor` | enriched — trim, collapse, typo |
| `BKG-D04` | `kolkatta` / `Ahmedabd` | enriched — doubled and dropped letters |
| `BKG-D05` | `Delh` / `Pune` | enriched — reached `New Delhi` via its token |
| `BKG-D06` | `Warsaw` / `Mumbai` | flagged — `UNMATCHED_ORIGIN_CITY` |
| `BKG-D07` | `Mumbai` / `Lisbon` | flagged — `UNMATCHED_DESTINATION_CITY` |
| `BKG-D08` | `Warsaw` / `Lisbon` | flagged — both reasons, origin first |
| `BKG-D09` | `""` / `Mumbai` | flagged — `MISSING_ORIGIN_CITY` |
| `BKG-D10` | `Mumbai` / `"   "` | flagged — `MISSING_DESTINATION_CITY`, whitespace is missing |
| `BKG-D11` | `Pn` / `Mumbai` | flagged — two edits on a four-letter name is past the cap |
| `BKG-D12` | truncated JSON | flagged — `MALFORMED_MESSAGE` |

Five enriched, seven flagged. Or the 200-row generated sample:

```bash
mvn -q exec:java -Dexec.args='--file data/bookings-sample.jsonl'
```

```
rows published    200

  booking.enriched   90
  booking.flagged    110

  UNMATCHED_ORIGIN_CITY        40
  UNMATCHED_DESTINATION_CITY   35
  MISSING_ORIGIN_CITY          19
  MISSING_DESTINATION_CITY     25
  MALFORMED_MESSAGE            8
```

`--cities "Mumbai,New Delhi,...,Delhi"` overrides the reference list, which is how
to see an ambiguous match:

```bash
mvn -q exec:java -Dexec.args='--origin Delh --destination Mumbai \
  --cities "Mumbai,New Delhi,Bangalore,Chennai,Kolkata,Pune,Hyderabad,Ahmedabad,Delhi"'
```

> **`BookingLoader` is not this.** It streams a file into a bus with nothing
> attached to consume it, so it publishes and counts but enriches nothing. Use
> `EnrichmentDemo --file` to see enrichment happen.

---

## What gets tested

| Layer | Tool | What it proves |
|---|---|---|
| Unit | JUnit 5 + AssertJ | Every row of the SPEC §5 sanity table and every SPEC §6 flag reason, with no bus involved |
| **BDD** | **Cucumber-JVM 7** | **End-to-end routing through the bus matches the agreed scenarios** |
| Data-driven | Cucumber + committed JSONL | Behaviour holds over a 200-row reproducible dataset compared against its oracle |
| Bulk | JUnit `@Tag("bulk")` | Aggregate invariants and throughput at 20 000 and 30 000 messages |

Testing is BDD-first. `src/test/resources/features/city_enrichment.feature` is
extracted verbatim from SPEC §8.2 and asserted byte-identical to it, so the two
cannot drift. **A scenario is never edited to make code pass.** If a scenario is
wrong, that is a spec change, and it gets discussed before any code moves.

### Running a subset

```bash
mvn test -Dtest=CityMatcherTest          # one unit class
mvn test -Dtest=RunCucumberTest          # every Cucumber scenario
mvn verify -Pbulk                        # include the bulk run
```

### Reports

After `mvn verify`:

| Report | Path |
|---|---|
| Cucumber HTML | `target/cucumber/city-enrichment.html` |
| Cucumber JSON | `target/cucumber/city-enrichment.json` |
| Coverage (JaCoCo) | `target/site/jacoco/index.html` |
| Surefire XML | `target/surefire-reports/` |

Coverage currently sits at 98.3% of instructions and 89.9% of branches. The
remaining gaps are unexercised failure paths — a missing `cities.json`, a Jackson
error that cannot be provoked through the public API — rather than untested
logic.

---

## The matching rules in one paragraph

Applied independently to `origin` and `destination`. A null, empty or
whitespace-only value is `MISSING`. Otherwise the value is trimmed, its internal
whitespace collapsed, and case-folded. An exact match against a reference name
wins outright at confidence `1.0`. Otherwise each reference gets a *comparison
set* — its full normalised name plus its whitespace tokens if multi-word — and
becomes a candidate when Levenshtein distance to any member is within that
member's cap (`1` if the member is 6 characters or fewer, else `2`) **and** the
best Jaro-Winkler similarity across the set is at least `0.85`. Two or more
candidates is `AMBIGUOUS`; zero is `UNMATCHED`; exactly one is a match.

Token matching is what lets a short input reach a multi-word reference: `Delh` is
5 edits from `new delhi` as a whole, but 1 from its token `delhi`.

If **either** city fails, the whole booking is flagged. TMS never receives a
half-enriched booking.

Full detail, including the sanity table, is in SPEC §5.

---

## Regenerating the test data

The committed 200-row sample and its oracle live in `data/`:

| File | Purpose |
|---|---|
| `data/bookings-sample.jsonl` | 200 raw bookings, seed 42 |
| `data/bookings-sample.jsonl.expected.json` | Where every row is expected to land |

Larger files are git-ignored; only the 200-row sample is committed.

### Regenerate the committed sample

```bash
mvn -q compile
mvn -q exec:java -Dexec.mainClass=com.cozentus.enrichment.tools.BookingDataGenerator \
  -Dexec.args="--count 200 --seed 42 --out data/bookings-sample.jsonl"
```

Same seed, byte-identical output. Regenerating with the committed seed should
leave `git status` clean — if it does not, something in the matching contract
changed and the diff shows you exactly what.

### Generate a larger set

```bash
mvn -q exec:java -Dexec.mainClass=com.cozentus.enrichment.tools.BookingDataGenerator \
  -Dexec.args="--count 20000 --seed 42 --out data/bookings-20k.jsonl \
               --mix recoverable=70,flaggable=25,malformed=5"
```

`exec:java` defaults to `EnrichmentDemo` (see below), so the generator needs its
main class named explicitly. The classpath scope is preset either way.

| Flag | Default | Meaning |
|---|---|---|
| `--count` | `20000` | Rows to generate |
| `--seed` | `42` | Fixed seed; identical output every run |
| `--out` | `data/bookings-20k.jsonl` | Output path; the oracle is written to `<out>.expected.json` |
| `--mix` | `recoverable=70,flaggable=25,malformed=5` | Which corruption bucket a field is drawn from |

The mix biases *corruption selection* only. **It never decides the expected
outcome** — every row is labelled by running the real `CityMatcher` over the
corrupted value. `BKG-00000` in the committed sample shows why that matters: a
`SWAP_CHARS` from the `recoverable` bucket turned `Pune` into `Pnue`, which is
distance 2 against a four-character name and therefore lands `UNMATCHED`.
Inferring the label from the bucket name would have made the oracle wrong.

### The bulk run

```bash
mvn -q verify -Pbulk                            # 20 000, seed 42
mvn -q verify -Pbulk -Dbulk.count=30000         # 30 000
mvn -q verify -Pbulk -Dbulk.seed=99             # a different distribution
```

Measured locally (Apple silicon, JDK 21) and on GitHub's shared runners:

| Messages | Local | CI | Enriched | Flagged |
|---|---|---|---|---|
| 20 000 | 111 732 msg/sec | 20 161 msg/sec | 9 717 | 10 283 |
| 30 000 | 129 870 msg/sec | 19 330 msg/sec | 14 529 | 15 471 |

Throughput differs by 5–6× between machines; the enriched and flagged counts are
identical. Same seed, different hardware, same distribution to the message.

These figures measure the enrichment path and JSON binding over an in-memory
queue with synchronous delivery. They are a regression signal for "did this
change make matching dramatically slower" — **not** a Kafka capacity estimate.

---

## Swapping `MessageBus` for real Kafka

Everything above the bus depends on the `MessageBus` interface, never on the
in-memory implementation. `EnrichmentProcessor` takes a `MessageBus` in its
constructor and `Topics` is the only place topic names are written down.

```java
public interface MessageBus {
    void publish(String topic, String key, String payload);
    List<Message> consume(String topic);
    void subscribe(String topic, Consumer<Message> handler);
    void reset();
}
```

### What a Kafka implementation looks like

```java
public final class KafkaMessageBus implements MessageBus {

    private final KafkaProducer<String, String> producer;
    private final String bootstrapServers;
    private final List<AutoCloseable> consumers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String topic, String key, String payload) {
        producer.send(new ProducerRecord<>(topic, key, payload));
    }

    @Override
    public void subscribe(String topic, Consumer<Message> handler) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps());
        consumer.subscribe(List.of(topic));
        Thread poller = Thread.ofVirtual().start(() -> {
            while (running) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    try {
                        handler.accept(new Message(record.topic(), record.key(),
                                record.value(), record.offset(),
                                Instant.ofEpochMilli(record.timestamp())));
                    } catch (RuntimeException e) {
                        log.warn("Subscriber on {} threw for key {}", topic, record.key(), e);
                    }
                }
            }
        });
        consumers.add(...);
    }
}
```

Then wire it:

```java
MessageBus bus = new KafkaMessageBus(bootstrapServers);
new EnrichmentProcessor(bus, new BookingEnricher(
        new CityMatcher(CityReference.fromClasspath()))).start();
```

**Nothing in `matcher/`, `enrich/`, `model/` or `processor/` changes.** Add the
`kafka-clients` dependency and one new class in `bus/`.

### What the swap costs you

Be clear-eyed about this — it is the reason the harness uses a fake in the first
place.

| Concern | In-memory | Real Kafka |
|---|---|---|
| Delivery | Synchronous, inside `publish` | Asynchronous, via a poll loop |
| Negative assertions | Exact — when `publish` returns, processing is done | Require polling or a timeout, and become flaky |
| `consume(topic)` | Snapshot of everything so far | No equivalent; a consumer group has a position, not a history |
| Ordering | Per-topic insertion order | Per-partition only |
| `reset()` | Clears state | Needs topic deletion or fresh topic names per test |

`consume` is the awkward one: it exists so tests can ask "what ended up on this
topic", which Kafka has no direct answer for. A production `KafkaMessageBus`
would either throw `UnsupportedOperationException` for it, or a test-side adapter
would keep its own record of everything a subscriber saw.

Assertions like *"no booking BKG-9 appears on `booking.enriched`"* — which appear
in nearly every scenario — are exact today only because delivery is synchronous.
Against real Kafka they become "nothing arrived within N seconds", which is a
weaker claim and a slower, flakier suite. That trade is why SPEC §2 fixes the
in-memory bus for the harness while keeping the seam clean for production.

---

## Project layout

```
src/main/java/com/cozentus/enrichment/
├── model/       Booking, EnrichedBooking, FlaggedBooking, FlagReason, FieldFlag,
│                EnrichmentResult (sealed over the two outcomes)
├── matcher/     CityReference, CityMatcher, MatchResult (sealed)
├── enrich/      BookingEnricher          ← pure: Booking -> Enriched | Flagged
├── bus/         MessageBus, Message, InMemoryMessageBus
├── processor/   EnrichmentProcessor, Topics
├── tools/       BookingDataGenerator, BookingLoader
└── JsonSupport  the one shared ObjectMapper
```

`matcher/` and `enrich/` must not depend on `bus/` or `processor/` — that is what
keeps the matching logic a pure function, testable with no infrastructure and
reusable outside a messaging context. `processor/` depends only on the
`MessageBus` interface.

---

## CI

`.github/workflows/ci.yml`

| Job | Trigger | Does |
|---|---|---|
| **Build and test** | push to `main`, pull requests, manual | JDK 21 + Maven cache, then `mvn -B verify` |
| **Bulk** | manual, and nightly at 02:17 UTC | `-Pbulk` matrixed over 20 000 and 30 000 |

Cucumber, JaCoCo and Surefire reports upload as artifacts on **every** run
including failures, so a red build is diagnosable from the run page without
reproducing it locally.

---

## Stack

Java 21 · Maven · JUnit 5 · Cucumber-JVM 7 · Jackson · Apache Commons Text ·
AssertJ. No Spring, no Docker, no Testcontainers, no embedded Kafka.

Commons Text is not incidental — it supplies the `LevenshteinDistance` and
`JaroWinklerSimilarity` implementations the matching contract is built on.
