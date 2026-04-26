# CLAUDE.md

Цей файл надає настанови Claude Code (claude.ai/code) для роботи з кодом у цьому репозиторії.

## Що це за проєкт

Трикомпонентна система для запуску динамічних персональних SkyBlock островів у Minecraft через LXD-контейнери:

1. **`api/`** — FastAPI бекенд (Python): центральний мозок системи. Керує lifecycle контейнерів, зберігає метадані в MySQL, координує воркери через Redis.
2. **`Nestworldvelocity/`** — плагін для Velocity проксі (Java/Gradle): перенаправляє гравців на їхній острів після входу, опитує API поки острів не готовий.
3. **`mods-server/`** — Forge мод (Java/Gradle): запускається всередині кожного острів-контейнера, сигналізує API коли Minecraft сервер завантажився, та запускає заморозку після виходу останнього гравця.

## Команди

### API (Python)

```bash
# Налаштування (з директорії api/)
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# Запуск dev-сервера (з кореня репозиторію)
cd api && uvicorn app.main:app --reload

# Запуск усіх тестів
PYTHONPATH=$(pwd)/api python -m pytest api/app/tests

# Запуск одного файлу тестів
PYTHONPATH=$(pwd)/api python -m pytest api/app/tests/test_crud.py

# Запуск конкретного тесту за назвою
PYTHONPATH=$(pwd)/api python -m pytest api/app/tests/test_crud.py::test_create_island -v

# Встановлення залежностей лише для тестів (відсутні в requirements.txt)
pip install pytest pytest-asyncio httpx aiosqlite fakeredis
```

### Forge Мод

```bash
cd mods-server/
./gradlew build        # результат: build/libs/
./gradlew test
```

### Velocity Плагін

```bash
cd Nestworldvelocity/
./gradlew build        # результат: build/libs/
./gradlew test
```

## Архітектура

### Потік запитів

```
Гравець входить
  → Velocity плагін (Nestworldvelocity)
      → GET /api/v1/islands/{player_uuid}           # перевірка статусу
      → POST /api/v1/islands/start/{player_uuid}    # створення/запуск за потреби
      → опитує API доки status=RUNNING і minecraft_ready=true
          → Forge мод сигналізує: POST /api/v1/islands/{owner_uuid}/ready
      → Velocity динамічно реєструє IP:25565 контейнера, перенаправляє гравця

Гравець виходить (останній на острові)
  → Forge мод чекає FREEZE_TIMER_SECONDS
  → POST /api/v1/islands/{player_uuid}/freeze
```

### Модель власності островів

Острови належать **командам**, а не окремим гравцям. UUID гравця розв'язується через `TeamMember` → `Team` → `Island`. Метод `create_new_solo_island()` в `island_service.py` завжди створює пару команда+острів. `player_uuid` у легасі-ендпоінтах — це UUID власника команди.

### Стан-машина острова

Острови переходять між статусами, визначеними в `IslandStatusEnum` (`api/app/models/island.py`):

```
CREATING → STOPPED → PENDING_START → RUNNING → PENDING_FREEZE → FROZEN
                                             ↘ PENDING_STOP → STOPPED
```

Стани помилок: `ERROR`, `ERROR_CREATE`, `ERROR_START`. При запуску API, функція `reconcile_island_states()` в `main.py` порівнює кожен запис `RUNNING/FROZEN/PENDING_*` в БД з реальним станом LXD та виправляє невідповідності.

### Фонові воркери

Три asyncio-задачі запускаються через lifespan-менеджер при старті (обираються лідером через Redis-lock, щоб лише один Gunicorn-воркер їх виконував):

- **`creation_worker`** (`api/app/services/creation_worker.py`): спустошує чергу створення островів з урахуванням `MAX_RUNNING_SERVERS`.
- **`start_worker`** (`api/app/services/start_worker.py`): обробляє острови в черзі на запуск.
- **`update_worker`** (`api/app/services/update_worker.py`): надсилає оновлення образів на острови.

Redis pub/sub (`REDIS_CHANNEL`) використовується для відправки подій у реальному часі підключеним WebSocket-клієнтам (`/ws/{client_id}`).

### База даних

MySQL з асинхронним SQLAlchemy (драйвер `aiomysql`). Схема в `api/sql/schema.sql`. Ключові таблиці: `islands`, `teams`, `team_members`, `island_queue`, `island_settings`, `island_backups`.

**Патерн сесії**: всі CRUD-функції приймають `AsyncSession`, що передається з `AsyncSessionLocal`. Кожен CRUD-метод самостійно комітить свою транзакцію — ті, хто їх викликає, не повинні комітити вручну.

### Інтеграція з LXD

`api/app/services/lxd_service.py` обгортає `pylxd` асинхронними хелперами (синхронні виклики pylxd виконуються через `asyncio.to_thread`). Ледаче ініціалізує єдиний клієнт при першому використанні з thread-safe lock. Острови — це LXD-контейнери, клоновані з `LXD_BASE_IMAGE` та запущені з профілями з `LXD_DEFAULT_PROFILES`.

## Конфігурація середовища

API зчитує з `api/.env` (скопіюйте `api/env_example`). Ключові змінні:

| Змінна | Призначення |
|---|---|
| `DATABASE_URL` | `mysql+aiomysql://user:pass@host:port/db` |
| `LXD_SOCKET_PATH` | Шлях до unix-сокета LXD |
| `LXD_BASE_IMAGE` | Аліас шаблонного LXD-образу |
| `LXD_DEFAULT_PROFILES` | Профілі LXD через кому (за замовчуванням: `default,skyblock`) |
| `MAX_RUNNING_SERVERS` | Ліміт одночасно запущених контейнерів (за замовчуванням: 10) |
| `FREEZE_TIMER_SECONDS` | Час бездіяльності до заморозки (за замовчуванням: 300) |
| `REDIS_URL` | Підключення до Redis (за замовчуванням: `redis://localhost:6379/0`) |

## Нотатки щодо тестування

- Тести використовують **SQLite в памʼяті** (`aiosqlite`) через `conftest.py` — реальна база даних та LXD не потрібні.
- Виклики LXD-сервісу необхідно мокати в тестах, що зачіпають `island_service.py`.
- Потрібен `pytest-asyncio`; async тест-функції потребують `@pytest.mark.asyncio`.
- CI (`pr-checks.yml`) запускає `python -m pytest api/app/tests` з `PYTHONPATH`, встановленим у корінь репозиторію, щоб `app.*` імпорти резолвились.

## CI / Деплой

- PR до `main`: запускає тести API + Gradle збірки обох Java-компонентів.
- Теги, що відповідають `api-v*`: SSH-деплой API, перезапуск systemd-сервісу, health-check `http://localhost:8000/`.
- Теги `mod-v*` / `plugin-v*`: деплой відповідного JAR-артефакту.
