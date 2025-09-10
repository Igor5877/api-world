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
    # Make exceptions accessible as class attributes
    LXDServiceError = LXDServiceError
    LXDContainerNotFoundError = LXDContainerNotFoundError

    def __init__(self):
        self.client: Optional[pylxd.Client] = None
        self._connect_lock = asyncio.Lock() # To ensure client is initialized once if needed concurrently
        logger.info("LXDService: Initialized for pylxd.")
        if not settings.LXD_SOCKET_PATH:
            logger.warning("LXDService: LXD_SOCKET_PATH not configured. Service will not be able to connect to LXD.")
        else:
            # Initialize client synchronously here, as pylxd.Client() is cheap
            # and doesn't do I/O until methods are called.
            try:
                self.client = pylxd.Client(
                    endpoint=settings.LXD_SOCKET_PATH,
                    project=settings.LXD_PROJECT
                )
                logger.info(f"LXDService: pylxd.Client initialized for project '{settings.LXD_PROJECT}' on socket {settings.LXD_SOCKET_PATH}.")
                # You can test connectivity if necessary, e.g., by listing profiles
                # self.client.profiles.all() # This would be a blocking call
            except Exception as e:
                logger.error(f"LXDService: Failed to initialize pylxd.Client: {e}")
                self.client = None


    async def _run_sync(self, func, *args, **kwargs):
        """Helper to run synchronous pylxd calls in a thread pool."""
        if not self.client:
            # This can happen if client init failed or LXD_SOCKET_PATH was not set
            logger.error("LXDService: LXD client is not available.")
            raise LXDServiceError("LXD client not available. Check configuration and LXD service.")
        
        loop = asyncio.get_running_loop()
        # Use partial to bind arguments to the function before sending to thread
        bound_func = partial(func, *args, **kwargs)
        return await loop.run_in_executor(None, bound_func)

    # Note: pylxd client doesn't have an explicit close() method for socket connections.
    # Connections are typically managed per request by the underlying requests library.
    # So, a specific close() method for the service might not be needed unless
    # we were managing a persistent connection differently.

    async def list_container_names(self) -> List[str]:
        """Lists names of all containers."""
        def _sync_list_container_names():
            return [c.name for c in self.client.containers.all()]
        try:
            return await self._run_sync(_sync_list_container_names)
        except LXDAPIException as e:
            logger.error(f"LXDService: LXD API error listing containers: {e}")
            raise LXDServiceError(f"LXD API error listing containers: {e}")
        except Exception as e: # Catch other potential errors like connection issues if client init was deferred
            logger.error(f"LXDService: Error listing containers: {e}")
            raise LXDServiceError(f"Error listing containers: {e}")

    async def clone_container(self, source_image_alias: str, new_container_name: str, config: Optional[Dict[str, Any]] = None, profiles: Optional[List[str]] = None) -> Dict[str, Any]:
        """
        Clones a new container from a source image using pylxd.
        """
        if profiles is None:
            profiles = ["default"]

        logger.info(f"LXDService: Attempting to clone '{source_image_alias}' to '{new_container_name}' with profiles {profiles} using pylxd.")

        def _sync_clone():
            if not self.client.images.exists(source_image_alias, alias=True):
                logger.error(f"LXDService: Source image '{source_image_alias}' not found.")
                raise LXDServiceError(f"Source image '{source_image_alias}' not found.")

            container_config_pylxd = {
                'name': new_container_name,
                'source': {'type': 'image', 'alias': source_image_alias},
                'profiles': profiles,
            }
            if config: # pylxd uses 'config' key for container config, not top-level
                container_config_pylxd['config'] = config 

            if self.client.containers.exists(new_container_name):
                logger.warning(f"LXDService: Container '{new_container_name}' already exists. Returning existing.")
                container = self.client.containers.get(new_container_name)
            else:
                logger.info(f"LXDService: Creating container '{new_container_name}'...")
                container = self.client.containers.create(container_config_pylxd, wait=True)
                logger.info(f"LXDService: Container '{new_container_name}' created from '{source_image_alias}'.")
            
            return {"name": container.name, "status": container.status, "ephemeral": container.ephemeral}

        try:
            return await self._run_sync(_sync_clone)
        except NotFound as e: # pylxd specific not found
            logger.error(f"LXDService: LXD NotFound error (e.g. image alias) cloning container '{new_container_name}': {e}")
            raise LXDServiceError(f"LXD NotFound error cloning container: {e}")
        except LXDAPIException as e:
            logger.error(f"LXDService: LXD API error cloning container '{new_container_name}': {e}")
            raise LXDServiceError(f"LXD API error cloning container: {e}")
        except Exception as e:
            logger.error(f"LXDService: Unexpected error cloning container '{new_container_name}': {e}")
            raise LXDServiceError(f"Unexpected error cloning container: {e}")

    async def start_container(self, container_name: str) -> bool:
        logger.info(f"LXDService: Attempting to start container '{container_name}'.")
        def _sync_start():
            try:
                container = self.client.containers.get(container_name)
                if container.status.lower() == 'running':
                    logger.info(f"LXDService: Container '{container_name}' is already running.")
                    return True
                container.start(wait=True, timeout=settings.LXD_OPERATION_TIMEOUT)
                logger.info(f"LXDService: Container '{container_name}' started successfully.")
                return True
            except NotFound:
                logger.error(f"LXDService: Container '{container_name}' not found for starting.")
                raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        try:
            return await self._run_sync(_sync_start)
        except LXDContainerNotFoundError: # Re-raise specific error
             raise
        except LXDAPIException as e:
            logger.error(f"LXDService: LXD API error starting container '{container_name}': {e}")
            raise LXDServiceError(f"LXD API error starting container: {e}")
        except Exception as e:
            logger.error(f"LXDService: Error starting container '{container_name}': {e}")
            raise LXDServiceError(f"Error starting container: {e}")

    async def stop_container(self, container_name: str, force: bool = True, timeout: Optional[int] = None) -> bool: # pylxd default force=True
        stop_timeout = timeout if timeout is not None else settings.LXD_OPERATION_TIMEOUT
        logger.info(f"LXDService: Attempting to stop container '{container_name}' (force={force}, timeout={stop_timeout}).")
        def _sync_stop():
            try:
                container = self.client.containers.get(container_name)
                if container.status.lower() == 'stopped':
                    logger.info(f"LXDService: Container '{container_name}' is already stopped.")
                    return True
                container.stop(wait=True, force=force, timeout=stop_timeout)
                logger.info(f"LXDService: Container '{container_name}' stopped successfully.")
                return True
            except NotFound:
                logger.error(f"LXDService: Container '{container_name}' not found for stopping.")
                raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        try:
            return await self._run_sync(_sync_stop)
        except LXDContainerNotFoundError: # Re-raise specific error
             raise
        except LXDAPIException as e:
            logger.error(f"LXDService: LXD API error stopping container '{container_name}': {e}")
            raise LXDServiceError(f"LXD API error stopping container: {e}")
        except Exception as e:
            logger.error(f"LXDService: Error stopping container '{container_name}': {e}")
            raise LXDServiceError(f"Error stopping container: {e}")

    async def delete_container(self, container_name: str, stop_if_running: bool = True) -> bool:
        logger.info(f"LXDService: Attempting to delete container '{container_name}'.")
        async def _sync_delete_wrapper(): # Need async wrapper for await self.stop_container
            try:
                # Check existence with _run_sync first to avoid awaiting stop_container for non-existent one
                if not await self._run_sync(self.client.containers.exists, container_name):
                    logger.warning(f"LXDService: Container '{container_name}' not found for deletion. Assuming already deleted.")
                    return False

                container = await self._run_sync(self.client.containers.get, container_name)
                if stop_if_running and container.status.lower() != 'stopped':
                    logger.info(f"LXDService: Container '{container_name}' is not stopped. Stopping before deletion...")
                    await self.stop_container(container_name, force=True) # This is already an async method using _run_sync
                
                await self._run_sync(container.delete, wait=True)
                logger.info(f"LXDService: Container '{container_name}' deleted successfully.")
                return True
            except NotFound: # Should be caught by exists check, but as safeguard
                logger.warning(f"LXDService: Container '{container_name}' not found for deletion (during get/delete).")
                return False
        
        try:
            return await _sync_delete_wrapper()
        except LXDContainerNotFoundError: # From stop_container if called
             logger.warning(f"LXDService: Container '{container_name}' not found during pre-delete stop attempt.")
             return False # Or handle as appropriate
        except LXDAPIException as e:
            logger.error(f"LXDService: LXD API error deleting container '{container_name}': {e}")
            raise LXDServiceError(f"LXD API error deleting container: {e}")
        except Exception as e:
            logger.error(f"LXDService: Error deleting container '{container_name}': {e}")
            raise LXDServiceError(f"Error deleting container: {e}")

    async def get_container_state(self, container_name: str) -> Optional[Dict[str, Any]]:
        logger.debug(f"LXDService: Getting state for container '{container_name}'.")
        def _sync_get_state():
            try:
                container = self.client.containers.get(container_name)
                # pylxd's container.state() is a blocking call that returns a ContainerState object
                state = container.state() 
                
                ip_address: Optional[str] = None
                device_name = 'eth0' # Default, adjust if needed
                if state.network and device_name in state.network:
                    for addr_info in state.network[device_name].get('addresses', []):
                        if addr_info['family'].lower() == 'inet' and addr_info['scope'].lower() == 'global':
                            ip_address = addr_info['address']
                            break
                
                return {
                    "name": container.name,
                    "status": state.status,
                    "status_code": state.status_code,
                    "ip_address": ip_address,
                    "pid": state.pid,
                    "ephemeral": container.ephemeral,
                    "profiles": container.profiles,
                    "description": container.description,
                }
            except NotFound:
                logger.warning(f"LXDService: Container '{container_name}' not found when getting state (sync).")
                return None # Explicitly return None if not found inside sync part
        
        try:
            result = await self._run_sync(_sync_get_state)
            if result is None and await self._run_sync(self.client.containers.exists, container_name) is False: # Double check for log clarity
                 logger.warning(f"LXDService: Confirmed container '{container_name}' not found when getting state.")
            return result
        except LXDAPIException as e: # Should be caught by _sync_get_state's try/except for NotFound
            logger.error(f"LXDService: LXD API error getting state for '{container_name}': {e}")
            raise LXDServiceError(f"LXD API error getting state for '{container_name}': {e}")
        except Exception as e:
            logger.error(f"LXDService: Error getting state for '{container_name}': {e}")
            raise LXDServiceError(f"Error getting state for '{container_name}': {e}")

    async def get_container_ip(self, container_name: str, interface: str = 'eth0', family: str = 'inet', scope: str = 'global') -> Optional[str]:
        logger.debug(f"LXDService: Attempting to get IP for container '{container_name}' on interface '{interface}'.")
        
        async def _sync_get_ip_with_retry(): # Needs to be async to use asyncio.sleep
            try:
                # Initial check if container exists and is running
                container_exists = await self._run_sync(self.client.containers.exists, container_name)
                if not container_exists:
                    logger.error(f"LXDService: Container '{container_name}' not found when trying to get IP.")
                    raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")

                container_status = (await self.get_container_state(container_name)).get('status', '').lower()
                if container_status != "running":
                    logger.warning(f"Container '{container_name}' is not running (status: {container_status}). Cannot get IP.")
                    return None

                for attempt in range(settings.LXD_IP_RETRY_ATTEMPTS):
                    state_data = await self.get_container_state(container_name) # Re-fetch state
                    if state_data and state_data.get('ip_address'): # get_container_state now extracts IP
                        ip = state_data['ip_address']
                        # Verify family and scope if necessary, though get_container_state should handle it
                        logger.info(f"Found IP {ip} for {container_name} on attempt {attempt + 1}")
                        return ip
                    
                    if attempt < settings.LXD_IP_RETRY_ATTEMPTS - 1:
                        logger.debug(f"IP not found for {container_name} on attempt {attempt + 1}, retrying in {settings.LXD_IP_RETRY_DELAY}s...")
                        await asyncio.sleep(settings.LXD_IP_RETRY_DELAY)
                
                logger.warning(f"Could not find IP address for container '{container_name}' after {settings.LXD_IP_RETRY_ATTEMPTS} attempts.")
                return None
            except NotFound: # Should be caught by exists check
                logger.error(f"LXDService: Container '{container_name}' not found (sync) when trying to get IP.")
                raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        
        try:
            return await _sync_get_ip_with_retry()
        except LXDContainerNotFoundError:
            raise
        except Exception as e:
            logger.error(f"LXDService: Error getting IP for '{container_name}': {e}")
            raise LXDServiceError(f"Error getting IP for '{container_name}': {e}")

    async def execute_command_in_container(self, container_name: str, command: List[str], environment: Optional[Dict[str, str]] = None) -> Tuple[int, str, str]:
        logger.info(f"LXDService: Executing command {command} in container '{container_name}'.")
        def _sync_execute():
            try:
                container = self.client.containers.get(container_name)
                if container.status.lower() != "running":
                    logger.error(f"LXDService: Container '{container_name}' is not running. Cannot execute command.")
                    # This error should ideally be raised to stop further processing
                    raise LXDServiceError(f"Container '{container_name}' is not running.")
                
                # pylxd's execute returns (exit_code, stdout, stderr)
                result = container.execute(command, environment=environment)
                logger.debug(f"LXDService: Command in '{container_name}' exited with code {result[0]}. Stdout: {result[1][:100]}..., Stderr: {result[2][:100]}...")
                return result
            except NotFound:
                logger.error(f"LXDService: Container '{container_name}' not found for command execution.")
                raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        
        try:
            return await self._run_sync(_sync_execute)
        except LXDContainerNotFoundError: # Re-raise specific error
             raise
        except LXDServiceError as e: # Catch "not running" error from _sync_execute
            logger.error(f"LXDService: Service error executing command in '{container_name}': {e}")
            raise
        except LXDAPIException as e:
            logger.error(f"LXDService: LXD API error executing command in '{container_name}': {e}")
            raise LXDServiceError(f"LXD API error executing command: {e}")
        except Exception as e:
            logger.error(f"LXDService: Error executing command in '{container_name}': {e}")
            raise LXDServiceError(f"Error executing command: {e}")

    async def freeze_container(self, container_name: str) -> bool:
        logger.info(f"LXDService: Attempting to freeze container '{container_name}'.")
        def _sync_freeze():
            try:
                container = self.client.containers.get(container_name)
                if container.status.lower() == 'frozen':
                    logger.info(f"Container '{container_name}' is already frozen.")
                    return True
                if container.status.lower() != 'running':
                    logger.warning(f"Container '{container_name}' is not running, cannot freeze. Status: {container.status}")
                    return False
                container.freeze(wait=True, timeout=settings.LXD_OPERATION_TIMEOUT)
                logger.info(f"LXDService: Container '{container_name}' frozen successfully.")
                return True
            except NotFound:
                logger.error(f"LXDService: Container '{container_name}' not found for freezing.")
                raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        try:
            return await self._run_sync(_sync_freeze)
        except LXDContainerNotFoundError:
            raise
        except Exception as e:
            logger.error(f"LXDService: Error freezing container '{container_name}': {e}")
            raise LXDServiceError(f"Error freezing container: {e}")

    async def unfreeze_container(self, container_name: str) -> bool:
        logger.info(f"LXDService: Attempting to unfreeze container '{container_name}'.")
        def _sync_unfreeze():
            try:
                container = self.client.containers.get(container_name)
                if container.status.lower() != 'frozen':
                    logger.warning(f"Container '{container_name}' is not frozen. Status: {container.status}")
                    return False
                container.unfreeze(wait=True, timeout=settings.LXD_OPERATION_TIMEOUT)
                logger.info(f"LXDService: Container '{container_name}' unfrozen successfully.")
                return True
            except NotFound:
                logger.error(f"LXDService: Container '{container_name}' not found for unfreezing.")
                raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        try:
            return await self._run_sync(_sync_unfreeze)
        except LXDContainerNotFoundError:
            raise
        except Exception as e:
            logger.error(f"LXDService: Error unfreezing container '{container_name}': {e}")
            raise LXDServiceError(f"Error unfreezing container: {e}")

    async def push_file_to_container(self, container_name: str, target_path: str, content: bytes, mode: Optional[int] = None, uid: Optional[int] = None, gid: Optional[int] = None) -> bool:
        logger.info(f"LXDService: Attempting to push file to '{target_path}' in container '{container_name}'.")
        
        def _sync_push_file():
            try:
                container = self.client.containers.get(container_name)
                # pylxd's files.put uses keyword arguments for mode, uid, gid
                kwargs = {}
                if mode is not None:
                    kwargs['mode'] = mode
                if uid is not None:
                    kwargs['uid'] = uid
                if gid is not None:
                    kwargs['gid'] = gid
                
                container.files.put(target_path, content, **kwargs)
                logger.info(f"LXDService: File successfully pushed to '{target_path}' in container '{container_name}'.")
                return True
            except NotFound:
                logger.error(f"LXDService: Container '{container_name}' not found for pushing file.")
                raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
            except LXDAPIException as e:
                # Check if the error is because the parent directory doesn't exist
                if 'no such file or directory' in str(e).lower() or 'failed to create file' in str(e).lower():
                    # Attempt to create parent directory
                    # This is a common issue. LXD's files.put doesn't create parent dirs.
                    # We need to execute a mkdir command.
                    logger.warning(f"LXDService: Failed to push file, target path '{target_path}' likely missing parent directory. Attempting to create.")
                    parent_dir = "/".join(target_path.split('/')[:-1])
                    if parent_dir: # Ensure parent_dir is not empty (e.g. if target_path is in root)
                        try:
                            # Execute mkdir -p to create parent directories
                            # This is a synchronous call within _sync_push_file, so it's okay
                            mkdir_command = ['mkdir', '-p', parent_dir]
                            logger.info(f"LXDService: Executing {mkdir_command} in {container_name}")
                            exit_code, stdout, stderr = container.execute(mkdir_command)
                            if exit_code == 0:
                                logger.info(f"LXDService: Successfully created parent directory '{parent_dir}'. Retrying file push.")
                                # Retry pushing the file
                                container.files.put(target_path, content, **kwargs)
                                logger.info(f"LXDService: File successfully pushed to '{target_path}' after creating parent directory.")
                                return True
                            else:
                                logger.error(f"LXDService: Failed to create parent directory '{parent_dir}'. Exit code: {exit_code}, stderr: {stderr}")
                                raise LXDServiceError(f"Failed to create parent directory '{parent_dir}' for file push: {stderr}")
                        except Exception as mkdir_e:
                            logger.error(f"LXDService: Exception during mkdir attempt for '{parent_dir}': {mkdir_e}")
                            raise LXDServiceError(f"Exception during mkdir for file push: {mkdir_e}")
                    else: # No parent directory to create, original error stands
                        raise # Re-raise the original LXDAPIException
                else: # Other LXDAPIException
                    raise

        try:
            return await self._run_sync(_sync_push_file)
        except LXDContainerNotFoundError:
            raise
        except LXDAPIException as e: # Catch LXDAPIException re-raised from _sync_push_file or new ones
            logger.error(f"LXDService: LXD API error pushing file to '{target_path}' in '{container_name}': {e}")
            raise LXDServiceError(f"LXD API error pushing file: {e}")
        except Exception as e:
            logger.error(f"LXDService: Error pushing file to '{target_path}' in '{container_name}': {e}")
            raise LXDServiceError(f"Error pushing file: {e}")
    async def create_snapshot(self, container_name: str, snapshot_name: str):
        logger.info(f"Creating snapshot '{snapshot_name}' for container '{container_name}'...")
        def _sync_create_snapshot():
            try:
                container = self.client.containers.get(container_name)
                container.snapshots.create(snapshot_name, wait=True)
                logger.info(f"Snapshot '{snapshot_name}' created successfully for '{container_name}'.")
            except NotFound:
                raise LXDContainerNotFoundError(f"Container '{container_name}' not found for creating snapshot.")
            except LXDAPIException as e:
                raise LXDServiceError(f"LXD API error creating snapshot: {e}")
        try:
            await self._run_sync(_sync_create_snapshot)
        except (LXDContainerNotFoundError, LXDServiceError):
            raise
        except Exception as e:
            raise LXDServiceError(f"Unexpected error creating snapshot: {e}")

    async def restore_snapshot(self, container_name: str, snapshot_name: str):
        logger.info(f"Restoring container '{container_name}' from snapshot '{snapshot_name}'...")
        def _sync_restore_snapshot():
            try:
                container = self.client.containers.get(container_name)
                # Find the snapshot object first
                snapshot = None
                for s in container.snapshots.all():
                    if s.name == f"{container_name}/{snapshot_name}":
                        snapshot = s
                        break
                if not snapshot:
                     raise LXDServiceError(f"Snapshot '{snapshot_name}' not found in container '{container_name}'.")
                
                snapshot.restore(wait=True)
                logger.info(f"Container '{container_name}' restored successfully from '{snapshot_name}'.")
            except NotFound:
                raise LXDContainerNotFoundError(f"Container '{container_name}' not found for restoring snapshot.")
            except LXDAPIException as e:
                raise LXDServiceError(f"LXD API error restoring snapshot: {e}")
        try:
            await self._run_sync(_sync_restore_snapshot)
        except (LXDContainerNotFoundError, LXDServiceError):
            raise
        except Exception as e:
            raise LXDServiceError(f"Unexpected error restoring snapshot: {e}")

    async def delete_snapshot(self, container_name: str, snapshot_name: str):
        logger.info(f"Deleting snapshot '{snapshot_name}' from container '{container_name}'...")
        def _sync_delete_snapshot():
            try:
                container = self.client.containers.get(container_name)
                # Find the snapshot object
                snapshot = None
                # Snapshot names in pylxd are often prefixed with container name, e.g., "my-container/my-snapshot"
                full_snapshot_name = f"{container_name}/{snapshot_name}"
                for s in container.snapshots.all():
                    if s.name == full_snapshot_name:
                        snapshot = s
                        break
                
                if snapshot:
                    snapshot.delete(wait=True)
                    logger.info(f"Snapshot '{snapshot_name}' deleted successfully from '{container_name}'.")
                else:
                    logger.warning(f"Snapshot '{snapshot_name}' not found for deletion in '{container_name}'.")
            except NotFound:
                logger.warning(f"Container '{container_name}' not found for deleting snapshot. Assuming it's already gone.")
            except LXDAPIException as e:
                raise LXDServiceError(f"LXD API error deleting snapshot: {e}")
        try:
            await self._run_sync(_sync_delete_snapshot)
        except LXDServiceError:
            raise
        except Exception as e:
            raise LXDServiceError(f"Unexpected error deleting snapshot: {e}")

# Instantiate the service for use in other parts of the application
lxd_service = LXDService()


# Example usage (for testing or direct script execution):
async def _main_example():
    logging.basicConfig(level=logging.INFO)
    # settings.LXD_SOCKET_PATH = "/var/snap/lxd/common/lxd/unix.socket" # For local testing
    # settings.LXD_BASE_IMAGE = "your-ubuntu-image-alias" # For local testing
    # settings.LXD_OPERATION_TIMEOUT = 30
    # settings.LXD_IP_RETRY_ATTEMPTS = 5
    # settings.LXD_IP_RETRY_DELAY = 2


    if not settings.LXD_SOCKET_PATH or not settings.LXD_BASE_IMAGE:
        logger.error("LXD_SOCKET_PATH and LXD_BASE_IMAGE must be set in settings or environment for this example.")
        return
    
    # Re-initialize lxd_service if settings were just manually set for testing script
    global lxd_service
    if not lxd_service.client: # If it failed in global scope due to missing settings
        lxd_service = LXDService()
        if not lxd_service.client:
            logger.error("Failed to initialize LXDService client for example run.")
            return


    test_container_name = "test-pylxd-skyblock-02"

    try:
        logger.info(f"Available containers: {await lxd_service.list_container_names()}")

        logger.info(f"Cloning {settings.LXD_BASE_IMAGE} to {test_container_name}...")
        clone_info = await lxd_service.clone_container(settings.LXD_BASE_IMAGE, test_container_name, profiles=settings.LXD_DEFAULT_PROFILES or ["default"])
        logger.info(f"Clone info: {clone_info}")

        logger.info(f"Starting {test_container_name}...")
        await lxd_service.start_container(test_container_name)

        state = await lxd_service.get_container_state(test_container_name)
        logger.info(f"State of {test_container_name}: {state}")
        ip = await lxd_service.get_container_ip(test_container_name)
        logger.info(f"IP of {test_container_name}: {ip}")
        
        if ip: # Only try to execute if we have an IP / it's running
            exit_code, stdout, stderr = await lxd_service.execute_command_in_container(test_container_name, ["ls", "-la", "/"])
            logger.info(f"Command ls -la / in {test_container_name}: Exit={exit_code}, STDOUT='{stdout[:200]}...', STDERR='{stderr[:200]}...'")

        logger.info(f"Stopping {test_container_name}...")
        await lxd_service.stop_container(test_container_name)
        
        state_stopped = await lxd_service.get_container_state(test_container_name)
        logger.info(f"State of {test_container_name} after stop: {state_stopped}")

    except LXDContainerNotFoundError:
        logger.warning(f"Container {test_container_name} was not found at some point.")
    except LXDServiceError as e:
        logger.error(f"LXDServiceError during example: {e}")
    except Exception as e:
        logger.error(f"An unexpected error occurred during LXD interaction example: {e}", exc_info=True)
    finally:
        try:
            # Ensure container exists before trying to delete, to prevent error if clone failed
            if await lxd_service._run_sync(lxd_service.client.containers.exists, test_container_name):
                 logger.info(f"Attempting to delete {test_container_name} as cleanup...")
                 deleted = await lxd_service.delete_container(test_container_name, stop_if_running=True)
                 if deleted:
                     logger.info(f"Successfully deleted {test_container_name}.")
                 else:
                     logger.info(f"{test_container_name} was not found for deletion (delete returned False).")
            else:
                logger.info(f"Test container {test_container_name} does not exist, no need to delete.")

        except LXDServiceError as e:
            logger.error(f"Cleanup error: Failed to delete {test_container_name}: {e}")
        except Exception as e: # Catch any other error during cleanup
            logger.error(f"Unexpected cleanup error for {test_container_name}: {e}", exc_info=True)
        
        # No explicit client.close() for pylxd socket connections
        logger.info("LXD Service example finished (pylxd).")

if __name__ == "__main__":
    # Example: LXD_SOCKET_PATH=/var/snap/lxd/common/lxd/unix.socket LXD_BASE_IMAGE=your-base-image-alias python -m app.services.lxd_service
    asyncio.run(_main_example())
