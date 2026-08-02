# TODO: Гостьовий перегляд квестів — резолюція IslandData за гравцем замість острова

> Специфікація-нотатка. Знайдено 2026-07-30 під час тестування team-системи
> (Igor2/Igor/Yan/Igor3). Коли будеш готовий реалізувати — віддай цей файл
> Claude зі словами "реалізуй GUEST_QUEST_VIEW_TODO.md".

## Контекст

Архітектура api-world: **1 сервер = 1 острів** (`api/` створює окремий LXD-контейнер
під кожну команду). Це відрізняється від ванільного FTB Quests, який спроєктований
під **багато команд на одному спільному сервері** — і саме звідти в форк перейшла
хибна модель резолюції "чиї дані квестів показувати".

## Симптом

Гравець без власної команди (соло, ще не створив острів) заходить через `/tpa`
на чужий острів — коректно бачить реальний прогрес квестів власника (read-only,
здати/забрати нагороду не може — це очікувано).

Гравець, який **вже має свою окрему команду/острів**, заходить через `/tpa` на
чужий острів — бачить, що взагалі **нічого не виконано**, хоча власник острова
насправді пройшов купу квестів.

## Корінь

`BaseQuestFile.getOrCreateIslandData(Entity player)`
(`common/src/main/java/dev/ftb/mods/ftbquests/quest/BaseQuestFile.java:995-1002`):

```java
public IslandData getOrCreateIslandData(Entity player) {
    UUID islandId = NestworldModsServer.ISLAND_PROVIDER.getCachedTeamId(player.getUUID());
    if (islandId == null) {
        return IslandData.UNLOADED;
    }
    return getOrCreateIslandData(islandId);
}
```

Резолюція йде через `getCachedTeamId(player)` — **команду гравця, який зайшов**,
а не команду, якій належить сервер/острів, на якому він фізично стоїть.

- Гравець без команди → `islandId == null` → десь є фолбек, що показує реальні
  дані острова (працює випадково, не через цей метод).
- Гравець із власною (іншою) командою → `getCachedTeamId` коректно повертає
  **його власний** team id (не застарілий, TTL-кеш уже пофіксений окремо) →
  сервер створює/повертає **новий порожній** `IslandData`, прив'язаний до
  команди гостя — на цьому острові такого запису ще не існувало, тому
  "нічого не виконано".

Цей метод — центральний механізм, використовується в **десятках** місць:
`FTBQuestsEventHandler.java` (x3), `ClaimRewardMessage.java`, `SubmitTaskMessage.java`,
`ToggleChapterPinnedMessage.java`, `ToggleEditingModeMessage.java`, `TogglePinnedMessage.java`,
`FTBQuestsCommands.java` (x6), `DetectorBlockEntity.java`, `QuestBarrierBlockEntity.java`,
`FTBQuestsInventoryListener.java`, `NetUtils.java`, `StageTask.java`, `TaskScreenBlock.java`.

## Правильна поведінка

На конкретному острові-сервері `getOrCreateIslandData(player)` має **завжди**
повертати дані **цього острова** (тобто дані власника, під яким запущено
конкретний LXD-контейнер — `SkyBlockMod.getOwnerUuid()` / `islandContext.getOwnerUuid()`),
незалежно від того, якій команді сам гравець належить. `player`-параметр
лишається потрібним лише для per-player полів усередині вже правильно
підібраної `IslandData` (canEdit, claimedRewards тощо) — не для вибору,
яку саме `IslandData` брати.

## Що робити

1. Змінити `getOrCreateIslandData(Entity player)` в `BaseQuestFile.java`, щоб
   на сервері (`file.isServerSide()`) резолюція йшла через island-owner
   сервера, а не `getCachedTeamId(player)`.
2. `getCachedTeamId(player)`/`isPlayerOnTeam` (в `ServerQuestFile.java:161-165`)
   лишити для того, для чого вони й задумані — перевірки "чи гравець
   ЧЛЕН цієї команди" (canEdit/submit/claim), а не для вибору файлу даних.
3. Перевірити клієнтську синхронізацію (`ClientQuestFile`, `FTBQuestsNetClient`) —
   чи не тягне вона так само дані "своєї" команди гравця замість команди
   сервера, до якого він підключений (могла зʼявитись така ж помилка і там).
4. Протестити всі три випадки: власник, член команди острова, гість без
   команди, гість із власною окремою командою — усі мають бачити однаковий
   (реальний) прогрес острова, різнитись лише правами на дію (submit/claim/edit).
