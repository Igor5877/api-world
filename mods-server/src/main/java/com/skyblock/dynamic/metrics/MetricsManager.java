package com.skyblock.dynamic.metrics;

import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import org.slf4j.Logger;

import java.io.IOException;

public class MetricsManager {

    private final Logger logger;
    private HTTPServer server;

    // --- Metrics ---
    public static final Gauge tps = Gauge.build()
            .name("minecraft_tps")
            .help("Ticks per second.")
            .register();

    public static final Gauge online_players = Gauge.build()
            .name("minecraft_online_players")
            .help("Number of online players.")
            .register();

    public static final Gauge websocket_status = Gauge.build()
            .name("skyblock_websocket_status")
            .help("WebSocket connection status (1 = connected, 0 = disconnected).")
            .register();

    public MetricsManager(Logger logger) {
        this.logger = logger;
    }

    public void startServer(int port) {
        try {
            server = new HTTPServer(port);
            logger.info("Prometheus metrics server started on port " + port);
        } catch (IOException e) {
            logger.error("Failed to start Prometheus metrics server", e);
        }
    }

    public void stopServer() {
        if (server != null) {
            server.close();
            logger.info("Prometheus metrics server stopped.");
        }
    }
}
