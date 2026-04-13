# Developer Guide — NestWorld SkyBlock System

Цей гайд пояснює як влаштована система, як її запускати локально на Windows і як працює кожна частина.

---

## Що це за система?

NestWorld — це SkyBlock-сервер де **кожен гравець отримує окремий Minecraft-сервер** у LXD-контейнері. Коли гравець заходить — його сервер автоматично запускається, коли виходить — заморожується (зберігається в RAM). Це дозволяє тримати сотні островів, витрачаючи ресурси тільки на активних гравців.

---

## Компоненти системи

```
Гравець
   │
   ▼
[Velocity Proxy]  ←→  [FastAPI Backend]  ←→  [MySQL БД]
   │  (Nestworldvelocity plugin)               │
   │                    │                      ▼
   │                    ├──── LXD контейнери (острови гравців)
   │                    │         └── [Forge Mod: mods-server]
   │                    │
   │                    └──── WebSocket
   │                              │
   │                         [spawn_hub сервер]
   │                              └── [Forge Mod: RealMarket]
   │
   ▼
[Island сервер гравця]
```

### 1. FastAPI Backend (`/api/`)
Мозок системи. Керує LXD-контейнерами, зберігає стан в БД, відповідає на запити від Velocity та модів.

### 2. Velocity Plugin (`/Nestworldvelocity/`)
Запущений на проксі. Перехоплює вхід гравця, через API запускає його острів, чекає готовності і перенаправляє гравця туди.

### 3. Forge Mod — Island (`/mods-server/`)
Запущений **на кожному острові** гравця (всередині LXD). Повідомляє API коли сервер готовий (`/ready`). Стежить за неактивністю і викликає заморозку.

### 4. Forge Mod — RealMarket + Hub (`/RealMarket/`)
Запущений на **spawn_hub сервері**. Відповідає за:
- **Ринок**: синхронізація товарів з AE2, обробка покупок, видача предметів через AE2
- **Warp платформи**: отримує команди від API через WebSocket і створює/видаляє платформи в світі

---

## Як гравець потрапляє на острів

Є два сценарії залежно від того як гравець запускає острів:

### Сценарій 1 — Автоматично при вході (`PlayerConnectionListener`)
Використовує **HTTP polling** (опитування кожні N секунд):

1. Гравець підключається до Velocity → потрапляє на hub
2. `PlayerConnectionListener` відправляє `POST /islands/start/{uuid}` до API
3. API запускає або створює LXD контейнер
4. Plugin кожні N секунд (налаштовується в конфігу) робить `GET /islands/{uuid}` — чекає `status=RUNNING` + `minecraft_ready=true`
5. Forge Mod на острові після старту відправляє `POST /islands/{uuid}/ready`
6. API встановлює `minecraft_ready=true`
7. На наступному poll plugin отримує готовий статус і перенаправляє гравця

### Сценарій 2 — Команда `/myisland` (`MyIslandCommand`)
Використовує **WebSocket** (чекає push-сповіщення від API):

1. Гравець вводить `/myisland` → plugin відправляє `POST /islands/start/{uuid}`
2. Plugin одразу підключається до WebSocket `ws://api/ws/{uuid}`
3. Forge Mod після старту викликає `/ready` → API надсилає WS-повідомлення зі статусом
4. Plugin отримує `{status: "RUNNING", minecraft_ready: true}` і перенаправляє гравця — **без затримки на polling**

**Чому два підходи?**
- При вході WebSocket ще не підключений, тому використовується polling як надійний fallback
- `/myisland` підключає WS одразу і чекає push — це швидше і не навантажує API запитами

---

## Статуси острова

| Статус | Значення |
|--------|----------|
| `PENDING_CREATION` | Щойно запрошено створення, в черзі |
| `CREATING` | Контейнер клонується з шаблону |
| `STOPPED` | Контейнер є, але вимкнений |
| `RUNNING` | Контейнер запущений, Minecraft працює |
| `FROZEN` | Контейнер заморожений (RAM збережено на диск) |
| `DELETING` | Помічено для видалення |
| `ERROR` | Щось пішло не так |

---

## Система команд (Teams)

Кожен острів прив'язаний до **команди** (team). Навіть соло-гравець має команду з одного члена.

- `POST /teams/create_solo` — створює острів + команду (викликається автоматично при `/island start`)
- `GET /teams/my_team/{uuid}` — отримати інфо про свою команду
- `POST /teams/accept_invite` — прийняти запрошення в команду
- `POST /teams/{id}/leave` — покинути команду
- `PATCH /teams/{id}/rename` — перейменувати команду

**Важливо:** таблиця `team_members` зберігає `player_uuid` і `player_name`. `player_name` потрібен для пошуку UUID за ніком (використовується в системі варпів).

---

## Система варп-платформ (Warp Admin)

Гравці можуть купити підписку на **варп-платформу** через Azuriom (сайт). Після покупки Azuriom автоматично запускає команду на Velocity.

### Повний ланцюжок:
```
Azuriom (сайт)
   → AzLink (плагін на Velocity)
   → "warp-admin create {player}" (команда на Velocity)
   → Velocity знаходить UUID гравця (онлайн або через /warps/player-uuid/{name})
   → POST /api/v1/warps/{uuid}/create
   → API зберігає в чергу (warp_pending_commands) + надсилає через WebSocket
   → RealMarket на spawn_hub отримує WS-повідомлення
   → IslandManager.createIsland() створює платформу в світі
   → POST /warps/confirm/{id} — підтвердження виконання
```

**Чому черга?** Якщо spawn_hub перезапускається — команди не губляться. При повторному підключенні hub отримує всі невиконані команди.

### Команди:
```
warp-admin create <ім'я_гравця>   — створити платформу (підписка активована)
warp-admin suspend <ім'я_гравця>  — приховати платформу (підписка призупинена)
warp-admin restore <ім'я_гравця>  — відновити платформу (підписка поновлена)
warp-admin delete <ім'я_гравця>   — видалити платформу назавжди
```

**Права:** команда виконується тільки з консолі або з правом `warp.admin`.

---

## Ринок (RealMarket)

Гравці продають предмети через AE2 (Applied Energistics 2). Система:

1. **Синхронізація** (`MarketSyncManager`): кожні N секунд сканує `SOURCE`-блоки на островах і надсилає список предметів до API
2. **Перегляд**: покупець відкриває GUI (`/market`) і бачить всі доступні предмети
3. **Купівля**: `POST /market/islands/{uuid}/purchase` → API записує в `market_pending_extractions` → WebSocket повідомляє острів продавця → AE2 видає предмети покупцю
4. **Підтвердження**: мод відправляє `POST /market/islands/{uuid}/extraction/{id}/confirm`

---

## Налаштування на Windows (для розробки)

### Потрібно встановити:
1. **Git** — [git-scm.com](https://git-scm.com/download/win)
2. **IntelliJ IDEA** (Community edition) — для Java/Gradle проектів
3. **Python 3.11+** — [python.org](https://www.python.org/downloads/)
4. **WSL2** (Windows Subsystem for Linux) — для запуску API локально

   Відкрий PowerShell від адміна і виконай:
   ```powershell
   wsl --install
   ```
   Після перезавантаження встанови Ubuntu.

### Клонування репозиторію:
```bash
git clone https://github.com/<your-org>/api-world.git
cd api-world
git checkout RealMarket
```

### Запуск Velocity плагіна (тільки для тестування):
```bash
cd Nestworldvelocity
./gradlew runVelocity   # Windows: gradlew.bat runVelocity
```
Velocity запуститься на порту `25565`. Конфіг плагіна: `run/plugins/nestworldvelocity/nestworldvelocity.toml`

### Запуск RealMarket моду (тільки для тестування):
```bash
cd RealMarket
./gradlew runClient     # Windows: gradlew.bat runClient
```

### Запуск API (через WSL):
```bash
# У WSL терміналі:
cd /mnt/c/Users/<ім'я>/api-world/api
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
# Створити .env файл з DATABASE_URL та іншими налаштуваннями
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

---

## Структура БД (основні таблиці)

| Таблиця | Призначення |
|---------|-------------|
| `islands` | Острови гравців (uuid, статус, IP контейнера) |
| `teams` | Команди (назва, власник) |
| `team_members` | Члени команд (uuid, **player_name**, роль) |
| `market` | Товари виставлені на продаж |
| `market_pending_extractions` | Черга видачі предметів після купівлі |
| `warp_pending_commands` | Черга команд для spawn_hub (create/suspend/restore/delete) |

---

## API — швидка довідка

Документація: `http://api-dev.nestworld.site/docs`

| Метод | Endpoint | Що робить |
|-------|----------|-----------|
| `GET` | `/islands/{uuid}` | Статус острова |
| `POST` | `/islands/start/{uuid}?player_name=X` | Запустити/створити острів |
| `POST` | `/islands/stop/{uuid}` | Зупинити острів |
| `POST` | `/islands/{uuid}/freeze` | Заморозити острів |
| `POST` | `/islands/{uuid}/ready` | Мод сигналізує що Minecraft готовий |
| `GET` | `/teams/my_team/{uuid}` | Інфо про команду гравця |
| `POST` | `/teams/create_solo` | Створити соло острів+команду |
| `GET` | `/warps/status` | Чи онлайн spawn_hub, кількість в черзі |
| `GET` | `/warps/player-uuid/{name}` | Знайти UUID гравця за ніком |
| `POST` | `/warps/{uuid}/create` | Поставити в чергу створення варпу |
| `POST` | `/warps/confirm/{id}` | Мод підтверджує виконання команди |

---

## WebSocket

API підтримує WebSocket на `/ws/{client_id}`. Клієнти:
- Velocity плагін підключається як `velocity_{uuid}` і отримує оновлення статусу острова
- spawn_hub підключається як `spawn_hub` і отримує команди варпів та ринку
- Острови підключаються як `island_{uuid}` і отримують команди видачі предметів

---

## Часті питання

**Чому UUID в team_members?**
Minecraft використовує UUID для ідентифікації гравців (навіть якщо ник змінився). Але Azuriom передає тільки `{player}` (нік). Тому зберігаємо `player_name` поруч, щоб можна було знайти UUID за ніком.

**Чому не Mojang API для UUID?**
Сервер працює в offline-mode (пірат). UUID генерується локально, а не Mojang-ом. Mojang API повернув би інший UUID.

**Чому LXD, а не Docker?**
LXD дає повноцінну ОС з systemd, що потрібно для Minecraft Forge. Docker краще для stateless сервісів.

**Що таке freeze?**
LXD freeze = `SIGSTOP` для всіх процесів контейнера + збереження RAM на диск. Сервер "заморожується" за ~1 секунду і не споживає CPU. При наступному вході — розморожується за ~2 секунди.
