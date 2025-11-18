package com.skyblockdynamic.nestworld.velocity.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.HTTPServer;
import org.slf4j.Logger;

import java.io.IOException;

public class MetricsManager {

    private final Logger logger;
    private HTTPServer server;

    // --- Metrics ---
    public static final Gauge awaiting_connection_players = Gauge.build()
            .name("velocity_awaiting_connection_players")
            .help("Number of players currently awaiting connection to their island.")
            .register();

    public static final Counter successful_connections = Counter.build()
            .name("velocity_successful_connections_total")
            .help("Total number of successful connections to islands.")
            .register();

    public static final Counter failed_connections = Counter.build()
            .name("velocity_failed_connections_total")
            .help("Total number of failed connections to islands.")
            .register();

    public static final Histogram command_latency = Histogram.build()
            .name("velocity_command_latency_seconds")
            .help("Latency of command execution.")
            .labelNames("command")
            .register();

    public static final Counter command_usage = Counter.build()
            .name("velocity_command_usage_total")
            .help("Total number of command usages.")
            .labelNames("command")
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
