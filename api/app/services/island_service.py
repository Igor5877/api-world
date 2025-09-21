from typing import Optional
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload
from fastapi import BackgroundTasks
from app.core.config import settings
from app.services.websocket_manager import manager as websocket_manager
from app.services.lxd_service import lxd_service, LXDServiceError
from app.crud.crud_island import crud_island
from app.crud import crud_team
from app.schemas.island import IslandCreate, IslandResponse, IslandUpdate, IslandStatusEnum
from app.schemas.team import TeamCreate
from app.models.island import Island as IslandModel
from app.models.team import Team as TeamModel
import uuid
import asyncio
from datetime import datetime, timezone
import logging
import pathlib

logger = logging.getLogger(__name__)

class IslandService:
    def __init__(self):
        logger.info("IslandService: Initialized to use CRUD operations with DB session.")

    async def get_island_by_player_uuid(self, db_session: AsyncSession, *, player_uuid: str) -> IslandResponse | None:
        logger.debug(f"Service: Fetching island for player_uuid: {player_uuid}")
        island_db_model = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)
        if not island_db_model:
            logger.info(f"Service: Island not found for player_uuid: {player_uuid}")
            return None
        logger.info(f"Service: Island found for player_uuid: {player_uuid}, id: {island_db_model.id}")
        return IslandResponse.model_validate(island_db_model)

    async def create_new_solo_island(self, db_session: AsyncSession, *, player_uuid: str, player_name: str, background_tasks: BackgroundTasks) -> TeamModel:
        logger.info(f"Service: Explicitly creating a new solo island for player {player_uuid}.")
        if await crud_team.get_team_by_player(db=db_session, player_uuid=player_uuid):
            raise ValueError("Player is already in a team.")
        if await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid):
            raise ValueError("Player already has a legacy solo island.")

        safe_player_name = "".join(c if c.isalnum() else '_' for c in player_name)
        # We still need to parse the string UUID to get its hex part for the name
        team_name = f"island-of-{safe_player_name}-{uuid.UUID(player_uuid).hex[:8]}"
        team_create_data = TeamCreate(name=team_name, owner_uuid=player_uuid)

        team_db_model = await crud_team.create_team(db=db_session, team_in=team_create_data)
        await db_session.flush()

        container_name = f"skyblock-solo-{safe_player_name}-{uuid.UUID(player_uuid).hex[:8]}"
        new_island = await crud_island.create(
            db_session=db_session,
            team_id=team_db_model.id,
            container_name=container_name,
            player_uuid=player_uuid,
            player_name=player_name,
            initial_status=IslandStatusEnum.PENDING_CREATION
        )
        team_db_model.island = new_island
        db_session.add(team_db_model)
        
        await db_session.flush()
        await db_session.refresh(team_db_model, attribute_names=['island', 'members'])
        
        background_tasks.add_task(self._perform_lxd_clone_and_update_status, team_id=team_db_model.id, container_name=container_name)
        logger.info(f"Service: Background task scheduled for new solo island for player {player_uuid}.")
        
        return team_db_model

    async def _perform_lxd_clone_and_update_status(self, team_id: int, container_name: str):
        from app.db.session import AsyncSessionLocal
        async with AsyncSessionLocal() as db:
            try:
                result = await db.execute(
                    select(TeamModel)
                    .where(TeamModel.id == team_id)
                    .options(selectinload(TeamModel.island), selectinload(TeamModel.members))
                )
                team = result.scalars().first()

                if not team or not team.island:
                    logger.error(f"Clone task: Team {team_id} or its island not found.")
                    return

                logger.info(f"Clone task: Cloning '{settings.LXD_BASE_IMAGE}' to '{container_name}' for team {team_id}.")
                await lxd_service.clone_container(
                    source_image_alias=settings.LXD_BASE_IMAGE,
                    new_container_name=container_name,
                    profiles=settings.LXD_DEFAULT_PROFILES
                )

                member_uuids = [member.player_uuid for member in team.members]
                toml_content = f"is_island_server = true\n"
                toml_content += f"team_id = {team.id}\n"
                toml_content += f"owner_uuid = \"{team.owner_uuid}\"\n"
                toml_content += f"member_uuids = {member_uuids}\n"

                config_path = "/opt/minecraft/world/serverconfig/skyblock_island_data.toml"
                await lxd_service.push_file_to_container(container_name, config_path, toml_content.encode('utf-8'))
                
                updated_island = await crud_island.update(db, db_obj=team.island, obj_in={"status": IslandStatusEnum.STOPPED})
                await db.commit() # Commit the update
                for member_uuid in member_uuids:
                    await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), member_uuid)
                logger.info(f"Clone task: Island for team {team_id} successfully created and set to STOPPED.")

            except Exception as e:
                logger.error(f"Clone task for team {team_id} failed: {e}", exc_info=True)
                async with AsyncSessionLocal() as error_db:
                    island_to_update = await crud_island.get_by_team_id(error_db, team_id=team_id)
                    if island_to_update:
                        await crud_island.update(error_db, db_obj=island_to_update, obj_in={"status": IslandStatusEnum.ERROR_CREATE})
                        await error_db.commit()

    async def start_island_instance(self, db_session: AsyncSession, *, player_uuid: str, player_name: str, background_tasks: BackgroundTasks) -> IslandResponse:
        logger.info(f"Service: Player {player_uuid} attempting to start an island.")
        team = await crud_team.get_team_by_player(db=db_session, player_uuid=player_uuid)
        if team and team.island:
            return await self._start_existing_island(db_session, team.island, team, background_tasks)

        solo_island = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)
        if solo_island:
            return await self._start_existing_island(db_session, solo_island, None, background_tasks)

        raise ValueError("No island found for this player. Please create one first.")

    async def _start_existing_island(self, db_session: AsyncSession, island: IslandModel, team: Optional[TeamModel], background_tasks: BackgroundTasks) -> IslandResponse:
        current_status = island.status
        container_name = island.container_name

        if current_status == IslandStatusEnum.RUNNING:
            logger.info(f"Service: Island {container_name} is already RUNNING.")
        elif current_status in [IslandStatusEnum.STOPPED, IslandStatusEnum.FROZEN]:
            logger.info(f"Service: Starting island {container_name} from status {current_status.value}...")
            updated_island = await crud_island.update(db_session, db_obj=island, obj_in={"status": IslandStatusEnum.PENDING_START})
            await db_session.commit()
            if team:
                for member in team.members:
                    await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), member.player_uuid)
                background_tasks.add_task(self._perform_lxd_start_and_update_status, team_id=team.id, container_name=container_name, was_frozen=(current_status == IslandStatusEnum.FROZEN))
            else:
                player_uuid_str = str(island.player_uuid)
                await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), player_uuid_str)
                background_tasks.add_task(self._perform_solo_lxd_start_and_update_status, player_uuid_str=player_uuid_str, container_name=container_name, was_frozen=(current_status == IslandStatusEnum.FROZEN))
            return IslandResponse.model_validate(updated_island)
        elif current_status == IslandStatusEnum.PENDING_START:
            logger.info(f"Service: Island {container_name} is already PENDING_START.")
        else:
            raise ValueError(f"Island cannot be started from its current state: {current_status.value}")
        return IslandResponse.model_validate(island)

    async def _perform_solo_lxd_start_and_update_status(self, player_uuid_str: str, container_name: str, was_frozen: bool):
        from app.db.session import AsyncSessionLocal
        async with AsyncSessionLocal() as db:
            island = await crud_island.get_by_player_uuid(db, player_uuid=player_uuid_str)
            if not island: return

            try:
                if was_frozen: await lxd_service.unfreeze_container(container_name)
                await lxd_service.start_container(container_name)
                ip = await lxd_service.get_container_ip(container_name)
                if not ip: raise LXDServiceError("Failed to get IP for solo island.")
                
                update_data = {"status": IslandStatusEnum.RUNNING, "internal_ip_address": ip}
                updated_island = await crud_island.update(db, db_obj=island, obj_in=update_data)
                await db.commit()
                await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), player_uuid_str)
            except Exception as e:
                logger.error(f"Error starting solo island for {player_uuid_str}: {e}")
                updated_island = await crud_island.update(db, db_obj=island, obj_in={"status": IslandStatusEnum.ERROR_START})
                await db.commit()
                await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), player_uuid_str)

    async def _perform_lxd_start_and_update_status(self, team_id: int, container_name: str, was_frozen: bool):
        from app.db.session import AsyncSessionLocal
        async with AsyncSessionLocal() as db_session_bg:
            team = None
            try:
                result = await db_session_bg.execute(select(TeamModel).where(TeamModel.id == team_id).options(selectinload(TeamModel.island), selectinload(TeamModel.members)))
                team = result.scalars().first()
                if not team or not team.island:
                    logger.error(f"Service (background): Team or island not found for team_id {team_id}. Aborting start task.")
                    return

                logger.info(f"Service (background): Starting LXD ops for team {team_id}, container {container_name}")
                if was_frozen:
                    await lxd_service.unfreeze_container(container_name)
                if (await lxd_service.get_container_state(container_name)).get('status', '').lower() != 'running':
                    await lxd_service.start_container(container_name)

                ip_address = await lxd_service.get_container_ip(container_name)
                if not ip_address:
                    raise LXDServiceError("Failed to get IP address for container.")

                update_fields = {"internal_ip_address": ip_address, "internal_port": settings.DEFAULT_MC_PORT_INTERNAL, "status": IslandStatusEnum.RUNNING, "last_seen_at": datetime.now(timezone.utc)}
                updated_island = await crud_island.update(db_session_bg, db_obj=team.island, obj_in=update_fields)
                await db_session_bg.commit()
                for member in team.members:
                    await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), member.player_uuid)
                logger.info(f"Service (background): Island for team {team_id} is RUNNING. Awaiting ready signal from mod.")

            except Exception as e:
                logger.error(f"Service (background): Error during LXD start for team {team_id}: {e}", exc_info=True)
                if team and team.island:
                    updated_island = await crud_island.update(db_session_bg, db_obj=team.island, obj_in={"status": IslandStatusEnum.ERROR_START})
                    await db_session_bg.commit()
                    for member in team.members:
                         await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), member.player_uuid)

    async def stop_island_instance(self, db_session: AsyncSession, *, player_uuid: str, background_tasks: BackgroundTasks) -> IslandResponse:
        logger.info(f"Service: Player {player_uuid} attempting to stop an island.")
        team = await crud_team.get_team_by_player(db=db_session, player_uuid=player_uuid)
        if team and team.island:
            logger.info(f"Player {player_uuid} is in team {team.id}. Stopping team island {team.island.id}.")
            return await self._stop_existing_island(db_session, team.island, team, background_tasks)

        solo_island = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)
        if solo_island:
            logger.info(f"Player {player_uuid} has a legacy solo island {solo_island.id}. Stopping it.")
            return await self._stop_existing_island(db_session, solo_island, None, background_tasks)
        
        raise ValueError("No island found for this player to stop.")

    async def _stop_existing_island(self, db_session: AsyncSession, island: IslandModel, team: Optional[TeamModel], background_tasks: BackgroundTasks) -> IslandResponse:
        if island.status in [IslandStatusEnum.RUNNING, IslandStatusEnum.FROZEN, IslandStatusEnum.ERROR_START]:
            updated_island = await crud_island.update(db_session, db_obj=island, obj_in={"status": IslandStatusEnum.PENDING_STOP})
            await db_session.commit()
            if team:
                for member in team.members:
                    await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), member.player_uuid)
                background_tasks.add_task(self._perform_lxd_stop_and_update_status, team_id=team.id)
            else:
                player_uuid_str = str(island.player_uuid)
                await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), player_uuid_str)
                background_tasks.add_task(self._perform_solo_lxd_stop_and_update_status, player_uuid_str=player_uuid_str)
            
            return IslandResponse.model_validate(updated_island)
        elif island.status == IslandStatusEnum.STOPPED:
            logger.info(f"Island {island.container_name} is already stopped.")
            return IslandResponse.model_validate(island)
        else:
            raise ValueError(f"Island cannot be stopped from its current state: {island.status.value}")

    async def _perform_lxd_stop_and_update_status(self, team_id: int):
        from app.db.session import AsyncSessionLocal
        async with AsyncSessionLocal() as db:
            result = await db.execute(select(TeamModel).where(TeamModel.id == team_id).options(selectinload(TeamModel.island), selectinload(TeamModel.members)))
            team = result.scalars().first()
            if not team or not team.island: return
            try:
                await lxd_service.stop_container(team.island.container_name, force=True)
                update_fields = {"status": IslandStatusEnum.STOPPED, "internal_ip_address": None, "minecraft_ready": False}
                updated_island = await crud_island.update(db, db_obj=team.island, obj_in=update_fields)
                await db.commit()
                for member in team.members:
                    await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), member.player_uuid)
            except Exception as e:
                 logger.error(f"Error stopping team island for team {team_id}: {e}")

    async def _perform_solo_lxd_stop_and_update_status(self, player_uuid_str: str):
        from app.db.session import AsyncSessionLocal
        async with AsyncSessionLocal() as db:
            island = await crud_island.get_by_player_uuid(db, player_uuid=player_uuid_str)
            if not island: return
            try:
                await lxd_service.stop_container(island.container_name, force=True)
                update_fields = {"status": IslandStatusEnum.STOPPED, "internal_ip_address": None, "minecraft_ready": False}
                updated_island = await crud_island.update(db, db_obj=island, obj_in=update_fields)
                await db.commit()
                await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), player_uuid_str)
            except Exception as e:
                logger.error(f"Error stopping solo island for {player_uuid_str}: {e}")

    async def freeze_island_instance(self, db_session: AsyncSession, *, player_uuid: str, background_tasks: BackgroundTasks) -> IslandResponse:
        logger.info(f"Service: Player {player_uuid} attempting to freeze an island.")
        team = await crud_team.get_team_by_player(db=db_session, player_uuid=player_uuid)
        if team and team.island:
            return await self._freeze_existing_island(db_session, team.island, team, background_tasks)
        solo_island = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)
        if solo_island:
            return await self._freeze_existing_island(db_session, solo_island, None, background_tasks)
        raise ValueError("No island found for this player to freeze.")

    async def _freeze_existing_island(self, db_session: AsyncSession, island: IslandModel, team: Optional[TeamModel], background_tasks: BackgroundTasks) -> IslandResponse:
        if island.status == IslandStatusEnum.RUNNING:
            updated_island = await crud_island.update(db_session, db_obj=island, obj_in={"status": IslandStatusEnum.PENDING_FREEZE})
            await db_session.commit()
            if team:
                for member in team.members:
                    await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), member.player_uuid)
                background_tasks.add_task(self._perform_lxd_freeze_and_update_status, team_id=team.id)
            else:
                player_uuid_str = str(island.player_uuid)
                await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), player_uuid_str)
                background_tasks.add_task(self._perform_solo_lxd_freeze_and_update_status, player_uuid_str=player_uuid_str)
            return IslandResponse.model_validate(updated_island)
        elif island.status == IslandStatusEnum.FROZEN:
            logger.info(f"Island {island.container_name} is already frozen.")
            return IslandResponse.model_validate(island)
        else:
            raise ValueError(f"Island cannot be frozen from its current state: {island.status.value}")

    async def _perform_lxd_freeze_and_update_status(self, team_id: int):
        from app.db.session import AsyncSessionLocal
        async with AsyncSessionLocal() as db:
            result = await db.execute(select(TeamModel).where(TeamModel.id == team_id).options(selectinload(TeamModel.island), selectinload(TeamModel.members)))
            team = result.scalars().first()
            if not team or not team.island: return
            try:
                await lxd_service.freeze_container(team.island.container_name)
                updated_island = await crud_island.update(db, db_obj=team.island, obj_in={"status": IslandStatusEnum.FROZEN})
                await db.commit()
                for member in team.members:
                    await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), member.player_uuid)
            except Exception as e:
                 logger.error(f"Error freezing island for team {team_id}: {e}")

    async def _perform_solo_lxd_freeze_and_update_status(self, player_uuid_str: str):
        from app.db.session import AsyncSessionLocal
        async with AsyncSessionLocal() as db:
            island = await crud_island.get_by_player_uuid(db, player_uuid=player_uuid_str)
            if not island: return
            try:
                await lxd_service.freeze_container(island.container_name)
                updated_island = await crud_island.update(db, db_obj=island, obj_in={"status": IslandStatusEnum.FROZEN})
                await db.commit()
                await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), player_uuid_str)
            except Exception as e:
                logger.error(f"Error freezing solo island for {player_uuid_str}: {e}")

    async def rename_team(self, db: AsyncSession, *, team: TeamModel, new_name: str) -> TeamModel:
        from app.schemas.team import Team as TeamSchema
        existing_name = await crud_team.get_team_by_name(db, name=new_name)
        if existing_name and existing_name.id != team.id:
            raise ValueError("A team with this name already exists.")
        updated_team = await crud_team.rename_team(db, team=team, new_name=new_name)
        await db.commit()
        await db.refresh(updated_team, relationships=["members", "island"])

        logger.info(f"Service: Team {team.id} renamed to '{new_name}'. Notifying members.")
        team_response = TeamSchema.model_validate(updated_team)
        for member in updated_team.members:
            await websocket_manager.send_personal_message(
                {"event": "team_updated", "data": team_response.model_dump()},
                member.player_uuid
            )
        return updated_team

    async def mark_island_as_ready_for_players(self, db_session: AsyncSession, *, team_id: int):
        logger.info(f"Service: Attempting to mark island as ready for team_id: {team_id}")
        island_db_model = await crud_island.get_by_team_id(db_session, team_id=team_id)
        if not island_db_model:
            raise ValueError("Island not found for this team.")
        if island_db_model.status != IslandStatusEnum.RUNNING:
            raise ValueError("Island is not in RUNNING state.")
        if island_db_model.minecraft_ready:
            raise ValueError("Island already marked as ready.")

        updated_island = await crud_island.update(db_session, db_obj=island_db_model, obj_in={"minecraft_ready": True})
        await db_session.commit()
        
        result = await db_session.execute(select(TeamModel).where(TeamModel.id == team_id).options(selectinload(TeamModel.members)))
        team = result.scalars().first()
        if team:
            for member in team.members:
                await websocket_manager.send_personal_message(IslandResponse.model_validate(updated_island).model_dump_json(), member.player_uuid)
        logger.info(f"Service: Island for team {team_id} marked as ready.")

    async def handle_join_team(self, db_session: AsyncSession, *, player_to_join_uuid: str, team_to_join: TeamModel, background_tasks: BackgroundTasks):
        from app.schemas.team import Team as TeamSchema
        for member in team_to_join.members:
            if member.player_uuid == player_to_join_uuid:
                raise ValueError("Player is already in this team.")

        old_island = await crud_island.get_by_player_uuid(db_session, player_uuid=player_to_join_uuid)
        await crud_team.add_member(db=db_session, team=team_to_join, player_uuid=player_to_join_uuid)
        await db_session.commit()
        logger.info(f"Service: Player {player_to_join_uuid} added to team {team_to_join.id}.")

        if old_island:
            logger.info(f"Service: Player {player_to_join_uuid} has an old island (ID: {old_island.id}) that will be deleted.")
            background_tasks.add_task(self._perform_lxd_delete_and_cleanup, island_id=old_island.id, player_uuid_to_notify=player_to_join_uuid)
        
        await db_session.refresh(team_to_join, relationships=["members", "island"])
        
        logger.info(f"Service: Notifying team {team_to_join.id} about the new member.")
        team_response = TeamSchema.model_validate(team_to_join)
        for member in team_to_join.members:
            await websocket_manager.send_personal_message(
                {"event": "team_updated", "data": team_response.model_dump()},
                member.player_uuid
            )

        return team_to_join

    async def _perform_lxd_delete_and_cleanup(self, island_id: int, player_uuid_to_notify: Optional[str] = None):
        from app.db.session import AsyncSessionLocal
        async with AsyncSessionLocal() as db_session_bg:
            try:
                island = await crud_island.get(db_session_bg, island_id=island_id)
                if not island:
                    logger.warning(f"Service (background): Island {island_id} not found for deletion.")
                    return

                logger.info(f"Service (background): Deleting container {island.container_name} for island {island_id}")
                await lxd_service.delete_container(island.container_name, force=True)

                logger.info(f"Service (background): Deleting island record {island_id} from database.")
                await crud_island.remove_by_id(db_session_bg, island_id=island_id)
                await db_session_bg.commit()
                logger.info(f"Service (background): Island {island_id} fully deleted.")

                if player_uuid_to_notify:
                    await websocket_manager.send_personal_message(
                        {"event": "island_deleted", "data": {"island_id": island_id}},
                        player_uuid_to_notify
                    )
                    logger.info(f"Service (background): Notified player {player_uuid_to_notify} of island deletion.")

            except Exception as e:
                logger.error(f"Service (background): Error during island deletion for island_id {island_id}: {e}", exc_info=True)

island_service = IslandService()
