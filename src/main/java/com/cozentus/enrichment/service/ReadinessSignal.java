package com.cozentus.enrichment.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells the suite when it is safe to publish (CH-05).
 *
 * <p>{@code subscribe} does not replay history, so a message published before the
 * processor has subscribed is silently lost. Polling for a log line would be
 * fragile; this is an explicit signal.
 *
 * <p>Uses the JDK's own HTTP server, so it adds no dependency.
 */
public final class ReadinessSignal implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ReadinessSignal.class.getName());

    private final HttpServer server;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean open = new AtomicBoolean(true);

    private ReadinessSignal(HttpServer server) {
        this.server = server;
    }

    /** @param port the port to bind, or 0 for an ephemeral one */
    public static ReadinessSignal start(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            ReadinessSignal signal = new ReadinessSignal(server);

            server.createContext("/ready", exchange ->
                    signal.respond(exchange, signal.ready.get() ? 200 : 503,
                            signal.ready.get() ? "ready" : "not ready"));
            server.createContext("/health", exchange -> signal.respond(exchange, 200, "ok"));
            server.createContext("/", exchange -> signal.respond(exchange, 404, "not found"));

            server.setExecutor(null);
            server.start();
            return signal;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not bind the readiness endpoint on port " + port, e);
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Called once the processor has subscribed to the raw topic. */
    public void markReady() {
        if (ready.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO, "Readiness endpoint reporting ready on port " + port());
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            server.stop(0);
        }
    }
}
