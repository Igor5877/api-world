# api-world

## Project Overview

`api-world` is a backend service, primarily implemented in `api/backend.py`, designed to manage on-demand LXC (Linux Containers) for players. It provides an API interface for creating, starting, stopping, and cleaning up these containers. The system is intended to be used in conjunction with a game server setup, such as a Minecraft server with a lobby mod, where players can trigger the creation of their own isolated game world instance.

The service features player-specific container association, limits on concurrently running containers, and automated cleanup of inactive instances, all backed by a MySQL database for persistence and configured via environment variables.

## Features

*   **On-demand LXC Container Management:** Creates and starts LXC containers when requested by a player.
*   **Player-Specific Containers:** Each container is uniquely associated with a player identifier.
*   **API-Driven Control:**
    *   `/baza/add`: Creates/starts a container for a player.
    *   `/baza/stop`: Stops a container when a player session ends.
*   **Automated Cleanup:** A `/baza/admin/cleanup_inactive` endpoint (typically triggered by a cron job) removes containers that have been inactive for a configurable period.
*   **Concurrency Limiting:** Restricts the total number of concurrently running containers.
*   **MySQL Database Persistence:** Stores container metadata, player associations, and status.
*   **Environment Variable Configuration:** All key parameters are configurable via environment variables, allowing for flexible deployment.
*   **Robust IP Address Retrieval:** Dynamically finds suitable IP addresses for containers, not relying on hardcoded interface names.

## Requirements / Dependencies

*   **Python 3.x** (Python 3.10+ recommended)
*   **FastAPI & Uvicorn:** For the web framework and ASGI server.
    *   `fastapi`
    *   `uvicorn`
*   **MySQL Connector:** For database interaction.
    *   `mysql-connector-python`
*   **LXC:**
    *   LXC installed and configured on the host system.
    *   The `lxc` command-line tool must be available and executable by the user running the API service.
*   **MySQL Server:** A running MySQL (or compatible, e.g., MariaDB) server instance.
*   **Base LXC Image:** A pre-configured LXC image to be used as a template for new containers (default: `wan-blok`).
*   **`ipaddress` module:** Standard in Python 3.3+, used for IP validation.

## Setup & Installation

1.  **Clone the Repository:**
    ```bash
    git clone <repository_url>
    cd api-world
    ```

2.  **Install Python Dependencies:**
    It's recommended to use a virtual environment.
    ```bash
    python -m venv venv
    source venv/bin/activate # On Windows: venv\Scripts\activate
    pip install -r requirements.txt
    ```
    *(Ensure `requirements.txt` includes `fastapi`, `uvicorn`, `mysql-connector-python`)*

3.  **LXC Setup:**
    *   Install LXC on your server. Refer to your distribution's documentation.
    *   Ensure the user running the Python API can execute `lxc` commands (passwordless `sudo` for `lxc` might be needed depending on your setup, or add the user to the `lxd` group).
    *   Create or import a base LXC image that new containers will be cloned from. The default expected name is `wan-blok`. This image should be pre-configured with your game server and any necessary dependencies.
        Example (very basic, adapt as needed):
        ```bash
        lxc launch ubuntu:22.04 wan-blok-template 
        # ... configure wan-blok-template ...
        lxc publish wan-blok-template --alias wan-blok
        lxc delete wan-blok-template 
        ```

4.  **MySQL Database Setup:**
    *   Connect to your MySQL server.
    *   Create a database (e.g., `api_world_db` by default).
    *   Create a user (e.g., `api_user` by default) and grant it necessary privileges on the database.
        ```sql
        CREATE DATABASE api_world_db;
        CREATE USER 'api_user'@'localhost' IDENTIFIED BY 'changeme_password';
        GRANT ALL PRIVILEGES ON api_world_db.* TO 'api_user'@'localhost';
        FLUSH PRIVILEGES;
        ```
        *(Adjust `'localhost'` and password as needed for your environment).*

5.  **Configure Environment Variables:**
    Create a `.env` file or set environment variables directly. See the **Configuration** section below for a list of variables.

## Configuration

The `api/backend.py` service is configured using environment variables.

| Variable                    | Default Value                     | Description                                                                 |
| --------------------------- | --------------------------------- | --------------------------------------------------------------------------- |
| `DB_HOST`                   | `localhost`                       | Hostname or IP address of the MySQL server.                                 |
| `DB_USER`                   | `api_user`                        | MySQL username.                                                             |
| `DB_PASSWORD`               | `changeme_password`               | MySQL password for the specified user.                                      |
| `DB_NAME`                   | `api_world_db`                    | Name of the MySQL database to use.                                          |
| `MAX_CONCURRENT_CONTAINERS` | `5`                               | Maximum number of LXC containers that can be running simultaneously.        |
| `INACTIVITY_PERIOD_DAYS`    | `14`                              | Number of days after which an inactive container is eligible for cleanup.   |
| `ADMIN_API_KEY`             | `supersecretkey_pleasereplaceme`  | API key required to access admin endpoints (e.g., cleanup).                 |
| `LXC_BASE_IMAGE_NAME`       | `wan-blok`                        | Name of the base LXC image to clone for new containers.                     |

## Running the API

To run the FastAPI application:

```bash
uvicorn api.backend:app --host 0.0.0.0 --port 8000
```

You can adjust the host and port as needed. For production, consider using a process manager like Gunicorn with Uvicorn workers.

## API Endpoints

The API service provides the following endpoints:

### 1. Create/Start Container

*   **Endpoint:** `/baza/add`
*   **Method:** `POST`
*   **Description:** Creates a new LXC container for a player if one doesn't exist, or starts an existing stopped container. If the player already has a running container, it returns the details of the existing one. Subject to `MAX_CONCURRENT_CONTAINERS` limit.
*   **Request Payload:**
    ```json
    {
        "player": "player_uuid_or_name",
        "name": "world_base_name" 
    }
    ```
    *   `player`: A unique identifier for the player (e.g., UUID).
    *   `name`: A base name for the world/container (e.g., "survival"). The final container name will be `player-name`.
*   **Successful Response (200 OK):**
    ```json
    {
        "status": "success",
        "container": "player_uuid_or_name-world_base_name",
        "ip": "10.0.3.123",
        "port": 25565 
    }
    ```
*   **Error Responses:**
    *   Database connection failed:
        ```json
        {"status": "error", "message": "Database connection failed"}
        ```
    *   Max concurrent containers reached:
        ```json
        {"status": "error", "message": "Maximum number of concurrent containers reached. Please try again later."}
        ```
    *   LXC command failure (e.g., cloning, starting):
        ```json
        {"status": "error", "message": "LXC command failed: <details>"}
        ```
    *   IP address not found after start:
        ```json
        {"status": "error", "message": "IP не знайдено для <container_name> after creation and start."}
        ```

### 2. Stop Container

*   **Endpoint:** `/baza/stop`
*   **Method:** `POST`
*   **Description:** Stops the LXC container associated with the given player identifier and updates its status in the database.
*   **Request Payload:**
    ```json
    {
        "player_identifier": "player_uuid_or_name"
    }
    ```
*   **Successful Response (200 OK):**
    ```json
    {
        "status": "success",
        "message": "Container '<container_name>' for player '<player_identifier>' stopped successfully."
    }
    ```
    *   If already stopped:
    ```json
    {
        "status": "success",
        "message": "Container '<container_name>' for player '<player_identifier>' is already stopped."
    }
    ```
*   **Error Responses:**
    *   Container not found for player:
        ```json
        {"status": "error", "message": "Container for player '<player_identifier>' not found."}
        ```
    *   Failed to stop LXC container:
        ```json
        {"status": "error", "message": "Failed to stop LXC container '<container_name>': <details>"}
        ```

### 3. Cleanup Inactive Containers (Admin)

*   **Endpoint:** `/baza/admin/cleanup_inactive`
*   **Method:** `POST`
*   **Description:** Triggers a cleanup process that stops and deletes LXC containers (and their database records) that have been inactive for longer than `INACTIVITY_PERIOD_DAYS`. This endpoint is typically called by a scheduled job (e.g., cron).
*   **Headers:**
    *   `X-API-Key`: The value configured in the `ADMIN_API_KEY` environment variable.
*   **Request Payload:** (Empty)
*   **Successful Response (200 OK):**
    ```json
    {
        "status": "cleanup_triggered",
        "details": {
            "cleaned_up_count": 1,
            "errors": [] 
        }
    }
    ```
    *   `cleaned_up_count`: Number of containers successfully cleaned up.
    *   `errors`: A list of error messages encountered during the cleanup process (e.g., if an LXC command failed for a specific container).
*   **Error Responses:**
    *   Invalid or missing API Key (401 Unauthorized):
        ```json
        {"detail": "Invalid or missing API Key"}
        ```

## Integration with Game Server (`mods-server`)

This API is designed to be called by a mod running on a game server (e.g., a Minecraft Forge mod, as referenced by `mods-server` in this repository). The typical integration flow would be:

1.  **Player Command:** A player executes a command in the game (e.g., `/launchmyserver` or `/joinworld <world_type>`).
2.  **API Call to `/baza/add`:** The game server mod collects the player's unique identifier (like UUID) and the desired world type/name. It then makes an HTTP POST request to the `/baza/add` endpoint of this API service.
3.  **Receive Connection Details:** The mod parses the JSON response. If successful, it receives the IP address and port of the player's dedicated LXC container. The mod then typically directs the player to connect to this IP/port.
4.  **Player Logout/Quit:** When the player logs out of the game server or their dedicated container.
5.  **API Call to `/baza/stop`:** The game server mod (or a mod within the player's container itself) makes an HTTP POST request to the `/baza/stop` endpoint, providing the player's identifier, to shut down the LXC container.

This allows for dynamic provisioning and de-provisioning of game instances, optimizing resource usage.
