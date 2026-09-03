package com.cozentus.enrichment.support;

import com.cozentus.enrichment.bus.InMemoryMessageBus;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.CityReference;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.processor.EnrichmentProcessor;
import com.cozentus.enrichment.tools.BookingDataGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State shared between step classes within one scenario, injected by
 * cucumber-picocontainer. A fresh instance is created per scenario.
 */
public class ScenarioContext {

    private final List<String> referenceCities = new ArrayList<>();
    private final Map<String, Booking> publishedBookings = new LinkedHashMap<>();

    private MessageBus bus;
    private Message lastMessage;
    private BookingDataGenerator.Oracle oracle;

    public void useReferenceCities(List<String> cities) {
        referenceCities.clear();
        referenceCities.addAll(cities);
    }

    /**
     * Rebuilds the bus and processor around the extended reference list.
     *
     * <p>The bus has to be rebuilt rather than re-subscribed: leaving the old
     * processor attached would double-process every message. Rebuilding discards
     * anything already published, so this is only valid before the first publish
     * — the guard below makes a misuse fail loudly instead of silently losing
     * messages.
     */
    public void additionallyIncludeReferenceCity(String city) {
        if (bus != null && !bus.consume(com.cozentus.enrichment.processor.Topics.RAW).isEmpty()) {
            throw new IllegalStateException(
                    "Reference cities must be extended before any booking is published");
        }
        referenceCities.add(city);
        startProcessor();
    }

    /** SPEC 8.2 hook: a fresh bus and processor, wired and subscribed. */
    public void startProcessor() {
        bus = new InMemoryMessageBus();
        CityReference reference = CityReference.of(referenceCities.toArray(String[]::new));
        new EnrichmentProcessor(bus, new BookingEnricher(new CityMatcher(reference))).start();
    }

    public MessageBus bus() {
        return bus;
    }

    public void recordPublished(Booking booking) {
        publishedBookings.put(booking.bookingId(), booking);
    }

    public Booking publishedBooking(String bookingId) {
        Booking booking = publishedBookings.get(bookingId);
        if (booking == null) {
            throw new IllegalStateException("No raw booking was published for " + bookingId);
        }
        return booking;
    }

    public void rememberMessage(Message message) {
        this.lastMessage = message;
    }

    /** The message located by the most recent "is received on" step. */
    public Message lastMessage() {
        if (lastMessage == null) {
            throw new IllegalStateException("No message has been located yet in this scenario");
        }
        return lastMessage;
    }

    public void useOracle(BookingDataGenerator.Oracle oracle) {
        this.oracle = oracle;
    }

    public BookingDataGenerator.Oracle oracle() {
        if (oracle == null) {
            throw new IllegalStateException("No oracle has been loaded in this scenario");
        }
        return oracle;
    }

    public void tearDown() {
        if (bus != null) {
            bus.reset();
        }
    }
}
