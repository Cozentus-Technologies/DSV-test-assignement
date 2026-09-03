package com.cozentus.enrichment.processor;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.EnrichmentResult;
import com.cozentus.enrichment.model.FlaggedBooking;

/**
 * Consumes {@code booking.raw} and routes each booking to exactly one output
 * topic. The only class that knows topic names.
 *
 * <p>Depends on the {@link MessageBus} interface alone, never on the in-memory
 * implementation, so a real Kafka client can be dropped in unchanged.
 */
public final class EnrichmentProcessor {

    private static final System.Logger LOG = System.getLogger(EnrichmentProcessor.class.getName());

    /** SPEC 4: the key for a malformed message with no key of its own. */
    private static final String UNKNOWN_KEY = "UNKNOWN";

    private final MessageBus bus;
    private final BookingEnricher enricher;

    public EnrichmentProcessor(MessageBus bus, BookingEnricher enricher) {
        this.bus = bus;
        this.enricher = enricher;
    }

    public void start() {
        bus.subscribe(Topics.RAW, this::onMessage);
    }

    /**
     * Never throws. Delivery is synchronous, so an exception escaping here would
     * surface inside the producer's publish call and halt the pipeline — one bad
     * message would stop every message after it.
     */
    private void onMessage(Message message) {
        try {
            Booking booking = JsonSupport.read(message.payload(), Booking.class);
            if (booking == null) {
                flagMalformed(message);
                return;
            }
            route(booking, enricher.enrich(booking));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Could not process message with key " + message.key(), e);
            flagMalformed(message);
        }
    }

    private void route(Booking booking, EnrichmentResult result) {
        // Exhaustive over the sealed EnrichmentResult: a new outcome would fail
        // to compile here rather than silently go unrouted.
        switch (result) {
            case EnrichedBooking enriched ->
                    bus.publish(Topics.ENRICHED, booking.bookingId(), JsonSupport.write(enriched));
            case FlaggedBooking flagged ->
                    bus.publish(Topics.FLAGGED, booking.bookingId(), JsonSupport.write(flagged));
        }
    }

    private void flagMalformed(Message message) {
        String key = message.key() == null ? UNKNOWN_KEY : message.key();
        bus.publish(Topics.FLAGGED, key, JsonSupport.write(FlaggedBooking.malformed(message.payload())));
    }
}
