<?php

return [
    /*
     * Базова URL FastAPI (без завершального слеша).
     * Приклад: http://10.0.0.5:8000/api/v1
     */
    'api_url' => env('NESTWORLD_API_URL', 'http://127.0.0.1:8000/api/v1'),

    /*
     * Таймаут запиту до API (секунди).
     */
    'api_timeout' => env('NESTWORLD_API_TIMEOUT', 5),
];
