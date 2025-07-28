import asyncio
from typing import Dict, List, Any
from fastapi import WebSocket
import logging
import json

logger = logging.getLogger(__name__)

class ConnectionManager:
    def __init__(self):
        # Зберігаємо активні з'єднання. Ключ - це client_id (наприклад, player_uuid)
        self.active_connections: Dict[str, WebSocket] = {}

    async def connect(self, websocket: WebSocket, client_id: str):
        """
        Приймає нове WebSocket з'єднання.
        """
        await websocket.accept()
        self.active_connections[client_id] = websocket
        logger.info(f"WebSocket Manager: New connection for client_id: {client_id}. Total connections: {len(self.active_connections)}")

    def disconnect(self, client_id: str):
        """
        Закриває та видаляє з'єднання.
        """
        if client_id in self.active_connections:
            # Немає потреби викликати `websocket.close()` тут,
            # оскільки FastAPI обробляє це, коли ендпоінт завершується.
            del self.active_connections[client_id]
            logger.info(f"WebSocket Manager: Disconnected client_id: {client_id}. Total connections: {len(self.active_connections)}")

    async def send_personal_message(self, data: Any, client_id: str):
        """
        Надсилає особисте повідомлення конкретному клієнту.
        """
        if client_id in self.active_connections:
            websocket = self.active_connections[client_id]
            try:
                # Переконуємося, що дані є у форматі JSON-рядка
                if isinstance(data, dict) or isinstance(data, list):
                    await websocket.send_text(json.dumps(data))
                else:
                    await websocket.send_text(str(data))
                logger.debug(f"WebSocket Manager: Sent message to {client_id}: {data}")
            except Exception as e:
                logger.error(f"WebSocket Manager: Error sending message to {client_id}: {e}", exc_info=True)
                # Можливо, з'єднання вже закрите, варто його видалити
                self.disconnect(client_id)

    async def broadcast(self, data: Any):
        """
        Надсилає повідомлення усім підключеним клієнтам.
        """
        # Створюємо JSON-рядок один раз для всіх
        message_text = json.dumps(data) if isinstance(data, (dict, list)) else str(data)
        
        # Використовуємо asyncio.gather для паралельної відправки
        results = await asyncio.gather(
            *[conn.send_text(message_text) for conn in self.active_connections.values()],
            return_exceptions=True
        )
        
        # Обробляємо помилки, які могли виникнути (наприклад, закриті з'єднання)
        for i, result in enumerate(results):
            if isinstance(result, Exception):
                client_id = list(self.active_connections.keys())[i]
                logger.error(f"WebSocket Manager: Error broadcasting to {client_id}: {result}", exc_info=False)
                # Не видаляємо з'єднання тут, щоб не змінити список під час ітерації,
                # але можна зібрати "мертві" з'єднання і видалити їх пізніше.

# Створюємо єдиний екземпляр менеджера, який буде використовуватися у всьому додатку
manager = ConnectionManager()
