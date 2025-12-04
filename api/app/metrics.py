from prometheus_client import Gauge, Counter

# --- Update Metrics ---

SKYBLOCK_ISLANDS_VERSION = Gauge(
    "skyblock_islands_by_version",
    "Number of islands running a specific version",
    ["version"]
)

SKYBLOCK_UPDATE_EVENTS = Counter(
    "skyblock_update_events_total",
    "Total number of island update events",
    ["status"] # 'success' or 'failure'
)

# --- Other General Metrics (can be expanded) ---

SKYBLOCK_ISLANDS_STATUS = Gauge(
    "skyblock_islands_by_status",
    "Number of islands in each status",
    ["status"] # e.g., RUNNING, STOPPED, FROZEN
)

def register_metrics(app, instrumentator):
    """
    Registers custom metrics with the Prometheus instrumentator.
    This function can be expanded to add more complex metrics that require access to the app state.
    """
    # Example of how you might add a custom metric that needs periodic updates
    # This would typically be run in a background task.
    # instrumentator.add(
    #     metrics.Info(
    #         "app_info", "General information about the application"
    #     ).add_updater(...)
    # )
    pass
