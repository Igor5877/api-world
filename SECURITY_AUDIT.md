# Аудит безпеки — SkyBlock Dynamic

Проаналізовано: FastAPI backend, Velocity plugin, RealMarket mod, mods-server.

---

## КРИТИЧНІ (негайне виправлення)

### [C-1] Весь API без автентифікації
**Файли:** усі файли в `api/app/api/v1/endpoints/`

Кожен endpoint відкритий без перевірки токену:
- Зупинити/заморозити/видалити чужий острів (`islands.py`)
- Виконати фінансову операцію (`market.py` — buy/sell/credit/refund)
- Розпустити команду (`teams.py` — leave/rename/accept_invite)
- Видалити варп-дані гравця (`warps.py` — `/warps/{uuid}/delete`)
- Позначити аномалію вирішеною (`analytics.py` — admin дія!)
- Змінити alias LXD-образу (`images.py` — admin дія!)

Коментар `# This should come from an auth token` в `teams.py:107,155` — це відомо, але не зроблено.

**Рішення:** Додати `API_KEY` заголовок для внутрішніх клієнтів (Velocity, Forge mod) + JWT для user-facing ендпоінтів.

---

### [C-2] WebSocket — підміна client_id
**Файл:** `api/app/main.py:267-327`

```python
@app.websocket("/ws/{client_id}")
async def websocket_endpoint(websocket: WebSocket, client_id: str):
    await websocket_manager.connect(websocket, client_id)
```

`client_id` — рядок з URL без жодної перевірки. Наслідки:
- Підключитись як `spawn_hub` → отримати всі pending warp-команди
- Підключитись як `island_<uuid>` → перехопити `market_purchase` повідомлення чужого острова

**Рішення:** Перевіряти `client_id` через API-ключ або підписаний токен при WS-підключенні.

---

### [C-3] CORS wildcard + credentials
**Файл:** `api/app/main.py:259-264`

```python
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],      # <-- проблема
    allow_credentials=True,   # <-- + це = CSRF вразливість
    ...
)
```

**Рішення:**
```python
allow_origins=settings.CORS_ORIGINS,  # конкретні домени
```

---

### [C-4] URL-ін'єкція через ім'я гравця
**Файл:** `Nestworldvelocity/src/main/java/.../network/ApiClient.java:87`

```java
String path = "/islands/start/" + playerUuid + "?player_name=" + playerName;
URI.create(apiUrlBase + path);
```

Ім'я гравця без URL-кодування. Гравець з ніком `alex&admin=1` ін'єктує `?player_name=alex&admin=1`.

**Рішення:**
```java
URI.create(apiUrlBase + "/islands/start/" + playerUuid + "?player_name=" +
    URLEncoder.encode(playerName, StandardCharsets.UTF_8));
```

---

### [C-5] TOCTOU у фінансових операціях
**Файл:** `api/app/api/v1/endpoints/market.py:141-197`

В `execute_purchase` послідовність:
1. `get_balance(...)` — читання з Azuriom
2. `purchase_item(...)` — row-lock в БД
3. `deduct_buyer(...)` — зняття грошей

Між 1 і 3 — гонка: 10 паралельних запитів проходять перевірку балансу одночасно, всі 10 знімають гроші.

**Рішення:** Використовувати idempotency key або атомарний lock на рівні Azuriom/Redis перед перевіркою балансу.

---

### [C-6] PacketShopAction — клієнт контролює суму і UUID острова
**Файл:** `RealMarket/src/main/java/.../network/PacketShopAction.java`

Клієнт надсилає `itemId`, `amount`, `islandUuid` — жодних серверних перевірок:
- `amount` не перевірений на позитивність → `amount = -100` = отримання грошей без товару
- Немає rate limit на пакети → DDoS API через Minecraft
- `islandUuid` довільний → покупка від будь-якого острова

**Рішення:** Валідувати `amount > 0`, додати cooldown на пакети, перевіряти що `islandUuid` відповідає тому блоку, з яким взаємодіє гравець.

---

## ВИСОКІ

### [H-1] confirm_extraction без FOR UPDATE — подвійне нарахування
**Файл:** `api/app/crud/crud_market.py:229-268`

```python
result = await db_session.execute(
    select(MarketPendingExtraction).where(...)
    # Немає with_for_update()!
)
```

Два паралельні виклики → обидва знаходять pending → обидва викликають `credit_seller` → продавець отримує гроші двічі.

**Рішення:** Додати `.with_for_update()` до всіх `select` в `confirm_extraction` і `cancel_purchase`.

---

### [H-2] Відкрита база UUID гравців
**Файл:** `api/app/api/v1/endpoints/warps.py:75-87`

```python
@router.get("/warps/player-uuid/{player_name}", ...)
async def get_uuid_by_name(player_name: str, ...):
```

Без auth — будь-хто перебирає нікнейми і отримує UUID для атак на острови/команди/маркет.

**Рішення:** Закрити endpoint за API-ключем або видалити взагалі якщо не потрібен зовні.

---

### [H-3] HTTP без загального timeout → заморозка сервера
**Файл:** `RealMarket/src/main/java/.../api/MarketSyncManager.java:57-58`

```java
private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(); // requestTimeout відсутній
```

При "зависанні" API — `sendAsync` чекає нескінченно, виснажує JVM thread pool.

**Рішення:**
```java
HttpRequest req = HttpRequest.newBuilder()
        .timeout(Duration.ofSeconds(10)) // додати
        ...
```

---

### [H-4] Integer overflow → безкоштовна покупка
**Файл:** `RealMarket/src/main/java/.../network/PacketTrade.java:42`

```java
double price = IslandManager.PRICES.getOrDefault(player.getUUID(), 10.0) * msg.amount;
```

`msg.amount` = `Integer.MAX_VALUE` (2 147 483 647) → overflow до від'ємного `double` → `updateAsync(id, -price)` зараховує гроші замість знімання.

**Рішення:**
```java
if (msg.amount <= 0 || msg.amount > 9999) return; // bounds check
```

---

### [H-5] Сервер не перевіряє наявність товару при продажу
**Файли:** `market.py:200-222`, `PacketShopAction.java:105-135`

Перевірка `handStack.getCount() < amt` — клієнтський код. Модифікований клієнт надсилає пакет на продаж 1000 предметів маючи 1 → сервер кредитує продавця.

**Рішення:** Сервер повинен знімати предмети з інвентаря і лише тоді кредитувати. Використовувати ServerPlayer inventory API на Server Thread перед API-викликом.

---

### [H-6] static UUID в MarketSyncManager — некоректний при кількох командах
**Файл:** `MarketSyncManager.java:49`

```java
private static UUID currentIslandUuid;
private static volatile int sellerAzuriomId = -1;
```

`static` поля — спільні для всього класу. При кількох командах на одному сервері `currentIslandUuid` перезаписується → extraction-підтвердження відправляються на неправильний острів.

**Рішення:** Зробити поля instance-level або перейти на Map<UUID, ...>.

---

## СЕРЕДНІ

### [M-1] Rate limit відключається при збої Redis
**Файл:** `api/app/api/v1/endpoints/market.py:22`

```python
except Exception:
    return True  # якщо Redis недоступний — пропускаємо
```

При Redis-збої → нескінченний sync з тисячами предметів → навантаження на БД.

**Рішення:** При збої Redis — повернути `False` (відхилити) або використати локальний in-memory fallback з коротким TTL.

---

### [M-2] NBT-десеріалізація без валідації
**Файл:** `IslandManager.java:253`

```java
CompoundTag nbt = net.minecraft.nbt.TagParser.parseTag(entry.get("be_nbt").getAsString());
be.load(nbt);
```

Якщо хтось має доступ до файлової системи сервера і підмінить `.json` файл варп-платформи — довільний NBT потрапить у block entity (потенційний краш або дюп).

**Рішення:** Валідувати ключі NBT перед `be.load()`, не довіряти файлу повністю.

---

### [M-3] Синхронний файловий I/O на Server Tick Thread
**Файл:** `IslandManager.java:31-47, 88-95, 140-189`

`savePrices()`, `saveSlots()`, `savePlatform()` — синхронні `FileWriter` виклики в Tick Thread. `savePlatform` зберігає до ~5000 блоків в JSON → 100+ мс заморозки при повільному диску.

**Рішення:**
```java
CompletableFuture.runAsync(() -> {
    try (FileWriter w = new FileWriter(file)) { GSON.toJson(data, w); }
    catch (IOException e) { e.printStackTrace(); }
});
```

---

### [M-4] Незавершений rollback при збої Azuriom
**Файл:** `api/app/api/v1/endpoints/market.py:185-189`

```python
deducted = await deduct_buyer(...)
if not deducted:
    await crud_market.cancel_purchase(...)  # якщо ця теж падає?
```

Якщо `cancel_purchase` кидає виняток → товар назавжди заблокований у pending, продавець ніколи не отримає оплату.

**Рішення:** Обгорнути в `try/except`, логувати і ставити в очередь повторної обробки.

---

### [M-5] UUID/IP у debug-логах
**Файл:** `Nestworldvelocity/src/main/java/.../network/ApiClient.java:70`

```java
logger.debug("... Body: {}", httpResponse.body().substring(0, 500));
```

У DEBUG-режимі логуються тіла відповідей з UUID, IP-адресами, статусами островів.

**Рішення:** Не логувати body у production (перевірити рівень логування у deployment).

---

### [M-6] Адмін-ендпоінти images/analytics без auth
**Файли:** `images.py`, `analytics.py`

- `POST /api/v1/images/` — додати LXD image alias (→ наступний острів стартує з іншого образу)
- `PUT /api/v1/images/{id}` — оновити image alias
- `POST /api/v1/analytics/anomalies/{id}/resolve` — закрити аномалію без перевірки

**Рішення:** Ці endpoints потребують admin-токен, не загальний API-ключ.

---

### [M-7] Warp delete/suspend без авторизації
**Файл:** `api/app/api/v1/endpoints/warps.py:28-53`

`POST /warps/{player_uuid}/delete` і `/suspend` — відкриті. UUID для атаки беруться через [H-2].

**Рішення:** Прибрати [H-2] та закрити warp-endpoints за API-ключем.

---

## НИЗЬКІ

### [L-1] catch (Exception ignored) у парсингу FTB-Quests
**Файли:** `IntProperty.java`, `DoubleProperty.java`

Помилки конфігу ховаються без логів. Квест не завантажується мовчки.
**Рішення:** `LOGGER.warn("Failed to parse property: '{}'", string);`

### [L-2] CountDownLatch без await() — витік пам'яті
**Файл:** `Nestworldvelocity/src/main/java/.../network/WebSocketManager.java`

`CountDownLatch` створюється, але `await()` ніде не викликається. Якщо об'єкти не чистяться — memory leak.

### [L-3] Глобальний стан worker при кількох Gunicorn процесах
**Файл:** `api/app/services/update_worker.py`

`_worker_running`, `_worker_task` — глобальні змінні. При 4 Gunicorn workers — 4 копії, race conditions в черзі БД.
**Рішення:** Celery або Redis distributed lock (як вже зроблено для `startup_lock` в `main.py`).

### [L-4] Дефолтні секрети у config.py
**Файл:** `api/app/core/config.py:39`

```python
DATABASE_URL: str = os.getenv("DATABASE_URL", "mysql+aiomysql://skyblock_user:skyblock_pass@localhost:3306/skyblock_db")
```

Якщо `.env` відсутній — додаток стартує з дефолтними паролями без попередження.
**Рішення:** Якщо змінна відсутня — кидати виняток при старті.

---

## Пріоритетний план виправлення

| Пріоритет | Що робити | Що вирішує |
|-----------|-----------|------------|
| 1 | Додати `X-API-Key` header перевірку на всі endpoints | C-1, M-6, M-7, H-2 |
| 2 | Перевірка `client_id` при WS-підключенні | C-2 |
| 3 | URL-кодування `playerName` в ApiClient | C-4 |
| 4 | `with_for_update()` в `confirm_extraction` та `cancel_purchase` | H-1 |
| 5 | Bounds check для `amount` в PacketShopAction і PacketTrade | C-6, H-4 |
| 6 | HTTP `timeout` в MarketSyncManager | H-3 |
| 7 | Асинхронний file I/O в IslandManager | M-3 |
| 8 | CORS origins з env | C-3 |
| 9 | Rate limit fallback (не True при Redis збої) | M-1 |
| 10 | Виняток замість дефолтних секретів | L-4 |
