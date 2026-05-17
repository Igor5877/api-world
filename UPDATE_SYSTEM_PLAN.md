# Система автоматичних оновлень островів

## Загальна ідея

Адмін пушить зміни (моди, конфіги, квести, рецепти) в окремий GitHub репозиторій з тегом версії.
GitHub надсилає webhook на FastAPI. API визначає що змінилось через `git diff`, створює кампанію оновлення,
і worker по черзі оновлює кожен острів з урахуванням стану гравця.
Гравець не помічає оновлення якщо воно серверне — якщо потрібен перезахід, отримує повідомлення в грі.

---

## Репозиторій оновлень (skyblock-updates)

### Структура

```
skyblock-updates/
  mods/                   ← повний список .jar (серверні + both моди)
    economy-2.1.jar
    ftbquests-x.x.jar
    ...
  config/
    economy.toml
    somemod/
      settings.json
  quests/
    chapters/
      chapter1.snbt
      chapter2.snbt
  recipes/
    custom_table.json
  scripts/
    post_update.sh        ← виконується після копіювання (опціонально)
```

**Чого тут немає:** шейдери, OptiFine та інші чисто клієнтські моди — це справа лаучера.

Репо завжди містить **повний актуальний стан** файлів, не diff.
git сам відстежує що змінилось між тегами.

### Конвенція тегів

```bash
git tag v1.3.0           # server_only — дефолт, більшість оновлень
git tag v1.3.1-both      # мод потрібен і на клієнті і на сервері
git tag v1.3.2-critical  # критичний фікс — кік гравця одразу
```

### Workflow адміна

```bash
# 1. Змінив файли (моди, квести, конфіги, рецепти)
# 2. Коміт з описом що змінилось
git commit -m "видалення рецепту столу, оновлення economy 2.1, нові квести розділу 2"

# 3. Тег з версією (суфікс тільки якщо -both або -critical)
git tag v1.3.0

# 4. Пуш — webhook спрацьовує автоматично
git push && git push --tags
```

Більше нічого робити не треба.

---

## Як API визначає що робити (без маніфесту)

```
tag: v1.3.0              → update_type = server_only
tag: v1.3.1-both         → update_type = both (notify player)
tag: v1.3.2-critical     → update_type = critical (kick immediately)
commit message           → message для гравців і логів

git diff v1.2.0..v1.3.0:
  mods/*.jar змінились           → requires_restart = True
  config/ftbquests/**            → reload_commands += ["ftbquests reload"]
  kubejs/server_scripts/**       → reload_commands += ["reload"]
  kubejs/startup_scripts/**      → requires_restart = True
  config/** (решта)              → reload_commands += ["reload"]
  defaultconfigs/**              → тільки базовий контейнер, острови не чіпаємо
  тільки scripts/                → нічого додаткового
```

Таблиця дій по типу оновлення:

| update_type | Гравець онлайн | Дія |
|---|---|---|
| server_only | неважливо | copy files → reload/restart → готово |
| both | онлайн | повідомлення в чат → чекаємо logout → copy → restart |
| both | офлайн | copy → restart → готово |
| critical | онлайн | кік → copy → restart |
| critical | офлайн | copy → restart → готово |

Якщо `requires_restart = False` (тільки конфіги/квести/рецепти):
- гравець **не виходить** незалежно від update_type
- команди перезавантаження виконуються через WebSocket

---

## Зміни в коді

### 1. `api/app/services/lxd_service.py` — нові методи

```python
async def create_snapshot(container_name: str, snapshot_name: str) -> None:
    # lxc snapshot <container> <snapshot_name>

async def restore_snapshot(container_name: str, snapshot_name: str) -> None:
    # lxc stop <container> (якщо запущений)
    # lxc restore <container> <snapshot_name>
    # lxc start <container> (якщо був запущений)

async def delete_snapshot(container_name: str, snapshot_name: str) -> None:
    # lxc delete <container>/<snapshot_name>

async def push_directory(container_name: str, local_path: str, container_path: str) -> None:
    # rsync-логіка: копіює всі файли, видаляє зайві
    # використовує pylxd file push або lxc exec + tar

async def backup_files(container_name: str, paths: list[str], backup_dir: str) -> None:
    # копіює конкретні файли в /opt/minecraft/backups/<version>/
    # для soft rollback (без restart)

async def restore_files(container_name: str, backup_dir: str, paths: list[str]) -> None:
    # відновлює файли з backup_dir

async def exec_command(container_name: str, command: list[str]) -> str:
    # lxc exec <container> -- <command>
    # використовується для systemctl restart minecraft
```

### 2. `api/app/services/git_sync.py` — новий файл

```python
class UpdateManifest:
    version: str
    prev_version: str
    update_type: str          # server_only | both | critical
    requires_restart: bool    # є .jar в changed_paths
    reload_commands: list[str] # ["ftbquests reload", "reload"] або []
    changed_paths: list[str]  # з git diff
    message: str              # з commit message
    repo_local_path: str      # локальний шлях до склонованого репо

async def clone_or_pull(repo_url: str, local_path: str, token: str) -> None:
    # git clone або git pull
    # використовує GITHUB_TOKEN з env для приватного репо

async def checkout_tag(local_path: str, tag: str) -> None:
    # git checkout <tag>

def get_previous_tag(local_path: str, current_tag: str) -> str:
    # git describe --tags --abbrev=0 <tag>^
    # повертає попередній тег для diff

def get_changed_paths(local_path: str, from_tag: str, to_tag: str) -> list[str]:
    # git diff --name-only <from_tag>..<to_tag>

def parse_tag(tag: str) -> tuple[str, str]:
    # "v1.3.1-both" → ("v1.3.1", "both")
    # "v1.3.0"      → ("v1.3.0", "server_only")
    # "v1.3.2-critical" → ("v1.3.2", "critical")

def determine_actions(changed_paths: list[str]) -> tuple[bool, list[str]]:
    # requires_restart: будь-який .jar в mods/
    # reload_commands: quests/ → ftbquests reload, recipes/|config/ → reload

async def build_manifest(repo_url: str, tag: str, commit_message: str) -> UpdateManifest:
    # orchestrates all above
```

### 3. `api/app/island_service.py` — новий метод

```python
async def perform_island_update(
    db: AsyncSession,
    island: Island,
    campaign: UpdateCampaign,
    manifest: UpdateManifest
) -> None:
    container_name = f"island-{island.player_uuid}"
    snapshot_name = f"pre-update-{campaign.version}"

    if manifest.requires_restart:
        # Hard update — потрібен snapshot (для відкату)
        await lxd_service.create_snapshot(container_name, snapshot_name)
        await crud_island_backup.create(db, island_id=island.id,
                                        snapshot_name=snapshot_name,
                                        version=campaign.version)
        await lxd_service.push_directory(
            container_name,
            manifest.repo_local_path,
            "/opt/minecraft/"
        )
        # restart через systemctl або scripts/
        await lxd_service.exec_command(container_name,
                                       ["systemctl", "restart", "minecraft"])
    else:
        # Soft update — backup тільки змінених файлів
        backup_dir = f"/opt/minecraft/backups/{campaign.version}"
        await lxd_service.backup_files(container_name,
                                       manifest.changed_paths, backup_dir)
        await lxd_service.push_directory(
            container_name,
            manifest.repo_local_path,
            "/opt/minecraft/"
        )
        # виконати reload команди через WebSocket
        for cmd in manifest.reload_commands:
            await websocket_manager.send_to_island(island.player_uuid, {
                "event": "EXECUTE_COMMAND",
                "command": cmd
            })

    # оновити версію в БД
    await crud_island.set_version(db, island_id=island.id,
                                  version=campaign.version)
```

### 4. `api/app/services/update_worker.py` — заповнити скелет

Поточний скелет (`process_next_in_queue`) вже існує. Доповнити логікою:

```python
async def process_next_in_queue(db_session):
    next_item = await crud_update_queue.get_next_pending_island(db_session)
    if not next_item:
        return

    island = await crud_island.get(db_session, id=next_item.island_id)
    campaign = await crud_update_campaign.get_active(db_session)
    manifest = await git_sync.build_manifest(...)  # з кешу поточної кампанії

    await crud_update_queue.set_status_processing(db_session, next_item.id)

    # якщо requires_restart — перевіряємо чи гравець офлайн
    if manifest.requires_restart and island.status == "RUNNING":
        if campaign.update_type == "critical":
            # кікаємо гравця
            await websocket_manager.send_to_island(island.player_uuid, {
                "event": "PENDING_UPDATE",
                "message": "Критичне оновлення! Сервер перезапускається.",
                "kick": True
            })
            await asyncio.sleep(5)  # дати час гравцю побачити повідомлення
        elif campaign.update_type == "both":
            # повідомляємо і чекаємо
            await websocket_manager.send_to_island(island.player_uuid, {
                "event": "PENDING_UPDATE",
                "message": f"Оновлення {campaign.version}: після виходу оновіть клієнт.",
            })
            # re-queue — обробимо коли острів зупиниться
            await crud_update_queue.set_status_waiting(db_session, next_item.id)
            return
        else:
            # server_only + restart — теж чекаємо logout
            await crud_update_queue.set_status_waiting(db_session, next_item.id)
            return

    # острів офлайн або soft update — оновлюємо
    try:
        await island_service.perform_island_update(db_session, island,
                                                   campaign, manifest)
        await crud_update_queue.set_status_completed(db_session, next_item.id)
    except Exception as e:
        await crud_update_queue.set_status_failed(db_session, next_item.id,
                                                  str(e), next_item.retry_count + 1)
```

**Тригер "гравець вийшов"** — коли Velocity повідомляє про logout:
```python
# новий endpoint: POST /islands/{uuid}/player_left
# worker перевіряє всі острови зі статусом WAITING в поточній кампанії
# і одразу починає їх обробку
```

**Після всіх островів** — оновлення базового контейнера:
```python
async def update_template_container(manifest: UpdateManifest):
    container_name = settings.TEMPLATE_CONTAINER_NAME  # з .env
    await lxd_service.push_directory(container_name,
                                     manifest.repo_local_path,
                                     "/opt/minecraft/")
    # нові острови одразу створюються з актуальними файлами
    # (образ перестворювати не обов'язково — можна просто тримати
    #  шаблонний контейнер в актуальному стані)
```

### 5. `api/app/api/v1/endpoints/updates.py` — новий файл

```
POST /api/v1/updates/webhook
  - перевірити GitHub webhook secret (HMAC-SHA256)
  - витягнути tag з payload ("refs/tags/v1.3.0")
  - запустити git_sync.build_manifest()
  - створити UpdateCampaign в БД
  - додати всі острови в update_queue
  - повернути 200 одразу (webhook не чекає)

POST /api/v1/updates/campaign
  - ручний тригер (адмін)
  - тіло: {"tag": "v1.3.0", "islands": ["uuid1", "uuid2"] | "all"}

GET /api/v1/updates/campaigns
  - список кампаній з прогресом

GET /api/v1/updates/campaign/{id}
  - детальний статус: скільки островів оновлено, скільки чекає, помилки

POST /api/v1/updates/rollback/island/{uuid}
  - відкат одного острова (hard або soft залежно від backup_type)

POST /api/v1/updates/rollback/campaign/{campaign_id}
  - відкат всіх островів що оновились в цій кампанії
```

### 6. `api/app/api/v1/endpoints/islands.py` — новий endpoint

```
POST /api/v1/islands/{uuid}/player_left
  - викликає Velocity при виході гравця з острова
  - update_worker перевіряє чи є WAITING оновлення для цього острова
```

### 7. Нові таблиці в БД (`api/sql/schema.sql`)

```sql
CREATE TABLE update_campaigns (
    id              INT PRIMARY KEY AUTO_INCREMENT,
    version         VARCHAR(50) NOT NULL,
    git_commit      VARCHAR(40),
    update_type     ENUM('server_only','both','critical') NOT NULL,
    requires_restart BOOLEAN DEFAULT FALSE,
    reload_commands JSON,                    -- ["ftbquests reload", "reload"]
    changed_paths   JSON,
    message         TEXT,
    status          ENUM('pending','in_progress','completed',
                         'failed','rolled_back') DEFAULT 'pending',
    created_at      TIMESTAMP DEFAULT NOW(),
    completed_at    TIMESTAMP NULL
);

-- версія кожного острова
ALTER TABLE islands
    ADD COLUMN current_version  VARCHAR(50) NULL,
    ADD COLUMN skip_auto_updates BOOLEAN DEFAULT FALSE;
    -- skip_auto_updates = TRUE → унікальний сервер, не чіпати

-- повідомлення для модів (якщо WebSocket недоступний — fallback polling)
CREATE TABLE island_pending_messages (
    id          INT PRIMARY KEY AUTO_INCREMENT,
    player_uuid VARCHAR(36) NOT NULL,
    message     TEXT NOT NULL,
    type        VARCHAR(30) DEFAULT 'info',
    created_at  TIMESTAMP DEFAULT NOW(),
    delivered   BOOLEAN DEFAULT FALSE
);
```

Існуюча таблиця `update_queue` залишається, додати статус `WAITING`:
```sql
ALTER TABLE update_queue
    MODIFY COLUMN status ENUM('PENDING','PROCESSING','WAITING',
                              'COMPLETED','FAILED');
-- WAITING = острів онлайн, чекаємо logout
```

### 8. Forge мод — `IslandWebSocketClient.java`

Додати обробку двох нових подій в `handleMessage()`:

```java
case "PENDING_UPDATE": {
    String msg = json.has("message")
        ? json.get("message").getAsString()
        : "Оновлення буде застосовано після виходу.";
    boolean kick = json.has("kick") && json.get("kick").getAsBoolean();
    MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
    srv.execute(() -> {
        srv.getPlayerList().getPlayers().forEach(p ->
            p.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("[Сервер] " + msg)
            )
        );
        if (kick) {
            srv.getPlayerList().getPlayers().forEach(p ->
                p.connection.disconnect(
                    net.minecraft.network.chat.Component.literal(msg)
                )
            );
        }
    });
    break;
}
case "EXECUTE_COMMAND": {
    String cmd = json.get("command").getAsString();
    MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
    srv.execute(() ->
        srv.getCommands().performPrefixedCommand(
            srv.createCommandSourceStack(), cmd
        )
    );
    break;
}
```

### 9. Velocity — `ApiClient.java` + `PlayerConnectionListener.java`

Новий метод в `ApiClient.java`:
```java
public CompletableFuture<ApiResponse> notifyPlayerLeft(UUID ownerUuid) {
    return sendPost(baseUrl + "islands/" + ownerUuid + "/player_left", "");
}
```

В `PlayerConnectionListener.java` — після рядка ~302 (де логується "Last team member disconnected"):
```java
// Повідомити API що гравець покинув острів (тригер для update worker)
apiClient.notifyPlayerLeft(ownerUuid);
// далі існуючий код зі scheduleStop...
```

### 10. Нові змінні в `.env`

```env
# Репо з оновленнями
UPDATES_REPO_URL=https://github.com/YourOrg/skyblock-updates
UPDATES_REPO_LOCAL_PATH=/opt/skyblock/updates-repo
GITHUB_TOKEN=ghp_xxxx                    # для приватного репо
GITHUB_WEBHOOK_SECRET=your-secret-here  # для верифікації webhook

# Базовий контейнер (шаблон для нових островів)
TEMPLATE_CONTAINER_NAME=skyblock-base

# Snapshot retention
SNAPSHOT_RETENTION_DAYS=7   # через скільки днів автоматично видаляти snapshots
```

---

## Логіка відкату (детально)

### Snapshot стратегія

Перед кожним оновленням автоматично робиться LXD snapshot як страховка.
Автоматичний відкат завжди використовує тільки файли — world не чіпається.
Snapshot існує тільки для ручного аварійного відновлення адміном.

```
Перед оновленням (автоматично):
  lxc snapshot island-{uuid} pre-update-v1.3.0
  → запис в island_backups: snapshot_name, created_at

Через SNAPSHOT_RETENTION_DAYS днів (LXD видаляє сам):
  snapshot створюється з --expiry = now + SNAPSHOT_RETENTION_DAYS
  LXD автоматично видаляє після закінчення терміну
  Ніякого фонового worker не потрібно

Адмін може в будь-який момент:
  GET /updates/snapshots/{uuid}     ← список доступних snapshots
  (ручний lxc restore через консоль сервера)
```

⚠️ Автоматичний відкат через snapshot НЕ підтримується — він відкочує весь
контейнер включно зі світом і гравець втратить прогрес.
Замість цього зберігаємо тільки змінені файли.

### Rollback квестів / конфігів / рецептів (без restart)

```
Перед оновленням зберігаємо:
  /opt/minecraft/backups/v1.2.0/
    config/ftbquests/quests/chapter2.snbt
    config/economy.toml

island_backups записує:
  backup_type = "files"
  backup_path = "/opt/minecraft/backups/v1.2.0/"
  changed_paths = ["config/ftbquests/quests/chapter2.snbt", "config/economy.toml"]

POST /updates/rollback/island/{uuid}:
  1. copy backup_path → /opt/minecraft/ (тільки changed_paths)
  2. WebSocket: EXECUTE_COMMAND "ftbquests reload"
  3. WebSocket: EXECUTE_COMMAND "reload"
  4. island.current_version = попередня версія

world/ не чіпається. Гравець не виходить, відкат миттєвий.
```

### Rollback модів (з restart)

```
Перед оновленням зберігаємо:
  /opt/minecraft/backups/v1.2.0/
    mods/economy-1.9.jar       ← старі .jar
    mods/somemod-1.0.jar
    config/economy.toml        ← конфіги що змінились разом з модом

island_backups записує:
  backup_type = "files"
  backup_path = "/opt/minecraft/backups/v1.2.0/"
  changed_paths = ["mods/economy-1.9.jar", "config/economy.toml"]

POST /updates/rollback/island/{uuid}:
  1. copy backup_path → /opt/minecraft/ (тільки changed_paths)
  2. видалити нові .jar яких не було в попередній версії
  3. systemctl restart minecraft
  4. island.current_version = попередня версія

world/ не чіпається. Гравець переконнектиться — прогрес збережено ✓
```

### Emergency rollback (пошкоджений світ — тільки вручну)

```
Якщо мод пошкодив сам світ (corrupted chunks, зламані NBT):
  1. GET /updates/snapshots/{uuid}  ← перевірити чи є snapshot
  2. Адмін вручну: lxc restore island-{uuid} pre-update-v1.3.0
  3. Прогрес після дати snapshot втрачається, але світ був corrupted так і так

Це НЕ автоматична операція — тільки свідоме рішення адміна.
```

### Rollback всієї кампанії

```
POST /updates/rollback/campaign/{id}:
  - для кожного острова що має backup з цією campaign_id
  - copy старих файлів → restart або reload залежно від типу
  - world/ жодного острова не чіпається
  - шаблонний контейнер: git checkout prev_tag → copy files
```

---

## Порядок реалізації

```
Крок 1 — DB міграція
  + update_campaigns таблиця
  + island_pending_messages таблиця
  + ALTER islands: current_version, skip_auto_updates
  + ALTER update_queue: додати статус WAITING

Крок 2 — lxd_service.py
  + create_snapshot()     ← страховка перед оновленням, expiry = now + SNAPSHOT_RETENTION_DAYS
  + list_snapshots()      ← для GET /updates/snapshots/{uuid}
  # delete_snapshot() не потрібен — LXD видаляє сам по expiry
  + push_directory()      ← копіює нові файли в контейнер
  + backup_files()        ← зберігає старі файли перед оновленням
  + restore_files()       ← повертає старі файли при відкаті
  + delete_new_files()    ← видаляє .jar яких не було в попередній версії
  + exec_command()        ← systemctl restart minecraft

Крок 3 — services/git_sync.py (новий)
  + clone_or_pull()
  + parse_tag()
  + get_changed_paths()
  + determine_actions()
  + build_manifest()

Крок 4 — island_service.py
  + perform_island_update()

Крок 5 — update_worker.py
  + заповнити perform_next_in_queue()
  + логіка WAITING (чекати logout)
  + update_template_container() після всіх островів

Крок 6 — endpoints/updates.py (новий)
  + POST /updates/webhook
  + POST /updates/campaign
  + GET /updates/campaigns
  + GET /updates/campaign/{id}
  + POST /updates/rollback/island/{uuid}
  + POST /updates/rollback/campaign/{id}

Крок 7 — endpoints/islands.py
  + POST /islands/{uuid}/player_left

Крок 8 — Forge мод
  + IslandWebSocketClient.java: PENDING_UPDATE + EXECUTE_COMMAND
  + PlayerEventHandler.java: ServerStoppingEvent + ServerStoppedEvent
  + Heartbeat таймер кожні 30с → POST /islands/{uuid}/heartbeat
    якщо Spark доступний (soft dependency) → додати tps, mspt, cpu в тіло
  + build.gradle: spark-api як soft dependency
  + Всі нові HTTP виклики з .orTimeout(10, TimeUnit.SECONDS)   ← аудит #5
  + Фікс reflection кешування в PlayerEventHandler             ← аудит #18

Крок 9 — Velocity плагін
  + ApiClient.java: notifyPlayerLeft()
  + PlayerConnectionListener.java: виклик при logout
  + Exponential backoff в pollForRunningAndConnect              ← аудит #3
  + Один прохід замість двох stream() при disconnect            ← аудит #12

Крок 10 — GitHub webhook
  + налаштувати в settings репо skyblock-updates
  + URL: https://your-api/api/v1/updates/webhook
  + secret: GITHUB_WEBHOOK_SECRET
  + тільки tag push події
```

---

## Схема потоку даних

```
git push --tags (v1.3.0)
        │
        ▼
GitHub webhook → POST /api/v1/updates/webhook
        │
        ├─ verify HMAC-SHA256 secret
        ├─ git pull repo
        ├─ parse tag → update_type = server_only
        ├─ git diff v1.2.0..v1.3.0 → changed_paths
        ├─ determine_actions → requires_restart=False, reload=["ftbquests reload"]
        ├─ INSERT update_campaigns
        └─ INSERT update_queue для кожного острова (крім skip_auto_updates=True)
                │
                ▼
        update_worker (кожні 10с)
                │
        ┌───────┴────────────────────────────┐
        │                                    │
   острів OFFLINE                      острів ONLINE
        │                                    │
        ▼                              server_only + no restart
   backup files                              │
   push files                          backup files
   reload via WS                       push files
   set current_version                 reload via WS → done
   COMPLETED                           set current_version
                                       COMPLETED
                                  (гравець нічого не помічає)

        обидва: після всіх островів
                │
                ▼
        update_template_container()
        (базовий контейнер теж оновлюється)
        нові острови одразу з новими файлами
```

---

---

## Система моніторингу стану острова

### Проблема

API зараз не знає про:
- Minecraft крашнувся і перезапускається всередині контейнера
- Хтось вручну зупинив або запустив контейнер поза API
- Контейнер завис і не відповідає
- Minecraft починає планову зупинку

### Три компоненти

#### 1. Forge мод — події Minecraft (основний джерело)

Додати в мод три нові HTTP виклики до API:

| Forge event | Коли | POST endpoint |
|---|---|---|
| `ServerStoppingEvent` | Minecraft починає зупинку (і краш, і планова) | `/islands/{uuid}/service_event` → `stopping` |
| `ServerStoppedEvent` | Minecraft повністю зупинився | `/islands/{uuid}/service_event` → `stopped` |
| Таймер кожні 30с | Minecraft живий | `/islands/{uuid}/heartbeat` з метриками |

`ServerStoppingEvent` спрацьовує і при краші (JVM shutdown hook) і при плановій зупинці.
Мод вже надсилає `/ready` при старті — цей сигнал покриває сценарій краш→рестарт.

Переваги перед systemd хуками: не треба модифікувати базовий контейнер,
мод вже має HTTP клієнт, більше контексту про стан.

**Spark інтеграція в heartbeat:**
Spark має публічний Java API для інших модів. Якщо Spark встановлений (soft dependency),
мод читає метрики напряму і додає в heartbeat:
```
SparkProvider.get().tickStatistics().tps1Min()   → TPS
SparkProvider.get().tickStatistics().duration1Min() → MSPT
SparkProvider.get().cpuProcess().last1Minute()   → CPU
```
Якщо Spark не встановлений — heartbeat надсилається без метрик (soft dependency).
Дані зберігаються в існуючу таблицю analytics.

#### 2. LXD Event Listener — події контейнера

Новий фоновий сервіс `services/lxd_event_listener.py`.
Підписується на LXD WebSocket events — покриває операції поза API
(ручна зупинка/запуск контейнера, збій хоста).

| LXD подія | Умова | Дія API |
|---|---|---|
| контейнер зупинився | статус був `RUNNING/FROZEN` | → `STOPPED`, `minecraft_ready=False` |
| контейнер запустився | статус був `STOPPED` | → `STARTING` (чекаємо `/ready` від мода) |
| контейнер заморожений | будь-який | → `FROZEN` |

Запускається в `main.py` поряд з іншими workers.

#### 3. Health Check Worker — fallback для зависань

Новий фоновий worker `services/health_check_worker.py`.
Запускається кожні `HEALTH_CHECK_INTERVAL_SECONDS` секунд.
Потрібен коли мод не може надіслати сигнал (контейнер вбито зовні, мережева помилка).

| Виявлена ситуація | Дія |
|---|---|
| Острів `RUNNING` але heartbeat не приходив > N хв | `minecraft_ready=False` → сповіщення |
| Острів `RUNNING` але контейнер `STOPPED` в LXD | статус → `STOPPED` |
| Острів в `STARTING` довше `ISLAND_STARTING_TIMEOUT_MINUTES` | статус → `ERROR` |

### Нові endpoints

```
POST /api/v1/islands/{uuid}/service_event   ← мод надсилає stopping/stopped
POST /api/v1/islands/{uuid}/heartbeat       ← мод надсилає кожні 30с
```

### Нові змінні в `.env`

```
HEALTH_CHECK_INTERVAL_SECONDS=30
ISLAND_STARTING_TIMEOUT_MINUTES=10   ← /ready не прийшов → ERROR
HEARTBEAT_TIMEOUT_MINUTES=3          ← heartbeat не приходив → minecraft_ready=False
```

### Порядок реалізації

```
1. Forge мод: ServerStoppingEvent + ServerStoppedEvent + heartbeat таймер
2. Новий endpoint: POST /islands/{uuid}/service_event
3. Новий endpoint: POST /islands/{uuid}/heartbeat
4. LXD Event Listener (services/lxd_event_listener.py)
5. Health Check Worker (services/health_check_worker.py)
```

### Покриття сценаріїв після реалізації

| Подія | Хто виявляє |
|---|---|
| Minecraft крашнувся → перезапускається | Мод: `stopping` → потім `/ready` |
| Minecraft зупинився планово | Мод: `stopping` → `stopped` |
| Хтось вручну `lxc stop` | LXD Event Listener |
| Хтось вручну `lxc start` | LXD Event Listener |
| Контейнер завис, мод не відповідає | Health Check (heartbeat timeout) |
| Острів застряг в `STARTING` | Health Check (starting timeout) |

---

## Що НЕ робить ця система

- Не чіпає `world/` — ніколи (ні при оновленні, ні при відкаті)
- Не оновлює острови з `skip_auto_updates = True` (унікальні сервери)
- Не розповідає про чисто клієнтські моди (шейдери, OptiFine) — це лаучер
- Не робить автоматичний rollback при помилці — тільки позначає FAILED,
  адмін вирішує чи робити відкат
