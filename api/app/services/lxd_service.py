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
    """Provides an asynchronous interface to the LXD API."""
    LXDServiceError = LXDServiceError
    LXDContainerNotFoundError = LXDContainerNotFoundError

    def __init__(self):
        """Initializes the LXDService."""
        self.client: Optional[pylxd.Client] = None
        self._init_lock = asyncio.Lock()
        logger.info("LXDService: Initialized for lazy client connection.")

    async def _get_client(self) -> pylxd.Client:
        """Lazily initializes and returns the pylxd.Client.

        This method is thread-safe and ensures the client is created only once per
        process.

        Returns:
            The pylxd.Client instance.

        Raises:
            LXDServiceError: If the LXD_SOCKET_PATH is not configured or if the
                client fails to initialize.
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
        """Runs synchronous pylxd calls in a thread pool.

        Args:
            func: The function to run.
            *args: The positional arguments to pass to the function.
            **kwargs: The keyword arguments to pass to the function.

        Returns:
            The result of the function.
        """
        await self._get_client() # Ensures client is initialized
        loop = asyncio.get_running_loop()
        bound_func = partial(func, *args, **kwargs)
        return await loop.run_in_executor(None, bound_func)

    async def list_container_names(self) -> List[str]:
        """Lists the names of all containers.

        Returns:
            A list of container names.
        """
        client = await self._get_client()
        return await self._run_sync(lambda: [c.name for c in client.containers.all()])

    async def clone_container(self, source_image_alias: str, new_container_name: str, config: Optional[Dict[str, Any]] = None, profiles: Optional[List[str]] = None):
        """Clones a container from a source image.

        Args:
            source_image_alias: The alias of the source image.
            new_container_name: The name of the new container.
            config: A dictionary of configuration options for the new container.
            profiles: A list of profiles to apply to the new container.

        Returns:
            A dictionary containing the name and status of the new container.

        Raises:
            LXDServiceError: If the source image is not found or if the container
                fails to clone.
        """
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
        """Starts a container.

        Args:
            container_name: The name of the container to start.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If the container fails to start.
        """
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
        """Stops a container.

        Args:
            container_name: The name of the container to stop.
            force: Whether to force the container to stop.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If the container fails to stop.
        """
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
        """Deletes a container.

        Args:
            container_name: The name of the container to delete.

        Raises:
            LXDServiceError: If the container fails to delete.
        """
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
        """Gets the state of a container.

        Args:
            container_name: The name of the container.

        Returns:
            A dictionary containing the status and IP address of the container,
            or None if the container is not found.

        Raises:
            LXDServiceError: If the state fails to be retrieved.
        """
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
        """Gets the IP address of a container.

        This method retries several times to get the IP address.

        Args:
            container_name: The name of the container.

        Returns:
            The IP address of the container, or None if not found.
        """
        for _ in range(settings.LXD_IP_RETRY_ATTEMPTS):
            state = await self.get_container_state(container_name)
            if state and state.get("ip_address"):
                return state["ip_address"]
            await asyncio.sleep(settings.LXD_IP_RETRY_DELAY)
        return None

    async def push_file_to_container(self, container_name: str, target_path: str, content: bytes, mode=0o644, uid=0, gid=0):
        """Pushes a file to a container.

        Args:
            container_name: The name of the container.
            target_path: The path to the file in the container.
            content: The content of the file.
            mode: The file mode.
            uid: The user ID of the file owner.
            gid: The group ID of the file owner.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If the file fails to be pushed.
        """
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
        """Executes a command in a container.

        Args:
            container_name: The name of the container.
            command: The command to execute.
            environment: A dictionary of environment variables.

        Returns:
            A tuple containing the exit code, stdout, and stderr.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If the command fails to execute.
        """
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
        """Freezes a container.

        Args:
            container_name: The name of the container to freeze.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If the container fails to freeze.
        """
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
        """Unfreezes a container.

        Args:
            container_name: The name of the container to unfreeze.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If the container fails to unfreeze.
        """
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

    async def create_snapshot(self, container_name: str, snapshot_name: str):
        """Creates a snapshot of a container.

        Args:
            container_name: The name of the container.
            snapshot_name: The name for the snapshot.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If snapshot creation fails.
        """
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            await self._run_sync(container.snapshots.create, snapshot_name, wait=True)
            logger.info(f"Successfully created snapshot '{snapshot_name}' for container '{container_name}'.")
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error creating snapshot for '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to create snapshot: {e}")

    async def restore_snapshot(self, container_name: str, snapshot_name: str):
        """Restores a container from a snapshot.

        Args:
            container_name: The name of the container.
            snapshot_name: The name of the snapshot to restore.

        Raises:
            LXDContainerNotFoundError: If the container or snapshot is not found.
            LXDServiceError: If restoration fails.
        """
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            # pylxd's restore is a bit unintuitive. It's a method on the snapshot object.
            def _sync_restore():
                if snapshot_name not in container.snapshots.all():
                    raise LXDContainerNotFoundError(f"Snapshot '{snapshot_name}' not found for container '{container_name}'.")
                snapshot = container.snapshots.get(snapshot_name)
                snapshot.restore(wait=True)

            await self._run_sync(_sync_restore)
            logger.info(f"Successfully restored container '{container_name}' from snapshot '{snapshot_name}'.")
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error restoring snapshot for '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to restore snapshot: {e}")

    async def delete_snapshot(self, container_name: str, snapshot_name: str):
        """Deletes a snapshot from a container.

        Args:
            container_name: The name of the container.
            snapshot_name: The name of the snapshot to delete.

        Raises:
            LXDContainerNotFoundError: If the container or snapshot is not found.
            LXDServiceError: If deletion fails.
        """
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)

            def _sync_delete():
                if snapshot_name not in [s.name for s in container.snapshots.all()]:
                     raise LXDContainerNotFoundError(f"Snapshot '{snapshot_name}' not found for container '{container_name}'.")
                snapshot = container.snapshots.get(snapshot_name)
                snapshot.delete(wait=True)

            await self._run_sync(_sync_delete)
            logger.info(f"Successfully deleted snapshot '{snapshot_name}' from container '{container_name}'.")
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error deleting snapshot for '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to delete snapshot: {e}")

    async def list_snapshots(self, container_name: str) -> List[str]:
        """Lists all snapshots for a container.

        Args:
            container_name: The name of the container.

        Returns:
            A list of snapshot names.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
        """
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)
            return await self._run_sync(lambda: [s.name for s in container.snapshots.all()])
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")

    async def create_image_from_container(self, container_name: str, image_alias: str, public: bool = False, description: str = ""):
        """Creates a new LXD image from a container's snapshot.

        Args:
            container_name: The name of the source container.
            image_alias: The alias (name) for the new image.
            public: Whether the image should be public.
            description: A description for the new image.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If image creation fails.
        """
        client = await self._get_client()
        try:
            container = await self._run_sync(client.containers.get, container_name)

            def _sync_publish():
                image_config = {
                    'alias': image_alias,
                    'public': public,
                    'description': description or f"Image created from {container_name}"
                }
                # publish() creates a temporary snapshot, creates the image, then deletes the snapshot.
                return container.publish(wait=True, properties=image_config)

            image = await self._run_sync(_sync_publish)
            logger.info(f"Successfully created image '{image_alias}' from container '{container_name}'. Fingerprint: {image.fingerprint}")
            return image
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error creating image from container '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to create image: {e}")

lxd_service = LXDService()
