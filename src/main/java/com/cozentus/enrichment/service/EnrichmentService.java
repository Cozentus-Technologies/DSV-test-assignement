package com.cozentus.enrichment.service;

import com.cozentus.enrichment.bus.KafkaMessageBus;
import com.cozentus.enrichment.config.KafkaConfig;
import com.cozentus.enrichment.enrich.BookingEnricher;
import com.cozentus.enrichment.matcher.CityMatcher;
import com.cozentus.enrichment.matcher.CityReference;
import com.cozentus.enrichment.processor.EnrichmentProcessor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The long-running entry point (CH-02).
 *
 * <p>Subscribes to the raw topic <em>before</em> signalling readiness: subscribe
 * does not replay history, so anything published beforehand is silently lost.
 */
public final class EnrichmentService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(EnrichmentService.class.getName());

    private final KafkaMessageBus bus;
    private final ReadinessSignal readiness;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private EnrichmentService(KafkaMessageBus bus, ReadinessSignal readiness) {
        this.bus = bus;
        this.readiness = readiness;
    }

    /** Starts and returns immediately; the caller decides whether to block. */
    public static EnrichmentService start(KafkaConfig config) {
        // System.Logger formats with MessageFormat, not printf, so the summary is
        // assembled here. CH-02 requires broker, topics, group and city source in
        // the startup line, so a failing run is diagnosable from logs alone.
        LOG.log(System.Logger.Level.INFO,
                "Starting city enrichment service\n" + config.describe());

        KafkaMessageBus bus = new KafkaMessageBus(config);
        ReadinessSignal readiness = ReadinessSignal.start(config.readinessPort());

        CityReference cities = CityReference.fromSource(config.citiesSource());
        new EnrichmentProcessor(bus, new BookingEnricher(new CityMatcher(cities)), config.topics())
                .start();

        // Only now is it safe for a publisher to send.
        readiness.markReady();
        LOG.log(System.Logger.Level.INFO,
                "Subscribed to " + config.topics().raw() + "; ready on port " + readiness.port());

        return new EnrichmentService(bus, readiness);
    }

    public int readinessPort() {
        return readiness.port();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        LOG.log(System.Logger.Level.INFO, "Shutting down: stopping poll loops and flushing producer");
        readiness.close();
        bus.close();
    }

    public static void main(String[] args) throws InterruptedException {
        EnrichmentService service = start(KafkaConfig.fromEnvironment(args));
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "shutdown"));
        Thread.currentThread().join();
    }
}
