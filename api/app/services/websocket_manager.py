import asyncio
import json
import logging
from typing import Any, Dict, List

from fastapi import WebSocket
from starlette.websockets import WebSocketDisconnect

from app.core.config import settings
from app.core.redis import get_redis_client

logger = logging.getLogger(__name__)

class ConnectionManager:
    """Manages WebSocket connections and communication."""
    def __init__(self):
        """Initializes the ConnectionManager."""
        self.active_connections: Dict[str, WebSocket] = {}

    async def connect(self, websocket: WebSocket, client_id: str):
        """Connects a new client.

        If a connection for this client_id already exists, it is closed first.

        Args:
            websocket: The WebSocket connection.
            client_id: The ID of the client.
        """
        # If a connection for this client_id already exists, close it first.
        if client_id in self.active_connections:
            logger.warning(f"Client {client_id} is reconnecting. Closing the old connection.")
            old_websocket = self.active_connections[client_id]
            try:
                await old_websocket.close()
            except Exception as e:
                logger.error(f"Error closing old websocket for {client_id}: {e}", exc_info=False)
            self.disconnect(client_id)

        await websocket.accept()
        self.active_connections[client_id] = websocket
        logger.info(f"WebSocket Manager: New connection for client_id: {client_id} on this worker.")

    def disconnect(self, client_id: str):
        """Disconnects a client.

        Args:
            client_id: The ID of the client.
        """
        if client_id in self.active_connections:
            del self.active_connections[client_id]
            logger.info(f"WebSocket Manager: Disconnected client_id: {client_id} on this worker.")

    async def _send_direct_personal_message(self, data: Any, websocket: WebSocket):
        """Sends a message directly to a WebSocket connection.

        This method is used by the redis_listener to send messages to locally
        connected clients.

        Args:
            data: The data to send.
            websocket: The WebSocket connection.
        """
        try:
            message_text = json.dumps(data) if isinstance(data, (dict, list)) else str(data)
            await websocket.send_text(message_text)
        except (WebSocketDisconnect, RuntimeError) as e:
            # Find client_id to disconnect if the connection is dead
            for cid, ws in self.active_connections.items():
                if ws == websocket:
                    logger.warning(f"WebSocket Manager: Connection to {cid} closed while sending: {e}")
                    self.disconnect(cid)
                    break
        except Exception as e:
            logger.error(f"WebSocket Manager: Unexpected error sending direct message: {e}", exc_info=True)

    async def publish_to_redis(self, client_ids: List[str], data: Any):
        """Publishes a message to the Redis channel.

        This will be picked up by all workers.

        Args:
            client_ids: The IDs of the clients to send the message to.
            data: The data to send.
        """
        redis = get_redis_client()
        payload = {
            "client_ids": client_ids,
            "data": data
        }
        await redis.publish(settings.REDIS_CHANNEL, json.dumps(payload))
        logger.info(f"WebSocket Manager: Published message to Redis for clients: {client_ids}")

    async def send_personal_message(self, data: Any, client_id: str):
        """Publishes a message for a single client to Redis.

        Args:
            data: The data to send.
            client_id: The ID of the client.
        """
        await self.publish_to_redis([client_id], data)

    async def send_message_to_clients(self, client_ids: List[str], data: Any):
        """Publishes a message for multiple clients to Redis.

        Args:
            client_ids: The IDs of the clients to send the message to.
            data: The data to send.
        """
        await self.publish_to_redis(client_ids, data)

    async def redis_listener(self):
        """Listens for messages on the Redis Pub/Sub channel.

        This function sends messages to clients connected to this specific worker
        process.
        """
        redis = get_redis_client()
        pubsub = redis.pubsub()
        await pubsub.subscribe(settings.REDIS_CHANNEL)
        logger.info(f"Subscribed to Redis channel: {settings.REDIS_CHANNEL} on this worker.")
        
        while True:
            try:
                message = await pubsub.get_message(ignore_subscribe_messages=True, timeout=1.0)
                if message and message.get("type") == "message":
                    payload = json.loads(message["data"])
                    data = payload["data"]
                    client_ids = payload["client_ids"]
                    
                    # Send to locally connected clients
                    for client_id in client_ids:
                        if client_id in self.active_connections:
                            websocket = self.active_connections[client_id]
                            logger.debug(f"Redis Listener: Sending message from channel to local client: {client_id}")
                            await self._send_direct_personal_message(data, websocket)
                await asyncio.sleep(0.01)  # Prevent high CPU usage
            except Exception as e:
                logger.error(f"Redis listener error: {e}", exc_info=True)
                await asyncio.sleep(5)  # Wait before retrying on major error

manager = ConnectionManager()
