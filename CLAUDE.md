# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

A three-component system for running dynamic, per-player SkyBlock Minecraft islands using LXD containers:

1. **`api/`** — FastAPI backend (Python): the central brain. Manages container lifecycle, stores metadata in MySQL, coordinates workers via Redis.
2. **`Nestworldvelocity/`** — Velocity proxy plugin (Java/Gradle): routes players to their island server on login, polls the API until the island is ready.
3. **`mods-server/`** — Forge server mod (Java/Gradle): runs inside each island container, signals the API when the Minecraft server has finished loading, and triggers freeze after the last player leaves.

## Commands

### API (Python)

```bash
# Set up (from api/)
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# Run dev server (from repo root)
cd api && uvicorn app.main:app --reload

# Run all tests
PYTHONPATH=$(pwd)/api python -m pytest api/app/tests

# Run a single test file
PYTHONPATH=$(pwd)/api python -m pytest api/app/tests/test_crud.py

# Run a single test by name
PYTHONPATH=$(pwd)/api python -m pytest api/app/tests/test_crud.py::test_create_island -v

# Install test-only dependencies (not in requirements.txt)
pip install pytest pytest-asyncio httpx aiosqlite fakeredis
```

### Forge Mod

```bash
cd mods-server/
./gradlew build        # output: build/libs/
./gradlew test
```

### Velocity Plugin

```bash
cd Nestworldvelocity/
./gradlew build        # output: build/libs/
./gradlew test
```

## Architecture

### Request Flow

```
Player login
  → Velocity plugin (Nestworldvelocity)
      → GET /api/v1/islands/{player_uuid}           # check status
      → POST /api/v1/islands/start/{player_uuid}    # create/start if needed
      → polls API until status=RUNNING & minecraft_ready=true
          → Forge mod signals: POST /api/v1/islands/{owner_uuid}/ready
      → Velocity dynamically registers container IP:25565, redirects player

Player leaves (last on island)
  → Forge mod waits FREEZE_TIMER_SECONDS
  → POST /api/v1/islands/{player_uuid}/freeze
```

### Island Ownership Model

Islands are owned by **teams**, not individual players. A player UUID is resolved to a `TeamMember` → `Team` → `Island`. `create_new_solo_island()` in `island_service.py` always creates a team+island pair. The `player_uuid` in legacy endpoints is the team owner's UUID.

### Island State Machine

Islands transition through statuses defined in `IslandStatusEnum` (`api/app/models/island.py`):

```
CREATING → STOPPED → PENDING_START → RUNNING → PENDING_FREEZE → FROZEN
                                             ↘ PENDING_STOP → STOPPED
```

Error states: `ERROR`, `ERROR_CREATE`, `ERROR_START`. On API startup, `reconcile_island_states()` in `main.py` compares every `RUNNING/FROZEN/PENDING_*` DB record against the actual LXD state and corrects mismatches.

### Background Workers

Three asyncio background tasks start via the lifespan manager at startup (leader-elected via a Redis lock so only one Gunicorn worker runs them):

- **`creation_worker`** (`api/app/services/creation_worker.py`): drains the island creation queue respecting `MAX_RUNNING_SERVERS`.
- **`start_worker`** (`api/app/services/start_worker.py`): processes islands queued for start.
- **`update_worker`** (`api/app/services/update_worker.py`): pushes image updates to islands.

Redis pub/sub (`REDIS_CHANNEL`) is used to push real-time events to connected WebSocket clients (`/ws/{client_id}`).

### Database

MySQL with async SQLAlchemy (`aiomysql` driver). Schema in `api/sql/schema.sql`. Key tables: `islands`, `teams`, `team_members`, `island_queue`, `island_settings`, `island_backups`.

**Session pattern**: all CRUD functions accept an `AsyncSession` injected from `AsyncSessionLocal`. Each CRUD method commits its own transaction; callers do not need to commit.

### LXD Integration

`api/app/services/lxd_service.py` wraps `pylxd` with async helpers (runs sync pylxd calls in `asyncio.to_thread`). Lazy-initializes a single client at first use with a thread-safe lock. Islands are LXD containers cloned from `LXD_BASE_IMAGE` and launched with profiles from `LXD_DEFAULT_PROFILES`.

## Environment Configuration

API reads from `api/.env` (copy `api/env_example`). Key variables:

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | `mysql+aiomysql://user:pass@host:port/db` |
| `LXD_SOCKET_PATH` | Path to LXD unix socket |
| `LXD_BASE_IMAGE` | Alias of the LXD template image |
| `LXD_DEFAULT_PROFILES` | Comma-separated LXD profiles (default: `default,skyblock`) |
| `MAX_RUNNING_SERVERS` | Cap on concurrent running containers (default: 10) |
| `FREEZE_TIMER_SECONDS` | Idle time before freeze (default: 300) |
| `REDIS_URL` | Redis connection (default: `redis://localhost:6379/0`) |

## Testing Notes

- Tests use **in-memory SQLite** (`aiosqlite`) via `conftest.py` — no real database or LXD needed.
- LXD service calls must be mocked in tests that exercise `island_service.py`.
- `pytest-asyncio` is required; async test functions need `@pytest.mark.asyncio`.
- CI (`pr-checks.yml`) runs `python -m pytest api/app/tests` with `PYTHONPATH` set to the repo root so `app.*` imports resolve.

## CI / Deployment

- PRs to `main`: runs API tests + Gradle builds for both Java components.
- Tags matching `api-v*`: SSH deploys the API, restarts the systemd service, health-checks `http://localhost:8000/`.
- Tags matching `mod-v*` / `plugin-v*`: deploy the respective JAR artifact.
