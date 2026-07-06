# Система авто-оновлень — стан і інструкція з експлуатації

Дизайн і початковий план — `UPDATE_SYSTEM_PLAN.md`. Цей файл — те, що реально
зроблено, перевірено на dev, і як цим користуватись.

Останнє оновлення: 2026-07-06.

---

## Нове 2026-07-06: моніторинг здоров'я + економні бекапи + мод

**1. Health-моніторинг (закрито сліпу зону "контейнер живий / MC мертвий"):**
- Мод (v1.3.0) шле heartbeat кожні ~30с по WS **з server tick loop** — якщо
  server thread завис, heartbeat зникає сам собою. Поля: `tps`, `players`.
- Мод шле `shutting_down` при штатній зупинці — watchdog відрізняє її від крашу.
- WS-з'єднання мода тепер **перепідключається** (раніше reconnect не існував).
- Новий watchdog-воркер в API (`services/health_worker.py`): RUNNING-острови
  без heartbeat > `HEARTBEAT_TIMEOUT_SECONDS` → перевірка реального стану LXD:
  контейнер впав → подія `crashed` + STOPPED; контейнер живий, MC мовчить →
  подія `hung`/`stopped_externally`, контейнер зупиняється, острів STOPPED
  (гравець перезаходить — острів стартує заново; жодних рестарт-циклів).
- Повторний `/ready` без старту з боку API = подія `restarted` (MC перезапустив
  systemd), більше не ігнорується.
- Журнал інцидентів: таблиця `island_events`, перегляд
  `GET /api/v1/islands/{uuid}/events` (crashed/hung/restarted/stopping/…).
- **Острови зі старим модом (без heartbeat) watchdog не чіпає** — безпечний
  rollout: спочатку задеплой мод кампанією, watchdog підхопить нові сесії.
- Міграція: `api/sql/migration_2026-07_health_monitoring.sql` (виконати на dev і prod!).

**2. Економія диска (SSD був забитий на 100%):**
- Retention file-бекапів: тримаються останні `UPDATE_BACKUP_KEEP_VERSIONS=2`
  версії на острів; чистка при завершенні кампанії і при старті воркера
  (rollback і так уміє лише на попередню версію — нічого не втрачаємо).
- `UPDATE_SNAPSHOT_MODE=always|critical|never` — на dir-бекенді LXD снапшот =
  повна копія контейнера; `critical` робить снапшоти лише для critical-тегів.
- Разова ручна чистка старого сміття на сервері (виконати руками):
  ```bash
  du -sh /opt/skyblock/island-backups/*            # подивитись, що зжерло місце
  lxc list --project <проєкт> -c n | tail -n +3 | awk '{print $2}' | while read c; do
    lxc info "$c" --project <проєкт> | sed -n '/Snapshots/,$p'; done   # старі снапшоти
  lxc delete <container>/<snapshot> --project <проєкт>                 # видалення
  ```

**3. Мод тепер обробляє команди оновлень (пункт 1 зі "Що лишилось" — ЗАКРИТО):**
- `execute_command` → виконується на server thread → `command_ack` в API;
- `pending_update` → повідомлення в чат (золотим) або кік усіх (`kick:true`).

**Нові змінні `.env`:**
```bash
UPDATE_BACKUP_KEEP_VERSIONS=2      # скільки версій file-бекапів тримати на острів
UPDATE_SNAPSHOT_MODE=always        # always | critical | never
HEARTBEAT_TIMEOUT_SECONDS=90       # тиша від MC довше цього = перевірка watchdog
HEALTH_WORKER_INTERVAL=30          # частота проходів watchdog
```

**Деплой цього пакета:** 1) SQL-міграція health_monitoring; 2) новий код API;
3) мод `nestworld-mods-server-1.3.0.jar` на острови кампанією оновлень
(або руками в шаблон + спавн). Порядок неважливий — усе backward-сумісне.

---

## Стан: E2E перевірено на dev, готово до продакшену після Forge мода

Повний цикл (webhook/ручний тригер → git sync → снапшот → бекап → синхронізація
файлів із коректним видаленням застарілих → перепублікація шаблону) підтверджено
робочим на `api-dev.nestworld.site` (`LXD_PROJECT=SkyBlock-dev`) 2026-07-04.

## Що зроблено

**API:**
- `models/update.py` — `UpdateCampaign`, `UpdateQueue`, `IslandPendingCommand`
- `services/git_sync.py` — clone/pull, парсинг тегів, diff, визначення дій
- `services/update_service.py` — оркестрація оновлення острова, rollback,
  оновлення шаблонного контейнера
- `services/update_worker.py` — фоновий воркер черги, WAITING-логіка,
  recovery після рестарту
- `api/v1/endpoints/updates.py` — webhook (HMAC, без api-key) + admin-роутер
  (campaigns, rollback, snapshots)
- `POST /islands/{uuid}/player_left` — тригер розблокування WAITING-запису
- WS: доставка `pending_update`/`execute_command` при reconnect + `command_ack`
- `lxd_service.py` — снапшоти з expiry, `push_directory` з `delete_extra` для
  `mods/`, host-side бекап/відновлення файлів
- SQL-міграція `sql/migration_2026-07_update_system.sql` — виконана на dev

**Velocity:**
- `ApiClient.notifyPlayerLeft()` + виклик у `PlayerConnectionListener`
- Фікс WS: `WebSocketManager` тепер шле `X-Api-Key`

## Баги, знайдені й виправлені під час E2E-тестування (усі в `lxd_service.py`)

| # | Місце | Проблема | Фікс |
|---|---|---|---|
| 1 | `create_snapshot` | project param не передавався в сирий `.api` виклик | `_project_params()` helper (виявилось не причиною — страховка) |
| 2 | `create_snapshot`, `list_snapshots` | `client.api.containers[name]` — легасі LXD endpoint не підтримує `/snapshots` у LXD 6.8 | `containers` → `instances` |
| 3 | `list_directory` | той самий легасі-шлях `/1.0/containers/{name}/files`, помилка тихо ковталась як "not found" → `delete_extra` завжди бачив порожню директорію → **старі моди ніколи не видалялись** | `/1.0/containers/` → `/1.0/instances/` |
| 4 | `delete_file` | прапорець `--recursive` не існує в `lxc file delete` (LXD CLI 6.8) | прапорець прибрано |
| 5 | `create_snapshot` | retry після часткової невдачі падав з "snapshot already exists" | ловимо `"already exists"` і перевикористовуємо снапшот |

**Урок:** будь-який прямий (не через модель pylxd) виклик LXD API — перевіряти
на `containers` vs `instances` endpoint і на реальні прапорці поточної версії
`lxc` CLI (`lxc <subcommand> --help`), а не покладатися на документацію/пам'ять.

---

## Що лишилось

1. ~~**Forge мод — обробка WS-подій**~~ — ✅ зроблено 2026-07-06 (мод v1.3.0).

2. **Токен GitHub** — переконайся, що ротував той, що засвітився в логах і
   чаті 2026-07-03 (`github_pat_11A45LP3Y...`).

3. **Оновлення ядра сервера** (частина Б) — чекає на вивід з сервера:
   `lxc exec <контейнер> --project <проєкт> -- ls -la /opt/minecraft` — щоб
   знати реальний layout (libraries/, run.sh, user_jvm_args.txt) і додати
   core-набір до синхронізації (тільки коли diff його торкається).

4. **Прибрати сміттєві тестові острови на dev** — `Chaos1924` (id=3) і `Igor`
   (id=7) не мають реальних LXD-контейнерів, кожна кампанія їх намарно
   намагається оновити і падає:
   ```sql
   UPDATE islands SET status='STOPPED', skip_auto_updates=1 WHERE id IN (3, 7);
   ```

5. ~~**Тестів на нову логіку немає.**~~ — ✅ 2026-07-06: 7 тестів на watchdog і
   retention (`app/tests/test_health_worker.py`), разом 50 проходять.

6. **Продакшн-деплой:**
   - Виконати SQL-міграцію на прод-базі (так само, як на dev)
   - Задеплоїти новий код API (тег `api-v*` → CI/CD)
   - Заповнити `.env` на проді (див. розділ нижче)
   - Створити/перевірити webhook на GitHub для репо оновлень

---

## Змінні `.env` (API)

```bash
UPDATES_REPO_URL=https://github.com/Igor5877/Server-NestWorls-modpak.git
UPDATES_REPO_LOCAL_PATH=/opt/skyblock/updates-repo   # директорія має існувати, власник = юзер сервісу
GITHUB_TOKEN=github_pat_...                          # репо приватне — обов'язково; fine-grained, Contents: Read-only
GITHUB_WEBHOOK_SECRET=<openssl rand -hex 32>         # той самий рядок у налаштуваннях GitHub webhook

TEMPLATE_CONTAINER_NAME=skyblock-base                # реальна назва контейнера-шаблону, перевір: lxc list --project <проєкт>
UPDATES_TARGET_DIR=/opt/minecraft                    # куди в контейнері копіюються файли
UPDATES_BACKUP_DIR=/opt/skyblock/island-backups      # host-side бекапи файлів перед оновленням

SNAPSHOT_RETENTION_DAYS=7
UPDATE_WORKER_INTERVAL=10
UPDATE_MAX_RETRIES=3
LXC_BIN=lxc                                          # /snap/bin/lxc якщо LXD через snap і PATH сервісу його не бачить
```

⚠️ **Систематичний нюанс systemd**, знайдений при налагодженні: якщо юніт має
`Environment=PATH=/path/to/venv/bin` — це **замінює** системний PATH цілком, а
не додає до нього. Якщо після цього `git`/`lxc` не знаходяться в підпроцесах
API — перевір:
```bash
sudo systemctl cat <service> | grep PATH
```
і додай системні шляхи через override:
```bash
sudo systemctl edit <service>
# [Service]
# Environment=PATH=/path/to/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
sudo systemctl daemon-reload && sudo systemctl restart <service>
```

Також переконайся, що директорії `UPDATES_REPO_LOCAL_PATH` і
`UPDATES_BACKUP_DIR` існують і належать юзеру, від імені якого працює сервіс
(`chown -R <user>:<group> /opt/skyblock`), інакше перший запуск впаде з
`PermissionError`.

---

## Як зробити оновлення (інструкція для адміна)

### 1. Онови вміст репозиторію оновлень

Репо: `https://github.com/Igor5877/Server-NestWorls-modpak` — містить лише
whitelisted-директорії, які реально синхронізуються на острови:
`mods/`, `config/`, `quests/`, `recipes/`, `kubejs/`, `defaultconfigs/`,
`scripts/`. Усе решта (наприклад, `server.properties`, інсталятори) в репо
можна тримати, але воно ігнорується системою.

```bash
cd Server-NestWorls-modpak/
# додай/онови/вилучи файли в mods/, config/, kubejs/ тощо
git add .
git commit -m "опис що змінилось: нові моди, фікс конфігу, тощо"
```

### 2. Постав тег і визнач тип оновлення

| Тег | Тип | Поведінка для онлайн-гравця |
|---|---|---|
| `v1.3.0` | `server_only` (дефолт) | чекає виходу гравця (`WAITING`) |
| `v1.3.0-both` | `both` — мод потрібен і на клієнті | повідомлення в чат, чекає виходу |
| `v1.3.0-critical` | `critical` | кік одразу через ~5с, API сам зупиняє контейнер |

Версія має бути **новою** — та сама не використовується двічі.

```bash
git tag v1.3.0
git push origin main
git push origin v1.3.0    # окремо! звичайний push теги не надсилає
```

### 3. Кампанія запускається автоматично через webhook

GitHub надсилає push-подію на `POST /api/v1/updates/webhook` → якщо це
push тегу, HMAC пройшов і нема активної кампанії — створюється кампанія,
усі острови (крім `skip_auto_updates=1`) стають у чергу.

**Ручний запуск** (якщо webhook не спрацював, або для тесту/підмножини островів):
```bash
curl -X POST https://<api-host>/api/v1/updates/campaign \
  -H "X-Api-Key: <ключ>" -H "Content-Type: application/json" \
  -d '{"tag": "v1.3.0", "islands": "all"}'
  # або "islands": ["player-uuid-1", "player-uuid-2"] для підмножини
```

### 4. Стеж за прогресом

```bash
curl -H "X-Api-Key: <ключ>" https://<api-host>/api/v1/updates/campaigns
curl -H "X-Api-Key: <ключ>" https://<api-host>/api/v1/updates/campaign/<id>
```

Другий запит показує розбивку по кожному острову (`entries`): `PENDING`,
`WAITING` (онлайн, чекає виходу), `PROCESSING`, `COMPLETED`, `FAILED`,
`SKIPPED`.

Логи воркера в реальному часі:
```bash
sudo journalctl -u <api-service> -f | grep -iv "UserWarning\|warnings.warn\|orm_mode"
```

### 5. Якщо острів впав (`FAILED`)

Після 3 спроб (`UPDATE_MAX_RETRIES`) запис стає термінально `FAILED` і
воркер більше сам не повторює. Спочатку зʼясуй причину з `error_message` в
`GET /updates/campaign/{id}`, виправ (наприклад, права/директорія/мережа),
і повтори лише невдалі записи тієї ж кампанії:

```bash
curl -X POST https://<api-host>/api/v1/updates/campaign/<id>/requeue_failed \
  -H "X-Api-Key: <ключ>"
```

### 6. Відкат (rollback)

```bash
# один острів — до попередньої версії
curl -X POST https://<api-host>/api/v1/updates/rollback/island/<player_uuid> \
  -H "X-Api-Key: <ключ>"

# уся кампанія
curl -X POST https://<api-host>/api/v1/updates/rollback/campaign/<id> \
  -H "X-Api-Key: <ключ>"
```

Rollback ніколи не чіпає `world/` — тільки файли з бекапу
(`UPDATES_BACKUP_DIR/<container>/<version>/`). LXD-снапшот (`pre-update-<version>`)
лишається як аварійна страховка для ручного `lxc restore`, якщо сам світ
пошкоджено — це свідома ручна дія адміна, не автоматична.

### 7. Форсувати оновлення онлайн-острова негайно (без `-critical` тегу)

Якщо кампанія вже запущена, а конкретний острів `WAITING` (гравець онлайн) і
чекати не хочеться:
```bash
curl -X POST https://<api-host>/api/v1/islands/stop/<player_uuid> \
  -H "X-Api-Key: <ключ>"
```
Щойно острів перейде в `STOPPED`, воркер на наступному тіку (до 10с) сам
перекладе запис із `WAITING` у `PENDING` і застосує оновлення.

---

## Типові помилки під час діагностики (шпаргалка)

| Симптом | Причина | Де дивитись |
|---|---|---|
| Webhook повертає 404 | новий код API не задеплоєний / роутер не підключений | `curl .../updates/campaigns` — має бути `[]`, не 404 |
| Webhook 403 | `GITHUB_WEBHOOK_SECRET` не збігається з налаштуванням на GitHub | `.env` vs GitHub webhook secret |
| `git`/`lxc` `FileNotFoundError` у логах | PATH юніту systemd не містить системні шляхи | `systemctl cat <service> \| grep PATH` |
| `PermissionError` на директоріях оновлень | директорія належить не тому юзеру, від якого працює сервіс | `chown -R <user>:<group>` |
| Кампанія "зависла" без помилок у логах | острів у транзитному DB-статусі (`PENDING_*`) без реального LXD-контейнера — тихий `defer_entry` без логу | звір `SELECT container_name FROM islands` з `lxc list --project <проєкт>` |
| `Container not found` при снапшоті/файлах, хоч контейнер реально є | сирий виклик API/CLI б'є в застарілий LXD endpoint (`containers` замість `instances`) | див. баги #2, #3 вище |
| Старі й нові версії модів співіснують після оновлення | `delete_extra` не бачив вміст контейнера через баг #3 | вже виправлено; на старих островах прибирається наступним тегом |
