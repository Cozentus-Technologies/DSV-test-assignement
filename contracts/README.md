# Published contract schemas (CH-09)

JSON Schema (draft 2020-12) contracts for the three Kafka topics in the enrichment
pipeline, derived verbatim from `SPEC.md` section 4 (payload shapes) and section 6
(flag reasons). These are the producer's published contract: the test suite
validates every outbound message against the matching file here.

| File | Topic | Describes |
|---|---|---|
| `booking-raw-v1.schema.json` | `booking.raw` | What the service accepts |
| `booking-enriched-v1.schema.json` | `booking.enriched` | What TMS receives |
| `booking-flagged-v1.schema.json` | `booking.flagged` | What the flag route emits |

## Versioning rule

The version is in the filename (`-v1`), not in the schema body. **A breaking change
means a new version file — `booking-enriched-v2.schema.json` — never an edit to the
existing one.** Consumers pin to the version they were built against; an old
version file is never deleted or mutated once another topic/consumer may depend
on it. A non-breaking, purely-additive documentation change (e.g. a clarified
`description`) may be made in place, but any change to `required`, `type`,
`enum`, or `additionalProperties` is a new version file.

## Deviation from CH-09

CH-09 asked for a `"status": { "enum": ["ENRICHED"] }` property on the enriched
schema so that a flagged booking could never validate against it. That field does
not exist in the payload: `SPEC.md` section 4 defines no `status` field — the
enrichment outcome travels only as the Kafka header `x-enrichment-status` — and
`APP_CHANGE_SPEC.md` section 3 explicitly forbids changing the payload shapes in
`SPEC.md` section 4. Adding the field to satisfy CH-09 would itself violate the
spec it is meant to protect. Instead, the same guarantee is achieved
structurally: `booking-enriched-v1.schema.json` sets `additionalProperties: false`
and requires `shipper`, `mode`, `requestedDate`, and `enrichment` (none of which a
`booking.flagged` payload carries), while `booking-flagged-v1.schema.json`
requires `reasons` and `fields` (which an enriched payload does not carry) and is
likewise closed with `additionalProperties: false`. A flagged payload genuinely
fails validation against the enriched schema, and vice versa, without inventing
a field the wire format does not have. See the `$comment` in
`booking-enriched-v1.schema.json` for the same explanation in place.
