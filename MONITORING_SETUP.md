# Налаштування Моніторингу з Prometheus

Цей документ надає інструкції та приклади конфігурації для налаштування Prometheus для збору метрик з системи Dynamic SkyBlock.

---

## 1. Огляд

Система експортує метрики у форматі, сумісному з Prometheus, з трьох основних компонентів:

-   **API Backend**: Експортує метрики на `/metrics` (порт `8000` за замовчуванням).
-   **Velocity Proxy Plugin**: Експортує метрики на `/metrics` (порт `9091` за замовчуванням).
-   **Minecraft Mod (на кожному острові)**: Експортує метрики на `/metrics` (порт `9092` за замовчуванням).

## 2. Конфігурація Prometheus (`prometheus.yml`)

Нижче наведено приклад конфігурації для `prometheus.yml`, який демонструє, як збирати метрики з усіх компонентів.

```yaml
global:
  scrape_interval: 15s # Інтервал збору метрик за замовчуванням

scrape_configs:
  # 1. API Backend
  - job_name: 'skyblock-api'
    static_configs:
      - targets: ['localhost:8000'] # Замініть на IP-адресу вашого API

  # 2. Velocity Proxy Plugin
  - job_name: 'skyblock-velocity'
    static_configs:
      - targets: ['localhost:9091'] # Замініть на IP-адресу вашого Velocity-сервера

  # 3. Minecraft Mods (острови)
  # Оскільки острови динамічно створюються та видаляються,
  # рекомендується використовувати service discovery.
  # Нижче наведено приклад з статичною конфігурацією для демонстрації.
  # У реальному середовищі ви можете використовувати 'file_sd_configs' або 'http_sd_configs'.
  - job_name: 'skyblock-islands'
    static_configs:
      - targets:
        - 'island_ip_1:9092'
        - 'island_ip_2:9092'
        # ... інші острови
```

### 2.1. Динамічне виявлення островів (`file_sd_configs`)

Для автоматичного виявлення островів ви можете створити скрипт, який буде періодично опитувати ваш API (або базу даних) на предмет запущених островів та генерувати JSON-файл для Prometheus.

**Приклад `islands.json`:**

```json
[
  {
    "targets": ["10.0.0.101:9092"],
    "labels": {
      "island_owner": "player_uuid_1"
    }
  },
  {
    "targets": ["10.0.0.102:9092"],
    "labels": {
      "island_owner": "player_uuid_2"
    }
  }
]
```

**Оновлена конфігурація `prometheus.yml`:**

```yaml
# ... (global та інші jobs)

- job_name: 'skyblock-islands'
  file_sd_configs:
    - files:
      - 'path/to/your/islands.json'
```

## 3. Налаштування портів

-   Порт для метрик **Velocity** можна змінити в конфігураційному файлі плагіна.
-   Порт для метрик **Minecraft Mod** наразі жорстко закодований (`9092`). У майбутньому це можна буде змінити через конфігураційний файл мода.

## 4. Візуалізація з Grafana

Після того, як Prometheus почне збирати метрики, ви можете підключити його як джерело даних до Grafana та створювати дашборди для візуалізації зібраних даних.
