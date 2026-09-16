# Dynamic SkyBlock — Deep Audit Report

**Дата:** 2026-09-16
**Гілка:** `claude/skyblock-deep-audit-7fj3y7`
**Метод:** статичний аналіз поточного коду + запуск наявного тест-сьюту. Проєкт не змінювався.
**Принцип:** CODE WINS — стара документація не вважалася джерелом правди.

---

## 1. Executive Summary

**Що це насправді.** Система — це не «три компоненти», як каже README/CLAUDE.md, а **щонайменше вісім** окремих артефактів: FastAPI-бекенд, Velocity-плагін, острівний Forge-мод (`mods-server`), маркет-мод хабу (`RealMarket`), два teams-моди (`nestworld-teams-addon` серверний + `nestworld-teams-client` клієнтський з міксинами), вендорений FTB-Quests і Laravel-плагін аналітики для Azuriom. Стан системи розподілено між **шістьма** джерелами (MySQL, Redis, LXD, пам'ять FastAPI-процесу, пам'ять Velocity, файли всередині контейнера), і **жодного розподіленого локу на життєвий цикл острова не існує**.

**Головні архітектурні спостереження:**

1. **Модель власності змінилася з «гравець → острів» на «команда → острів»**, але БД-схема, черги (`island_queue`, `island_start_queue`), README і половина CRUD-шару досі живуть у старій моделі. `api/sql/schema.sql` **фізично не може створити робочу базу** — у ньому немає колонок `team_id`, `minecraft_ready`, heartbeat-колонок, а `market_*` таблиці досі на `island_uuid`.
2. **Черги створення/запуску островів — мертвий код.** `start_creation_worker()` і `start_start_worker()` — це `pass`. Самі `trigger_*` функції викликають неіснуючі методи. Наслідок: `MAX_RUNNING_SERVERS` **не застосовується ніде в системі**.
3. **Уся LXD-робота йде через `fastapi.BackgroundTasks`** — непересистентні, in-process. Рестарт API під час будь-якої операції втрачає її назавжди.
4. **Автентифікація є (`X-Api-Key`), авторизації немає.** Один спільний ключ роздано у кожен острівний контейнер; всі ендпоінти беруть `player_uuid`/`requester_uuid` з query-параметра і безумовно довіряють.
5. **Оновлення й життєвий цикл острова конкурують без координації** — між update worker і start-флоу немає жодного спільного локу.

**Кількість findings:** 32 (2×P0, 11×P1, 11×P2, 8×P3/INFO).

**Виконано під час аудиту:** прочитано ~9.9k рядків Python і ~4.5k рядків Java; запущено наявний тест-сьют (55/55 pass у CI-конфігурації; **19/55 fail** щойно вмикається API-ключ).

---

## 2. Actual Architecture

```
                            ┌────────────────────┐
                            │  Minecraft Client  │
                            │ (+ teams-client    │
                            │    mixins GUI)     │
                            └─────────┬──────────┘
                                      │
                            ┌─────────▼──────────┐
                            │  Velocity Proxy    │
                            │  Nestworldvelocity │
                            │  • PlayerConnListener
                            │  • /myisland /team /island
                            │    /tpa /spawn /warp-admin
                            │  • ApiClient (java.net.http)
                            │  • WebSocketManager (per-player)
                            └───┬─────────────┬──┘
                        HTTP    │             │  WS  ws://api/ws/<playerUuid>
                    X-Api-Key   │             │  X-Api-Key
                                │             │
   ┌────────────────────────────▼─────────────▼─────────────────────────┐
   │                      FastAPI  (gunicorn -w N)                      │
   │  routers: islands · teams · market · warps · analytics · updates   │
   │           (images — НЕ підключено)                                 │
   │  services: island_service · lxd_service · update_service           │
   │            git_sync · websocket_manager                            │
   │  workers (leader-only, Redis "startup_lock" ex=60):                │
   │      ✔ update_worker   ✔ health_worker   ✔ analytics_worker        │
   │      ✘ creation_worker (pass)  ✘ start_worker (pass)               │
   └───┬──────────────────┬───────────────────────┬────────────────────┘
       │                  │                       │
  ┌────▼─────┐     ┌──────▼──────┐        ┌───────▼────────┐
  │  MySQL   │     │    Redis    │        │  LXD (pylxd +  │
  │ 16 tbl   │     │ pub/sub +   │        │   lxc CLI)     │
  └──────────┘     │ locks+cache │        └───────┬────────┘
                   └─────────────┘                │
                         ▲            ┌───────────┼───────────┐
                         │            ▼           ▼           ▼
                         │       Island A     Island B    skyblock-base
                         │       (Forge)      (Forge)     (template)
                         │          │                          │
                         │          │ WS ws://api/ws/core_<owner>
                         │          │ HTTP /ready /freeze /my_team
                         │          │      /quest-progress
                         │    ┌─────▼──────────────────┐
                         │    │ mods-server            │
                         │    │ + nestworld-teams-addon│
                         │    │ + RealMarket           │
                         │    └────────────────────────┘
                         │
                   ┌─────┴────────────────────┐
                   │  spawn_hub (LXD)         │
                   │  WS client_id="spawn_hub"│
                   │  RealMarket + warps      │
                   └──────────────────────────┘
                                 │
                   ┌─────────────▼────────────┐
                   │ Azuriom (nestworld.site) │
                   │ /azlink — гроші гравців  │
                   └──────────────────────────┘
```

**Три різні WebSocket client_id-простори на одному ендпоінті `/ws/{client_id}`:**

| client_id | Хто конектиться | Призначення |
|---|---|---|
| `<player-uuid>` (голий) | Velocity `WebSocketManager` | сигнал «острів готовий» |
| `core_<owner-uuid>` | `mods-server` `IslandWebSocketClient` | heartbeat, `shutting_down`, `execute_command`, `pending_update`, `TEAM_UPDATED`, `TEAM_INVITE` |
| `island_<owner-uuid>` | RealMarket `MarketWebSocketClient` | `market_purchase`, pending extractions |
| `spawn_hub` | RealMarket на хабі | warp-команди |

---

## 3. Component Analysis

### 3.1 Backend (FastAPI)

| Модуль | Відповідальність | Стан у пам'яті | Зовнішні залежності |
|---|---|---|---|
| `island_service` | lifecycle островів/команд | — | LXD, MySQL, Redis(WS) |
| `lxd_service` | pylxd + `lxc` CLI (recursive file ops) | `self.client` (lazy singleton) | LXD unix socket, `lxc` binary |
| `update_service` | кампанії оновлень, бекапи, rollback | — | git, LXD, MySQL |
| `git_sync` | clone/pull/checkout/diff тегів | — | git + GITHUB_TOKEN |
| `websocket_manager` | `Dict[client_id, WebSocket]` **per-process** | ✅ (тому й потрібен Redis pub/sub) | Redis |
| `health_worker` | watchdog heartbeat → реальний стан LXD | `_worker_running` глобал | LXD, MySQL |
| `update_worker` | розкатка кампанії по островах | `_worker_running` глобал | LXD, git, MySQL |
| `analytics_worker` | погодинна агрегація маркету | — | MySQL (**MySQL-only** `on_duplicate_key_update`) |
| ~~`creation_worker`~~ | **мертвий** | — | — |
| ~~`start_worker`~~ | **мертвий** | — | — |

Leader-election: `redis.set("startup_lock","1", ex=60, nx=True)`. Ключ живе 60 с і **ніколи не оновлюється**. Якщо будь-який gunicorn-воркер стартує пізніше ніж через 60 с після першого (повільний старт, staggered reload) — він теж стане «лідером» і підніме другий `update_worker`/`health_worker`. Комбінований ризик з SKY-006.

### 3.2 Database

16 таблиць. Транзакційна модель **непослідовна**: `get_db_session` комітить в кінці реквесту, але `crud_island.update()` / `update_by_id()` / `crud_island_event.add()` комітять **усередині** — тобто сервісний шар не може відкотити те, що вже закомічено CRUD-ом. `island_service.handle_join_team` явно покладається на «endpoint's dependency will handle the commit», але `crud_team.remove_member`/`add_member` навколо нього поводяться по-різному.

Ізоляція: дефолтна для InnoDB (REPEATABLE READ). `SELECT ... FOR UPDATE` використовується **тільки** в маркеті (`purchase_item`, `confirm_extraction`, `cancel_purchase`). У life-cycle островів **жодного** `FOR UPDATE` чи умовного `UPDATE ... WHERE status = ...` немає — усі переходи це read-modify-write.

### 3.3 Redis

| Ключ | Producer | Consumer | TTL | Проблема |
|---|---|---|---|---|
| `skyblock_island_notifications` (pub/sub) | будь-який воркер | `redis_listener` у кожному воркері | — | fire-and-forget: якщо клієнт не підключений саме зараз — повідомлення втрачено назавжди |
| `startup_lock` | lifespan | lifespan | 60 с | не поновлюється → можливе подвійне лідерство |
| `purchase_lock:<buyer>` | `execute_purchase` | — | 30 с | ✅ коректно (Lua-release з токеном) |
| `team_id:<uuid>` | `_resolve_team_id` | market | 3600 с | **не інвалідується при зміні команди** → купівлі 1 год йдуть у стару команду |
| `market:hash:<team_id>` | sync | sync | 3600 с | ✅ інвалідується при покупці |
| `ratelimit:sync:<uuid>` | sync | sync | 60 с | ✅ fail-closed |

Перезапуск Redis: `get_redis_client()` кидає `RuntimeError`, якщо пул не ініціалізовано, але **reconnect-логіки немає взагалі** — `redis_client` створюється лише в `init_redis_pool()` на старті. `redis_listener` ловить виняток, спить 5 с і продовжує з тим самим (мертвим) pubsub-об'єктом.

### 3.4 LXD

`pylxd` синхронний → обгорнутий у `loop.run_in_executor(None, ...)` — тобто **дефолтний ThreadPoolExecutor** (`min(32, cpu+4)`). Операції `clone_container` (`wait=True`) і `publish_container_image` (timeout 1800 с) займають потоки цього ж пулу, який ділиться з усім іншим у процесі.

Рекурсивні файлові операції зроблені через `lxc` CLI subprocess (бо pylxd їх не вміє) — коректно через `create_subprocess_exec` без shell.

### 3.5 Velocity

Ключове рішення, яке тягне за собою баг SKY-003: **псевдо-сервер називається `island-<playerUuid>`, а не `island-<ownerUuid>`**. Тобто двоє тіммейтів на одному фізичному острові сидять на **двох різних `RegisteredServer`** з однаковою адресою.

### 3.6 Forge Mod (mods-server)

- `is_island_server` / `owner_uuid` читаються з `world/serverconfig/skyblock_island_data.toml`, який пише API один раз під час клонування.
- ready-сигнал: `ServerStartedEvent` + 40 тіків затримки → `POST /ready` (**один раз на JVM**).
- heartbeat: кожні 30 с із `ServerTickEvent` (зависання server thread ⇒ тиша ⇒ watchdog).
- auto-freeze: **хардкод 5 хвилин** у `PlayerEventHandler.scheduleFreezeTask` — `FREEZE_TIMER_SECONDS` з API-конфігу не використовується ніде.

---

## 4. Complete Data Flows

### 4.1 Player Login (фактичний)

```
Player → Velocity
  PlayerChooseInitialServerEvent
    ├─ cancelPendingStopForPlayersTeam()   GET /teams/my_team/{uuid}  [async, executorService]
    ├─ setInitialServer(hub)
    └─ if auto_redirect_to_island_on_login:   ← ДЕФОЛТ false!
         pollForRunningAndConnect(player, 0)
```

> ⚠️ У `nestworldvelocity_default.toml` `auto_redirect_to_island_on_login = false`. Тобто **дефолтний login-флоу взагалі не запускає острів** — README крок 3-8 описує гілку, яка вимкнена за замовчуванням. Реальний вхід на острів — це `/myisland`.

### 4.2 `/myisland` (реальний основний флоу)

```
/myisland
 └─ GET /islands/{uuid}
      ├─ island_service.get_island_by_player_uuid
      │    ├─ crud_team.get_team_by_player  (JOIN team_members, selectinload island+members)
      │    └─ fallback: crud_island.get_by_player_uuid   (legacy solo)
      ├─ RUNNING && minecraft_ready → attemptSingleConnection()  → КІНЕЦЬ
      └─ інакше startAndListen():
            awaitingConnection.add(uuid)
            POST /islands/start/{uuid}?player_name=...
              └─ island_service.start_island_instance
                   ├─ team? island? → _start_existing_island
                   │     STOPPED|FROZEN|ERROR_START → status=PENDING_START, commit,
                   │                                   WS-notify, BackgroundTask
                   │     RUNNING        → no-op
                   │     PENDING_START  → no-op
                   │     ІНШЕ           → ValueError → 409/400
                   ├─ team без острова → crud_island.create(PENDING_CREATION) + BG clone
                   └─ ні team, ні острова → create_new_solo_island() + BG clone
            (202 | 409) → connectToWebSocket()  ws://api/ws/<playerUuid>
            ...
            BackgroundTask _perform_lxd_start_and_update_status:
              get_container_state → start_container (unfreeze якщо frozen)
              get_container_ip (10 спроб × 3 с)
              UPDATE islands SET status=RUNNING, ip=..., last_heartbeat_at=NULL
                                [, minecraft_ready=TRUE якщо resumed]
              redis.publish → всім членам команди
            ...
            Мод усередині: ServerStartedEvent +40 тіків → POST /islands/{owner}/ready
              → UPDATE minecraft_ready=TRUE, last_heartbeat_at=NULL, last_seen_at=now
              → redis.publish
            ...
            WebSocketManager.onText: status==RUNNING && minecraft_ready
              → awaitingConnection.remove() → attemptSingleConnection() → close()
```

### 4.3 Island Creation

| Питання | Відповідь з коду |
|---|---|
| Ініціатор | `POST /islands/start/{uuid}` (Velocity) або `POST /teams/create_solo` |
| Коли створюється DB-запис | **синхронно, в реквесті**, статус `PENDING_CREATION` |
| Коли створюється контейнер | **після відповіді**, у `BackgroundTasks` |
| Ім'я контейнера | solo: `skyblock-solo-{safeName}-{uuid.hex[:8]}`; team: `skyblock-team-{safeTeamName}-{teamId}` |
| UUID | приходить ззовні (від Velocity), не генерується |
| IP | видається LXD DHCP, читається ретраями (10×3 с) |
| Порт | **завжди** `settings.DEFAULT_MC_PORT_INTERNAL = 25565`; `external_port` ніколи не заповнюється |
| Failure | `status=ERROR_CREATE`, контейнер може лишитися |
| Повторний запит | `PENDING_CREATION` → `ValueError` → 400/409 |

**Double creation.** Захист від дублювання є, але **не там, де треба**:

- `container_name` має `UNIQUE` → другий `INSERT` з тим самим ім'ям впаде на DB-рівні. Для solo це рятує (ім'я детерміноване від uuid).
- **Але** `islands.team_id` теж `UNIQUE`, і при одночасних запитах двох членів однієї команди (гілка «team без острова») обидва зроблять `crud_island.create(...)` з тим самим `container_name` → другий отримає `IntegrityError` → 500, а **`clone_container` вже ідемпотентний** (`if client.containers.exists(...): return existing`), тож двох контейнерів не буде.
- Реальна дірка — не дублювання, а **orphan**: якщо `commit` пройшов, а процес помер до виконання BackgroundTask, острів навічно `PENDING_CREATION` (див. SKY-005).

---

## 5. State Machine

### 5.1 Фактичні стани

`IslandStatusEnum` оголошує 16 значень. З них **реально виставляються 11**:

| State | Значення | Хто ставить | Хто читає | Дозволені переходи (з коду) |
|---|---|---|---|---|
| `PENDING_CREATION` | запис є, контейнера нема | `crud_island.create` | Velocity(poll), update_worker(`_TRANSIENT`) | → `STOPPED` (clone ok) / `ERROR_CREATE` |
| `CREATING` | **не виставляється ніде** (тільки ORM default) | — | update_worker | — |
| `STOPPED` | контейнер існує, вимкнений | clone-task, stop-task, watchdog, update_service, reconcile | все | → `PENDING_START` |
| `PENDING_START` | старт у польоті | `_start_existing_island` | Velocity, reconcile | → `RUNNING` / `ERROR_START` |
| `RUNNING` | контейнер запущено | start-task, reconcile, watchdog | все | → `PENDING_STOP` / `PENDING_FREEZE` / `UPDATING` |
| `FROZEN` | cgroup freezer | freeze-task, reconcile, watchdog | все | → `PENDING_START` / `PENDING_STOP` |
| `PENDING_FREEZE` | freeze у польоті | `_freeze_existing_island` | reconcile | → `FROZEN` |
| `PENDING_STOP` | stop у польоті | `_stop_existing_island` | reconcile | → `STOPPED` |
| `ERROR_CREATE` | клон упав | clone-task | Velocity | **тупик** (нема шляху назад) |
| `ERROR_START` | старт упав | start-task, reconcile | Velocity | → `PENDING_START` / `PENDING_STOP` |
| `ERROR` | контейнер зник | reconcile, watchdog | Velocity | **тупик** |
| `UPDATING` | оновлення в процесі | `update_service` | update_worker | → `STOPPED` / попередній / `UPDATE_FAILED` |
| `UPDATE_FAILED` | оновлення впало | `update_service` | update_worker | → `PENDING_START` |
| `DELETING` | **не виставляється ніде** | — | `create_campaign_from_tag` | — |
| `ARCHIVED` | **не виставляється ніде** | — | `create_campaign_from_tag` | — |
| `PENDING_UPDATE` | **не виставляється ніде** | — | — | — |

### 5.2 Перевірка інваріантів

| Перевірка | Результат |
|---|---|
| Invalid transitions можливі? | **Так.** `_perform_lxd_start_and_update_status` пише `RUNNING` **без перевірки поточного статусу** — може перетерти `UPDATING`, `PENDING_STOP`, `ERROR`. |
| Перехід без persistence? | **Ні** — усі переходи комітяться. |
| DB ≠ LXD? | **Так**, постійно. Часткове покриття: `reconcile_island_states` на старті + `health_worker` для `RUNNING`. Не покриті: `PENDING_CREATION`, `CREATING`, `DELETING`, `UPDATE_FAILED`, `UPDATING`(тільки на старті update_worker). |
| «API думає RUNNING, контейнер STOPPED»? | **Так** — але ловиться watchdog-ом за ≥90 с, **лише якщо** `minecraft_ready=true` **і** був хоч один heartbeat у цьому бутi. |
| `minecraft_ready` може бути stale? | **Так.** Скидається в `stop`-тасках, watchdog-ом і hard-update-ом. **Не** скидається при `ERROR_CREATE`, і **штучно ставиться в TRUE** при unfreeze без жодної перевірки, що JVM живий. |

---

## 6. Concurrency Analysis

| Сценарій | Race? | Shared state | Lock | Наслідок |
|---|---|---|---|---|
| A і B стартують той самий острів | ні | `islands.status` | нема | обидва бачать `PENDING_START`, другий = no-op. Ідемпотентно за замовчуванням |
| A стартує / B стопить | **так** | `islands.status` | **нема** | RMW без умови: остання BG-таска перетирає статус |
| Update worker vs start-флоу | **так, P1** | `islands.status` + контейнер | **нема** | SKY-006 |
| freeze (мод) vs join (гравець) | **так** | контейнер | **нема** | SKY-020 |
| Останній вийшов vs інший зайшов | **так, P1** | `pendingStopTasks` + Velocity `RegisteredServer` | in-memory map | SKY-003 |
| disconnect під час PENDING_START | **так, P1** | `pendingStartPlayers` | — | SKY-004 |
| MC ready vs рестарт контейнера | ні (є `restarted` event) | `minecraft_ready` | — | фіксується в журнал |
| Дві покупки одного товару | ні | `market_items` | ✅ `FOR UPDATE` + `purchase_lock` | коректно |
| Два gunicorn-лідери | **так** | `startup_lock` | Redis lock **без renew** | два update_worker'и на одну чергу |

---

## 7. Reliability Analysis

| # | Аварія | Початковий стан | Точка відмови | Що спостерігаємо | Автовідновлення | Потрібне втручання |
|---|---|---|---|---|---|---|
| A | FastAPI падає під час створення | `PENDING_CREATION` | BackgroundTask не виконався | острів навічно `PENDING_CREATION` | **немає** (reconcile не покриває) | ✅ ручний SQL |
| A′ | FastAPI падає під час старту | `PENDING_START` | BG-таска | reconcile на старті виправить за LXD | ✅ часткове | ні |
| B | MySQL падає | будь-який | `SQLAlchemyError` | 500 на всіх ендпоінтах; `pool_pre_ping=True` | ✅ після повернення | ні |
| C | Redis падає | будь-який | `get_redis_client()` | **WS-нотифікації мовчки зникають**, маркет-sync відхиляється (fail-closed), `redis_listener` крутиться на мертвому pubsub | **немає reconnect** | ✅ рестарт API |
| D | LXD падає | будь-який | `LXDServiceError` | reconcile `continue`-ить кожен острів; watchdog логує | ✅ часткове | залежить |
| E | Minecraft падає | `RUNNING` | тиша heartbeat | watchdog за ≥90 с → `STOPPED` + event `crashed`/`hung` | ✅ **добре** | ні |
| F | Velocity рестартує | будь-який | `pendingStopTasks`, `webSocketManagers`, реєстр серверів — у пам'яті | заплановані stop втрачено; `FROZEN` острови висять вічно | **немає** | ✅ |
| G | Хост ребутиться | будь-який | усі контейнери → `Stopped` | reconcile править `RUNNING`/`FROZEN`→`STOPPED` | ✅ | ні |
| H | Мережа між компонентами зникла | `RUNNING` | heartbeat не доходить | watchdog **зупинить живий контейнер з гравцями** (LXD доступний, MC «мовчить») | ❌ хибне спрацювання | — |
| I | Response втрачено після успішної операції | — | — | ретрай `/start` ідемпотентний; **ретрай `/sell` дублює гроші** | частково | — |
| J | Процес помер після LXD-ефекту, до DB-запису | `UPDATING` | — | `reset_stale_updating_islands` ✅; для create/delete — **немає** | часткове | ✅ |

---

## 8. Security Analysis

**Модель загроз.** API слухає `0.0.0.0:8000` (за README/CLAUDE.md). Валідні ключі: `PROXY_API_KEY` (Velocity) **або** `SPAWN_API_KEY` (кожен острівний контейнер + хаб). `require_api_key` не розрізняє їх — це **один рівень довіри**.

```python
valid_keys = {k for k in [settings.PROXY_API_KEY, settings.SPAWN_API_KEY] if k}
if not valid_keys:
    return  # auth disabled when no keys configured   ← FAIL-OPEN
```

**Fail-open:** якщо обидві змінні порожні (наприклад, `.env` не доїхав під час деплою) — API стає **повністю анонімним**, і жоден health-check це не помітить (`GET /` не залежить від auth).

| Вектор | Оцінка |
|---|---|
| Доступ до чужого острова | ✅ можливий — будь-який власник ключа шле будь-який `player_uuid` |
| Stop/freeze чужого острова | ✅ можливий |
| Rollback/update чужого острова | ✅ можливий (`/updates/rollback/island/{uuid}`) |
| **Знищення чужого острова** | ✅ через `POST /teams/{id}/members?player_uuid=<жертва>` — **без жодної перевірки requester** |
| Читання внутрішнього стану | ✅ `/islands/{uuid}/events`, `/updates/campaigns`, `/warps/player-uuid/{name}` |
| Довільні LXD-операції | ❌ немає ендпоінта, що приймає ім'я контейнера ззовні |
| Command injection | ❌ всюди `create_subprocess_exec` без shell |
| Path traversal | ⚠️ `UPDATES_TARGET_DIR + changed_paths` з git-діфу — контроль за репозиторієм оновлень |
| SSRF | ❌ немає user-controlled URL |
| Rate limiting | лише `/market/.../sync` (6/60 с). Решта — **без обмежень** |
| Exception leakage | ⚠️ `/updates/spawn/sync` віддає `str(e)` з git/lxc stderr у 500 |

**SECRET DETECTED: yes**
**LOCATION:** `RealMarket/src/main/resources/realmarket-api.toml`
**TYPE:** Azuriom Link API token (production, `https://nestworld.site/api/azlink`)

> Файл лежить у `src/main/resources` → пакується в JAR. Коментар у самому файлі: *«DO NOT commit this file with your API token to version control!»*. Токен у git-історії.

Другий вектор витоку: `git_sync._authenticated_url()` вписує `GITHUB_TOKEN` у remote URL → git зберігає його у відкритому вигляді в `.git/config` репозиторію оновлень на хості API.

---

## 9. Performance Analysis

**Розрахунок навантаження** (позначено, де число не виводиться зі статики):

| Джерело | Формула | При N=100 островів, P=50 гравців онлайн |
|---|---|---|
| heartbeat WS | N_running × 2/хв | ~100 msg/хв → 100 `UPDATE islands` + 100 `SELECT` |
| market sync | N_running × 2/хв (30 с) | 100 req/хв; з Redis-хешем → ~0 SQL при статиці |
| Velocity polling | лише під час старту: ~15 спроб з backoff 2 с→30 с | пік при масовому логіні |
| `cancelPendingStopForPlayersTeam` | 1 × `GET /teams/my_team` на **кожен** `ServerConnectedEvent` + логін | JOIN + 2 selectinload на кожен перехід між серверами |
| health_worker | 1 × `SELECT ... WHERE status=RUNNING LIMIT 1000` / 30 с | 120 запитів/год + до N `lxc state` при тиші |
| analytics_worker | 1 прохід / 5 хв, **переагрегує ту саму годину 12 разів** | 12× зайвих `GROUP BY` над `market_transactions` |
| reconcile на старті | до 1000 островів × 2 синхронні pylxd-виклики | **UNKNOWN — requires runtime measurement**; при 1000 островах це послідовні виклики без конкурентності |

**Bottlenecks:**

1. **`reconcile_island_states` послідовний.** Цикл `for island in islands_to_reconcile` з `await lxd_service.get_container_state(...)` — жодного `asyncio.gather`. Lifespan **блокує старт API**, поки він не завершиться. При сотнях островів старт розтягується на хвилини, і за цей час `startup_lock` (60 с) протухає.
2. **`get_container_ip`: до 30 с на острів** (10 спроб × 3 с) у BG-тасці, тримаючи потік executor'а.
3. **Executor sizing:** `run_in_executor(None, ...)` → дефолтний пул `min(32, cpu+4)`. `clone_container(wait=True)` може тримати потік хвилинами. **Одночасні створення островів > розміру пулу заблокують усі інші LXD-операції в процесі.**
4. **N+1:** `list_invites` робить `get_member` в циклі; `list_campaigns` робить `count_by_status` на кожну кампанію; `_detect_anomalies` — `AVG` на кожен рядок.
5. **Connection pools:** `create_async_engine` без `pool_size`/`max_overflow` → дефолт 5+10. При 4 gunicorn-воркерах це 60 конектів — **UNKNOWN**, чи вкладається в `max_connections` MySQL.
6. **`get_multi(limit=100000)`** у `create_campaign_from_tag` — тягне всі острови в пам'ять одним запитом.

---

## 10. Resource Management

| Ресурс | Створюється | Знищується | При помилці | При рестарті |
|---|---|---|---|---|
| Velocity `RegisteredServer` `island-<uuid>` | `attemptSingleConnection` | **тільки** при невдалому конекті в `MyIslandCommand` | лишається | губиться |
| Velocity `WebSocketManager` | `connectToWebSocket` | лише при наступному `/myisland` того самого гравця | **лишається в мапі назавжди** | губиться |
| `HttpClient` у `WebSocketManager` | `HttpClient.newHttpClient()` **на кожен інстанс** | ніколи (Java 11 не Closeable) | selector-потік живе | — |
| Velocity `executorService` | `newCachedThreadPool()` | ✅ `ProxyShutdownEvent` | — | — |
| Velocity `pendingStopTasks` | `handleLeftIslandServer` | `remove` після виконання/скасування | ✅ | **губиться** |
| Мод `ScheduledExecutorService` | `new PlayerEventHandler()` | **ніколи** (`shutdown()` не викликається) | — | вмирає з JVM |
| Мод `HttpClient` (IslandProvider) | статичний singleton | — | ✅ | — |
| API `pylxd.Client` | lazy, під `asyncio.Lock` | ніколи | ✅ | — |
| API `httpx.AsyncClient` (Azuriom) | lazy singleton | ✅ `close_azuriom_client()` | ✅ | — |
| API Redis pool | lifespan | ✅ | **без reconnect** | — |
| `redis_listener_task` | lifespan у **кожному** воркері | `.cancel()` без `await` | ✅ | — |
| LXD snapshots | `pre-update-<ver>` | `expiry_days=SNAPSHOT_RETENTION_DAYS` ✅ | — | — |
| File backups | `UPDATES_BACKUP_DIR` | ✅ `prune_file_backups` | ✅ | — |

---

## 11. Database Analysis

**Що є добре:** UNIQUE на `islands.player_uuid`, `islands.container_name`, `islands.team_id`, `team_members(team_id, player_uuid)`, `update_queue(campaign_id, island_id)`, `team_invites(team_id, invited_uuid)`; FK з `ON DELETE CASCADE` на `island_events`, `island_backups`, `island_quest_progress`, `update_queue`.

**Проблеми:**

1. **`api/sql/schema.sql` не відповідає ORM** (SKY-013).
2. **Патерн `if not exists: create()` без DB-захисту** — `create_new_solo_island`:
   ```python
   if await crud_team.get_team_by_player(...): raise ValueError(...)
   if await crud_island.get_by_player_uuid(...): raise ValueError(...)
   ...
   team_db_model = await crud_team.create_team(...)
   ```
   `teams.owner_uuid` **не має UNIQUE** (лише індекс). Захист тримається тільки на `teams.name` UNIQUE (детермінований від uuid) — тобто працює **випадково**, а не за задумом.
3. **`SELECT → рішення → UPDATE` без умови** у всіх lifecycle-переходах. Правильний патерн був би `UPDATE islands SET status='PENDING_START' WHERE id=? AND status IN ('STOPPED','FROZEN')` з перевіркою `rowcount`.
4. **Orphan-записи:** `islands.team_id` має `ON DELETE SET NULL` → після видалення команди острів лишається з `team_id=NULL` і `player_uuid=NULL` → **недосяжний жодним ендпоінтом**, але контейнер живий.
5. **Немає Alembic.** Міграції — це коментарі всередині `schema.sql` + чотири `.sql`-файли, які треба виконувати вручну.
6. `island_queue` / `island_start_queue` — таблиці **без жодного живого читача** (обидва воркери мертві).

---

## 12. Redis Analysis

Див. таблицю в §3.3. Додатково:

- **Втрата повідомлень гарантована за дизайном.** `publish_to_redis` → якщо в жодного воркера немає локального WS з таким `client_id`, повідомлення просто зникає. Компенсація існує **лише** для `island_pending_commands`, `market_pending_extractions`, `warp_pending_commands` (досилаються на реконнекті). Для `TEAM_UPDATED`, `TEAM_INVITE` і статусів острова — **компенсації немає**.
- **Дублювання:** `send_personal_message` викликається з кількох місць на одну подію; мод/плагін мусять бути ідемпотентними; `WebSocketManager.onText` захищений через `awaitingConnection.remove()` (atomic) ✅.
- **Ordering:** pub/sub гарантує порядок у межах одного каналу й одного публікатора, але з N gunicorn-воркерів порядок між воркерами не визначений. Для `status` це важливо: `PENDING_START` з воркера 1 і `RUNNING` з воркера 2 можуть прийти у зворотному порядку → Velocity відкине `PENDING_START` (перевіряє `RUNNING && ready`) ✅ випадково безпечно.

---

## 13. API Contract Analysis

### Повний реєстр ендпоінтів

| Method | Path | Auth | Idempotent | DB | LXD | Redis |
|---|---|---|---|---|---|---|
| GET | `/` | ❌ none | ✅ | — | — | — |
| WS | `/ws/{client_id}`, `/api/v1/ws/{client_id}` | key (client_id **не звірено**) | — | ✅ | — | pub/sub |
| GET | `/islands/{player_uuid}` | key | ✅ | R | — | — |
| POST | `/islands/start/{uuid}?player_name=` | key | ✅ | R/W | start/clone | pub |
| POST | `/islands/stop/{uuid}` | key | ✅ | R/W | stop | pub |
| POST | `/islands/{uuid}/freeze` | key | ✅ | R/W | freeze | pub |
| POST | `/islands/{uuid}/player_left` | key | ✅ | R/W | — | — |
| POST | `/islands/{owner}/ready` | key | ✅ (пише event `restarted`) | W | — | pub |
| GET | `/islands/{owner}/events` | key | ✅ | R | — | — |
| PUT/GET | `/islands/{owner}/quest-progress` | key | ✅ upsert | R/W | — | — |
| POST | `/teams/create_solo` | key | ❌ 409 | W | clone | pub |
| GET | `/teams/my_team/{uuid}` | key | ✅ | R | — | — |
| PATCH | `/teams/{id}/rename?player_uuid=` | key + **owner check** ✅ | ✅ | W | — | pub |
| POST | `/teams/accept_invite?player_uuid=` | key + invite check ✅ | ❌ | W | **delete** | pub |
| POST | `/teams/{id}/leave?player_uuid=` | key + member check ✅ | ❌ | W | — | pub |
| **POST** | **`/teams/{id}/members?player_uuid=`** | **key only — БЕЗ requester** ⚠️ | ❌ | W | **delete** | pub |
| POST | `/teams/{id}/invite` | key + owner/mod ✅ | ❌ 409 | W | — | pub |
| GET/POST/DELETE | `/teams/invites/*` | key + invited_uuid ✅ | mixed | W | delete | pub |
| DELETE | `/teams/{id}/members/{uuid}?requester_uuid=` | key + requester ✅ | ✅ | W | — | pub |
| POST | `/market/islands/{uuid}/purchase` | key | ❌ | W | — | pub |
| POST | `/market/islands/{uuid}/purchase/execute` | key + `purchase_lock` | ❌ | W | — | pub |
| POST | `/market/islands/{uuid}/sell` | key | ❌ **гроші!** | W | — | — |
| POST | `.../extraction/{id}/confirm` | key | ✅ (404 на повтор) | W | — | — |
| POST | `.../purchase/{id}/cancel` | key | ✅ | W | — | — |
| POST | `.../extraction/{id}/fail` | key | ✅ **no-op** | R | — | — |
| POST | `.../inventory/sync` | key + rate-limit | ✅ | W | — | cache |
| GET | `/market/players/{azuriom_id}/balance` | key | ✅ | — | — | — |
| POST | `/warps/{uuid}/{create\|suspend\|restore\|delete}` | key only | ❌ | W | — | pub |
| POST | `/warps/confirm/{id}` | key | ✅ | W | — | — |
| GET | `/warps/status`, `/warps/player-uuid/{name}` | key | ✅ | R | — | — |
| POST | `/updates/webhook` | **HMAC** ✅ | ✅ (dedup) | R/W | — | — |
| POST | `/updates/campaign` | key | ❌ 409 | W | — | — |
| GET | `/updates/campaigns`, `/campaign/{id}` | key | ✅ | R | — | — |
| POST | `/updates/campaign/{id}/requeue_failed` | key | ✅ | W | — | — |
| POST | `/updates/rollback/island/{uuid}` | key | ❌ | W | files | — |
| POST | `/updates/rollback/campaign/{id}` | key | ❌ | W | files | — |
| GET | `/updates/snapshots/{uuid}` | key | ✅ | R | list | — |
| POST | `/updates/spawn/sync` | key | ❌ | — | push+restart | — |
| GET | `/analytics/*` | key | ✅ | R | — | — |
| — | `/images/*` | **роутер НЕ підключено** | — | — | — | — |

### Cross-component contracts

| Контракт | Velocity очікує | API віддає | Мод шле | Вердикт |
|---|---|---|---|---|
| Готовність | `status=="RUNNING" && minecraft_ready==true` | те саме | `POST /ready` | ✅ узгоджено |
| Адреса | `internal_ip_address` + `internal_port` | IPv4 + 25565 | — | ✅ |
| UUID | canonical з дефісами | `path: uuid.UUID` для start/stop/freeze, **`str` для `/ready` та `/{owner}/events`** | canonical | ⚠️ асиметрія валідації |
| WS client_id | голий `<uuid>` | `/ws/{client_id}` | `core_<uuid>` / `island_<uuid>` | ✅ (розведено після коміту 65a2a7f) |
| Створення solo | `createSoloIsland` → плоский JSON | `player_info: dict` ← плоский | — | ✅ |
| Створення команди | **`createTeam` → вкладений `{"player_info":{...}}`** | той самий ендпоінт `create_solo` | — | ❌ **SKY-022: 400** |
| Timeout | 10 с (config), connect 5 с | без обмежень | 10 с | ⚠️ API може відповідати довше за 10 с |
| Freeze timer | — | `FREEZE_TIMER_SECONDS` (не використовується) | **хардкод 5 хв** | ❌ drift |
| Stop delay | `stop_delay_seconds=900` | `STOP_TIMER_SECONDS` (не використовується) | — | ❌ drift |
| Retry semantics | poll з backoff ×1.5, cap 30 с, 15 спроб | — | heartbeat-reconnect кожні 30 с | ✅ |

---

## 14. Tests Analysis

**Що є:** 55 тестів у 5 файлах. `pytest app/tests -q` → **55 passed** (4.35 с) у конфігурації CI.

| Файл | Тестів | Що реально перевіряє |
|---|---|---|
| `test_islands.py` | 20 | HTTP-роутинг і коди статусів; `island_service` **повністю замокано** |
| `test_island_service.py` | 22 | переходи станів на SQLite in-memory; `lxd_service` замокано |
| `test_health_worker.py` | 7 | **найкращий набір** — реальні гілки watchdog-а |
| `test_update_core.py` | 5 | чиста функція `determine_actions` + pruning |
| `test_crud.py` | 1 | create+update solo island |

### Що НЕ покрито

| Сценарій | Покриття |
|---|---|
| Одночасне створення | ❌ |
| Одночасний старт | ❌ |
| freeze/unfreeze race | ❌ |
| **update vs start race** | ❌ |
| API timeout | ❌ |
| LXD failure (окрім watchdog) | ❌ |
| DB failure | ❌ |
| Redis failure | ❌ |
| **Restart recovery / `reconcile_island_states`** | ❌ — нуль тестів на найскладнішу функцію (200 рядків if/elif) |
| Duplicate requests | ❌ |
| Stale state | частково (watchdog) |
| Orphan container | ❌ |
| Player reconnect | ❌ |
| **Автентифікація** | ❌ — див. нижче |
| Java-компоненти | ❌ — **жодного** теста в 4 Gradle-проєктах |

### Критична знахідка про тести

```
$ python -m pytest app/tests -q                        → 55 passed
$ PROXY_API_KEY=testkey python -m pytest app/tests -q  → 19 failed, 36 passed
```

Тести не шлють `X-Api-Key`. Вони проходять **виключно тому, що `require_api_key` fail-open'иться на порожній конфігурації**. `_test-python.yml` теж задає лише `DATABASE_URL`. Тобто **CI перевіряє режим, який у продакшені не використовується**.

---

## 15. Documentation Drift

### DOCUMENTED, BUT NOT IMPLEMENTED

| Документ | Твердження | Реальність |
|---|---|---|
| `CLAUDE.md` | `endpoints`: `islands, teams, market, **images**` | роутер `images` **не підключено**; модуль імпортує неіснуючий `app.crud.crud_image_version` |
| `CLAUDE.md` | «Key tables: `islands`, `island_queue`, `island_backups`, `island_settings`, …» | `island_queue`, `island_start_queue`, `island_settings` — **без жодного живого читача** |
| `CLAUDE.md` / `config.py` | `MAX_RUNNING_SERVERS=10` | не застосовується ніде |
| `CLAUDE.md` / `config.py` | `FREEZE_TIMER_SECONDS`, `STOP_TIMER_SECONDS` | не використовуються; мод має хардкод 5 хв, Velocity — свій `stop_delay_seconds` |
| `config.py` docstring | `CLEANUP_INTERVAL_HOURS`, `MAX_ISLAND_INACTIVITY_DAYS` | **cleanup-воркера не існує** |
| `CLAUDE.md` | «Workers use Redis leader-election locks» | правда для 3 з 5; 2 воркери — `pass` |
| `README.md` | «Player connects → plugin routes to island» (кроки 3–8) | `auto_redirect_to_island_on_login = false` у дефолтному конфізі |
| `CLAUDE.md` | «Island statuses: … `ARCHIVED`, `DELETING`, `PENDING_*`» | `ARCHIVED`, `DELETING`, `PENDING_UPDATE`, `CREATING` ніколи не виставляються |
| `SECURITY_AUDIT.md` план п.1 | «`X-API-Key` вирішує C-1, M-6, **M-7, H-2**» | M-7 і H-2 **не вирішені** — ключ є в кожному контейнері |
| `SECURITY_AUDIT.md` план п.2 | «Перевірка `client_id` при WS-підключенні» | перевіряється **ключ**, а не відповідність `client_id` ключу → SKY-009 |
| `SECURITY_AUDIT.md` M-3 | «Асинхронний file I/O в IslandManager» | `IslandManager.java:46,94,187` — досі синхронний `FileWriter` |
| `SECURITY_AUDIT.md` H-3 | «HTTP timeout» | `NestworldModsServer.refreshAndGetTeamId` — **синхронний `send()` на server thread** |
| `api/sql/schema.sql` | описує `islands` | немає `team_id`, `minecraft_ready`, heartbeat-колонок; `player_uuid NOT NULL` (ORM: nullable) |
| `api/sql/schema.sql` | `market_items(island_uuid)` | ORM: `market_items(team_id FK)`; міграція **закоментована** |

### IMPLEMENTED, BUT NOT DOCUMENTED

- `/warps/*` (7 ендпоінтів), `/analytics/*`, `/updates/*` (11 ендпоінтів) — немає в `CLAUDE.md`
- `nestworld-teams-addon`, `nestworld-teams-client`, `azuriom-plugin-analytics` — немає в списку компонентів
- Три префікси `client_id` для WS
- `island_events`, heartbeat-протокол, `PROXY_API_KEY`/`SPAWN_API_KEY`, `CORS_ORIGINS`, весь блок auto-update env
- `UPDATE_SYSTEM_STATUS.md` не згаданий у `CLAUDE.md`

### DOCUMENTED DIFFERENTLY

- `CLAUDE.md`: `pytest api/app/tests -v` → **не працює з кореня** (потрібен `PYTHONPATH=api` або `cd api`)
- `CLAUDE.md`: кореневий `settings.gradle` включає `mods-server` і `FTB-Quests-1.20.1-main`, але **у корені немає `gradlew`**, а обидва підпроєкти мають власні `settings.gradle`

---

## 16. Findings

| ID | Sev | Conf | Component | Problem |
|---|---|---|---|---|
| SKY-001 | P0 | CONFIRMED | RealMarket / git | Azuriom-токен продакшену закомічено і запаковано в JAR |
| SKY-002 | P0 | CONFIRMED | API teams | Вступ у команду безповоротно знищує острів гравця без бекапу |
| SKY-003 | P1 | CONFIRMED | Velocity | Псевдо-сервер на гравця → острів зупиняється під активними тіммейтами |
| SKY-004 | P1 | CONFIRMED | Velocity + API | Дисконект під час `PENDING_START` лишає острів `RUNNING` назавжди |
| SKY-005 | P1 | CONFIRMED | API | `PENDING_CREATION` не відновлюється після рестарту API |
| SKY-006 | P1 | CONFIRMED | API update/start | Немає локу між update worker і стартом острова |
| SKY-007 | P1 | CONFIRMED | API workers | Черги мертві; `MAX_RUNNING_SERVERS` не діє |
| SKY-008 | P1 | CONFIRMED | API auth | Один ключ = повні права; роздано в кожен контейнер; fail-open |
| SKY-009 | P1 | CONFIRMED | API WS | `client_id` не прив'язаний до ключа → hijack/DoS чужого острова |
| SKY-010 | P1 | CONFIRMED | API market | Гроші нараховуються після commit'у → незворотна втрата коштів |
| SKY-011 | P1 | CONFIRMED | API market | `/sell` не ідемпотентний → дублювання грошей при ретраї |
| SKY-012 | P1 | CONFIRMED | mods-server | Блокуючий HTTP + file I/O на Minecraft server thread |
| SKY-013 | P1 | CONFIRMED | DB | `schema.sql` не створює робочу базу |
| SKY-014 | P2 | CONFIRMED | Velocity | Витік `RegisteredServer`, `WebSocketManager`, `HttpClient` |
| SKY-015 | P2 | CONFIRMED | Velocity | Polling-ланцюжок мовчки вмирає на будь-якому винятку |
| SKY-016 | P2 | CONFIRMED | API | `BackgroundTasks` непересистентні → orphan-контейнери |
| SKY-017 | P2 | CONFIRMED | API | Немає reaper'а — `FROZEN` острови накопичуються після рестарту Velocity |
| SKY-018 | P2 | CONFIRMED | API | `ERROR`/`ERROR_CREATE` — тупикові стани без відновлення |
| SKY-019 | P2 | CONFIRMED | Tests/CI | Тести й CI працюють лише з вимкненою автентифікацією |
| SKY-020 | P2 | LIKELY | mod + API | Freeze без перевірки присутності гравців |
| SKY-021 | P2 | CONFIRMED | API teams | BG-таск видалення ставиться до commit'у |
| SKY-022 | P2 | CONFIRMED | Velocity | `createTeam()` шле payload, який API не розуміє |
| SKY-023 | P2 | CONFIRMED | API images | Модуль імпортує неіснуючий CRUD; задокументований, але не підключений |
| SKY-024 | P2 | LIKELY | API reconcile | `FROZEN→RUNNING` без IP лишає `RUNNING` з `ip=NULL` |
| SKY-025 | P3 | POSSIBLE | API updates | `git checkout --force <tag>` з невалідованим тегом |
| SKY-026 | P3 | CONFIRMED | API git_sync | `GITHUB_TOKEN` осідає у `.git/config` |
| SKY-027 | P3 | CONFIRMED | API market | `/extraction/{id}/fail` — no-op, ігнорує `pending_id` |
| SKY-028 | P3 | CONFIRMED | API analytics | Переагрегація тієї самої години 12×/год |
| SKY-029 | P3 | LIKELY | API | Змішування `datetime.utcnow()` і `func.now()` (server TZ) |
| SKY-030 | P3 | LIKELY | RealMarket | Синхронний `FileWriter` на tick thread |
| SKY-031 | P3 | POSSIBLE | API/mod | `minecraft_ready=TRUE` при unfreeze без перевірки JVM |
| SKY-032 | INFO | CONFIRMED | Repo | Мертві файли в корені; неузгоджені Gradle-збірки |

---

### SKY-001 · P0 · CONFIRMED · Секрет у репозиторії

**Location:** `RealMarket/src/main/resources/realmarket-api.toml:9`

**Problem:** Azuriom Link API-токен продакшн-сайту закомічено у git і розміщено в `src/main/resources`, звідки він потрапляє в зібраний JAR.

**Evidence:** сам файл містить коментар `# DO NOT commit this file with your API token to version control!` і `# Your Azuriom API token - keep it secret!`. Поруч — `url = "https://nestworld.site/api/azlink"`, `server_id = 1`. Файл присутній у git-історії. `api/app/core/azuriom_client.py` демонструє, що цей токен дає: `POST /azlink/user/{id}/money/{add|remove}`.

**Execution scenario:**
1. Будь-хто з read-доступом до репозиторію (або хто отримав JAR) читає токен.
2. `POST https://nestworld.site/api/azlink/user/<any-id>/money/add` з `Azuriom-Link-Token`.
3. Баланс будь-якого гравця змінюється на довільну суму.

**Impact:** повна компрометація ігрової економіки; можливе й читання/зміна даних користувачів сайту залежно від прав токена.

**Why existing protection does not prevent it:** `.gitignore` містить `.env`, але не цей файл; secret-scanning у CI немає.

**Reproduction:** `git log -p -- RealMarket/src/main/resources/realmarket-api.toml`

**Recommended direction:** негайно **відкликати токен в адмінці Azuriom**; прибрати файл з історії (`git filter-repo`); перевести на env-змінну (у файлі вже згадано `REALMARKET_API_TOKEN`); додати gitleaks у `pr-checks.yml`.

---

### SKY-002 · P0 · CONFIRMED · Незворотне знищення острова

**Location:** `api/app/services/island_service.py:668-745` (`handle_join_team`), `:742-764` (`_perform_lxd_delete_and_cleanup`); `api/app/api/v1/endpoints/teams.py:241-268` (`force_join_team`)

**Problem:** Коли гравець приєднується до чужої команди, його власний острів **видаляється фізично** — і LXD-контейнер, і рядок у БД — без снапшота, без бекапу, без підтвердження і без можливості відкату. `force_join_team` при цьому **не перевіряє, хто робить запит** — лише наявність API-ключа.

**Evidence:**
```python
# island_service.py
if current_team.island:
    old_island_to_delete = current_team.island
await db_session.delete(current_team)
...
background_tasks.add_task(self._perform_lxd_delete_and_cleanup, island_id=old_island_to_delete.id)

# _perform_lxd_delete_and_cleanup
await lxd_service.delete_container(island.container_name)   # незворотно
await crud_island.remove_by_id(db_session_bg, island_id=island_id)
```
```python
# teams.py — force_join_team: жодного requester_uuid/owner check
@router.post("/{team_id}/members", ...)
async def force_join_team(*, team_id: int, player_uuid: str, ...):
    team = ...  # тільки перевірка існування
    updated_team = await island_service.handle_join_team(...)
```

**Execution scenario:**
1. Атакувальник має `SPAWN_API_KEY` (він лежить у конфізі кожного острівного контейнера і в шаблонному образі).
2. Створює власну команду: `POST /teams/create_solo`.
3. `POST /api/v1/teams/{своя_команда}/members?player_uuid=<uuid-жертви>` з `X-Api-Key`.
4. `handle_join_team` бачить, що жертва в solo-команді (`len(members)==1`) → «It's safe to delete it».
5. BG-таск стирає контейнер жертви разом зі світом.

**Actual result:** світ гравця знищено назавжди; `island_backups` для нього не створювався (снапшоти робить лише update-пайплайн).

**Expected result:** видалення острова має вимагати явного підтвердження власника + обов'язковий снапшот із retention.

**Impact:** незворотна втрата даних гравців; при масовому виконанні — знищення всього сервера.

**Why existing protection does not prevent it:** `accept_invite` (легітимний шлях) вимагає інвайт, але `force_join_team` спеціально доданий, щоб його обійти («consent already happened in FTB Teams GUI»), і довіряє клієнту. Навіть у легітимному флоу втрата даних відбувається мовчки.

**Reproduction:** `curl -XPOST -H "X-Api-Key: $SPAWN_API_KEY" "http://api:8000/api/v1/teams/1/members?player_uuid=<victim>"` — **лише на тестовому стенді**.

**Recommended direction:** (а) ніколи не видаляти контейнер — робити `create_snapshot` + перевести в `ARCHIVED` із retention; (б) додати `requester_uuid` до `force_join_team`; (в) розділити ключі й дозволити цей ендпоінт лише spawn-ключу.

---

### SKY-003 · P1 · CONFIRMED · Острів зупиняється під активними тіммейтами

**Location:** `Nestworldvelocity/.../PlayerConnectionListener.java:196` (`attemptSingleConnection`), `:288-370` (`handleLeftIslandServer`); `MyIslandCommand.java:207`

**Problem:** Псевдо-сервер у Velocity іменується за UUID **гравця**, а не власника острова:
```java
String serverName = "island-" + player.getUniqueId();
```
Двоє членів однієї команди на одному фізичному острові опиняються на **двох різних `RegisteredServer`** з однаковою `InetSocketAddress`. Перевірка «чи лишився хтось із команди» читає `islandServer.getPlayersConnected()` — а це список **тільки одного** з цих псевдо-серверів.

**Evidence:**
```java
for (Player p : islandServer.getPlayersConnected()) {   // ← лише "island-<A>"
    if (p.getUniqueId().equals(leftPlayerUuid)) continue;
    (teamMemberUuids.contains(p.getUniqueId()) ? teamMembers : guests).add(p);
}
boolean otherTeamMembersOnline = !teamMembers.isEmpty();   // ← завжди false
```

**Execution scenario:**
```
T0  A і B — одна команда, острів RUNNING
T1  A: /myisland → registerServer("island-A") → connect
T2  B: /myisland → registerServer("island-B") → connect (той самий ip:port)
T3  A виходить з мережі → DisconnectEvent
T4  handleLeftIslandServer(A, islandServer="island-A")
T5  getPlayersConnected() на "island-A" → [] (A уже пішов, B на "island-B")
T6  otherTeamMembersOnline = false
T7  notifyPlayerLeft(owner) → WAITING-оновлення розблоковано
T8  scheduleTask(stopDelay=900s) → POST /islands/stop/{owner}
T9  API: RUNNING → PENDING_STOP → lxd stop --force
```

**Actual result:** через 15 хв контейнер вбивається **force-stop'ом** під гравцем B.

**Expected result:** острів має лишатися піднятим, поки на ньому є хоч один гравець.

**Impact:** втрата прогресу гравців у командах; пошкодження чанків при force-stop; погіршується тим, що `notifyPlayerLeft` ще й розблоковує hard-оновлення.

**Why existing protection does not prevent it:** `onServerConnected` скасовує pending stop **лише** якщо гравець заходить наново — B уже сидить на острові й події не генерує. Перевірка справді проходить по всій команді (`teamMemberUuids`), але дивиться не в той список гравців.

**Reproduction:** два акаунти в одній команді → обидва `/myisland` → вийти першим → чекати `stop_delay_seconds`.

**Recommended direction:** іменувати псевдо-сервер за `ownerUuid` (один `RegisteredServer` на острів), або рахувати присутність через `proxyServer.getAllPlayers()` з фільтром за адресою бекенду.

---

### SKY-004 · P1 · CONFIRMED · Вічно запущений порожній острів

**Location:** `PlayerConnectionListener.java:266-276` (`onPlayerDisconnect`); `island_service.py:_stop_existing_island`; `mods-server/.../PlayerEventHandler.java:96-103`

**Problem:** Якщо гравець від'єднується, поки його острів у `PENDING_START`, Velocity шле `stop`, API його **відхиляє** (409), і жоден інший механізм більше не зупинить цей острів.

**Evidence:**
```java
if (pendingStartPlayers.remove(disconnectedPlayerUuid)) {
    apiClient.requestIslandStop(disconnectedPlayerUuid);   // результат не перевіряється
    return;                                                // ← handleLeftIslandServer не викликається
}
```
```python
# _stop_existing_island — PENDING_START не в списку
if island.status in [RUNNING, FROZEN, ERROR_START]: ...
elif island.status == STOPPED: ...
else: raise ValueError(f"Island cannot be stopped from its current state: {...}")  # → 409
```

**Execution scenario:**
```
T0  Гравець логіниться, /myisland → POST /start → PENDING_START
T1  pendingStartPlayers.add(uuid)
T2  Гравець закриває клієнт (довгий старт контейнера)
T3  DisconnectEvent → requestIslandStop → API 409
T4  return — pendingStopTasks НЕ створюється
T5  BG-таск завершує старт → status=RUNNING
T6  Мод шле /ready → minecraft_ready=TRUE
T7  Мод шле heartbeat кожні 30 с
```

**Actual result:** острів працює **безстроково** з нулем гравців.
- `PlayerEventHandler.onPlayerLogout` не спрацює — ніхто не заходив.
- `playerJoinedWithinFirstHour == false` → авто-freeze мода **вимкнено за дизайном**.
- `health_worker` бачить свіжий heartbeat → нічого не робить.
- Idle-reaper'а на боці API не існує (SKY-017).

**Impact:** невідновне витікання RAM/CPU хоста. Кожен «нетерплячий» гравець додає один вічний контейнер.

**Reproduction:** `/myisland` → відразу вийти з гри → через 10 хв `lxc list` показує контейнер `RUNNING`, `GET /islands/{uuid}` → `RUNNING`, `online_players: 0`.

**Recommended direction:** серверний idle-reaper: `RUNNING` + `online_players == 0` довше за `FREEZE_TIMER_SECONDS` → freeze; довше за `STOP_TIMER_SECONDS` → stop. Це задіє вже наявні, але мертві налаштування.

---

### SKY-005 · P1 · CONFIRMED · Вічний `PENDING_CREATION`

**Location:** `api/app/main.py:38-46` (`statuses_to_check`), `island_service.py:_start_existing_island`

**Problem:** `reconcile_island_states` перевіряє 6 статусів і **не включає `PENDING_CREATION`** (як і `CREATING`, `DELETING`, `UPDATE_FAILED`). А `_start_existing_island` для `PENDING_CREATION` кидає `ValueError`.

**Evidence:**
```python
statuses_to_check = [RUNNING, FROZEN, PENDING_START, PENDING_FREEZE, PENDING_STOP, ERROR_START]
```

**Execution scenario:**
```
T0  Новий гравець: POST /start → INSERT islands(status=PENDING_CREATION), COMMIT
T1  Відповідь 202 віддано
T2  FastAPI перезапускається (deploy / OOM / SIGTERM) до виконання BackgroundTask
T3  Старт: reconcile пропускає PENDING_CREATION
T4  Гравець: /myisland → GET /islands/{uuid} → PENDING_CREATION
T5  POST /start → _start_existing_island → ValueError → 400
T6  Velocity: status.startsWith("PENDING_") → «зачекайте» × 15 спроб
T7  «Your island took too long to start»
```

**Actual result:** гравець **ніколи** не отримає острів; контейнера не існує; ручного recovery-ендпоінта немає.

**Impact:** «мертві» акаунти. Ймовірність висока: `deploy-api.yml` рестартує systemd-юніт.

**Why existing protection does not prevent it:** `clone_container` ідемпотентний і `container_name` детермінований — повтор був би безпечним, але жоден код його не ініціює.

**Recommended direction:** додати `PENDING_CREATION`/`CREATING` в reconcile; дозволити перехід `ERROR_CREATE → PENDING_CREATION`.

---

### SKY-006 · P1 · CONFIRMED · Оновлення і старт без взаємного локу

**Location:** `update_worker.py:process_queue_entry`, `update_service.py:perform_island_update`, `island_service.py:_perform_lxd_start_and_update_status`

**Problem:** Update worker і lifecycle-флоу читають/пишуть `islands.status` без жодного спільного локу й без умовних `UPDATE`. Обидва працюють з тим самим контейнером.

**Evidence:**
```python
# update_worker: рішення на основі знімка статусу
island = await crud_island.get(db_session, island_id=entry.island_id)
if island.status in _TRANSIENT: defer
...
# update_service: безумовний запис
await crud_island.update_by_id(db_session, island_id=island.id, obj_in={"status": UPDATING})
await lxd_service.stop_container(container_name, force=False)
```
```python
# island_service (BG-таска): жодної перевірки статусу
current_state = await lxd_service.get_container_state(container_name)
if current_state.get('status','').lower() != 'running':
    resumed = await lxd_service.start_container(container_name)
...
update_fields = {"status": RUNNING, ...}
await crud_island.update(db_session_bg, db_obj=team.island, obj_in=update_fields)
```

**Execution scenario A — оновлення вбиває сесію гравця:**
```
T0  Острів STOPPED, у черзі hard-update PENDING
T1  update_worker читає island → STOPPED ∈ _UPDATABLE_OFFLINE
T2  Гравець: POST /start → status=PENDING_START, BG-таска в черзі
T3  update_service: status=UPDATING, snapshot, push файлів
T4  BG-таска старту виконується: контейнер стартує ПОСЕРЕД push'у
T5  BG-таска пише status=RUNNING, перетираючи UPDATING
T6  update_service завершує: status=STOPPED, ip=NULL, minecraft_ready=FALSE
T7  Контейнер фізично RUNNING, БД каже STOPPED
```

**Execution scenario B — рестарт під гравцем:**
```
T0  Гравець виходить → POST /player_left → WAITING → PENDING
T1  update_worker читає island → STOPPED
T2  Гравець передумав: POST /start → PENDING_START
T3  update_worker: perform_island_update → stop_container(force=False)
T4  Гравець підключається до контейнера, який щойно зупинили
```

**Impact:** пошкодження встановлення модів (частково завантажені jar'и в живий JVM), розсинхрон БД↔LXD, вибивання гравців під час кампанії.

**Why existing protection does not prevent it:** `_TRANSIENT`-перевірка — це TOCTOU. `reset_stale_updating_islands` рятує лише після краху процесу. Redis `purchase_lock` існує **тільки** для маркету.

**Recommended direction:** per-island Redis-лок навколо **усього** lifecycle-переходу разом із LXD-операцією; плюс умовні `UPDATE ... WHERE status = <очікуваний>` з перевіркою `rowcount`.

---

### SKY-007 · P1 · CONFIRMED · Черги мертві, ліміт не діє

**Location:** `api/app/services/creation_worker.py:66-68`, `start_worker.py:74-76`

**Problem:**
```python
async def start_creation_worker():
    """Starts the creation queue worker in the background."""
    pass

async def start_start_worker():
    """Starts the start queue worker in the background."""
    pass
```
Обидві функції викликаються в `lifespan` і **не роблять нічого**. Самі `trigger_*` ніде не викликаються — і **не запрацювали б**:

```python
# creation_worker.py:40 — методу create_new_island не існує
await island_service.create_new_island(...)          # AttributeError

# start_worker.py:43 — сигнатура не має was_frozen
await island_service._perform_solo_lxd_start_and_update_status(
    player_uuid_str=..., container_name=..., was_frozen=...)   # TypeError
```

**Evidence:** `grep -rn "MAX_RUNNING_SERVERS" api/app/` дає лише `config.py` + ці два мертві файли. `grep -n "def create_new_island" island_service.py` → нічого.

**Impact:** `MAX_RUNNING_SERVERS` не обмежує **нічого**. Масовий логін → десятки одночасних `clone`+`start` → OOM/своп хоста. Підсилює SKY-004.

**Recommended direction:** або реалізувати черги й підключити до `_start_existing_island`, або чесно видалити мертвий код і винести ліміт у семафор. Поточний стан — найгірший: конфіг обіцяє захист, якого нема.

---

### SKY-008 · P1 · CONFIRMED · Один ключ = усі права; fail-open

**Location:** `api/app/core/auth.py:6-17`, `main.py:265-271` (WS), `mods-server/.../Config.java`

**Problem:** Три дефекти в одному місці:
1. `PROXY_API_KEY` і `SPAWN_API_KEY` приймаються **взаємозамінно** — рівнів довіри немає.
2. `SPAWN_API_KEY` записаний у `skyblock-common.toml` **всередині кожного острівного контейнера** і в шаблонному образі.
3. Порожня конфігурація ⇒ автентифікація **вимикається мовчки**.

**Evidence:**
```python
valid_keys = {k for k in [settings.PROXY_API_KEY, settings.SPAWN_API_KEY] if k}
if not valid_keys:
    return  # auth disabled when no keys configured
```
Авторизація всюди спирається на query-параметр від клієнта — коментарі в коді самі це визнають: `# This should come from an auth token`.

**Execution scenario:**
1. Доступ до ФС будь-якого острова → читає `SPAWN_API_KEY`.
2. `POST /islands/stop/<будь-який-uuid>` — глушить чужі острови.
3. `DELETE /teams/{id}/members/{victim}?requester_uuid=<owner>` — кікає будь-кого.
4. `POST /updates/rollback/campaign/{id}` — відкочує весь сервер.
5. `GET /warps/player-uuid/{nick}` — вивантажує базу «нік → UUID».
6. + SKY-002 → знищення островів.

**Impact:** компрометація одного острова = компрометація всієї платформи. Окремо: `.env`, який не доїхав під час деплою, робить API анонімним, і health-check (`GET /`) це не ловить.

**Recommended direction:** окремі ключі зі scope'ами (proxy / spawn / admin); підписані короткоживучі токени з `player_uuid` у payload; замінити fail-open на fail-closed.

---

### SKY-009 · P1 · CONFIRMED · Підміна WebSocket `client_id`

**Location:** `api/app/main.py:262-276`; `websocket_manager.py:22-41`

**Problem:** WS-ендпоінт перевіряє **наявність валідного ключа**, але не перевіряє, що `client_id` належить пред'явнику. Плюс `ConnectionManager.connect` **примусово закриває** попереднє з'єднання з тим самим `client_id`.

**Evidence:**
```python
api_key = websocket.headers.get("x-api-key", "")
if api_key not in valid_keys:
    await websocket.close(code=4001)
    return
await websocket_manager.connect(websocket, client_id)   # client_id не звіряється
```
```python
if client_id in self.active_connections:
    logger.warning(f"Client {client_id} is reconnecting. Closing the old connection.")
    await old_websocket.close()
```

**Execution scenario:**
```
T0  Острів жертви підключений як core_<victim-owner>
T1  Атакувальник з тим самим SPAWN_API_KEY:
    ws://api:8000/ws/core_<victim-owner>  + X-Api-Key
T2  ConnectionManager закриває з'єднання жертви
T3  Атакувальник отримує TEAM_UPDATED, TEAM_INVITE, execute_command, pending_update жертви
T4  Може слати heartbeat за жертву → watchdog вважає її острів здоровим
```

**Impact:** DoS + hijack чужих команд оновлення + нейтралізація watchdog + закриття чужих `pending_id` через `command_ack`.

**Why existing protection does not prevent it:** коміт `65a2a7f` розвів **префікси**, щоб компоненти не витісняли одне одного — це захист від колізій, а не від зловмисника.

**Recommended direction:** короткоживучий токен, прив'язаний до `owner_uuid` (пушити в `skyblock_island_data.toml` при клонуванні), звіряти з `client_id`; замість витіснення — відхиляти нове з'єднання або тримати set.

---

### SKY-010 · P1 · CONFIRMED · Незворотна втрата коштів у маркеті

**Location:** `endpoints/market.py:243-262` (`confirm_extraction`), `:265-288` (`cancel_purchase`); `crud_market.py:231-271`, `:273-320`

**Problem:** БД комітиться **до** звернення до Azuriom. Компенсації немає — лише `logger.error`.

**Evidence:**
```python
# crud_market.confirm_extraction
tx.seller_paid = True
await db_session.delete(record)
await db_session.commit()          # ← точка неповернення
return seller_info

# endpoint
seller_info = await crud_market.confirm_extraction(...)   # уже закомічено
credited = await credit_seller(seller_info["seller_azuriom_id"], seller_info["total_price"])
if not credited:
    logger.error(...)              # ← і все
```

**Execution scenario:**
1. Гравець купує → `execute_purchase` списує гроші покупця ✅.
2. Острів витягує предмет з AE2 → `POST /extraction/{id}/confirm`.
3. `seller_paid=TRUE`, `pending` видалено, COMMIT.
4. Azuriom недоступний → обидві спроби провалюються.
5. Продавець **ніколи** не отримає гроші. Повтор дасть 404, `seller_paid` уже `TRUE`.

Симетрично в `cancel_purchase`: покупець втрачає гроші назавжди.

**Impact:** тихе зникнення грошей; єдиний слід — рядок у логу; механізму звірки немає.

**Recommended direction:** outbox-патерн — `pending_payout(tx_id, azuriom_id, amount, status)` у тій самій транзакції + воркер з ретраями й ідемпотентним ключем.

---

### SKY-011 · P1 · CONFIRMED · `/sell` не ідемпотентний

**Location:** `endpoints/market.py:219-241`

```python
credited = await credit_seller(payload.seller_azuriom_id, total)
if not credited:
    raise HTTPException(502, ...)
await crud_market.record_sell_transaction(...)   # ← якщо впаде, гроші вже нараховані
```
Немає ні ідемпотентного ключа, ні `purchase_lock`, ні перевірки, що гравець **справді** мав ці предмети.

**Execution scenario:**
```
T0  POST /sell {item, qty=64, unit_price=10, seller_azuriom_id=7}
T1  credit_seller(+640) → Azuriom OK
T2  record_sell_transaction — commit
T3  HTTP-відповідь втрачена (таймаут 10 с)
T4  Мод ретраїть той самий запит
T5  credit_seller(+640) знову → 1280 за 64 предмети
```

**Impact:** інфляція/дюп грошей; за наявності ключа — довільна емісія: `POST /sell {quantity: 1, unit_price: 999999}`.

**Why existing protection does not prevent it:** `purchase_lock` є **тільки** в `execute_purchase`. `SECURITY_AUDIT.md` H-5 досі відкрито.

**Recommended direction:** ідемпотентний `sale_id` з UNIQUE; спершу транзакція, потім платіж; валідація `unit_price` проти серверного прайс-листа.

---

### SKY-012 · P1 · CONFIRMED · Блокуючий I/O на Minecraft server thread

**Location:** `mods-server/.../NestworldModsServer.java:83-89`, `:130-176`

```java
public UUID getCachedTeamId(UUID playerUuid) {
    Long cachedAt = islandCacheTimestamps.get(playerUuid);
    if (cachedAt != null && (now - cachedAt) < TEAM_CACHE_TTL_MS) return islandCache.get(playerUuid);
    // If not, fetch it synchronously. This will block, but it's necessary for FTB Quests.
    return refreshAndGetTeamId(playerUuid);
}
```
`refreshAndGetTeamId` виконує **синхронний** `httpClient.send(...)` (connect 10 с + request 10 с) плюс синхронний `FileWriter`/`FileReader`. Викликається з провайдера RealMarket і з FTB-Quests — тобто з main server thread.

**Execution scenario:**
```
T0  TTL кешу (60 с) спливає
T1  FTB Quests / RealMarket питає команду гравця на main thread
T2  API перевантажений або мережа просідає
T3  httpClient.send() блокує до 20 с
T4  Server tick thread стоїть
T5  Heartbeat (з ServerTickEvent) не відправляється
T6  Якщо сумарний стоп > 90 с — health_worker класифікує як "hung" і ЗУПИНЯЄ контейнер
```

**Impact:** лаг-спайки до 20 с при кожному протуханні кешу; у важких випадках watchdog вбиває живий сервер.

**Recommended direction:** прогрівати кеш асинхронно на `PlayerLoggedInEvent` і по WS-події `TEAM_UPDATED` (вона вже існує!); `getCachedTeamId` має повертати stale-while-revalidate і **ніколи** не блокувати.

---

### SKY-013 · P1 · CONFIRMED · `schema.sql` не створює робочу базу

**Location:** `api/sql/schema.sql` vs `api/app/models/island.py`, `models/market.py`

| Колонка/таблиця | `schema.sql` | ORM |
|---|---|---|
| `islands.player_uuid` | `VARCHAR(36) NOT NULL UNIQUE` | `nullable=True` — team-острови створюються з `NULL` |
| `islands.team_id` | **відсутня** | `FK teams.id UNIQUE` — основа моделі власності |
| `islands.minecraft_ready` | **відсутня** | `Boolean NOT NULL` — головний прапорець контракту з Velocity |
| `islands.last_heartbeat_at/last_tps/online_players` | **відсутні** (є лише як `ALTER ... AFTER minecraft_ready` — посилається на неіснуючу колонку) | є |
| `market_items` | `island_uuid VARCHAR(36) NOT NULL` | `team_id INT FK NOT NULL`, `UNIQUE uq_team_item` |
| `market_transactions.seller_paid` | **відсутня** | `Boolean NOT NULL` — використовується в `confirm_extraction` і в analytics |
| Міграція `island_uuid → team_id` | **повністю закоментована** (5 кроків) | обов'язкова |
| `teams.owner_uuid` | лише `INDEX` | код покладається на унікальність |

**Execution scenario:**
1. Новий деплой за README: `mysql db < api/sql/schema.sql`.
2. Старт API — схема не валідується, все виглядає добре.
3. Перший `POST /islands/start/{uuid}`: `Unknown column 'team_id'` → 500.

**Impact:** неможливо відтворити середовище з нуля; disaster recovery неможливий.

**Why existing protection does not prevent it:** тести працюють на SQLite через `Base.metadata.create_all()` — **з ORM, а не зі `schema.sql`**. Дрейф невидимий тестам.

**Recommended direction:** Alembic з baseline-ревізією, згенерованою з продакшн-схеми; CI-крок «підняти MySQL зі `schema.sql` + прогнати тести».

---

### SKY-014 · P2 · CONFIRMED · Витік ресурсів у Velocity

**Location:** `PlayerConnectionListener.java:196-200`; `MyIslandCommand.java:170-190`; `WebSocketManager.java:54`

1. `proxyServer.registerServer(...)` для `island-<uuid>` — `unregisterServer` викликається **тільки** при невдалому конекті в `MyIslandCommand`. Реєстр росте монотонно.
2. `plugin.getWebSocketManagers()` — `remove` лише в `connectToWebSocket` того самого гравця. `DisconnectEvent` мапу не чистить.
3. `WebSocketManager.connect()` робить `HttpClient.newHttpClient()` **на кожен інстанс** — окремий selector-потік, який у Java 11 закрити не можна.

**Impact:** тисячі `RegisteredServer` зі застарілими адресами і сотні «зомбі»-потоків. Побічно шкодить SKY-003.

**Recommended direction:** `unregisterServer` + `webSocketManagers.remove(uuid).close()` у `DisconnectEvent`; один статичний `HttpClient` на плагін.

---

### SKY-015 · P2 · CONFIRMED · Мовчазна смерть polling-ланцюжка

**Location:** `PlayerConnectionListener.java:99-165`

Ланцюг `getIslandDetails().thenComposeAsync(...).thenAccept(...)` **не має `.exceptionally()`**. Усередині — необроблені `JsonParser.parseString(...)`, `get("status").getAsString()`, `get("internal_ip_address").getAsString()`.

**Execution scenario:**
1. API віддає `RUNNING` з `internal_ip_address: null` (SKY-024 або гонка reconcile).
2. `JsonNull.getAsString()` → `UnsupportedOperationException`.
3. `thenAccept` не виконується; `scheduleNextPoll` не викликається.
4. Polling тихо припиняється. Гравець не отримує ні підключення, ні повідомлення, ні таймауту. У логах — **нічого**.

**Impact:** «зависає на хабі без причини» — сценарій, який неможливо діагностувати з логів.

**Recommended direction:** `.exceptionally(...)` в кінці ланцюга; захисні `has()`-перевірки перед кожним `get()`.

---

### SKY-016 · P2 · CONFIRMED · Непересистентні BackgroundTasks

**Location:** усі `background_tasks.add_task(...)`

**Найгірший підвид — видалення:**
```
T0  handle_join_team: DELETE teams row, add_task(_perform_lxd_delete_and_cleanup)
T1  Відповідь 200, dependency комітить
T2  BackgroundTask стартує, lxd delete виконано
T3  Процес падає до crud_island.remove_by_id
T4  Контейнера немає, рядок islands лишився (team_id=NULL) → недосяжний
```
Симетрично: падіння **до** таски → рядок видалено, контейнер живий і невідомий системі → **orphan назавжди**.

**Impact:** orphan-контейнери (диск+RAM) і orphan-рядки; `reconcile` знаходить лише «рядок є, контейнера немає», зворотний випадок **не звіряється ніколи**.

**Recommended direction:** персистентна черга job'ів із ретраями; reconcile-прохід «контейнери в LXD, яких немає в БД».

---

### SKY-017 · P2 · CONFIRMED · Немає reaper'а — `FROZEN` острови накопичуються

**Location:** відсутність коду; `config.py:52-55` (мертві налаштування)

Єдиний механізм `FROZEN → STOPPED` — `pendingStopTasks` у **пам'яті Velocity** (900 с).

```
T0  10 островів FROZEN, у кожного заплановано stop у Velocity
T1  Velocity рестартує
T2  pendingStopTasks втрачено
T3  Жоден із 10 островів ніколи не буде зупинено
T4  health_worker їх не бачить — він дивиться тільки на RUNNING
T5  reconcile: FROZEN/Frozen → «consistent», нічого не робить
```

**Impact:** заморожені контейнери тримають RAM (cgroup freezer не звільняє пам'ять) невизначено довго. Монотонне витікання після кожного рестарту проксі.

**Why existing protection does not prevent it:** `STOP_TIMER_SECONDS`, `MAX_ISLAND_INACTIVITY_DAYS`, `CLEANUP_INTERVAL_HOURS` існують у конфізі, але не читаються жодним рядком коду.

**Recommended direction:** reaper у `health_worker`: `FROZEN` + старий `last_seen_at` → stop; `STOPPED` + `MAX_ISLAND_INACTIVITY_DAYS` → снапшот + `ARCHIVED`.

---

### SKY-018 · P2 · CONFIRMED · `ERROR` і `ERROR_CREATE` — тупики

**Location:** `island_service.py:_start_existing_island`; `health_worker.py`; `main.py` reconcile

`_start_existing_island` дозволяє старт із `STOPPED`, `FROZEN`, `ERROR_START`. `ERROR` і `ERROR_CREATE` — ні. При цьому обидва виставляються автоматично (watchdog/reconcile при зниклому контейнері; невдалий клон). Жоден ендпоінт не виводить острів із цих станів.

**Execution scenario:** адмін випадково `lxc delete` → watchdog ставить `ERROR` → гравець `/myisland` → Velocity бачить `startsWith("ERROR")` → `/start` → 400 → 15 повторів → «took too long». Назавжди.

**Recommended direction:** `POST /islands/{uuid}/repair` або автоперехід `ERROR` + контейнер відсутній → `PENDING_CREATION` із перезапуском клону; запис у `island_events`.

---

### SKY-019 · P2 · CONFIRMED · Тести й CI не перевіряють продакшн-конфігурацію

**Location:** `api/app/tests/conftest.py`, `.github/workflows/_test-python.yml:52-57`

```
$ python -m pytest app/tests -q                        → 55 passed
$ PROXY_API_KEY=testkey python -m pytest app/tests -q  → 19 failed, 36 passed
```
`conftest.py` не додає `X-Api-Key`. Workflow задає лише `DATABASE_URL`.

**Impact:** усі 20 HTTP-тестів `/islands/*` перевіряють **анонімний** режим. Регресія в `require_api_key` пройде CI зеленою. Fail-open нормалізовано як «робоча конфігурація тестів».

**Recommended direction:** задати `PROXY_API_KEY` у CI-env, додати заголовок у фікстуру `client`, додати негативні тести (без ключа → 401, чужий ключ → 401).

---

### SKY-020 · P2 · LIKELY · Freeze без перевірки присутності гравців

**Location:** `PlayerEventHandler.java:141-160`; `island_service.py:_freeze_existing_island`

Мод планує freeze через 5 хв. Скасування — **тільки** через `PlayerLoggedInEvent` усередині того самого JVM. API, отримавши `POST /{uuid}/freeze`, **не перевіряє `online_players`** — лише що статус `RUNNING`.

```
T0    Останній гравець виходить → scheduleFreezeTask(5 хв)
T4:59 Гравець: /myisland → RUNNING && ready → attemptSingleConnection
T5:00 freezeTask спрацьовує → POST /freeze → lxc freeze
T5:01 PlayerLoggedInEvent... який уже не виконається — JVM заморожено
```

**Confidence LIKELY:** вікно вузьке, потрібне runtime-підтвердження. Але з боку API захисту **немає жодного**: `_freeze_existing_island` не дивиться ні на `online_players` (яке API вже має з heartbeat!), ні на `last_seen_at`.

**Recommended direction:** на API відхиляти freeze при `online_players > 0` або свіжому `last_seen_at`; на моді перевіряти `getPlayerCount() == 0` **всередині** runnable.

---

### SKY-021 · P2 · CONFIRMED · BG-таска видалення ставиться до commit'у

**Location:** `island_service.py:735-740`

```python
if old_island_to_delete:
    background_tasks.add_task(self._perform_lxd_delete_and_cleanup, island_id=old_island_to_delete.id)
# коміт відбудеться пізніше, у get_db_session
```
Якщо коміт впаде (deadlock, FK-конфлікт), транзакція відкотиться — але таска вже зареєстрована. Контейнер буде видалено, а рядок `islands` і команда — збережені.

**Impact:** гравець лишається у старій команді без контейнера; `/myisland` → `LXDContainerNotFoundError` → `ERROR_START`, далі SKY-018.

**Recommended direction:** реєструвати руйнівні побічні ефекти лише після успішного коміту, або перенести в персистентну чергу (SKY-016).

---

### SKY-022 · P2 · CONFIRMED · Contract mismatch у `createTeam`

**Location:** `ApiClient.java:191-200` vs `endpoints/teams.py:33-70`

```java
gson.toJson(Map.of("player_info", Map.of("player_uuid", ..., "player_name", ...)))
```
```python
async def create_solo_island_and_team(*, player_info: dict, ...):
    player_uuid = player_info.get("player_uuid")   # → None
    if not player_uuid or not player_name:
        raise HTTPException(400, "player_uuid and player_name are required.")
```
FastAPI з єдиним body-параметром типу `dict` **не** робить embed → тіло цілком стає `player_info`. Вкладений варіант дає `{"player_info": {...}}` і 400. Викликається з `TeamCommand.java:104` → `/team create <name>` **завжди повертає помилку**. Додатково `teamName` передається, але ендпоінт його ігнорує.

**Recommended direction:** перевести `create_solo` на Pydantic-схему (422 з чітким описом) і виправити payload, або видалити мертвий метод.

---

### SKY-023 · P2 · CONFIRMED · Зламаний і не підключений роутер `images`

**Location:** `api/app/api/v1/endpoints/images.py:28,54,78`

```python
from app.crud.crud_image_version import crud_image_version
```
Такого модуля в `api/app/crud/` **немає**. Імпорт локальний, тож застосунок стартує, але виклик дав би `ModuleNotFoundError` → 500. Роутер при цьому **не підключений** у `main.py`. Проте `CLAUDE.md` перелічує `images` серед робочих ендпоінтів.

**Recommended direction:** видалити модуль і згадку в `CLAUDE.md`, або дописати CRUD і підключити роутер.

---

### SKY-024 · P2 · LIKELY · reconcile може лишити `RUNNING` без IP

**Location:** `api/app/main.py:113-124`

```python
elif db_status == IslandStatusEnum.FROZEN:
    ...
    elif lxd_status_str == "Running":
        new_status = IslandStatusEnum.RUNNING
        if actual_lxd_state_info and actual_lxd_state_info.get("ip_address"):
            extra_fields_to_update["internal_ip_address"] = ...
        # якщо IP немає — статус усе одно RUNNING
```
Для `PENDING_START` аналогічний випадок оброблено правильно (→ `ERROR_START`), для `FROZEN` і `ERROR_START` — ні. `get_container_state` читає IP **одноразово, без ретраїв** (на відміну від `get_container_ip`), а щойно піднятий контейнер часто ще не має DHCP-адреси.

**Наслідок ланцюжком:** `RUNNING` + `minecraft_ready=true` + `ip=null` → Velocity → SKY-015.

**Recommended direction:** використовувати `get_container_ip` у reconcile; без IP ставити `ERROR_START`.

---

### SKY-025 · P3 · POSSIBLE · Argument injection у git-теґ

**Location:** `endpoints/updates.py:247-252` (`sync_spawn`), `git_sync.checkout_tag`

`body.tag` (довільний рядок від клієнта з API-ключем) потрапляє в `git checkout --force <tag>`. Shell не задіяний (RCE немає), але значення на `-`/`--` git інтерпретує як опцію. `parse_tag` валідує регексом — але **після** `checkout_tag`.

**Recommended direction:** валідувати тег **до** будь-якої git-команди; додати `--` перед аргументом.

---

### SKY-026 · P3 · CONFIRMED · GITHUB_TOKEN осідає у `.git/config`

**Location:** `git_sync.py:_authenticated_url`

```python
return repo_url.replace("https://", f"https://x-access-token:{token}@", 1)
```
URL зберігається як `remote.origin.url` у `.git/config` на хості API у відкритому вигляді. Токен також може потрапити в stderr git'а → `GitSyncError` → `str(e)` → тіло 500-відповіді `/updates/spawn/sync`.

**Recommended direction:** `GIT_ASKPASS` / `http.extraheader` замість URL; санітизувати повідомлення про помилки.

---

### SKY-027 · P3 · CONFIRMED · `/extraction/{id}/fail` — no-op

**Location:** `endpoints/market.py:293-308`

Ендпоінт приймає `pending_id`, але **ніде його не використовує**: рахує всі pending команди й повертає число. Ні лічильника спроб, ні відмітки «борг», ні алерту. Docstring обіцяє «Returns current debt status».

**Recommended direction:** писати `attempts`/`last_failed_at` у `market_pending_extractions`, або видалити ендпоінт.

---

### SKY-028 · P3 · CONFIRMED · Analytics переагрегує ту саму годину 12×

**Location:** `workers/analytics_worker.py:150-162`

`_analytics_loop` спить 300 с і щоразу агрегує `now - 1 година`. За годину — 12 повних `GROUP BY` над `market_transactions` з тим самим результатом. `_detect_anomalies` при цьому робить `AVG` на **кожен** рядок кожного проходу.

**Recommended direction:** курсор останньої агрегованої години або годинний інтервал.

---

### SKY-029 · P3 · LIKELY · Змішування naive-UTC і серверного часу БД

**Location:** `main.py` (heartbeat: `datetime.utcnow()`), `models/island.py` (`server_default=func.now()`), `crud_island_event.has_recent_event`

```python
cutoff = datetime.utcnow() - timedelta(seconds=within_seconds)
... IslandEvent.created_at >= cutoff
```
`island_events.created_at` заповнює MySQL через `NOW()` — у **часовому поясі сервера БД**. Порівнюється з Python-UTC. Якщо MySQL у `Europe/Kyiv` (UTC+3), усі події виглядають на 3 години «свіжішими».

**Наслідок:** `has_recent_event("stopping", 300)` майже завжди `True` → watchdog класифікує справжні зависання як `stopped_externally`, спотворюючи журнал.

**Recommended direction:** заповнювати всі timestamp'и з Python в UTC (або все через `func.now()` + `SET time_zone='+00:00'`); стартова перевірка, що БД у UTC.

---

### SKY-030 · P3 · LIKELY · Синхронний file I/O на tick thread (RealMarket)

**Location:** `RealMarket/.../world/IslandManager.java:46, 94, 187`

`savePrices()`, `saveSlots()`, `savePlatform()` досі використовують синхронний `FileWriter` + `GSON.toJson`. `SECURITY_AUDIT.md` M-3 описує це і оцінює `savePlatform` у 100+ мс; рекомендований `CompletableFuture.runAsync` не застосовано.

---

### SKY-031 · P3 · POSSIBLE · `minecraft_ready=TRUE` при unfreeze без перевірки

**Location:** `island_service.py:334-339` і `:383-388`

```python
if resumed:
    # Resuming a frozen JVM does not fire a fresh server-started event...
    update_fields["minecraft_ready"] = True
```
Припущення «якщо був заморожений — значить був готовий» не перевіряється. Якщо острів заморозили **до** приходу `/ready`, unfreeze проставить `minecraft_ready=TRUE` для сервера, який ще вантажиться → Velocity під'єднає гравця → kick «Server is still starting!».

**Recommended direction:** після unfreeze робити активну перевірку (TCP-probe на 25565 або heartbeat) перед виставленням прапорця.

---

### SKY-032 · INFO · CONFIRMED · Гігієна репозиторію

- `AzuriomClient.java` у корені — пакет `com.real.market`, не входить у жоден Gradle-проєкт.
- `TeamCommand.java` у корені — **буквально заглушка**: `// This is a placeholder for the actual content of TeamCommand.java from the 5.0 commit.`
- Кореневий `settings.gradle` включає `mods-server` і `FTB-Quests-1.20.1-main`, які мають **власні** `settings.gradle` (nested builds); `gradlew` у корені **немає**; `Nestworldvelocity`, `RealMarket`, `nestworld-teams-*` у кореневу збірку не входять.
- `.gitignore` містить `/.claude` тричі й зафіксовані шляхи до конкретних `.pyc`-файлів.
- `requirements.txt` у корені та `api/requirements.txt` розходяться; жоден не містить `pytest`/`aiosqlite`, які потрібні CI.

---

## 17. Unknowns

Що **неможливо** довести статичним аналізом:

1. **Реальна тривалість `reconcile_island_states`** при N островах — визначає, чи протухає `startup_lock` (60 с).
2. **Чи справді два gunicorn-воркери стають лідерами.**
3. **Часовий пояс продакшн-MySQL** — вирішальний для SKY-029.
4. **Фактична пропускна здатність `run_in_executor(None)`** під час масового логіну.
5. **Чи є `PROXY_API_KEY`/`SPAWN_API_KEY` виставленими на проді** — якщо ні, API анонімний прямо зараз.
6. **Чи розповсюджується `RealMarket.jar` клієнтам** — визначає, чи витік SKY-001 публічний.
7. **Чи слухає API `0.0.0.0` без firewall.**
8. **Реальна затримка `getCachedTeamId`** — чи доходить до 90 с сумарно.
9. **Кількість orphan-контейнерів зараз** — `lxc list` vs БД ніколи не звірялися.
10. **Скільки островів зараз у `FROZEN`/`RUNNING` з нулем гравців.**
11. **Стан продакшн-схеми** — які саме `ALTER` реально виконані.
12. **Чи є непроведені виплати** — звірка `seller_paid` з історією Azuriom.
13. **Вміст репозиторію `skyblock-updates`** — недоступний, тому path traversal у `UPDATES_TARGET_DIR` не перевірений.
14. **Чи Java-модулі взагалі збираються** — Gradle-збірка не запускалася (немає Java-toolchain і доступу до Maven-дзеркал).

---

## 18. Recommended Verification

| Finding | Що перевірити | Як | Очікуваний результат |
|---|---|---|---|
| SKY-001 | Чи токен активний | Ротувати в адмінці Azuriom, перевірити 401 на старому | Старий токен мертвий |
| SKY-002 | Знищення острова | dev: `POST /teams/{id}/members?player_uuid=<жертва>`, потім `lxc list` | Контейнер зник — **тільки на dev** |
| SKY-003 | Зупинка під тіммейтом | 2 акаунти в команді → обидва `/myisland` → вийти першим → 900 с | Контейнер зупинено, другий вилетів |
| SKY-004 | Вічний острів | `/myisland` → негайний вихід → `lxc list` через 30 хв | `RUNNING`, `online_players: 0` |
| SKY-005 | Пастка PENDING_CREATION | `POST /start` новому uuid → `systemctl restart` → повторний `/start` | 400/409 назавжди |
| SKY-006 | Гонка update/start | Кампанія на dev + `POST /start` у момент `PROCESSING` | Розсинхрон БД↔LXD |
| SKY-007 | Мертві воркери | `grep -A2 "async def start_creation_worker"` + логи старту | Немає «queue worker started» |
| SKY-008 | Fail-open | API з порожніми ключами → `curl` без заголовка | 200 замість 401 |
| SKY-009 | WS hijack | Два WS до `/ws/core_<same>` з валідним ключем | Перше з'єднання закрито |
| SKY-010 | Втрата виплат | `SELECT COUNT(*) FROM market_transactions WHERE seller_paid=1` vs історія Azuriom | Розбіжність = сума втрат |
| SKY-011 | Дубль грошей | Двічі той самий `POST /sell` | Баланс зріс удвічі |
| SKY-012 | Блокування thread | `jstack` під час промаху кешу; або `tc` затримка до API | Server thread у `SocketRead` |
| SKY-013 | Схема | MySQL зі `schema.sql` + прогнати тести проти неї | `Unknown column 'team_id'` |
| SKY-014 | Витік | Дамп реєстру після 100 логінів; `jcmd Thread.print` | Реєстр і потоки ростуть |
| SKY-015 | Мовчазна смерть | Підсунути `internal_ip_address: null` при `RUNNING` | Polling зупиняється без логів |
| SKY-016 | Orphan | `comm -13 <(mysql -N -e 'SELECT container_name FROM islands'\|sort) <(lxc list -c n --format csv\|sort)` | Список orphan-контейнерів |
| SKY-017 | FROZEN-накопичення | Рестарт Velocity із 3 FROZEN → `lxc list` через годину | Досі FROZEN |
| SKY-019 | Тести | `PROXY_API_KEY=x pytest app/tests -q` | 19 failed (відтворено ✅) |
| SKY-020 | Freeze-гонка | `POST /freeze` одночасно з підключенням гравця | Зависле з'єднання |
| SKY-029 | TZ | `SELECT @@global.time_zone, NOW(), UTC_TIMESTAMP()` | Якщо не UTC — підтверджено |

---

## Рекомендований порядок робіт

| Черга | Findings | Обґрунтування |
|---|---|---|
| **Негайно** | SKY-001 (ротувати токен), SKY-008 (fail-open → fail-closed) | активний секрет + можлива анонімність API |
| **Тиждень 1** | SKY-002, SKY-010, SKY-011 | незворотна втрата даних і грошей |
| **Тиждень 2** | SKY-004, SKY-017, SKY-007 | витікання ресурсів хоста (зростає щодня) |
| **Тиждень 3** | SKY-003, SKY-005, SKY-006, SKY-018 | видимі гравцям збої життєвого циклу |
| **Тиждень 4** | SKY-013, SKY-019 | фундамент: без коректної схеми й тестів решта фіксів неперевірювана |
| Далі | SKY-009, SKY-012, SKY-014…SKY-024 | |
| Прибирання | SKY-025…SKY-032 | |

---

**Обмеження цього аудиту:** Java-модулі не збиралися (немає toolchain'у та доступу до Maven-дзеркал), тому висновки щодо Velocity/Forge зроблено виключно зі статичного читання. Runtime-спостережень за живою системою не проводилося. Репозиторій оновлень (`UPDATES_REPO_URL`) недоступний. Проєкт під час аудиту не змінювався.
