import asyncio
import logging
import os
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.services.git_service import git_service, GitServiceError
from app.services.lxd_service import lxd_service, LXDServiceError, LXDContainerNotFoundError
from app.crud import crud_island
from app.models.island import IslandStatusEnum
from app.metrics import SKYBLOCK_UPDATE_EVENTS
from app.services.websocket_manager import manager as websocket_manager

logger = logging.getLogger(__name__)

class UpdateService:
    """Orchestrates the update process for a single island."""

    async def update_island(self, db: AsyncSession, island_uuid: str, version_tag: str):
        """
        Manages the full update lifecycle for a single island.

        Args:
            db: The SQLAlchemy async session.
            island_uuid: The UUID of the island's owner to update.
            version_tag: The Git tag to update to.
        """
        island = await crud_island.get_island_by_player_uuid(db, player_uuid=island_uuid)
        if not island:
            logger.error(f"[Update] Island for player {island_uuid} not found.")
            return

        container_name = island.container_name
        snapshot_name = f"before-update-{version_tag}-{datetime.utcnow().strftime('%Y%m%d%H%M%S')}"

        try:
            # 1. Set status to PENDING_UPDATE
            await crud_island.update_island_status(db, island=island, status=IslandStatusEnum.PENDING_UPDATE)

            # 1.5. Send graceful shutdown command via WebSocket
            logger.info(f"[Update] Sending graceful shutdown command to {container_name} (owner: {island_uuid}).")
            shutdown_payload = {"event": "GRACEFUL_SHUTDOWN_FOR_UPDATE"}
            await websocket_manager.send_message_to_client(island_uuid, shutdown_payload)
            await asyncio.sleep(5) # Wait a few seconds for players to be kicked

            # 2. Stop the container
            logger.info(f"[Update] Stopping container {container_name} for update.")
            await lxd_service.stop_container(container_name)

            # 3. Create a snapshot for rollback
            logger.info(f"[Update] Creating snapshot {snapshot_name} for {container_name}.")
            await lxd_service.create_snapshot(container_name, snapshot_name)

            # 4. Prepare update files
            await git_service.clone_or_update_repo()
            await git_service.checkout_tag(version_tag)

            # 5. Set status to UPDATING and apply files
            await crud_island.update_island_status(db, island=island, status=IslandStatusEnum.UPDATING)

            # 6. Copy files to container
            # This is a simplified version. A real implementation would be more robust,
            # iterating through the git repo and handling directories.
            update_path = git_service.get_version_files_path()

            # Recursively copy all files and directories from the cloned repo to the container.
            update_path = git_service.get_version_files_path()
            server_root_in_container = "/opt/minecraft/server" # Assuming this is the server root

            for root, dirs, files in os.walk(update_path, topdown=True):
                # Exclude the .git directory from being copied
                dirs[:] = [d for d in dirs if d != '.git']

                for file in files:
                    source_file_path = os.path.join(root, file)
                    relative_path = os.path.relpath(source_file_path, update_path)
                    target_file_path = os.path.join(server_root_in_container, relative_path)

                    logger.info(f"[Update] Pushing file {relative_path} to {container_name}")
                    with open(source_file_path, "rb") as f:
                        await lxd_service.push_file_to_container(container_name, target_file_path, f.read())

            # 7. Start the container
            logger.info(f"[Update] Starting container {container_name} after update.")
            await lxd_service.start_container(container_name)

            # 8. Wait for heartbeat (not implemented here, will be handled by the heartbeat endpoint)
            # For now, we assume success after start. The final status will be set by the heartbeat.
            # We set it back to a neutral state like 'STOPPED' or 'RUNNING' after a while.
            # In a real scenario, we might set it to a state like 'AWAITING_HEARTBEAT'.

            # For now, let's assume it will boot up and report its status.
            # The heartbeat endpoint will be responsible for setting the final "RUNNING" state
            # and updating the version tag.
            logger.info(f"[Update] Island {container_name} update process initiated. Awaiting heartbeat for verification.")

        except (LXDServiceError, GitServiceError, LXDContainerNotFoundError) as e:
            logger.error(f"[Update] Update failed for {container_name}: {e}. Rolling back...")
            SKYBLOCK_UPDATE_EVENTS.labels(status='failure').inc()
            await self.rollback_island(db, island_uuid, snapshot_name)
        except Exception as e:
            logger.critical(f"[Update] An unexpected error occurred during update for {container_name}: {e}", exc_info=True)
            SKYBLOCK_UPDATE_EVENTS.labels(status='failure').inc()
            await self.rollback_island(db, island_uuid, snapshot_name)

    async def rollback_island(self, db: AsyncSession, island_uuid: str, snapshot_name: str):
        """
        Rolls back an island to its pre-update state using a snapshot.
        """
        island = await crud_island.get_island_by_player_uuid(db, player_uuid=island_uuid)
        if not island:
            return

        container_name = island.container_name
        try:
            logger.warning(f"[Rollback] Starting rollback for {container_name} from snapshot {snapshot_name}.")
            await crud_island.update_island_status(db, island=island, status=IslandStatusEnum.UPDATE_FAILED)

            await lxd_service.stop_container(container_name, force=True)
            await lxd_service.restore_snapshot(container_name, snapshot_name)
            await lxd_service.start_container(container_name)

            logger.info(f"[Rollback] Container {container_name} has been restored and started.")
        except (LXDServiceError, LXDContainerNotFoundError) as e:
            logger.error(f"[Rollback] Rollback failed for {container_name}: {e}. Setting status to ERROR.")
            await crud_island.update_island_status(db, island=island, status=IslandStatusEnum.ERROR)
        except Exception as e:
            logger.critical(f"[Rollback] An unexpected error occurred during rollback for {container_name}: {e}", exc_info=True)
            await crud_island.update_island_status(db, island=island, status=IslandStatusEnum.ERROR)


update_service = UpdateService()
