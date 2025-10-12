import asyncio
import logging
from typing import Optional, Dict, Any, List, Tuple
from functools import partial

import pylxd
from pylxd.exceptions import LXDAPIException, NotFound

from app.core.config import settings

logger = logging.getLogger(__name__)

class LXDServiceError(Exception):
    """Custom exception for LXDService errors."""
    pass

class LXDContainerNotFoundError(LXDServiceError):
    """Specific error for when a container is not found."""
    pass

class LXDService:
    LXDServiceError = LXDServiceError
    LXDContainerNotFoundError = LXDContainerNotFoundError

    def __init__(self):
        self.client: Optional[pylxd.Client] = None
        self._init_lock = asyncio.Lock()
        logger.info("LXDService: Initialized for lazy client connection.")

    async def _get_client(self) -> pylxd.Client:
        """
        Lazily initializes and returns the pylxd.Client.
        This method is thread-safe and ensures the client is created only once per process.
        """
        if self.client:
            return self.client

        async with self._init_lock:
            if self.client:
                return self.client

            if not settings.LXD_SOCKET_PATH:
                logger.error("LXDService: LXD_SOCKET_PATH not configured. Cannot connect to LXD.")
                raise LXDServiceError("LXD_SOCKET_PATH is not configured.")
            
            try:
                logger.info(f"LXDService: First-time access. Initializing pylxd.Client for project '{settings.LXD_PROJECT}'...")
                client = pylxd.Client(endpoint=settings.LXD_SOCKET_PATH, project=settings.LXD_PROJECT)
                self.client = client
                logger.info("LXDService: pylxd.Client initialized.")
                return self.client
            except Exception as e:
                logger.error(f"LXDService: Failed to initialize pylxd.Client: {e}", exc_info=True)
                raise LXDServiceError(f"Failed to initialize or connect to LXD: {e}")

    async def _run_sync(self, func, *args, **kwargs):
        """Helper to run synchronous pylxd calls in a thread pool."""
        await self._get_client() # Ensures client is initialized
        loop = asyncio.get_running_loop()
        bound_func = partial(func, *args, **kwargs)
        return await loop.run_in_executor(None, bound_func)

    async def list_container_names(self) -> List[str]:
        client = await self._get_client()
        return await self._run_sync(lambda: [c.name for c in client.containers.all()])

    async def clone_container(self, source_image_alias: str, new_container_name: str, config: Optional[Dict[str, Any]] = None, profiles: Optional[List[str]] = None):
        client = await self._get_client()
        if profiles is None:
            profiles = ["default"]
        
        def _sync_clone():
            if not client.images.exists(source_image_alias, alias=True):
                raise LXDServiceError(f"Source image '{source_image_alias}' not found.")
            
            container_config = {'name': new_container_name, 'source': {'type': 'image', 'alias': source_image_alias}, 'profiles': profiles}
            if config:
                container_config['config'] = config
            
            if client.containers.exists(new_container_name):
                return client.containers.get(new_container_name)
            else:
                return client.containers.create(container_config, wait=True)

        try:
            container = await self._run_sync(_sync_clone)
            return {"name": container.name, "status": container.status}
        except Exception as e:
            logger.error(f"Error cloning container '{new_container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to clone container: {e}")

    async def start_container(self, container_name: str):
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            if container.status.lower() != 'running':
                await self._run_sync(container.start, wait=True, timeout=settings.LXD_OPERATION_TIMEOUT)
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error starting container '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to start container: {e}")

    async def stop_container(self, container_name: str, force: bool = True):
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            if container.status.lower() != 'stopped':
                await self._run_sync(container.stop, wait=True, force=force, timeout=settings.LXD_OPERATION_TIMEOUT)
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error stopping container '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to stop container: {e}")

    async def delete_container(self, container_name: str):
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            if container.status.lower() != 'stopped':
                await self.stop_container(container_name)
            await self._run_sync(container.delete, wait=True)
        except LXDContainerNotFoundError:
             logger.warning(f"Container '{container_name}' not found for deletion, assuming already deleted.")
        except Exception as e:
            logger.error(f"Error deleting container '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to delete container: {e}")

    async def get_container_state(self, container_name: str) -> Optional[Dict[str, Any]]:
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            state = await self._run_sync(container.state)
            ip_address = None
            if state.network and 'eth0' in state.network:
                for addr in state.network['eth0'].get('addresses', []):
                    if addr['family'] == 'inet':
                        ip_address = addr['address']
                        break
            return {"status": state.status, "ip_address": ip_address}
        except NotFound:
            return None
        except Exception as e:
            logger.error(f"Error getting state for '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to get state for container: {e}")

    async def get_container_ip(self, container_name: str):
        for _ in range(settings.LXD_IP_RETRY_ATTEMPTS):
            state = await self.get_container_state(container_name)
            if state and state.get("ip_address"):
                return state["ip_address"]
            await asyncio.sleep(settings.LXD_IP_RETRY_DELAY)
        return None

    async def push_file_to_container(self, container_name: str, target_path: str, content: bytes, mode=0o644, uid=0, gid=0):
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            await self._run_sync(container.files.put, target_path, content, mode=mode, uid=uid, gid=gid)
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except LXDAPIException as e:
             if 'no such file or directory' in str(e).lower():
                 parent_dir = "/".join(target_path.split('/')[:-1])
                 if parent_dir:
                     await self.execute_command_in_container(container_name, ['mkdir', '-p', parent_dir])
                     await self._run_sync(container.files.put, target_path, content, mode=mode, uid=uid, gid=gid)
                 else:
                     raise LXDServiceError(f"LXD API error pushing file: {e}")
             else:
                 raise LXDServiceError(f"LXD API error pushing file: {e}")
        except Exception as e:
            logger.error(f"Error pushing file to '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to push file: {e}")
            
    async def execute_command_in_container(self, container_name: str, command: List[str], environment: Optional[Dict[str, str]] = None) -> Tuple[int, str, str]:
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            return await self._run_sync(container.execute, command, environment=environment)
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error executing command in '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to execute command: {e}")

    async def freeze_container(self, container_name: str):
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            if container.status.lower() == 'running':
                await self._run_sync(container.freeze, wait=True, timeout=settings.LXD_OPERATION_TIMEOUT)
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error freezing container '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to freeze container: {e}")

    async def unfreeze_container(self, container_name: str):
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            if container.status.lower() == 'frozen':
                await self._run_sync(container.unfreeze, wait=True, timeout=settings.LXD_OPERATION_TIMEOUT)
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error unfreezing container '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to unfreeze container: {e}")

lxd_service = LXDService()
