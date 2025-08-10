import asyncio
import logging
import os
import pathlib
import random
import shutil
import tarfile
import uuid
from datetime import datetime, timezone
from typing import Optional

from fastapi import BackgroundTasks
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.crud.crud_island import crud_island
from app.crud.crud_update_queue import crud_update_queue
from app.models.island import Island as IslandModel
from app.schemas.island import (IslandCreate, IslandResponse, IslandStatusEnum)
from app.services.lxd_service import LXDServiceError, lxd_service
from app.services.websocket_manager import manager as websocket_manager

logger = logging.getLogger(__name__)

class IslandService:
    def __init__(self):
        logger.info("IslandService: Initialized.")

    async def get_island_by_player_uuid(self, db_session: AsyncSession, *, player_uuid: uuid.UUID) -> Optional[IslandResponse]:
        island = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)
        return IslandResponse.model_validate(island) if island else None

    async def create_new_island(
        self, db_session: AsyncSession, *, island_create_data: IslandCreate, background_tasks: BackgroundTasks
    ) -> IslandResponse:
        if await crud_island.get_by_player_uuid(db_session, player_uuid=island_create_data.player_uuid):
            raise ValueError("Island already exists for this player.")
        
        safe_player_name = "".join(c if c.isalnum() or c in ['-'] else '_' for c in island_create_data.player_name)
        container_name = f"skyblock-{safe_player_name or 'player'}-{str(island_create_data.player_uuid)}"
        
        island_db_model = await crud_island.create(
            db_session=db_session, island_in=island_create_data, container_name=container_name,
            initial_status=IslandStatusEnum.PENDING_CREATION
        )
        await websocket_manager.send_personal_message(IslandResponse.model_validate(island_db_model).model_dump_json(), str(island_db_model.player_uuid))
        
        background_tasks.add_task(self._perform_lxd_clone_and_update_status, str(island_db_model.player_uuid), container_name)
        return IslandResponse.model_validate(island_db_model)

    async def _perform_lxd_clone_and_update_status(self, player_uuid: str, container_name: str):
        from app.db.session import AsyncSessionLocal
        async with AsyncSessionLocal() as db:
            try:
                await lxd_service.clone_container(settings.LXD_BASE_IMAGE, container_name, profiles=settings.LXD_DEFAULT_PROFILES)
                skyblock_toml = f"is_island_server = true\ncreator_uuid = \"{player_uuid}\"\n"
                await lxd_service.push_file_to_container(container_name, "/opt/minecraft/world/serverconfig/skyblock_island_data.toml", skyblock_toml.encode('utf-8'))
                template = (pathlib.Path(__file__).parent.parent / "templates" / "playersync-common.template.toml").read_text()
                playersync_content = template.replace("{{SERVER_ID}}", str(random.randint(100000, 999999)))
                await lxd_service.push_file_to_container(container_name, "/opt/minecraft/config/playersync-common.toml", playersync_content.encode('utf-8'))
                updated_island = await crud_island.update_status(db, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.STOPPED, extra_fields={"minecraft_ready": False})
                if updated_island:
                    await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), player_uuid)
            except Exception as e:
                logger.error(f"Error during LXD clone for {player_uuid}: {e}", exc_info=True)
                await crud_island.update_status(db, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_CREATE)
    
    async def queue_island_for_update(self, db_session: AsyncSession, *, player_uuid: uuid.UUID):
        island = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)
        if not island:
            raise ValueError("Island not found.")
        await crud_update_queue.add_island_to_queue(db_session, island=island)

    async def queue_all_islands_for_update(self, db_session: AsyncSession) -> int:
        all_islands = await crud_island.get_multi(db_session, limit=10000)
        count = 0
        for island in all_islands:
            try:
                await crud_update_queue.add_island_to_queue(db_session, island=island)
                count += 1
            except ValueError as e:
                logger.warning(f"Could not queue island {island.id} for update: {e}")
        return count

    async def _synchronous_stop(self, db_session: AsyncSession, island: "IslandModel"):
        logger.info(f"Synchronously stopping island {island.container_name}...")
        try:
            state = await lxd_service.get_container_state(island.container_name)
            if state and state.get('status', '').lower() in ['running', 'frozen']:
                await lxd_service.stop_container(island.container_name, force=True)
            await crud_island.update_status(db_session, player_uuid=uuid.UUID(island.player_uuid), status=IslandStatusEnum.STOPPED, extra_fields={"internal_ip_address": None, "minecraft_ready": False})
        except Exception as e:
            await crud_island.update_status(db_session, player_uuid=uuid.UUID(island.player_uuid), status=IslandStatusEnum.ERROR)
            raise

    async def _synchronous_start_and_wait(self, db_session: AsyncSession, island: "IslandModel"):
        logger.info(f"Synchronously starting island {island.container_name}...")
        try:
            await lxd_service.start_container(island.container_name)
            ip = await lxd_service.get_container_ip(island.container_name)
            if not ip: raise LXDServiceError("Failed to get IP.")
            await crud_island.update_status(db_session, player_uuid=uuid.UUID(island.player_uuid), status=IslandStatusEnum.RUNNING, extra_fields={"internal_ip_address": ip, "minecraft_ready": False})
            for _ in range(180):
                await asyncio.sleep(1)
                reloaded = await crud_island.get(db_session, island_id=island.id)
                if reloaded and reloaded.minecraft_ready:
                    logger.info(f"Island {island.id} is ready.")
                    return
            raise TimeoutError("Island did not become ready in 180 seconds.")
        except Exception as e:
            await crud_island.update_status(db_session, player_uuid=uuid.UUID(island.player_uuid), status=IslandStatusEnum.ERROR_START)
            raise

    async def _perform_file_based_update(self, db_session: AsyncSession, island: "IslandModel"):
        logger.info(f"Starting FILE-BASED update for island {island.id}...")
        was_running = island.status == IslandStatusEnum.RUNNING
        snapshot_name = f"update-snapshot-{island.id}-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"
        try:
            await lxd_service.create_snapshot(island.container_name, snapshot_name)
            if island.status != IslandStatusEnum.STOPPED:
                await self._synchronous_stop(db_session, island)
            await lxd_service.push_file_from_host(
                container_name=island.container_name,
                source_path=settings.ISLAND_UPDATE_FILE_SOURCE_PATH,
                target_path=settings.ISLAND_UPDATE_FILE_TARGET_PATH
            )
            if was_running:
                await self._synchronous_start_and_wait(db_session, island)
            await lxd_service.delete_snapshot(island.container_name, snapshot_name)
        except Exception as e:
            try:
                await lxd_service.restore_snapshot(island.container_name, snapshot_name)
                if was_running: await self._synchronous_start_and_wait(db_session, island)
                else: await crud_island.update_status(db_session, player_uuid=uuid.UUID(island.player_uuid), status=IslandStatusEnum.STOPPED)
            except Exception as rollback_e:
                logger.critical(f"CRITICAL: Rollback for island {island.id} FAILED: {rollback_e}")
                await crud_island.update_status(db_session, player_uuid=uuid.UUID(island.player_uuid), status=IslandStatusEnum.ERROR)
            finally:
                await lxd_service.delete_snapshot(island.container_name, snapshot_name)
            raise e

    async def _perform_image_based_update(self, db_session: AsyncSession, island: "IslandModel"):
        logger.info(f"Starting IMAGE-BASED update for island {island.id} using host backup strategy.")
        temp_dir = pathlib.Path(f"/tmp/skyblock-temp-{island.player_uuid}")
        backup_archive_path = pathlib.Path(f"/tmp/skyblock-backup-{island.player_uuid}-{int(asyncio.get_running_loop().time())}.tar.gz")
        data_path_in_container = "/opt/minecraft/world"
        original_info = {"id": island.id, "player_uuid": island.player_uuid, "player_name": island.player_name, "container_name": island.container_name}
        try:
            if island.status != IslandStatusEnum.STOPPED:
                await self._synchronous_stop(db_session, island)
            if temp_dir.exists(): shutil.rmtree(temp_dir)
            temp_dir.mkdir()
            await lxd_service.pull_directory_to_host(original_info['container_name'], data_path_in_container, temp_dir)
            with tarfile.open(backup_archive_path, "w:gz") as tar:
                tar.add(str(temp_dir), arcname='.')
        except Exception as e:
            raise LXDServiceError(f"Backup failed for island {original_info['id']}: {e}")
        finally:
            if temp_dir.exists(): shutil.rmtree(temp_dir)
        try:
            await lxd_service.delete_container(original_info['container_name'])
            await crud_island.remove_by_id(db_session, island_id=original_info['id'])
        except Exception as e:
            logger.critical(f"DESTRUCTION FAILED for island {original_info['id']}. Data is safe at {backup_archive_path}")
            raise LXDServiceError(f"Destruction failed. Data at {backup_archive_path}. Error: {e}")
        new_island = None
        try:
            create_schema = IslandCreate(player_uuid=uuid.UUID(original_info['player_uuid']), player_name=original_info['player_name'])
            new_name = f"skyblock-{original_info['player_name']}-{uuid.uuid4().hex[:8]}"
            new_island = await crud_island.create(db_session, island_in=create_schema, container_name=new_name, initial_status=IslandStatusEnum.UPDATING)
            await lxd_service.clone_container(settings.LXD_NEW_BASE_IMAGE, new_name, profiles=settings.LXD_DEFAULT_PROFILES)
            with open(backup_archive_path, "rb") as f:
                backup_bytes = f.read()
            await lxd_service.push_tar_to_directory(new_name, backup_bytes, data_path_in_container)
            skyblock_toml = f"is_island_server = true\ncreator_uuid = \"{new_island.player_uuid}\"\n"
            await lxd_service.push_file_to_container(new_name, "/opt/minecraft/world/serverconfig/skyblock_island_data.toml", skyblock_toml.encode('utf-8'))
            await self._synchronous_start_and_wait(db_session, new_island)
        except Exception as e:
            logger.critical(f"REBUILD FAILED for player {original_info['player_uuid']}. Data is safe at {backup_archive_path}")
            if new_island:
                await crud_island.update_status(db_session, player_uuid=uuid.UUID(new_island.player_uuid), status=IslandStatusEnum.UPDATE_FAILED)
            raise LXDServiceError(f"Rebuild failed. Data at {backup_archive_path}. Error: {e}")
        finally:
            if backup_archive_path.exists(): os.remove(backup_archive_path)

    async def perform_island_update(self, db_session: AsyncSession, *, queue_entry):
        island = await crud_island.get(db_session, island_id=queue_entry.island_id)
        if not island: raise ValueError("Island to update not found.")
        await crud_island.update_status(db_session, player_uuid=uuid.UUID(island.player_uuid), status=IslandStatusEnum.UPDATING)
        try:
            if settings.UPDATE_STRATEGY == 'image':
                await self._perform_image_based_update(db_session, island)
            else:
                await self._perform_file_based_update(db_session, island)
            await crud_island.update_status(db_session, player_uuid=uuid.UUID(island.player_uuid), status=IslandStatusEnum.STOPPED)
        except Exception as e:
            await crud_island.update_status(db_session, player_uuid=uuid.UUID(island.player_uuid), status=IslandStatusEnum.UPDATE_FAILED)
            raise

island_service = IslandService()
