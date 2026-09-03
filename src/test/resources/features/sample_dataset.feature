Feature: The committed sample replays exactly as its oracle predicts

  Regression guard (SPEC 8.3). The 200 rows in data/bookings-sample.jsonl and the
  oracle beside them were generated together with a fixed seed and are committed.
  Any change that moves a booking to a different topic, corrects a city
  differently, or alters a flag reason will fail here.

  Background:
    Given the reference cities are Mumbai, New Delhi, Bangalore, Chennai, Kolkata, Pune, Hyderabad, Ahmedabad
    And the enrichment processor is consuming from "booking.raw"

  Scenario: Every row in the committed sample lands where its oracle predicts
    When the sample file "data/bookings-sample.jsonl" is loaded into "booking.raw"
    Then every row lands on the topic its oracle predicts
    And every enriched row has the origin and destination its oracle predicts
    And every flagged row has the reasons its oracle predicts
    And the per-topic totals match the oracle
    And the per-reason totals match the oracle
    And no bookingId appears on both output topics
