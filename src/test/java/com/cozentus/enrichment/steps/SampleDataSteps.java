package com.cozentus.enrichment.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.FlagReason;
import com.cozentus.enrichment.model.FlaggedBooking;
import com.cozentus.enrichment.processor.Topics;
import com.cozentus.enrichment.support.ScenarioContext;
import com.cozentus.enrichment.tools.BookingDataGenerator.Expectation;
import com.cozentus.enrichment.tools.BookingDataGenerator.Oracle;
import com.cozentus.enrichment.tools.BookingLoader;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** SPEC 8.3: replays the committed sample against its oracle. */
public class SampleDataSteps {

    private final ScenarioContext context;

    public SampleDataSteps(ScenarioContext context) {
        this.context = context;
    }

    @When("the sample file {string} is loaded into {string}")
    public void theSampleFileIsLoaded(String file, String topic) throws IOException {
        Path data = Path.of(file);
        Path oraclePath = Path.of(file + ".expected.json");

        assertThat(data).as("committed sample").exists();
        assertThat(oraclePath).as("committed oracle").exists();

        context.useOracle(JsonSupport.read(Files.readString(oraclePath), Oracle.class));
        int loaded = BookingLoader.load(data, topic, context.bus());

        assertThat(loaded)
                .as("rows loaded should match the oracle's row count")
                .isEqualTo(context.oracle().byId().size());
    }

    @Then("every row lands on the topic its oracle predicts")
    public void everyRowLandsOnItsPredictedTopic() {
        Map<String, String> actual = actualTopicByKey();

        context.oracle().byId().forEach((bookingId, expectation) ->
                assertThat(actual.get(bookingId))
                        .as("topic for %s", bookingId)
                        .isEqualTo(expectation.topic()));
    }

    @Then("every enriched row has the origin and destination its oracle predicts")
    public void everyEnrichedRowMatches() {
        for (Message message : context.bus().consume(Topics.ENRICHED)) {
            EnrichedBooking enriched = JsonSupport.read(message.payload(), EnrichedBooking.class);
            Expectation expected = context.oracle().byId().get(message.key());

            assertThat(expected).as("oracle entry for %s", message.key()).isNotNull();
            assertThat(enriched.origin()).as("origin of %s", message.key())
                    .isEqualTo(expected.origin());
            assertThat(enriched.destination()).as("destination of %s", message.key())
                    .isEqualTo(expected.destination());
        }
    }

    @Then("every flagged row has the reasons its oracle predicts")
    public void everyFlaggedRowMatches() {
        for (Message message : context.bus().consume(Topics.FLAGGED)) {
            FlaggedBooking flagged = JsonSupport.read(message.payload(), FlaggedBooking.class);
            Expectation expected = context.oracle().byId().get(message.key());

            assertThat(expected).as("oracle entry for %s", message.key()).isNotNull();
            assertThat(flagged.reasons()).as("reasons for %s", message.key())
                    .isEqualTo(expected.reasons());
        }
    }

    @Then("the per-topic totals match the oracle")
    public void perTopicTotalsMatch() {
        assertThat(context.bus().consume(Topics.ENRICHED))
                .as("enriched count").hasSize(context.oracle().totals().enriched());
        assertThat(context.bus().consume(Topics.FLAGGED))
                .as("flagged count").hasSize(context.oracle().totals().flagged());
        assertThat(context.bus().consume(Topics.ENRICHED).size()
                + context.bus().consume(Topics.FLAGGED).size())
                .as("nothing lost, nothing duplicated")
                .isEqualTo(context.oracle().byId().size());
    }

    @Then("the per-reason totals match the oracle")
    public void perReasonTotalsMatch() {
        Map<FlagReason, Integer> actual = new EnumMap<>(FlagReason.class);
        for (Message message : context.bus().consume(Topics.FLAGGED)) {
            for (FlagReason reason : JsonSupport.read(message.payload(), FlaggedBooking.class).reasons()) {
                actual.merge(reason, 1, Integer::sum);
            }
        }

        for (FlagReason reason : FlagReason.values()) {
            assertThat(actual.getOrDefault(reason, 0))
                    .as("count of %s", reason)
                    .isEqualTo(context.oracle().totals().byReason().getOrDefault(reason, 0));
        }
    }

    @Then("no bookingId appears on both output topics")
    public void noBookingIdOnBothTopics() {
        List<String> enriched = keys(Topics.ENRICHED);
        List<String> flagged = keys(Topics.FLAGGED);

        assertThat(enriched).doesNotContainAnyElementsOf(flagged);
    }

    private List<String> keys(String topic) {
        return context.bus().consume(topic).stream().map(Message::key).toList();
    }

    private Map<String, String> actualTopicByKey() {
        Map<String, String> byKey = new java.util.LinkedHashMap<>();
        keys(Topics.ENRICHED).forEach(key -> byKey.put(key, Topics.ENRICHED));
        keys(Topics.FLAGGED).forEach(key -> byKey.put(key, Topics.FLAGGED));
        return byKey;
    }
}
