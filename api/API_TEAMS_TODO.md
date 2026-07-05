# TODO: Доповнення API для системи команд (FTB Teams аддон)

> Це специфікація-промпт. Коли будеш готовий реалізувати — віддай цей файл Claude
> зі словами "реалізуй API_TEAMS_TODO.md". Аддони (nestworld-teams-addon /
> nestworld-teams-client) уже написані під ці ендпоінти і запрацюють автоматично,
> щойно вони з'являться.

## Контекст

Зараз вступ у команду працює через `POST /teams/accept_invite` за **назвою команди** —
будь-хто, хто знає назву, може вступити без згоди власника. Справжньої системи
запрошень немає (`TeamInvite` schema і `crud_team.add_member` існують, але не
підключені до жодного ендпоінта). Також немає ендпоінта для вигнання (kick) і
жодного сховища прогресу квестів.

Всі нові ендпоінти — під існуючим захистом `x-api-key`, як у `endpoints/teams.py`.
Після кожної зміни складу команди викликати існуючий
`broadcast_team_update(db, team_id)` (`endpoints/teams.py:19`) — він пушить
`TEAM_UPDATED` у контейнер `island_<owner_uuid>` через WebSocket, аддон на це
вже підписаний.

## 1. Система запрошень

### Таблиця `team_invites`

```sql
CREATE TABLE team_invites (
    id INT AUTO_INCREMENT PRIMARY KEY,
    team_id INT NOT NULL,
    invited_uuid VARCHAR(36) NOT NULL,
    invited_name VARCHAR(32) NULL,
    inviter_uuid VARCHAR(36) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NULL,             -- напр. NOW() + INTERVAL 7 DAY
    UNIQUE KEY uq_team_invite (team_id, invited_uuid),
    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
);
```

+ SQLAlchemy модель `TeamInvite` у `models/team.py`, схеми у `schemas/team.py`
(розширити наявну `TeamInvite` схему), CRUD у `crud/crud_team.py`.

### Ендпоінти

| Метод | Шлях | Хто викликає | Логіка |
|---|---|---|---|
| POST | `/teams/{team_id}/invite` | власник/модератор | body `{invited_uuid, invited_name?, inviter_uuid}`. Перевірки: inviter — owner/moderator команди; invited не в команді; ліміт розміру команди (якщо є). Створює запис. Якщо invited онлайн — пуш WS-подію `TEAM_INVITE` на client_id `<invited_uuid>` (той самий механізм, що `_send_update_notification` в `island_service.py:112`). |
| GET | `/teams/invites/{player_uuid}` | аддон від імені гравця | Список активних запрошень гравця: `[{invite_id, team_id, team_name, inviter_name, created_at}]`. |
| POST | `/teams/invites/{invite_id}/accept` | аддон від імені гравця | query `player_uuid`. Перевіряє, що запрошення належить гравцю і не протермінувалось → виконує ту саму логіку, що зараз `accept_invite` (`island_service.handle_join_team`: розпуск його соло-команди, видалення старого острова, `add_member`), видаляє запрошення, `broadcast_team_update`. |
| DELETE | `/teams/invites/{invite_id}` | аддон від імені гравця | query `player_uuid` — відхилити запрошення (decline). |
| DELETE | `/teams/{team_id}/members/{player_uuid}` | власник/модератор (kick) | query `requester_uuid`. Перевірки: requester — owner/moderator; не можна кикнути owner; не можна кикнути себе (для себе є leave). Викликає `crud_team.remove_member` + логіку відновлення соло-острова для кикнутого (та сама, що в leave: `island_service`), `broadcast_team_update`. |

### Що зробити зі старим `accept_invite` за назвою

Залишити на перехідний період, але додати перевірку: приймати тільки якщо
для гравця існує активний запис у `team_invites` на цю команду. Це закриває
дірку "вступ без згоди власника" і не ламає Velocity-команду `/team accept`.

## 2. Прогрес квестів (для перегляду на спавні)

Аддон на сервері острова пушить знімок прогресу команди; спавн-сервер (той самий
аддон, режим hub — визначається через mods-server) читає прогрес усіх команд.

### Таблиця `team_quest_progress`

```sql
CREATE TABLE team_quest_progress (
    team_id INT PRIMARY KEY,
    progress_data JSON NOT NULL,          -- знімок: {completed_quests: [...], claimed_rewards: [...], task_progress: {...}}
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
);
```

### Ендпоінти

| Метод | Шлях | Хто викликає | Логіка |
|---|---|---|---|
| PUT | `/quests/progress/{team_id}` | аддон з острова | body — JSON-знімок прогресу (формат визначає аддон, API зберігає як opaque JSON). Upsert. Не частіше ніж раз на N секунд з одного острова (аддон сам тротлить, але додати ліміт розміру body, напр. 1 МБ). |
| GET | `/quests/progress/{team_id}` | аддон зі спавна | Знімок однієї команди (404 якщо нема). |
| GET | `/quests/progress` | аддон зі спавна | Список усіх: `[{team_id, team_name, updated_at, progress_data}]`, опційно `?summary=true` — без повного progress_data, тільки лічильники, якщо аддон їх продублює в окремі колонки (не обов'язково в першій версії). |

## 3. Дрібниці

- У відповідь `GET /teams/my_team/{player_uuid}` додати поле `pending_invites_count`
  для власника (опційно, для бейджа в GUI).
- WS-подія для запрошеного онлайн-гравця: `{"event": "TEAM_INVITE", "payload": {invite_id, team_name, inviter_name}}`
  на client_id `<invited_uuid>` (гравецький сокет) **і** на `island_<owner_uuid>`
  контейнера, де гравець зараз грає, якщо це можливо визначити — аддон покаже
  повідомлення в чаті.
