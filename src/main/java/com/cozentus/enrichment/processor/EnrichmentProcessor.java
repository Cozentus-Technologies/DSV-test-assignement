package com.cozentus.enrichment.processor;

import com.cozentus.enrichment.JsonSupport;
import com.cozentus.enrichment.bus.Message;
import com.cozentus.enrichment.bus.MessageBus;
import com.cozentus.enrichment.config.TopicNames;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.model.Booking;
import com.cozentus.enrichment.model.EnrichedBooking;
import com.cozentus.enrichment.model.EnrichmentResult;
import com.cozentus.enrichment.model.FlaggedBooking;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** CH-06: stamped by the service on every outbound message. */
    private static final String STATUS_HEADER = "x-enrichment-status";
    private static final String VERSION_HEADER = "x-enrichment-version";
    private static final String PROCESSED_AT_HEADER = "x-processed-at";

    private static final String ENRICHED_STATUS = "ENRICHED";
    private static final String FLAGGED_STATUS = "FLAGGED";

    private static final String VERSION = versionOf();

    private static String versionOf() {
        String declared = EnrichmentProcessor.class.getPackage().getImplementationVersion();
        return declared == null ? "dev" : declared;
    }

    private final MessageBus bus;
    private final BookingEnricher enricher;
    private final TopicNames topics;

    /** Uses the default topic names. */
    public EnrichmentProcessor(MessageBus bus, BookingEnricher enricher) {
        this(bus, enricher, new TopicNames(Topics.RAW, Topics.ENRICHED, Topics.FLAGGED));
    }

    /**
     * CH-04: topic names come from configuration so the suite can provision
     * unique topics per scenario. Without that, scenarios contaminate each other.
     */
    public EnrichmentProcessor(MessageBus bus, BookingEnricher enricher, TopicNames topics) {
        this.bus = bus;
        this.enricher = enricher;
        this.topics = topics;
    }

    public void start() {
        bus.subscribe(topics.raw(), this::onMessage);
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
            route(booking, enricher.enrich(booking), message);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Could not process message with key " + message.key(), e);
            flagMalformed(message);
        }
    }

    private void route(Booking booking, EnrichmentResult result, Message inbound) {
        // Exhaustive over the sealed EnrichmentResult: a new outcome would fail
        // to compile here rather than silently go unrouted.
        switch (result) {
            case EnrichedBooking enriched -> bus.publish(topics.enriched(), booking.bookingId(),
                    JsonSupport.write(enriched), outboundHeaders(inbound, ENRICHED_STATUS));
            case FlaggedBooking flagged -> bus.publish(topics.flagged(), booking.bookingId(),
                    JsonSupport.write(flagged), outboundHeaders(inbound, FLAGGED_STATUS));
        }
    }

    private void flagMalformed(Message message) {
        String key = message.key() == null ? UNKNOWN_KEY : message.key();
        bus.publish(topics.flagged(), key,
                JsonSupport.write(FlaggedBooking.malformed(message.payload())),
                outboundHeaders(message, FLAGGED_STATUS));
    }

    /**
     * CH-06: every inbound header is carried through, so a correlation-id set by
     * the publisher survives and a booking stays traceable across systems. The
     * service's own three are then stamped on top.
     */
    private static Map<String, String> outboundHeaders(Message inbound, String status) {
        Map<String, String> headers = new LinkedHashMap<>(inbound.headers());
        headers.put(STATUS_HEADER, status);
        headers.put(VERSION_HEADER, VERSION);
        headers.put(PROCESSED_AT_HEADER, Instant.now().toString());
        return headers;
    }
}
