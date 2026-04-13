# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**api-world** is a dynamic SkyBlock island system for Minecraft 1.20.1. Each player gets an isolated LXD container running a full Minecraft (Forge) server. The system manages the full island lifecycle: create, start, freeze, stop, delete.

Three tightly coupled components:
- **FastAPI backend** (`/api/`) — central management API, LXD orchestration, database
- **Velocity proxy plugin** (`/Nestworldvelocity/`) — player routing and connection management
- **Forge server mod** (`/mods-server/`) — island-side server management, signals readiness to API

Player flow: `Player → Velocity → Plugin calls API → API creates/starts LXD container → Mod signals ready → Player routed to island server`

## Commands

### FastAPI Backend (`/api/`)
```bash
# Install
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt

# Dev server
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

# Production
gunicorn -w 4 -k uvicorn.workers.UvicornWorker app.main:app

# Tests
pytest api/app/tests -v

# Single test
pytest api/app/tests/test_islands.py::test_name -v
```

### Forge Mods (`/mods-server/`, `/RealMarket/`, `/FTB-Quests-1.20.1-main/`)
```bash
cd mods-server/
./gradlew build          # output: build/libs/nestworld-mods-server-*.jar
./gradlew runServer      # dev environment with hot reload
```

### Velocity Plugin (`/Nestworldvelocity/`)
```bash
cd Nestworldvelocity/
./gradlew build          # shadowJar task
./gradlew runVelocity    # dev environment
```

## Architecture

### API (`/api/app/`)

| Path | Purpose |
|------|---------|
| `main.py` | Entry point, lifespan startup (starts background workers) |
| `core/config.py` | All settings via env vars (Pydantic Settings) |
| `core/redis_client.py` | Redis connection for pub/sub and distributed locks |
| `api/v1/endpoints/` | REST + WebSocket endpoints: `islands`, `teams`, `market`, `images` |
| `models/` | SQLAlchemy ORM models |
| `schemas/` | Pydantic request/response schemas |
| `crud/` | Database access layer |
| `services/island_service.py` | Core business logic — island lifecycle orchestration |
| `services/lxd_service.py` | LXD container operations (create, start, freeze, delete) |
| `services/workers/` | Background workers: `creation_worker`, `start_worker`, `update_worker` |
| `db/session.py` | Async SQLAlchemy session factory |

Workers use Redis leader-election locks to remain single-leader across multiple Gunicorn workers. Island status transitions drive all logic.

**Island statuses**: `CREATING`, `STOPPED`, `RUNNING`, `FROZEN`, `DELETING`, `ARCHIVED`, `ERROR`, `PENDING_*`, `UPDATING`

**Key API endpoints** (prefix `/api/v1/`):
- `GET /islands/{player_uuid}` — island status
- `POST /islands/start/{player_uuid}` — create or start island
- `POST /islands/stop/{player_uuid}` — stop island
- `POST /islands/{player_uuid}/freeze` — freeze to RAM
- `POST /islands/{player_uuid}/ready` — called by Forge mod when Minecraft is ready
- `WS /ws/{client_id}` — real-time status updates

### Forge Mod (`/mods-server/src/main/java/com/skyblock/dynamic/`)

Runs inside each player's LXD container. On server ready, it calls `POST /islands/{uuid}/ready`. Handles player join/leave events for inactivity tracking.

### Velocity Plugin (`/Nestworldvelocity/src/main/java/com/skyblockdynamic/nestworld/velocity/`)

On player connect: checks island status via API, triggers creation if needed, polls readiness, then routes player. Uses Java-WebSocket for real-time API updates.

### Database

MySQL/MariaDB. Schema at `api/sql/schema.sql`. Key tables: `islands`, `island_queue`, `island_backups`, `island_settings`, `teams`, `market`.

## Configuration

### API environment variables (`.env`)
```
DATABASE_URL=mysql+aiomysql://user:pass@host:port/db
LXD_SOCKET_PATH=/var/snap/lxd/common/lxd/unix.socket
LXD_BASE_IMAGE=skyblock-template
LXD_PROJECT=default
LXD_DEFAULT_PROFILES=default,skyblock
MAX_RUNNING_SERVERS=10
FREEZE_TIMER_SECONDS=300
STOP_TIMER_SECONDS=900
REDIS_URL=redis://localhost:6379/0
```

### Mod config (inside container at `world/config/skyblock-common.toml`)
```toml
apiBaseUrl = "http://api-ip:8000/api/v1"
apiRequestTimeoutSeconds = 10
```

### Velocity plugin config (`plugins/nestworldvelocity/nestworldvelocity.toml`)
```toml
fallback_server = "hub"
base_url = "http://api-ip:8000/api/v1"
polling_interval_millis = 5000
max_polling_attempts = 120
```

## CI/CD

GitHub Actions workflows in `.github/workflows/`:
- `deploy-api.yml` — triggered on `api-v*` tags; runs tests then deploys via SSH
- `deploy-plugin.yml` — Velocity plugin deployment
- `deploy-mod.yml` — Forge mod deployment
- `pr-checks.yml` — PR validation

## Other Mods

- **`/RealMarket/`** — Marketplace mod using Applied Energistics 2 (AE2), NeoForge
- **`/sales-addon/`** — Addon extending RealMarket functionality
- **`/FTB-Quests-1.20.1-main/`** — Integrated FTB Quests (common/forge/fabric subprojects)
