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
    And a raw booking "BKG-20" with origin "Mumbi" and destination "Pune" is published
    Then the message for "BKG-18" on "booking.flagged" has key "BKG-18"
    And the message for "BKG-20" on "booking.enriched" has key "BKG-20"

  Scenario: Duplicate bookingId is processed each time it arrives
    When a raw booking "BKG-19" with origin "Mumbai" and destination "Pune" is published twice
    Then exactly 2 bookings "BKG-19" are received on "booking.enriched"
