package com.cozentus.enrichment.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.FieldFlag;
import com.cozentus.enrichment.model.FlagReason;
import com.cozentus.enrichment.model.FlaggedBooking;
import com.cozentus.enrichment.support.ScenarioContext;
import io.cucumber.java.en.Then;
import java.util.List;

/** Then steps: what landed on which topic, and what it contained. */
public class AssertionSteps {

    private final ScenarioContext context;

    public AssertionSteps(ScenarioContext context) {
        this.context = context;
    }

    // --- routing --------------------------------------------------------

    @Then("a booking {string} is received on {string}")
    public void aBookingIsReceivedOn(String bookingId, String topic) {
        assertThat(messagesFor(topic, bookingId))
                .as("messages keyed %s on %s", bookingId, topic)
                .hasSize(1);
        context.rememberMessage(messagesFor(topic, bookingId).get(0));
    }

    @Then("no booking {string} appears on {string}")
    public void noBookingAppearsOn(String bookingId, String topic) {
        assertThat(messagesFor(topic, bookingId))
                .as("messages keyed %s on %s", bookingId, topic)
                .isEmpty();
    }

    @Then("exactly {int} bookings {string} are received on {string}")
    public void exactlyNBookingsAreReceivedOn(int count, String bookingId, String topic) {
        assertThat(messagesFor(topic, bookingId)).hasSize(count);
    }

    @Then("the message for {string} on {string} has key {string}")
    public void theMessageHasKey(String bookingId, String topic, String expectedKey) {
        assertThat(messagesFor(topic, bookingId))
                .singleElement()
                .extracting(Message::key)
                .isEqualTo(expectedKey);
    }

    // --- enriched content -----------------------------------------------

    @Then("its origin is {string} and destination is {string}")
    public void itsOriginAndDestinationAre(String origin, String destination) {
        EnrichedBooking enriched = enriched();
        assertThat(enriched.origin()).isEqualTo(origin);
        assertThat(enriched.destination()).isEqualTo(destination);
    }

    @Then("the enrichment metadata retains original values {string} and {string}")
    public void theMetadataRetainsOriginalValues(String origin, String destination) {
        EnrichedBooking enriched = enriched();
        assertThat(enriched.enrichment().originalOrigin()).isEqualTo(origin);
        assertThat(enriched.enrichment().originalDestination()).isEqualTo(destination);
    }

    @Then("shipper, mode and requestedDate are unchanged")
    public void nonCityFieldsAreUnchanged() {
        EnrichedBooking enriched = enriched();
        Booking raw = context.publishedBooking(enriched.bookingId());

        assertThat(enriched.shipper()).isEqualTo(raw.shipper());
        assertThat(enriched.mode()).isEqualTo(raw.mode());
        assertThat(enriched.requestedDate()).isEqualTo(raw.requestedDate());
    }

    // --- flagged content ------------------------------------------------

    @Then("^its reasons are exactly (.+)$")
    public void itsReasonsAreExactly(String quotedReasons) {
        List<FlagReason> expected = PublishSteps.quotedList(quotedReasons).stream()
                .map(FlagReason::valueOf)
                .toList();

        assertThat(flagged().reasons()).containsExactlyElementsOf(expected);
    }

    @Then("the flagged field {string} has value {string} and no candidates")
    public void theFlaggedFieldHasValueAndNoCandidates(String field, String value) {
        FieldFlag flag = flagFor(field);
        assertThat(flag.value()).isEqualTo(value);
        assertThat(flag.candidates()).isEmpty();
    }

    @Then("^the flagged field \"([^\"]*)\" has candidates (.+)$")
    public void theFlaggedFieldHasCandidates(String field, String quotedCandidates) {
        assertThat(flagFor(field).candidates())
                .containsExactlyElementsOf(PublishSteps.quotedList(quotedCandidates));
    }

    @Then("a message with key {string} is received on {string} with reason {string}")
    public void aMessageWithKeyIsReceivedWithReason(String key, String topic, String reason) {
        assertThat(messagesFor(topic, key)).as("messages keyed %s on %s", key, topic).hasSize(1);

        FlaggedBooking flaggedBooking =
                JsonSupport.read(messagesFor(topic, key).get(0).payload(), FlaggedBooking.class);
        assertThat(flaggedBooking.reasons()).containsExactly(FlagReason.valueOf(reason));
    }

    // --- helpers --------------------------------------------------------

    private List<Message> messagesFor(String topic, String key) {
        return context.bus().consume(topic).stream()
                .filter(message -> key.equals(message.key()))
                .toList();
    }

    private EnrichedBooking enriched() {
        return JsonSupport.read(context.lastMessage().payload(), EnrichedBooking.class);
    }

    private FlaggedBooking flagged() {
        return JsonSupport.read(context.lastMessage().payload(), FlaggedBooking.class);
    }

    private FieldFlag flagFor(String field) {
        return flagged().fields().stream()
                .filter(flag -> flag.field().equals(field))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No flag recorded for field " + field));
    }
}
