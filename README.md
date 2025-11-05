# Dynamic SkyBlock Island System

This project is a comprehensive system for creating and managing dynamic, per-player SkyBlock islands using LXD containers. It consists of three main components that work together:

1.  **FastAPI Backend (`api/`)**: The central management API that handles creating, starting, stopping, and managing the lifecycle of LXD containers for each player's island.
2.  **Velocity Proxy Plugin (`Nestworldvelocity/`)**: A plugin for the Velocity proxy that intelligently routes players to their islands, creating and starting them on demand.
3.  **Forge Server Mod (`mods-server/`)**: A Forge mod that runs on each island server, communicating its status back to the API and managing server-specific behavior like auto-freezing.

---

## Architecture and Interaction Flow

The system is designed to be efficient and scalable, creating player servers only when they are needed. Here is the typical user interaction flow:

1.  **Player Connects**: A player connects to the main Velocity proxy.
2.  **Initial Server**: The `Nestworldvelocity` plugin intercepts the connection and places the player on a default fallback server (e.g., a hub or lobby).
3.  **Island Check (Async)**: In the background, the plugin makes a request to the FastAPI backend to get the status of the player's island.
4.  **Island Creation/Start**:
    *   If the island doesn't exist, the API creates a new LXD container by cloning a base image.
    *   If the island exists but is `STOPPED` or `FROZEN`, the API sends a command to start/unfreeze it.
5.  **Polling for Readiness**: The Velocity plugin periodically polls the API, waiting for the island's status to become `RUNNING` and for the `minecraft_ready` flag to be `true`.
6.  **Server Ready Signal**: Once the Minecraft server inside the LXD container is fully loaded, the `skyblock` Forge mod sends a request to the API (`/islands/{uuid}/ready`), setting the `minecraft_ready` flag to `true`.
7.  **Player Redirection**: As soon as the Velocity plugin sees that the island is ready, it dynamically registers the island's container IP and port with the proxy and connects the player to their server.
8.  **Auto-Freeze on Disconnect**: When the last player leaves an island server, the Forge mod starts a timer. After a configurable delay (e.g., 5 minutes), it calls the API to `freeze` the container, preserving its state in RAM while consuming minimal CPU, thus saving resources.

---

## Installation Requirements

### Core Components
*   **LXD**: The container hypervisor. Must be installed and configured on the host machine.
*   **MySQL/MariaDB**: A database for the API to store island metadata.
*   **Java 17+**: Required for Velocity and the Minecraft server.
*   **Python 3.10+**: Required to run the FastAPI backend.

### Minecraft Components
*   A working **Velocity** proxy server.
*   A Minecraft server JAR (e.g., Forge) for the base island image.

---

## Setup and Configuration

### 1. API Backend (`api/`)

The API is the brain of the operation and must be configured first.

a. **Install Python dependencies**:
   ```bash
   cd api/
   python -m venv venv
   source venv/bin/activate
   pip install -r requirements.txt
   ```

b. **Configure the environment**:
   - Copy the example environment file: `cp env_example .env`
   - Edit the `.env` file with your settings:
     ```dotenv
     # Database connection string
     DATABASE_URL="mysql+aiomysql://user:password@host:port/database"

     # LXD Configuration
     LXD_SOCKET_PATH="/var/snap/lxd/common/lxd/unix.socket" # Or your LXD socket path
     LXD_BASE_IMAGE="your-base-image-alias" # The alias of your prepared LXD image
     LXD_DEFAULT_PROFILES=default,skyblock # Comma-separated list of LXD profiles
     ```

c. **Prepare the Database**:
   - Ensure the database specified in `DATABASE_URL` exists.
   - The initial schema is in `sql/schema.sql`.

d. **Prepare the LXD Base Image**:
   - Create an LXD container that will serve as the template for all player islands.
   - Install Java, Forge, and any base mods/configs inside it.
   - **Crucially**, place the compiled `skyblock-mod.jar` into the `mods` folder of this container.
   - Once ready, publish this container as an image with a specific alias (e.g., `your-base-image-alias`).

e. **Run the API**:
   ```bash
   cd api/
   uvicorn app.main:app --host 0.0.0.0 --port 8000
   ```

### 2. Forge Mod (`mods-server/`)

This mod must be installed on the base LXD image.

a. **Configure the mod**:
   - The mod's configuration is located at `world/config/skyblock-common.toml` inside the container.
   - Set the `apiBaseUrl` to point to your running API backend.
     ```toml
     # The base URL for the SkyBlock API (e.g., http://localhost:8000/api/v1)
     apiBaseUrl = "http://<YOUR_API_IP_OR_DOMAIN>:8000/api/v1"
     # Timeout in seconds for API requests
     apiRequestTimeoutSeconds = 10
     ```

b. **Build the mod**:
   ```bash
   cd mods-server/
   ./gradlew build
   ```
   The compiled JAR will be in `build/libs/`.

### 3. Velocity Plugin (`Nestworldvelocity/`)

This plugin runs on your Velocity proxy.

a. **Configure the plugin**:
   - After the first run, a config file will be created at `plugins/nestworldvelocity/nestworldvelocity.toml`.
   - Edit this file:
     ```toml
     [general]
     # The server players are sent to by default
     fallback_server = "hub"
     # Whether to automatically start the island connection process on login
     auto_redirect_to_island_on_login = true

     [api]
     # URL of your API backend
     base_url = "http://<YOUR_API_IP_OR_DOMAIN>:8000/api/v1"
     request_timeout_seconds = 10
     polling_interval_millis = 5000
     max_polling_attempts = 120
     ```

b. **Build the plugin**:
   ```bash
   cd Nestworldvelocity/
   ./gradlew build
   ```
   The compiled JAR will be in `build/libs/`. Place this JAR in your Velocity's `plugins` directory.

---

## Running the System

1.  Start the **FastAPI Backend**.
2.  Start your **Velocity** proxy with the `Nestworldvelocity` plugin installed.
3.  Start any static servers (like your hub) and register them with Velocity.
4.  Players can now connect. The system will handle island creation and management automatically.

---

## API Management Endpoints

The API includes endpoints for managing island updates.

*   `POST /islands/update-all`: Queues all existing islands for an update.
*   `POST /islands/{player_uuid}/update`: Queues a specific island for an update.
*   `POST /islands/rollback-all`: (Placeholder) Triggers a rollback for all islands with a snapshot.
*   `POST /islands/{player_uuid}/rollback`: (Placeholder) Triggers a rollback for a specific island.
