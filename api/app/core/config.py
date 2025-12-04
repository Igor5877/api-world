from pydantic_settings import BaseSettings
import os

class Settings(BaseSettings):
    """Represents the application settings.

    Attributes:
        API_V1_STR: The prefix for the API v1 routes.
        DATABASE_URL: The URL for the database connection.
        LXD_SOCKET_PATH: The path to the LXD socket.
        LXD_PROJECT: The LXD project to use.
        LXD_BASE_IMAGE: The name of the published LXD image to use for islands.
        LXD_OPERATION_TIMEOUT: The timeout in seconds for LXD operations.
        LXD_IP_RETRY_ATTEMPTS: The number of times to retry fetching an IP address.
        LXD_IP_RETRY_DELAY: The delay in seconds between IP fetch attempts.
        LXD_DEFAULT_PROFILES: A list of default LXD profiles to apply to islands.
        MAX_RUNNING_SERVERS: The maximum number of concurrent running islands.
        FREEZE_TIMER_SECONDS: The number of seconds of inactivity before freezing an
            island.
        STOP_TIMER_SECONDS: The number of seconds of inactivity before stopping an
            island.
        CLEANUP_INTERVAL_HOURS: The interval in hours to check for old islands.
        MAX_ISLAND_INACTIVITY_DAYS: The maximum number of days an island can be
            inactive before being deleted.
        CREATION_QUEUE_WORKER_INTERVAL: The interval in seconds between creation
            queue checks.
        DEFAULT_MC_PORT_INTERNAL: The default internal Minecraft server port.
        REDIS_URL: The URL for the Redis connection.
        REDIS_CHANNEL: The Redis channel for island notifications.
    """
    API_V1_STR: str = "/api/v1"
    
    # Database settings
    # Example for PostgreSQL using asyncpg:
    # DATABASE_URL: str = "postgresql+asyncpg://user:password@host:port/db"
    # Example for MySQL using aiomysql:
    # DATABASE_URL: str = "mysql+aiomysql://user:password@host:port/db"
    # For now, we'll use a placeholder. This should be configured via environment variables.
    DATABASE_URL: str = os.getenv("DATABASE_URL", "mysql+aiomysql://skyblock_user:skyblock_pass@localhost:3306/skyblock_db")

    # LXD Settings
    LXD_SOCKET_PATH: str | None = os.getenv("LXD_SOCKET_PATH", None) # e.g., /var/snap/lxd/common/lxd/unix.socket or /var/lib/lxd/unix.socket
    LXD_PROJECT: str | None = os.getenv("LXD_PROJECT", "default")
    LXD_BASE_IMAGE: str = os.getenv("LXD_BASE_IMAGE", "skyblock-template") # Name of the published LXD image for new islands
    LXD_OPERATION_TIMEOUT: int = int(os.getenv("LXD_OPERATION_TIMEOUT", "30")) # Seconds for LXD operations like start/stop
    LXD_IP_RETRY_ATTEMPTS: int = int(os.getenv("LXD_IP_RETRY_ATTEMPTS", "10")) # How many times to try fetching an IP
    LXD_IP_RETRY_DELAY: int = int(os.getenv("LXD_IP_RETRY_DELAY", "3")) # Delay in seconds between IP fetch attempts
    LXD_DEFAULT_PROFILES: list[str] = os.getenv("LXD_DEFAULT_PROFILES", "default,skyblock").split(',') # Comma-separated list e.g., "default,skyblock"

    # Update Mechanism Settings
    UPDATE_GIT_REPOSITORY_URL: str = os.getenv("UPDATE_GIT_REPOSITORY_URL", "") # URL of the Git repository with update files
    UPDATE_TEMP_CLONE_PATH: str = os.getenv("UPDATE_TEMP_CLONE_PATH", "/tmp/skyblock_updates") # Path to temporarily clone the repo

    # Security
    ADMIN_API_KEY: str = os.getenv("ADMIN_API_KEY", "changeme-in-production") # Static API key for admin endpoints
    # Server resource management
    MAX_RUNNING_SERVERS: int = int(os.getenv("MAX_RUNNING_SERVERS", "10")) # Max concurrent running island
    # Timers (in seconds)
    FREEZE_TIMER_SECONDS: int = int(os.getenv("FREEZE_TIMER_SECONDS", str(5 * 60)))      # 5 minutes
    STOP_TIMER_SECONDS: int = int(os.getenv("STOP_TIMER_SECONDS", str(15 * 60)))    # 15 minutes
    CLEANUP_INTERVAL_HOURS: int = int(os.getenv("CLEANUP_INTERVAL_HOURS", "24")) # Check for old islands once a day
    MAX_ISLAND_INACTIVITY_DAYS: int = int(os.getenv("MAX_ISLAND_INACTIVITY_DAYS", "30")) # Delete islands inactive for this many days
    CREATION_QUEUE_WORKER_INTERVAL: int = int(os.getenv("CREATION_QUEUE_WORKER_INTERVAL", "10")) # Seconds between creation queue checks
    # Default Minecraft server settings (can be overridden per island if needed)
    DEFAULT_MC_PORT_INTERNAL: int = 25565

    # Redis settings
    REDIS_URL: str = os.getenv("REDIS_URL", "redis://localhost:6379/0")
    REDIS_CHANNEL: str = os.getenv("REDIS_CHANNEL", "skyblock_island_notifications")


    class Config:
        """Represents the configuration for the settings.

        Attributes:
            env_file: The name of the environment file to load.
            env_file_encoding: The encoding of the environment file.
            case_sensitive: Whether the settings are case-sensitive.
        """
        # If you have a .env file, pydantic-settings will load it automatically.
        env_file = ".env"
        env_file_encoding = 'utf-8'
        case_sensitive = True

settings = Settings()

# Example usage:
# from app.core.config import settings
# print(settings.DATABASE_URL)
# print(settings.LXD_BASE_IMAGE)
# print(settings.MAX_RUNNING_SERVERS)
