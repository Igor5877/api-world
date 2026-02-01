# Документація плагіна Velocity (`Nestworldvelocity`)

Цей плагін працює на проксі-сервері Velocity і є першою точкою входу для гравців. Він відповідає за маршрутизацію гравців на їхні острови, автоматичний запуск серверів при вході та обробку команд керування командами.

## 1. Головний клас та Ядро

### `com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin`
Центральний клас плагіна.
*   **Логіка ініціалізації:**
    1. Завантажує конфігурацію `nestworldvelocity.toml`.
    2. Ініціалізує `LocaleManager` для підтримки української та англійської мов.
    3. Створює `ApiClient` для зв'язку з FastAPI бекендом.
    4. Реєструє команди (`/myisland`, `/spawn`, `/team`, `/island`, `/tpa`).
    5. Реєструє прослуховувач подій `PlayerConnectionListener`.

### `com.skyblockdynamic.nestworld.velocity.config.PluginConfig`
Керує налаштуваннями плагіна.
*   `base_url`: Адреса API.
*   `fallback_server`: Сервер, на який гравець потрапляє при вході (наприклад, хаб).
*   `auto_redirect_to_island_on_login`: Якщо `true`, плагін автоматично почне запуск острова при вході гравця.

---

## 2. Обробка підключень

### `com.skyblockdynamic.nestworld.velocity.listener.PlayerConnectionListener`
Найважливіша частина плагіна, що керує логікою "розумного" підключення.

*   `onPlayerChooseInitialServer(...)`:
    1. Направляє гравця на `fallback_server`.
    2. Якщо увімкнено авто-перенаправлення — запускає `pollForRunningAndConnect`.
    3. Також перевіряє, чи не був запланований зупин острова для цього гравця (якщо він швидко перезайшов) і скасовує його.
*   `pollForRunningAndConnect(...)`:
    1. Запитує статус острова в API.
    2. **Якщо острова немає:** Надсилає запит на створення/старт і продовжує опитування (polling).
    3. **Якщо статус RUNNING, але `minecraft_ready = false`:** Сервер завантажується, плагін продовжує чекати.
    4. **Якщо статус RUNNING та `minecraft_ready = true`:** Реєструє IP острова в проксі та переключає гравця.
*   `onPlayerDisconnect(...)`:
    1. Перевіряє, чи залишилися інші члени команди на острові.
    2. Якщо на острові залишилися тільки "гості" (не члени команди) — перенаправляє їх у хаб.
    3. Якщо острів став порожнім — планує запит на зупинку (`STOP`) через 5 хвилин для економії ресурсів.

---

## 3. Команди

### `com.skyblockdynamic.nestworld.velocity.commands.MyIslandCommand`
Команда `/myisland`.
*   **Логіка:**
    1. Перевіряє статус острова через API.
    2. Якщо острів не готовий — підключається до API через **WebSocket** (`WebSocketManager`), щоб миттєво дізнатися, коли сервер завантажиться, і не перевантажувати API постійними HTTP-запитами.

### `com.skyblockdynamic.nestworld.velocity.commands.TeamCommand`
Команда `/team` для керування командами.
*   **Підкоманди:**
    *   `create <назва>`: Створення нової команди (і острова).
    *   `info`: Список членів команди.
    *   `leave`: Вихід з команди.
    *   `rename <назва>`: Зміна назви.
    *   `accept <назва>`: Прийняття запрошення.

### `com.skyblockdynamic.nestworld.velocity.commands.TpaCommand`
Реалізація системи запитів на телепортацію між островами. Оскільки кожен острів — це окремий сервер, стандартні плагіни TPA не працюють. Цей плагін перенаправляє гравця на інший зареєстрований сервер острова.

---

## 4. Мережева взаємодія

### `com.skyblockdynamic.nestworld.velocity.network.ApiClient`
Використовує Java `HttpClient` для асинхронних запитів до FastAPI. Обробляє відповіді та помилки мережі.

### `com.skyblockdynamic.nestworld.velocity.network.WebSocketManager`
Клас для роботи з WebSocket. Використовується в команді `/myisland`. Чекає на JSON-повідомлення зі статусом `RUNNING` та `minecraft_ready: true`. Після отримання сигналу викликає колбек для підключення гравця.
