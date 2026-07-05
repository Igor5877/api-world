# Nestworld Teams — аддони для FTB Teams / FTB Chunks

Два аддони, що роблять команди єдиними для всієї мережі: гравець виконує **одну**
дію (команда або GUI), API — єдине джерело правди, FTB Teams і привати
підтягуються автоматично.

## Складові

| Проєкт | Куди ставити | Що робить |
|---|---|---|
| `nestworld-teams-addon` (цей) | сервери островів **і** спавн | синхронізація FTB Teams party зі складом команди з API, авто-приват чанків, обробка GUI-дій, пуш прогресу квестів |
| `nestworld-teams-client` | модпак гравців | перехоплення GUI FTB Teams (invite за ніком, kick через API), команда `/nwteam`, сповіщення про запрошення |
| `mods-server` ≥ 1.2.2 | сервери островів і спавн | обов'язково: аддон живиться подією `TeamDataUpdatedEvent` (нова в 1.2.2) |

## Як це працює

```
/team invite (Velocity) або GUI → API (джерело правди)
        API пушить TEAM_UPDATED → WebSocket island_<owner_uuid>
        mods-server приймає → TeamDataUpdatedEvent (Forge event bus)
        аддон реконсилює FTB Teams party:
          - створює party (коли власник онлайн)
          - додає членів (при їх вході на острів)
          - кикає зайвих (працює і для офлайн)
        FTB Chunks: одноразовий авто-клейм навколо спавну для команди
```

Ручне керування party гравцями вимкнено (`setPartyCreationFromAPIOnly` +
guard, що відкочує зміни повз API). Гості на острові не в party → привати
блокують їх автоматично.

## Збірка

```bash
cd nestworld-teams-addon && ./gradlew build   # build/libs/nestworld-teams-addon-1.0.0.jar
cd nestworld-teams-client && ./gradlew build  # build/libs/nestworld-teams-client-1.0.0.jar
```

Після зміни mods-server: `cd mods-server && ./gradlew shadowJar`, потім
скопіювати новий jar у `nestworld-teams-addon/libs/` (compileOnly-залежність).

## Конфіг (config/nestworld_teams-common.toml)

- `chunk_claims.autoClaimEnabled` / `autoClaimRadius` (2 = 5×5 чанків)
- `quest_progress.progressPushEnabled` — **false**, увімкнути після реалізації API
- `party_guard.lockManualPartyManagement`

URL/ключ API беруться з конфіга mods-server (`skyblock-common.toml`) — окремо
нічого не налаштовувати.

## Що чекає на API (api/API_TEAMS_TODO.md)

Уже написано в аддонах, увімкнеться саме собою після реалізації ендпоінтів:
- запрошення через GUI/`/nwteam invite` (зараз відповідає "ще недоступно";
  діючий шлях — `/nwteam join <назва>` або Velocity `/team accept`)
- kick через GUI
- пуш прогресу квестів + перегляд на спавні (`/nwteam progress`)

## Версії залежностей

Forge 1.20.1-47.2.19, ftb-teams-forge 2001.3.2, ftb-chunks-forge 2001.3.8,
ftb-library-forge 2001.2.9, architectury 9.1.12.
