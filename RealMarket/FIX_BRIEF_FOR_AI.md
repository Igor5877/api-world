# RealMarket Mod — Технічний бриф для виправлення

## Контекст проекту

RealMarket — це Minecraft Forge мод (версія 1.20.1, Forge 47.3.22) для мультиплеєрного сервера SkyBlock. Мод забезпечує ринок між гравцями: кожен гравець має свій острів з AE2 (Applied Energistics 2) мережею. Блок `market-link` підключається до AE2 мережі і синхронізує вміст до центральної бази даних через REST API. Блок `trade_station` дозволяє гравцям купувати/продавати предмети між собою.

## Проблеми що потрібно виправити

### ПРОБЛЕМА 1 (КРИТИЧНА): Синхронізація AE2 → API ніколи не запускається

**Файл**: `src/main/java/RealMarket/realmarket/api/MarketSyncManager.java`

**Симптом**: Предмети з AE2 мережі ніколи не потрапляють до бази даних.

**Причина**: Метод `MarketSyncManager.init(UUID islandUuid)` визначений, але **ніколи не викликається** ніде в коді. Scheduled executor з 30-секундним інтервалом ніколи не запускається.

**Як має працювати**:
`init()` треба викликати на стороні сервера, коли острів готовий. Найкраще місце — в `RealMarket.java` в обробнику події `PlayerEvent.PlayerLoggedInEvent`, після того як отримано UUID острова гравця. Або при завантаженні `MarketLinkBlockEntity` (`onLoad()`), якщо це перший активний лінк для цього острова.

**Де взяти island UUID**: `MarketIslandApi.getIslandUuid(playerUuid)` — повертає UUID острова для гравця. Якщо провайдер не зареєстрований — повертає `null`, тоді використовувати UUID гравця як fallback.

**Увага**: `MarketSyncManager` має поле `currentIslandUuid` — воно повинно відповідати UUID острова, що передається в POST запит до API. URL запиту: `{apiUrl}/api/v1/market/islands/{islandUuid}/inventory/sync`.

---

### ПРОБЛЕМА 2 (КРИТИЧНА): Блок постійно показує "синхронізація" в мультиплеєрі через ID невідповідність

**Файли**: 
- `src/main/java/RealMarket/realmarket/api/AzuriomClient.java`
- `src/main/java/RealMarket/realmarket/block/TradeBlock.java`
- `src/main/java/RealMarket/realmarket/client/TradeScreen.java`

**Симптом**: В мультиплеєрі при відкритті `trade_station` блоку гравець бачить повідомлення "Синхронізація ID... Спробуйте ще раз" замість нормального UI. В одиночному режимі (singleplayer) все працює.

**Причина**: 
1. При вході гравця викликається `AzuriomClient.sync(uuid, name)` — **асинхронний** HTTP запит до Azuriom API.
2. Якщо гравець відкриє `trade_station` до завершення цього запиту — `AzuriomClient.getPlayerId(uuid)` повертає `-1`.
3. `TradeBlock.use()` на клієнтській стороні викликає `AzuriomClient.getPlayerId()` — **але AzuriomClient.IDS мапа живе на сервері**, а клієнт звертається до своєї локальної (порожньої) копії.
4. В singleplayer клієнт і сервер — один процес, тому одна і та ж мапа IDS. В мультиплеєрі — різні процеси, різні мапи.

**Код проблеми в TradeBlock.java** (метод `use()`):
```java
// Це виконується і на клієнті і на сервері
int playerId = AzuriomClient.getPlayerId(player.getUUID()); // на клієнті завжди -1!
if (playerId < 0) {
    player.sendSystemMessage(Component.literal("Синхронізація ID... Спробуйте ще раз"));
    return InteractionResult.FAIL;
}
// Тільки після цього відкривається UI
DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.openTradeScreen(...));
```

**Правильне рішення**:
Логіку перевірки `playerId` треба **перенести на сервер**. Клієнт не повинен знати про Azuriom ID — він просто відкриває екран. Сервер перевіряє ID лише при обробці `PacketShopAction`. Ось правильна структура:

```java
// TradeBlock.use() — правильна версія:
@Override
public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, ...) {
    if (world.isClientSide) {
        // Клієнт просто відкриває екран БЕЗ перевірок ID
        ClientHooks.openTradeScreen(unitPrice);
        return InteractionResult.SUCCESS;
    }
    // Сервер нічого не робить при відкритті (тільки при торгівлі через пакет)
    return InteractionResult.CONSUME;
}
```

Перевірку `AzuriomClient.getPlayerId()` залишити тільки в `PacketShopAction.handle()` (на сервері) — там вона вже є і правильно працює.

---

### ПРОБЛЕМА 3 (ВИСОКА): Відсутня автентифікація в запитах синхронізації

**Файл**: `src/main/java/RealMarket/realmarket/api/MarketSyncManager.java`, метод `sendSyncRequest()`

**Симптом**: POST запит до API не містить токена авторизації.

**Причина**: В методі `sendSyncRequest()` немає заголовку `Authorization` або `Azuriom-Link-Token`.

**Виправлення**: Додати заголовок при побудові HTTP запиту:
```java
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(url))
    .header("Content-Type", "application/json")
    .header("Authorization", "Bearer " + ApiConfig.getToken()) // ДОДАТИ ЦЕ
    .POST(HttpRequest.BodyPublishers.ofString(json))
    .build();
```

Токен береться з `ApiConfig.getToken()` — він завантажується з файлу `config/realmarket-api.toml`.

---

### ПРОБЛЕМА 4 (СЕРЕДНЯ): PacketTrade визначений але не зареєстрований

**Файли**:
- `src/main/java/RealMarket/realmarket/network/PacketTrade.java` — існує
- `src/main/java/RealMarket/realmarket/network/ModMessages.java` — не реєструє PacketTrade

**Симптом**: Клас `PacketTrade` існує, але в `ModMessages.register()` зареєстрований тільки `PacketShopAction`. `PacketTrade` — мертвий код.

**Рішення**: Або видалити `PacketTrade.java` (якщо він дублює `PacketShopAction`), або зареєструвати його. Порівняти логіку двох класів: якщо функціональність однакова — видалити `PacketTrade`.

---

### ПРОБЛЕМА 5 (СЕРЕДНЯ): Немає відповіді від сервера до клієнта після торгівлі

**Файли**:
- `src/main/java/RealMarket/realmarket/network/PacketShopAction.java`
- `src/main/java/RealMarket/realmarket/network/ModMessages.java`

**Симптом**: Після натискання "Купити" або "Продати" клієнт не отримує підтвердження — ні успіху, ні помилки. Гравець не знає чи транзакція пройшла.

**Рішення**: Створити новий пакет `PacketTradeResponse` (SERVER → CLIENT) і відправляти його після завершення операції:

```java
// Новий клас PacketTradeResponse:
public class PacketTradeResponse {
    private final boolean success;
    private final String message;
    private final double newBalance; // оновлений баланс
    
    // encode/decode/handle методи
    // handle() викликає клієнтський код для оновлення UI
}
```

В `PacketShopAction.handleBuy()` і `handleSell()` в кінці async-блоку:
```java
ModMessages.sendToPlayer(new PacketTradeResponse(true, "Куплено!", newBalance), player);
```

---

### ПРОБЛЕМА 6 (СЕРЕДНЯ): AzuriomClient.IDS не зберігається на диску

**Файл**: `src/main/java/RealMarket/realmarket/api/AzuriomClient.java`

**Симптом**: Після перезапуску сервера всі гравці повинні знову підключитись щоб їх Azuriom ID завантажився. Якщо гравець не підключився після перезапуску — його ID недоступний.

**Рішення**: Зберігати `IDS` мапу в JSON файл (`config/realmarket-player-ids.json`). Завантажувати при старті сервера. Оновлювати при кожному `sync()` виклику.

---

## Структура файлів що потребують змін

```
src/main/java/RealMarket/realmarket/
├── RealMarket.java                  — додати виклик MarketSyncManager.init()
├── api/
│   ├── AzuriomClient.java          — зберігати IDS на диск
│   └── MarketSyncManager.java      — додати Authorization header
├── block/
│   └── TradeBlock.java             — прибрати перевірку playerId на клієнті
└── network/
    ├── ModMessages.java            — або зареєструвати PacketTrade або видалити
    └── PacketShopAction.java       — додати відповідь клієнту
```

## Архітектурна схема (для розуміння)

```
[Гравець підключається]
    → RealMarket.onPlayerJoin() (server-side)
    → AzuriomClient.sync(uuid, name) — async HTTP
    → Зберігає {uuid: azuriomId} в IDS мапі

[Гравець розміщує market-link блок]
    → MarketLinkBlock.setPlacedBy() (server-side)
    → Отримує islandUuid через MarketIslandApi
    → Зберігає в NBT блок-ентіті

[MarketLinkBlockEntity.onLoad()]
    → Створює AE2 grid node (тільки server-side)
    → Додає себе в RealMarket.ACTIVE_LINKS
    → ТУТ треба викликати MarketSyncManager.init(islandUuid) ← ВИПРАВЛЕННЯ

[Кожні 30 секунд — MarketSyncManager.syncInventory()]
    → Ітерує RealMarket.getActiveMarketLinks()
    → Для кожного: grid → IStorageService → MEStorage → getAvailableStacks()
    → Агрегує за itemId + NBT
    → POST /api/v1/market/islands/{islandUuid}/inventory/sync з Bearer токеном

[Гравець відкриває trade_station]
    → TradeBlock.use() (виконується на обох сторонах)
    → Якщо clientSide: просто відкрити TradeScreen ← ВИПРАВЛЕННЯ (прибрати перевірку ID)
    → TradeScreen показує ціну та баланс

[Гравець натискає Buy/Sell]
    → TradeScreen.send() → PacketShopAction (CLIENT → SERVER)
    → PacketShopAction.handle() на сервері:
        → AzuriomClient.getPlayerId(uuid) ← тут перевірка OK, бо server-side
        → Async: перевірка балансу → транзакція → видача предметів
    → PacketTradeResponse (SERVER → CLIENT) ← потрібно додати
```

## Важливі деталі реалізації

### AE2 API (версія 15.4.8)
```java
// Правильний спосіб читання предметів з AE2:
IGrid grid = blockEntity.getGrid(); // може повернути null якщо не підключено
if (grid == null) return;
IStorageService storageService = grid.getService(IStorageService.class);
MEStorage storage = storageService.getInventory();
KeyCounter stacks = storage.getAvailableStacks();

for (var entry : stacks) {
    AEKey key = entry.getKey();
    long amount = entry.getLongValue();
    if (key instanceof AEItemKey itemKey) {
        String itemId = ForgeRegistries.ITEMS.getKey(itemKey.getItem()).toString();
        CompoundTag nbt = itemKey.getTag(); // може бути null
        // обробка...
    }
}
```

### Перевірка client/server side в Forge 1.20.1
```java
// В методах Block/BlockEntity:
if (!level.isClientSide()) { /* server only */ }
if (level.isClientSide()) { /* client only */ }

// Безпечно запустити клієнтський код:
DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> { /* client code */ });
```

### Мережеві пакети Forge
```java
// Реєстрація пакету в ModMessages.register():
INSTANCE.messageBuilder(MyPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
    .decoder(MyPacket::decode)
    .encoder(MyPacket::encode)
    .consumerMainThread(MyPacket::handle)
    .add();

// Відправка гравцю:
ModMessages.INSTANCE.send(
    PacketDistributor.PLAYER.with(() -> (ServerPlayer) player),
    new MyPacket(...)
);
```

## Що НЕ чіпати

- `MarketLinkBlockEntity.java` — AE2 інтеграція правильна, grid node lifecycle коректний
- `MarketLinkBlock.java` — setPlacedBy() server-side guard правильний
- `ApiConfig.java` — завантаження конфігу коректне
- `ModMessages.java` реєстрація `PacketShopAction` — правильна
- `RealMarketClientInitializer.java` — Dist.CLIENT guard правильний
- Вся реєстрація блоків/предметів/block entities — коректна

## Очікувана поведінка після виправлень

1. Після розміщення `market-link` блоку він одразу починає синхронізувати AE2 вміст до API кожні 30 секунд
2. При відкритті `trade_station` в мультиплеєрі гравець бачить нормальний UI з ціною та балансом (без "Синхронізація ID...")
3. Після купівлі/продажу гравець бачить підтвердження в ActionBar
4. API отримує запити з Bearer токеном авторизації
