package com.cozentus.enrichment.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.processor.Topics;
import com.cozentus.enrichment.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import java.util.Arrays;
import java.util.List;

/** Given/When steps: reference data, processor wiring, and publishing. */
public class PublishSteps {

    private static final String SHIPPER = "ABC Logistics";
    private static final String MODE = "ROAD";
    private static final String REQUESTED_DATE = "2026-09-05";

    private final ScenarioContext context;

    public PublishSteps(ScenarioContext context) {
        this.context = context;
    }

    @Given("^the reference cities are (.+)$")
    public void theReferenceCitiesAre(String commaSeparated) {
        context.useReferenceCities(Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .toList());
    }

    @Given("the enrichment processor is consuming from {string}")
    public void theProcessorIsConsumingFrom(String topic) {
        assertThat(topic).isEqualTo(Topics.RAW);
        context.startProcessor();
    }

    @Given("the reference cities additionally include {string}")
    public void theReferenceCitiesAdditionallyInclude(String city) {
        context.additionallyIncludeReferenceCity(city);
    }

    @When("a raw booking {string} with origin {string} and destination {string} is published")
    public void aRawBookingIsPublished(String bookingId, String origin, String destination) {
        publish(bookingId, origin, destination);
    }

    @When("a raw booking {string} with origin {string} and destination {string} is published twice")
    public void aRawBookingIsPublishedTwice(String bookingId, String origin, String destination) {
        publish(bookingId, origin, destination);
        publish(bookingId, origin, destination);
    }

    @When("the raw payload {string} with key {string} is published to {string}")
    public void theRawPayloadIsPublished(String payload, String key, String topic) {
        context.bus().publish(topic, key, payload);
    }

    private void publish(String bookingId, String origin, String destination) {
        Booking booking = new Booking(bookingId, SHIPPER, origin, destination, MODE, REQUESTED_DATE);
        context.recordPublished(booking);
        context.bus().publish(Topics.RAW, bookingId, JsonSupport.write(booking));
    }

    /** Splits a Gherkin tail such as {@code "A", "B"} into the values A and B. */
    static List<String> quotedList(String tail) {
        return Arrays.stream(tail.split(","))
                .map(String::trim)
                .map(value -> value.replaceAll("^\"|\"$", ""))
                .toList();
    }
}
