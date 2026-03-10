package com.skyblockdynamic.nestworld.velocity.network;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;

import java.io.IOException;

public class MetricsManager {

    private final Logger logger;
    private HTTPServer metricsServer;

    // Define Metrics
    public static final Counter islandConnectAttempts = Counter.build()
            .name("velocity_island_connect_attempts_total")
            .help("Total attempts to connect to an island.")
            .labelNames("result") // e.g., "success", "failed_api", "timeout"
            .register();

    public static final Gauge onlinePlayersOnIslands = Gauge.build()
            .name("velocity_players_on_islands_total")
            .help("Number of players currently connected to dynamic islands.")
            .register();

    public static final Counter apiRequestsTotal = Counter.build()
            .name("velocity_api_requests_total")
            .help("Total requests made to the Python API.")
            .labelNames("endpoint", "status") // e.g., "islands/status", "200"
            .register();

    public MetricsManager(Logger logger) {
        this.logger = logger;
    }

    public void start(int port) {
        try {
            // Initialize default JVM metrics (memory, threads, etc.)
            DefaultExports.initialize();

            // Start HTTP Server for Prometheus to scrape
            this.metricsServer = new HTTPServer(port);
            logger.info("Prometheus metrics server started on port {}", port);
        } catch (IOException e) {
            logger.error("Failed to start Prometheus metrics server on port {}", port, e);
        }
    }

    public void stop() {
        if (this.metricsServer != null) {
            this.metricsServer.stop();
            logger.info("Prometheus metrics server stopped.");
        }
    }
}
