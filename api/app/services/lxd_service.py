import asyncio
import json
import logging
import os
import pathlib
from datetime import datetime, timedelta, timezone
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

    # ── Auto-update system helpers ────────────────────────────────────

    async def _run_lxc(self, *args: str, timeout: int = 600) -> str:
        """Runs the lxc CLI (needed for recursive file operations that pylxd lacks).

        Args:
            *args: Arguments passed to the lxc binary.
            timeout: Seconds before the command is killed.

        Returns:
            The command's stdout.

        Raises:
            LXDServiceError: If the command fails or times out.
        """
        cmd = [settings.LXC_BIN, *args]
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
        except asyncio.TimeoutError:
            proc.kill()
            raise LXDServiceError(f"lxc command timed out: {' '.join(cmd)}")
        except FileNotFoundError:
            raise LXDServiceError(f"lxc binary not found at '{settings.LXC_BIN}'.")
        if proc.returncode != 0:
            raise LXDServiceError(f"lxc command failed ({' '.join(cmd)}): {stderr.decode(errors='replace').strip()}")
        return stdout.decode(errors="replace")

    def _project_args(self) -> List[str]:
        """Returns --project args for the lxc CLI when a non-default project is configured."""
        if settings.LXD_PROJECT and settings.LXD_PROJECT != "default":
            return ["--project", settings.LXD_PROJECT]
        return []

    def _project_params(self) -> Dict[str, str]:
        """Returns the ?project= query param for raw client.api calls.

        client.containers.get() applies the client's configured project
        automatically, but the raw client.api.* node does not — callers must
        add it explicitly or LXD looks the container up in "default".
        """
        if settings.LXD_PROJECT and settings.LXD_PROJECT != "default":
            return {"project": settings.LXD_PROJECT}
        return {}

    async def create_snapshot(self, container_name: str, snapshot_name: str, expiry_days: Optional[int] = None):
        """Creates a stateless snapshot of a container, optionally with auto-expiry.

        Args:
            container_name: The name of the container.
            snapshot_name: The name of the snapshot.
            expiry_days: If set, LXD deletes the snapshot automatically after this many days.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If the snapshot fails to be created.
        """
        client = await self._get_client()

        def _sync_snapshot():
            payload: Dict[str, Any] = {"name": snapshot_name, "stateful": False}
            if expiry_days:
                expires = datetime.now(timezone.utc) + timedelta(days=expiry_days)
                payload["expires_at"] = expires.strftime("%Y-%m-%dT%H:%M:%SZ")
            response = client.api.instances[container_name].snapshots.post(
                json=payload, params=self._project_params())
            operation = response.json().get("operation")
            if operation:
                client.operations.wait_for_operation(operation)

        try:
            await self._run_sync(_sync_snapshot)
            logger.info(f"LXDService: Snapshot '{snapshot_name}' created for '{container_name}'.")
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            if "already exists" in str(e).lower():
                # A previous attempt for this same campaign already took this
                # snapshot before failing later in the pipeline — reuse it.
                logger.warning(f"LXDService: Snapshot '{snapshot_name}' for '{container_name}' "
                              f"already exists (retry of a previous attempt) — reusing it.")
                return
            logger.error(f"Error creating snapshot for '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to create snapshot: {e}")

    async def list_snapshots(self, container_name: str) -> List[Dict[str, Any]]:
        """Lists the snapshots of a container.

        Args:
            container_name: The name of the container.

        Returns:
            A list of dicts with name, created_at and expires_at.

        Raises:
            LXDContainerNotFoundError: If the container is not found.
            LXDServiceError: If the listing fails.
        """
        client = await self._get_client()

        def _sync_list():
            response = client.api.instances[container_name].snapshots.get(
                params={"recursion": "1", **self._project_params()})
            return [
                {
                    "name": snap["name"].split("/")[-1],
                    "created_at": snap.get("created_at"),
                    "expires_at": snap.get("expires_at"),
                }
                for snap in response.json().get("metadata", [])
            ]

        try:
            return await self._run_sync(_sync_list)
        except NotFound:
            raise LXDContainerNotFoundError(f"Container '{container_name}' not found.")
        except Exception as e:
            logger.error(f"Error listing snapshots for '{container_name}': {e}", exc_info=True)
            raise LXDServiceError(f"Failed to list snapshots: {e}")

    async def list_directory(self, container_name: str, path: str) -> List[str]:
        """Lists entry names of a directory inside a container.

        Works for both running and stopped containers (LXD file API).

        Args:
            container_name: The name of the container.
            path: The absolute directory path inside the container.

        Returns:
            A list of entry names; an empty list if the directory does not exist.
        """
        url = f"/1.0/instances/{container_name}/files?path={path}"
        if settings.LXD_PROJECT and settings.LXD_PROJECT != "default":
            url += f"&project={settings.LXD_PROJECT}"
        try:
            output = await self._run_lxc("query", url)
            entries = json.loads(output)
            return entries if isinstance(entries, list) else []
        except LXDServiceError as e:
            if "not found" in str(e).lower() or "no such file" in str(e).lower():
                return []
            raise

    async def push_directory(self, container_name: str, local_dir: str, container_parent_dir: str,
                             delete_extra: bool = False):
        """Recursively pushes a local directory into a container.

        Example: push_directory(c, "/repo/mods", "/opt/minecraft") copies the
        whole mods/ tree into /opt/minecraft/mods inside the container.
        Works for both running and stopped containers.

        Args:
            container_name: The name of the container.
            local_dir: The local directory to push (its basename is kept).
            container_parent_dir: The directory inside the container to push into.
            delete_extra: If True, files present in the container dir but absent
                locally are deleted first (used only for mods/ so stale .jar
                files don't stay loaded).

        Raises:
            LXDServiceError: If the push fails.
        """
        local_path = pathlib.Path(local_dir)
        if not local_path.is_dir():
            raise LXDServiceError(f"Local directory '{local_dir}' does not exist.")

        target_dir = f"{container_parent_dir.rstrip('/')}/{local_path.name}"

        if delete_extra:
            local_entries = {entry.name for entry in local_path.iterdir()}
            for name in await self.list_directory(container_name, target_dir):
                if name not in local_entries:
                    logger.info(f"LXDService: Deleting stale '{target_dir}/{name}' in '{container_name}'.")
                    await self.delete_file(container_name, f"{target_dir}/{name}")

        await self._run_lxc(
            "file", "push", "--recursive", "--create-dirs",
            str(local_path), f"{container_name}{container_parent_dir.rstrip('/')}/",
            *self._project_args(),
        )
        logger.info(f"LXDService: Pushed '{local_dir}' -> '{container_name}:{target_dir}'.")

    async def pull_file(self, container_name: str, container_path: str, local_path: str) -> bool:
        """Pulls a single file from a container to the host.

        Args:
            container_name: The name of the container.
            container_path: The absolute file path inside the container.
            local_path: The host destination path (parent dirs are created).

        Returns:
            True if the file was pulled, False if it does not exist in the container.
        """
        os.makedirs(os.path.dirname(local_path), exist_ok=True)
        try:
            await self._run_lxc(
                "file", "pull", f"{container_name}{container_path}", local_path,
                *self._project_args(),
            )
            return True
        except LXDServiceError as e:
            if "not found" in str(e).lower() or "no such file" in str(e).lower():
                return False
            raise

    async def delete_file(self, container_name: str, container_path: str):
        """Deletes a file or directory inside a container (running or stopped).

        Missing files are ignored.

        Args:
            container_name: The name of the container.
            container_path: The absolute path inside the container.
        """
        try:
            await self._run_lxc(
                "file", "delete",
                f"{container_name}{container_path}",
                *self._project_args(),
            )
        except LXDServiceError as e:
            if "not found" in str(e).lower() or "no such file" in str(e).lower():
                return
            raise

    async def backup_files(self, container_name: str, container_paths: List[str], host_backup_dir: str) -> List[str]:
        """Copies specific container files to a host-side backup directory.

        Used before soft/hard updates so the previous versions can be restored
        without touching the world. Files that do not exist yet (newly added by
        the update) are skipped.

        Args:
            container_name: The name of the container.
            container_paths: Absolute file paths inside the container.
            host_backup_dir: Host directory to store the files in (created if missing).

        Returns:
            The list of container paths that were actually backed up.
        """
        backed_up: List[str] = []
        for container_path in container_paths:
            relative = container_path.lstrip("/")
            local_path = os.path.join(host_backup_dir, relative)
            if await self.pull_file(container_name, container_path, local_path):
                backed_up.append(container_path)
        logger.info(f"LXDService: Backed up {len(backed_up)}/{len(container_paths)} files from '{container_name}' to '{host_backup_dir}'.")
        return backed_up

    async def restore_files(self, container_name: str, host_backup_dir: str, container_paths: List[str]):
        """Restores files from a host-side backup directory into a container.

        Args:
            container_name: The name of the container.
            host_backup_dir: The host directory created by backup_files().
            container_paths: Absolute container paths to restore (must exist in the backup).
        """
        for container_path in container_paths:
            relative = container_path.lstrip("/")
            local_path = os.path.join(host_backup_dir, relative)
            if not os.path.isfile(local_path):
                logger.warning(f"LXDService: Backup file missing, skipping restore of '{container_path}'.")
                continue
            await self._run_lxc(
                "file", "push", "--create-dirs", local_path, f"{container_name}{container_path}",
                *self._project_args(),
            )
        logger.info(f"LXDService: Restored {len(container_paths)} files into '{container_name}' from '{host_backup_dir}'.")

    async def publish_container_image(self, container_name: str, image_alias: str):
        """Publishes a (stopped) container as an image, replacing the alias.

        Used after the template container is updated so new islands are created
        from the fresh files.

        Args:
            container_name: The name of the template container.
            image_alias: The image alias to (re)point at the new image.
        """
        await self._run_lxc(
            "publish", container_name, "--alias", image_alias, "--reuse", "--force",
            *self._project_args(),
            timeout=1800,
        )
        logger.info(f"LXDService: Published '{container_name}' as image '{image_alias}'.")

lxd_service = LXDService()
