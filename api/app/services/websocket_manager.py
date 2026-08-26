import asyncio
import json
import logging
from typing import Any, Dict, List, Optional

from fastapi import WebSocket
from starlette.websockets import WebSocketDisconnect

from app.core.config import settings
from app.core.redis import get_redis_client

logger = logging.getLogger(__name__)

# Reserved pseudo client_id for routing request/response replies (e.g. idle
# reaper's "can I stop island X" query to the proxy_control connection) back
# to whichever worker process is awaiting them — never a real WS client.
INTERNAL_RESPONSE_CLIENT_ID = "_internal_response"

class ConnectionManager:
    """Manages WebSocket connections and communication."""
    def __init__(self):
        """Initializes the ConnectionManager."""
        self.active_connections: Dict[str, WebSocket] = {}
        # Pending request/response futures, keyed by request_id. Only ever
        # populated/resolved on the worker process that actually called
        # await_response — on every other worker, resolve_response for that
        # id is simply a no-op, which is expected.
        self._pending: Dict[str, asyncio.Future] = {}

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

    async def _send_direct_personal_message(self, data: Any, client_id: str):
        """Sends a message directly to a locally connected WebSocket client.

        This method is used by the redis_listener to send messages to locally
        connected clients.

        Args:
            data: The data to send.
            client_id: The ID of the client.
        """
        websocket = self.active_connections.get(client_id)
        if websocket is None:
            return
        try:
            message_text = json.dumps(data) if isinstance(data, (dict, list)) else str(data)
            await websocket.send_text(message_text)
        except (WebSocketDisconnect, RuntimeError) as e:
            logger.warning(f"WebSocket Manager: Connection to {client_id} closed while sending: {e}")
            self.disconnect(client_id)
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

    async def await_response(self, request_id: str, timeout: float) -> Optional[dict]:
        """Waits for a reply to a request sent via send_personal_message.

        The reply is expected to arrive as a message published to
        INTERNAL_RESPONSE_CLIENT_ID containing this request_id (see
        redis_listener). Used by idle_reaper to ask the proxy_control
        connection whether it's safe to stop an island.

        Args:
            request_id: The id the caller tagged its outgoing request with.
            timeout: Seconds to wait before giving up.

        Returns:
            The reply payload, or None if no reply arrived in time.
        """
        fut: asyncio.Future = asyncio.get_event_loop().create_future()
        self._pending[request_id] = fut
        try:
            return await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError:
            return None
        finally:
            self._pending.pop(request_id, None)

    def resolve_response(self, request_id: str, data: dict):
        """Delivers a reply to a locally-pending await_response call, if any.

        A no-op on any worker that isn't the one currently awaiting this
        request_id — expected, since the request may have been issued by a
        different worker than the one that received the reply.

        Args:
            request_id: The id from the reply payload.
            data: The full reply payload.
        """
        fut = self._pending.get(request_id)
        if fut and not fut.done():
            fut.set_result(data)

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
                    if client_ids == [INTERNAL_RESPONSE_CLIENT_ID]:
                        self.resolve_response(data.get("request_id"), data)
                        continue
                    local_client_ids = [c for c in client_ids if c in self.active_connections]
                    if local_client_ids:
                        logger.debug(f"Redis Listener: Sending message from channel to local clients: {local_client_ids}")
                        await asyncio.gather(*(
                            self._send_direct_personal_message(data, client_id)
                            for client_id in local_client_ids
                        ))
                # get_message already yields for up to timeout seconds — no extra sleep needed
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.error(f"Redis listener error: {e}", exc_info=True)
                await asyncio.sleep(5)

manager = ConnectionManager()
