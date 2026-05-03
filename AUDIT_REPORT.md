# Детальний технічний аудит кодової бази SkyBlock Dynamic

Цей звіт є розширеним та поглибленим технічним аналізом чотирьох основних модулів системи: бекенду FastAPI (`api/`), плагіну Velocity (`Nestworldvelocity/`), серверних модів (`mods-server/` та `RealMarket/`), а також інтегрованого модуля квестів (`FTB-Quests-1.20.1-main/`). У звіті вказані конкретні файли, фрагменти коду, детальні описи причин виникнення проблем та рекомендації щодо їх вирішення (Best Practices).

---

## 1. Модуль: `api/` (FastAPI Бекенд)

### 1.1 Вразливість CORS (Безпека)
*   **Файл:** `api/app/main.py`
*   **Проблема:** `CORSMiddleware` ініціалізується з `allow_origins=["*"]` та одночасно `allow_credentials=True`.
    *   *Чому це проблема:* У браузерах сучасна політика безпеки забороняє використовувати wildcard `*`, якщо дозволена передача credentials (cookies, auth headers). Навіть якщо це якось працює, це дозволяє будь-якому сторонньому веб-сайту робити запити від імені залогіненого користувача (атаки CSRF / Cross-Site Request Forgery).
*   **Як має бути:**
    ```python
    # Замість allow_origins=["*"]
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.CORS_ORIGINS, # Список конкретних доменів, наприклад ["https://my-dashboard.com"]
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    ```

### 1.2 Відсутність авторизації на маніпуляційних ендпоінтах (Безпека)
*   **Файли:** `api/app/api/v1/endpoints/islands.py`, `api/app/api/v1/endpoints/teams.py`
*   **Проблема:** Більшість ендпоінтів (наприклад, `POST /islands/stop/{player_uuid}`, `PATCH /teams/{team_id}/rename`) приймають UUID як частину URL і **не перевіряють**, чи має запитувач (клієнт) права на ці дії. У `teams.py` є коментар `# player_uuid: str, # This should come from an auth token`, але наразі параметр береться безпосередньо з тіла запиту або URL.
    *   *Чому це проблема:* Будь-хто, хто підбере UUID, може відправити POST-запит до API і зупинити, заморозити або видалити чужий острів.
*   **Як має бути:** Усі ендпоінти мають бути захищені за допомогою FastAPI `Depends`.
    ```python
    async def stop_island_endpoint(
        player_uuid: uuid.UUID,
        background_tasks: BackgroundTasks,
        db_session: AsyncSession = Depends(get_db_session),
        current_user: User = Depends(get_current_active_user) # <- Важливо! Перевірка токену
    ):
        if current_user.uuid != str(player_uuid) and not current_user.is_admin:
            raise HTTPException(status_code=403, detail="Forbidden")
    ```

### 1.3 Глобальний стан Worker-а в багатопроцесорному середовищі (Архітектура/Ефективність)
*   **Файл:** `api/app/services/update_worker.py`
*   **Проблема:** Використовується патерн Singleton на основі глобальних змінних: `_worker_running = False` та `_worker_task = None`.
    *   *Чому це проблема:* При запуску FastAPI через Gunicorn (наприклад, з 4 worker-процесами), **кожен** процес створить свою власну копію `_worker_task`. Вони будуть одночасно опитувати базу даних `crud_update_queue.get_next_pending_island(db_session)`, що призведе до race conditions, подвійної обробки даних та блокувань таблиць БД.
*   **Як має бути:** Фонова обробка (Cron-jobs) повинна бути винесена за межі FastAPI (наприклад, у Celery worker), або використовувати жорстке блокування в Redis (Redis Lock), щоб гарантувати, що лише один процес у кластері обробляє чергу.

---

## 2. Модуль: `Nestworldvelocity/` (Velocity Proxy Плагін)

### 2.1 Надмірний поллінг стану острова (Ефективність)
*   **Файл:** `Nestworldvelocity/src/main/java/com/skyblockdynamic/nestworld/velocity/commands/IslandCommand.java` та `PlayerConnectionListener.java`
*   **Проблема:** Після запиту на створення або запуск острова, плагін використовує `pollingExecutor.schedule(...)` для постійних HTTP GET запитів до API (наприклад, кожні 2 секунди) для перевірки, чи острів `RUNNING`.
    *   *Чому це проблема:* Якщо 100 гравців одночасно замовлять сервери, Velocity робитиме 50 HTTP-запитів на секунду тільки для опитування. Крім того, `pollingExecutor` у `IslandCommand.java` створений як **SingleThreadScheduledExecutor**. Це означає, що всі гравці, які очікують на острів, стають у чергу на одному потоці. Затримка для одного гравця заблокує поллінг для всіх інших.
*   **Як має бути:**
    1.  API бекенд і Velocity повинні спілкуватися через єдиний WebSocket або Redis Pub/Sub канал. Коли острів стає доступним, API публікує подію `ISLAND_READY {uuid}`, а Velocity миттєво перекидає гравця.
    2.  Якщо поллінг залишається, потрібно використовувати багатопоточний `Executors.newScheduledThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()))`.

### 2.2 Ризик витоку пам'яті: CountDownLatch без timeout (Ефективність/Архітектура)
*   **Файл:** `Nestworldvelocity/src/main/java/com/skyblockdynamic/nestworld/velocity/network/WebSocketManager.java`
*   **Проблема:** У класі є поле `private final CountDownLatch latch = new CountDownLatch(1);`, яке викликається через `latch.countDown();` в `onClose` та `onError`. Однак, у коді ніде не викликається `latch.await()`.
    *   *Чому це проблема:* Зайва об'єктна модель, яка може свідчити про нереалізований до кінця механізм блокування потоку. Якщо об'єкт WebSocketManager залишається в пам'яті через неправильне очищення мап, це сприятиме memory leaks.

---

## 3. Модулі: `mods-server/` та `RealMarket/` (Серверні моди Forge)

### 3.1 Блокуючі I/O операції під час Tick Thread (Архітектура)
*   **Файл:** `RealMarket/src/main/java/RealMarket/realmarket/world/IslandManager.java`
*   **Проблема:** Методи `savePrices()` та `loadPrices()` використовують `FileReader` та `FileWriter` для роботи з Gson. Вони викликаються синхронно в ігрових івентах.
    *   *Чому це проблема:* У Minecraft сервер має один головний потік (Server Tick Thread). Будь-який запис у файл (особливо на повільних дисках) призупиняє весь сервер (виникають мікро-фрізи). Якщо файл великий або диск зайнятий іншим LXD-контейнером, сервер "зависне".
*   **Як має бути:**
    ```java
    // Асинхронне збереження
    CompletableFuture.runAsync(() -> {
        try (FileWriter w = new FileWriter(PRICES_FILE)) {
            // ...
        } catch (IOException e) { e.printStackTrace(); }
    });
    ```

### 3.2 Відсутність повернення в Server Thread після HTTP-запитів (Архітектура)
*   **Файл:** `RealMarket/src/main/java/RealMarket/realmarket/api/MarketSyncManager.java` (метод `executePurchaseAsync`)
*   **Проблема:** HTTP запит виконується асинхронно через `HTTP.sendAsync(...)`, але його callback (`.thenAccept(res -> callback.accept(...))`) виконується в пулі потоків `HttpClient`, а не в головному потоці Minecraft. Якщо `callback.accept()` змінює ігрові об'єкти (наприклад, інвентар чи блоки), виникне `ConcurrentModificationException` або краш гри.
*   **Як має бути:** (Для порівняння: `FTB-Quests-1.20.1-main` робить це **правильно** у `TeamCommands.java:75` через `player.getServer().execute(() -> { ... });`).
    ```java
    // У RealMarket потрібно робити те ж саме перед зміною світу:
    HTTP.sendAsync(...)
        .thenAcceptAsync(res -> {
             // Виконання логіки обробки JSON...
             ServerLifecycleHooks.getCurrentServer().execute(() -> {
                 callback.accept(result); // Безпечно змінює ігровий світ
             });
        });
    ```

---

## 4. Модуль: `FTB-Quests-1.20.1-main/`

### 4.1 Приховування помилок парсингу конфігурацій (Swallowing Exceptions) (Безпека та Архітектура)
*   **Файли:** `.../theme/property/IntProperty.java` та `DoubleProperty.java`
*   **Проблема:**
    ```java
    @Override
    public Integer parse(String string) {
        try {
            int i = Integer.parseInt(string);
            return Mth.clamp(i, min, max);
        } catch (Exception ignored) {
        }
        return null;
    }
    ```
    *   *Чому це проблема:* Блок `catch (Exception ignored)` є антипатерном. Якщо розробник квестів або адміністратор випадково впише в конфіг літери замість числа, парсер поверне `null`, квест не завантажиться або виглядатиме зламаним у грі, а в логах сервера не буде **жодної** згадки про помилку.
*   **Як має бути:**
    ```java
    } catch (NumberFormatException e) {
        // Вивести лог для адміністратора, щоб він міг швидко знайти помилку
        FTBQuests.LOGGER.warn("Failed to parse integer property: '{}'", string);
    }
    ```

---

## 5. Загальні висновки щодо адміністрування

Система архітектурно розроблена непогано (окремий API-менеджер для LXD контейнерів), але наразі має вразливості, типові для ранніх етапів розробки (відсутність аутентифікації на внутрішніх API та синхронний/однопотоковий код там, де потрібен асинхронний).

**Найпріоритетніші кроки для Адміністратора:**
1.  **Закрити бекенд API:** Встановити обов'язковий `API_KEY` для викликів з Velocity та серверів Minecraft (RealMarket). Додати JWT-авторизацію для ендпоінтів, якими керують самі гравці через вебінтерфейси.
2.  **Оптимізувати Minecraft-сервери:** Перенести всі `FileWriter` та `HttpClient` обробники відповідей у правильні пули потоків (`CompletableFuture.runAsync` для I/O, `server.execute()` для зміни ігрового стану).
3.  **Переробити Velocity Polling:** Видалити `SingleThreadScheduledExecutor` і перейти на WebSockets, щоб проксі-сервер миттєво дізнавався про запуск контейнерів без спаму HTTP-запитами до бази даних FastAPI.
