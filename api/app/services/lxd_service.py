import asyncio
import logging
import os
import pathlib
from typing import Optional, Dict, Any, List, Tuple
from functools import partial
import pylxd
from pylxd.exceptions import LXDAPIException, NotFound
from app.core.config import settings

logger = logging.getLogger(__name__)

class LXDServiceError(Exception):
    pass

class LXDContainerNotFoundError(LXDServiceError):
    pass

class LXDService:
    LXDServiceError = LXDServiceError
    LXDContainerNotFoundError = LXDContainerNotFoundError

    def __init__(self):
        self.client: Optional[pylxd.Client] = None
        if not settings.LXD_SOCKET_PATH:
            logger.warning("LXDService: LXD_SOCKET_PATH not configured.")
        else:
            try:
                self.client = pylxd.Client(endpoint=settings.LXD_SOCKET_PATH, project=settings.LXD_PROJECT)
            except Exception as e:
                self.client = None

    async def _run_sync(self, func, *args, **kwargs):
        if not self.client: raise LXDServiceError("LXD client not available.")
        loop = asyncio.get_running_loop()
        bound_func = partial(func, *args, **kwargs)
        return await loop.run_in_executor(None, bound_func)

    async def pull_directory_to_host(self, container_name: str, container_path: str, host_path: pathlib.Path):
        logger.info(f"Pulling directory '{container_path}' from '{container_name}' to host path '{host_path}'")
        def _sync_pull():
            container = self.client.containers.get(container_name)
            exit_code, stdout, stderr = container.execute(['find', container_path, '-type', 'f'])
            if exit_code != 0:
                raise LXDServiceError(f"Could not list files in {container_path}: {stderr}")
            
            files_to_pull = stdout.strip().split('\n')
            for file_path in files_to_pull:
                if not file_path: continue
                relative_path = os.path.relpath(file_path, container_path)
                local_target_path = host_path / relative_path
                local_target_path.parent.mkdir(parents=True, exist_ok=True)
                file_content, _ = container.files.get(file_path)
                with open(local_target_path, 'wb') as f:
                    f.write(file_content)
        try:
            await self._run_sync(_sync_pull)
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            raise LXDServiceError(f"Failed to pull directory: {e}")

    async def pull_directory_as_tar(self, container_name: str, container_path: str) -> bytes:
        logger.info(f"LXDService: Creating tarball of '{container_path}' in container '{container_name}'.")
        temp_archive_path = f"/tmp/backup-{int(asyncio.get_running_loop().time())}.tar.gz"
        tar_command = ["tar", "-czf", temp_archive_path, "-C", container_path, "."]
        try:
            exit_code, _, stderr = await self.execute_command_in_container(container_name, tar_command)
            if exit_code != 0:
                raise LXDServiceError(f"Failed to create tarball in container. Stderr: {stderr}")
            
            def _sync_pull_file():
                container = self.client.containers.get(container_name)
                content, _ = container.files.get(temp_archive_path)
                return content
            
            tar_bytes = await self._run_sync(_sync_pull_file)
            return tar_bytes
        finally:
            try:
                await self.execute_command_in_container(container_name, ["rm", temp_archive_path])
            except Exception as cleanup_error:
                logger.warning(f"Failed to cleanup temporary archive in container: {cleanup_error}")

    async def push_file_to_container(self, container_name: str, target_path: str, content: bytes, mode: Optional[int] = None, uid: Optional[int] = None, gid: Optional[int] = None) -> bool:
        def _sync_push_file():
            container = self.client.containers.get(container_name)
            kwargs = {k: v for k, v in {'mode': mode, 'uid': uid, 'gid': gid}.items() if v is not None}
            try:
                container.files.put(target_path, content, **kwargs)
            except LXDAPIException as e:
                if 'no such file or directory' in str(e).lower():
                    parent_dir = "/".join(target_path.split('/')[:-1])
                    if parent_dir:
                        container.execute(['mkdir', '-p', parent_dir])
                        container.files.put(target_path, content, **kwargs)
                else:
                    raise
            return True
        try:
            return await self._run_sync(_sync_push_file)
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            raise LXDServiceError(f"Error pushing file: {e}")
    
    async def clone_container(self, source_image_alias: str, new_container_name: str, config: Optional[Dict[str, Any]] = None, profiles: Optional[List[str]] = None) -> Dict[str, Any]:
        if profiles is None: profiles = ["default"]
        def _sync_clone():
            if not self.client.images.exists(source_image_alias, alias=True): raise LXDServiceError(f"Source image '{source_image_alias}' not found.")
            container_config = {'name': new_container_name, 'source': {'type': 'image', 'alias': source_image_alias}, 'profiles': profiles}
            if config: container_config['config'] = config
            if self.client.containers.exists(new_container_name): container = self.client.containers.get(new_container_name)
            else: container = self.client.containers.create(container_config, wait=True)
            return {"name": container.name, "status": container.status, "ephemeral": container.ephemeral}
        try: return await self._run_sync(_sync_clone)
        except Exception as e: raise LXDServiceError(f"Error cloning container: {e}")

    async def start_container(self, container_name: str) -> bool:
        def _sync_start():
            try:
                container = self.client.containers.get(container_name)
                if container.status.lower() == 'running': return True
                container.start(wait=True, timeout=settings.LXD_OPERATION_TIMEOUT)
                return True
            except NotFound: raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        try: return await self._run_sync(_sync_start)
        except Exception as e: raise LXDServiceError(f"Error starting container: {e}")

    async def stop_container(self, container_name: str, force: bool = True, timeout: Optional[int] = None) -> bool:
        stop_timeout = timeout if timeout is not None else settings.LXD_OPERATION_TIMEOUT
        def _sync_stop():
            try:
                container = self.client.containers.get(container_name)
                if container.status.lower() == 'stopped': return True
                container.stop(wait=True, force=force, timeout=stop_timeout)
                return True
            except NotFound: raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        try: return await self._run_sync(_sync_stop)
        except Exception as e: raise LXDServiceError(f"Error stopping container: {e}")
    
    async def delete_container(self, container_name: str, stop_if_running: bool = True) -> bool:
        async def _sync_delete_wrapper():
            if not await self._run_sync(self.client.containers.exists, container_name): return False
            container = await self._run_sync(self.client.containers.get, container_name)
            if stop_if_running and container.status.lower() != 'stopped':
                await self.stop_container(container_name, force=True)
            await self._run_sync(container.delete, wait=True)
            return True
        try: return await _sync_delete_wrapper()
        except Exception as e: raise LXDServiceError(f"Error deleting container: {e}")

    async def create_snapshot(self, container_name: str, snapshot_name: str):
        def _sync_create():
            container = self.client.containers.get(container_name)
            container.snapshots.create(snapshot_name, wait=True)
        try: await self._run_sync(_sync_create)
        except NotFound: raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e: raise LXDServiceError(f"Error creating snapshot: {e}")

    async def restore_snapshot(self, container_name: str, snapshot_name: str):
        def _sync_restore():
            container = self.client.containers.get(container_name)
            snapshot = container.snapshots.get(snapshot_name)
            snapshot.restore(wait=True)
        try: await self._run_sync(_sync_restore)
        except NotFound: raise LXDContainerNotFoundError(f"Container or snapshot '{snapshot_name}' not found.")
        except Exception as e: raise LXDServiceError(f"Error restoring snapshot: {e}")
        
    async def delete_snapshot(self, container_name: str, snapshot_name: str):
        def _sync_delete():
            container = self.client.containers.get(container_name)
            snapshot = container.snapshots.get(snapshot_name)
            snapshot.delete(wait=True)
        try: await self._run_sync(_sync_delete)
        except NotFound: logger.warning(f"Snapshot '{snapshot_name}' not found for deletion.")
        except Exception as e: raise LXDServiceError(f"Error deleting snapshot: {e}")

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

