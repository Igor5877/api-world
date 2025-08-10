from pydantic_settings import BaseSettings
import os

class Settings(BaseSettings):
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
    LXD_BASE_IMAGE: str = os.getenv("LXD_BASE_IMAGE", "skyblock-template") # Name of the published LXD image
    LXD_OPERATION_TIMEOUT: int = int(os.getenv("LXD_OPERATION_TIMEOUT", "30")) # Seconds for LXD operations like start/stop
    LXD_IP_RETRY_ATTEMPTS: int = int(os.getenv("LXD_IP_RETRY_ATTEMPTS", "10")) # How many times to try fetching an IP
    LXD_IP_RETRY_DELAY: int = int(os.getenv("LXD_IP_RETRY_DELAY", "3")) # Delay in seconds between IP fetch attempts
    LXD_DEFAULT_PROFILES: list[str] = os.getenv("LXD_DEFAULT_PROFILES", "default,skyblock").split(',') # Comma-separated list e.g., "default,skyblock"
    # Server resource management
    MAX_RUNNING_SERVERS: int = int(os.getenv("MAX_RUNNING_SERVERS", "10")) # Max concurrent running islands
    
    # Timers (in seconds)
    FREEZE_TIMER_SECONDS: int = int(os.getenv("FREEZE_TIMER_SECONDS", str(5 * 60)))      # 5 minutes
    STOP_TIMER_SECONDS: int = int(os.getenv("STOP_TIMER_SECONDS", str(15 * 60)))    # 15 minutes
    CLEANUP_INTERVAL_HOURS: int = int(os.getenv("CLEANUP_INTERVAL_HOURS", "24")) # Check for old islands once a day
    MAX_ISLAND_INACTIVITY_DAYS: int = int(os.getenv("MAX_ISLAND_INACTIVITY_DAYS", "30")) # Delete islands inactive for this many days

    # Default Minecraft server settings (can be overridden per island if needed)
    DEFAULT_MC_PORT_INTERNAL: int = 25565

    # Island Update Settings
    UPDATE_STRATEGY: str = os.getenv("UPDATE_STRATEGY", "files") # 'files' or 'image'
    LXD_NEW_BASE_IMAGE: str = os.getenv("LXD_NEW_BASE_IMAGE", "skyblock-template-new")
    ISLAND_UPDATE_FILE_SOURCE_PATH: str = os.getenv("ISLAND_UPDATE_FILE_SOURCE_PATH", "/opt/skyblock_updates/new_config.jar")
    ISLAND_UPDATE_FILE_TARGET_PATH: str = os.getenv("ISLAND_UPDATE_FILE_TARGET_PATH", "/opt/minecraft/mods/some_mod.jar")
    UPDATE_WORKER_MAX_RETRIES: int = int(os.getenv("UPDATE_WORKER_MAX_RETRIES", "3"))
    UPDATE_WORKER_POLL_INTERVAL: int = int(os.getenv("UPDATE_WORKER_POLL_INTERVAL", "10"))

    class Config:
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

