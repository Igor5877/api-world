# API Configuration Guide

## Безпечне зберігання токену

Токен Azuriom API більше не зберігається в коді. Замість цього він читається з конфіг-файла.

### Налаштування

1. **Скопіюйте конфіг-файл** з `src/main/resources/realmarket-api.toml` в папку конфіга:
   ```
   config/realmarket-api.toml
   ```

2. **Добавте свій токен** в файл `config/realmarket-api.toml`:
   ```toml
   [azuriom]
       token = "ваш_токен_тут"
       url = "https://nestworld.site/api/azlink"
       server_id = 1
   ```

3. **Отримайте токен** від адміністратора Azuriom (https://nestworld.site/admin)

### Файли конфіга

- **Розташування**: `config/realmarket-api.toml` або `run/config/realmarket-api.toml`
- **НЕ КОМІТИТИ**: файл додан до `.gitignore`
- **Приватно**: Зберігайте токен в безпеці!

### Клас конфіга

`RealMarket.realmarket.config.ApiConfig` - автоматично читає конфіг при першому звернені

Методи:
- `ApiConfig.getToken()` - отримати токен
- `ApiConfig.getApiUrl()` - отримати URL API
- `ApiConfig.getServerId()` - отримати ID сервера

### Можливі локації конфіга:
1. `./config/realmarket-api.toml`
2. `run/config/realmarket-api.toml`
3. `config/realmarket-api.toml`

Процес розпізнавання проходить в цьому порядку.
