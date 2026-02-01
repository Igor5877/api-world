# Документація Forge-моду (`mods-server`)

Цей мод встановлюється на кожен Minecraft-сервер (острів) і відповідає за зв'язок з API, керування станом сервера та інтеграцію з квестами.

## 1. Головний клас та Конфігурація

### `com.skyblock.dynamic.SkyBlockMod`
Центральний клас моду, який ініціалізує всі системи.

*   **Логіка ініціалізації:**
    1. Реєструє обробники подій (`PlayerEventHandler`).
    2. Завантажує конфігурацію `skyblock-common.toml` (API URL тощо).
*   **Методи:**
    *   `onServerAboutToStart(ServerAboutToStartEvent)`:
        *   Завантажує контекст острова з файлу `world/serverconfig/skyblock_island_data.toml`.
        *   Визначає, чи є цей сервер островом (`isIslandServer`).
        *   Якщо так — синхронізує дані команди з API через `NestworldModsServer.ISLAND_PROVIDER`.
    *   `onServerStarted(ServerStartedEvent)`:
        *   Якщо це сервер острова: надсилає сигнал готовності (`sendReady`) в API та ініціалізує WebSocket-клієнт для отримання оновлень у реальному часі.
    *   `sendIslandReadyForPlayersSignal()`:
        *   Надсилає POST-запит на `/islands/{owner_uuid}/ready`. Це сигнал для API та Velocity, що сервер завантажився і гравців можна пускати.
    *   `loadIslandContextData(...)`:
        *   Читає файл `skyblock_island_data.toml`, який API записує в контейнер при створенні. Звідти береться `is_island_server` та `owner_uuid`.

### `com.skyblock.dynamic.Config`
Клас для роботи з налаштуваннями Forge.
*   `apiBaseUrl`: Базовий URL бекенду.
*   `apiRequestTimeoutSeconds`: Таймаут запитів (за замовчуванням 10 секунд).

---

## 2. Керування станом острова

### `com.skyblock.dynamic.events.PlayerEventHandler`
Обробляє вхід та вихід гравців для реалізації функції автоматичної заморозки (auto-freeze).

*   `onPlayerLogin(...)`: Якщо запланована заморозка сервера (таймер), вона скасовується, оскільки гравець повернувся.
*   `onPlayerLogout(...)`:
    *   Перевіряє кількість гравців на сервері.
    *   Якщо це останній гравець і це сервер острова — запускає таймер на 5 хвилин.
    *   Після закінчення 5 хвилин викликає `NestworldModsServer.ISLAND_PROVIDER.sendFreeze(...)`.

---

## 3. Взаємодія з API та квестами

### `com.skyblock.dynamic.nestworld.mods.NestworldModsServer`
Містить внутрішній клас `IslandProvider`, який є "містком" до REST API.

*   `refreshAndGetTeamId(playerUuid)`:
    *   **Логіка:**
        1. Робить GET-запит на API (`/teams/my_team/{uuid}`).
        2. При успіху: зберігає дані в локальний кеш `cached_team_data.json` і викликає `processTeamData`.
        3. При помилці (API лежить): намагається завантажити дані з локального кешу, щоб сервер міг працювати автономно.
*   `processTeamData(teamJson)`:
    *   Парсить UUID власника та список членів команди.
    *   Оновлює внутрішній кеш `islandCache`.
    *   Викликає `QuestTeamBridge` для синхронізації з модом FTB Quests.

### `com.skyblock.dynamic.utils.IslandWebSocketClient`
Клієнт для отримання миттєвих повідомлень від API.
*   **Логіка:** Слухає подію `TEAM_UPDATED`. Коли власник острова змінює склад команди через API, мод миттєво отримує ці дані і оновлює права доступу на сервері без перезавантаження.

### `com.skyblock.dynamic.utils.QuestTeamBridge`
Синхронізує команди SkyBlock з командами FTB Quests.
*   `syncTeamData(ownerUuid, memberUuids)`:
    *   Отримує `TeamManager` з FTB Quests.
    *   Створює або оновлює команду в FTB Quests.
    *   Додає нових членів команди та видаляє тих, хто пішов.
    *   Також оновлює спеціальний клас `IslandData` в FTB Quests (якщо він є).

---

## 4. Допоміжні класи

### `com.skyblock.dynamic.utils.IslandContext`
Проста структура даних (POJO) для зберігання інформації про те, чи є сервер островом і хто його власник.
