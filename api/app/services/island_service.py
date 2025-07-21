from sqlalchemy.ext.asyncio import AsyncSession
from fastapi import BackgroundTasks
from app.core.config import settings
from app.services.lxd_service import lxd_service
from app.crud.crud_island import crud_island
from app.crud.crud_island_queue_ops import crud_main_island_queue
from app.schemas.island import IslandCreate, IslandResponse, IslandUpdate, IslandStatusEnum
from app.models.island import Island as IslandModel
from app.services.creation_worker import trigger_creation_worker
from app.services.start_worker import trigger_start_worker
import uuid
import asyncio
from datetime import datetime, timezone
import logging
import pathlib # Added for path manipulation
# import random - already added in the previous change for this method, ensure it's there

logger = logging.getLogger(__name__)

class IslandService:
    def __init__(self):
        logger.info("IslandService: Initialized to use CRUD operations with DB session.")

    async def get_island_by_player_uuid(self, db_session: AsyncSession, *, player_uuid: uuid.UUID) -> IslandResponse | None:
        """
        Retrieves island details for a given player UUID using CRUD operations.
        """
        logger.debug(f"Service: Fetching island for player_uuid: {player_uuid}")
        island_db_model = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)

        if not island_db_model:
            logger.info(f"Service: Island not found for player_uuid: {player_uuid}")
            return None

        logger.info(f"Service: Island found for player_uuid: {player_uuid}, id: {island_db_model.id}")
        return IslandResponse.model_validate(island_db_model)

    async def create_new_island(
        self, db_session: AsyncSession, *, island_create_data: IslandCreate, background_tasks: BackgroundTasks
    ) -> IslandResponse:
        """
        Handles the business logic for creating a new island using CRUD operations.
        """
        logger.info(f"Service: Attempting to create new island for player_uuid: {island_create_data.player_uuid}")
        existing_island = await crud_island.get_by_player_uuid(db_session, player_uuid=island_create_data.player_uuid)
        if existing_island:
            logger.warning(f"Service: Island creation conflict for player_uuid: {island_create_data.player_uuid}")
            raise ValueError("Island already exists for this player.")

        running_islands_count = await crud_island.get_running_islands_count(db_session)
        if running_islands_count >= settings.MAX_RUNNING_SERVERS:
            logger.info(f"Service: Max running servers limit reached ({settings.MAX_RUNNING_SERVERS}). Queuing island creation for player_uuid: {island_create_data.player_uuid}")
            await crud_main_island_queue.add_to_queue(db_session, player_uuid=island_create_data.player_uuid, player_name=island_create_data.player_name)
            raise ValueError("Max running servers limit reached. Island creation queued.")

        # Sanitize player_name for use in container name (simple sanitization)
        # Replace spaces and other potentially problematic characters with underscores
        # LXD is generally quite permissive, but it's good practice.
        # A more robust solution might involve a whitelist of characters or more complex regex.
        safe_player_name = "".join(c if c.isalnum() or c in ['-'] else '_' for c in island_create_data.player_name)
        if not safe_player_name: # Handle cases where player_name is all non-alphanumeric
            safe_player_name = "player"

        container_name = f"skyblock-{safe_player_name}-{str(island_create_data.player_uuid)}"
        logger.info(f"Service: Generated container name: {container_name}")

        try:
            island_db_model = await crud_island.create(
                db_session=db_session,
                island_in=island_create_data,
                container_name=container_name,
                initial_status=IslandStatusEnum.PENDING_CREATION
            )
            logger.info(f"Service: Island record created in DB for {island_db_model.player_uuid}, status PENDING_CREATION. ID: {island_db_model.id}")
        except Exception as e:
            logger.error(f"Service: DB error during initial island record creation for {island_create_data.player_uuid}: {e}")
            raise ValueError(f"Failed to create initial island record in database: {e}")

        background_tasks.add_task(
            self._perform_lxd_clone_and_update_status,
            player_uuid=str(island_db_model.player_uuid), # Pass UUID as string
            container_name=container_name
        )
        logger.info(f"Service: Background task scheduled for LXD cloning for island {island_db_model.id}.")

        return IslandResponse.model_validate(island_db_model)

    async def _perform_lxd_clone_and_update_status(self, player_uuid: str, container_name: str):
        """
        Actual LXD cloning and DB status update, designed to be run in a background task.
        This method MUST acquire its own database session.
        """
        from app.db.session import AsyncSessionLocal

        async with AsyncSessionLocal() as db_session_bg:
            try:
                logger.info(f"Service (background): Cloning '{settings.LXD_BASE_IMAGE}' to '{container_name}' for {player_uuid} with profiles {settings.LXD_DEFAULT_PROFILES}...")
                # Assuming LXD_DEFAULT_PROFILES is a list like ["default", "skyblock"]
                await lxd_service.clone_container(
                    source_image_alias=settings.LXD_BASE_IMAGE,
                    new_container_name=container_name,
                    profiles=settings.LXD_DEFAULT_PROFILES
                )
                logger.info(f"Service (background): Container '{container_name}' cloned successfully.")

                # Inject skyblock_island_data.toml (assuming this is still required)
                # This part remains in _perform_lxd_clone_and_update_status as it's part of initial setup
                logger.info(f"Service (background): Attempting to inject skyblock_island_data.toml into {container_name} for player {player_uuid}")
                skyblock_config_file_target_path = "/opt/minecraft/world/serverconfig/skyblock_island_data.toml"
                skyblock_toml_content = f"is_island_server = true\ncreator_uuid = \"{player_uuid}\"\n"

                try:
                    await lxd_service.push_file_to_container(
                        container_name=container_name,
                        target_path=skyblock_config_file_target_path,
                        content=skyblock_toml_content.encode('utf-8'),
                        mode=0o644,
                        uid=0,
                        gid=0
                    )
                    logger.info(f"Service (background): Successfully injected skyblock_island_data.toml into {container_name}")
                except lxd_service.LXDServiceError as push_error:
                    logger.error(f"Service (background): LXDServiceError while pushing skyblock_island_data.toml to {container_name} for {player_uuid}: {push_error}")
                    await crud_island.update_status(
                        db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_CREATE
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR_CREATE due to skyblock_island_data.toml push failure.")
                    raise
                except Exception as e_push:
                    logger.error(f"Service (background): Generic error while pushing skyblock_island_data.toml to {container_name} for {player_uuid}: {e_push}", exc_info=True)
                    await crud_island.update_status(
                        db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_CREATE
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR_CREATE due to generic skyblock_island_data.toml push failure.")
                    raise
                
                # Server_id configuration is now moved to _perform_lxd_start_and_update_status.
                # The main task here is to prepare the playersync-common.toml from template.
                
                template_path = pathlib.Path(__file__).parent.parent / "templates" / "playersync-common.template.toml"
                target_playersync_path = "/opt/minecraft/config/playersync-common.toml"
                
                try:
                    logger.info(f"Service (background): Reading playersync template from {template_path}")
                    template_content = template_path.read_text()
                    
                    import random
                    generated_server_id = random.randint(100000, 999999)
                    
                    final_playersync_content = template_content.replace("{{SERVER_ID}}", str(generated_server_id))
                    
                    logger.info(f"Service (background): Pushing generated playersync-common.toml to {container_name}:{target_playersync_path} with Server_id {generated_server_id}")
                    await lxd_service.push_file_to_container(
                        container_name=container_name,
                        target_path=target_playersync_path,
                        content=final_playersync_content.encode('utf-8'),
                        mode=0o644,
                        uid=0,
                        gid=0
                    )
                    logger.info(f"Service (background): Successfully pushed playersync-common.toml to {container_name}")

                except FileNotFoundError:
                    logger.error(f"Service (background): CRITICAL - playersync-common.template.toml not found at {template_path}")
                    await crud_island.update_status(db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_CREATE)
                    raise # Re-raise to be caught by the outer exception handler for clone process
                except lxd_service.LXDServiceError as playersync_push_error:
                    logger.error(f"Service (background): LXDServiceError pushing playersync-common.toml to {container_name}: {playersync_push_error}")
                    await crud_island.update_status(db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_CREATE)
                    raise
                except Exception as e_playersync:
                    logger.error(f"Service (background): Generic error processing playersync-common.toml for {container_name}: {e_playersync}", exc_info=True)
                    await crud_island.update_status(db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_CREATE)
                    raise

                # After cloning and all file injections, the container is created but not started.
                # Its status in DB should be STOPPED.
                await crud_island.update_status(
                    db_session_bg, 
                    player_uuid=uuid.UUID(player_uuid), 
                    status=IslandStatusEnum.STOPPED,
                    extra_fields={"minecraft_ready": False} # Ensure minecraft_ready is False for a newly cloned, stopped island
                )
                logger.info(f"Service (background): Island {player_uuid} status updated to STOPPED and minecraft_ready=False in DB post-cloning and all initial configurations.")

            except lxd_service.LXDServiceError as lxd_e: # More specific error handling
                logger.error(f"Service (background): LXD Error during LXD clone for {player_uuid}: {lxd_e}")
                try:
                    await crud_island.update_status(
                        db_session_bg, 
                        player_uuid=uuid.UUID(player_uuid), 
                        status=IslandStatusEnum.ERROR_CREATE,
                        extra_fields={"minecraft_ready": False}
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR_CREATE and minecraft_ready=False in DB due to LXD clone failure.")
                except Exception as db_e:
                    logger.critical(f"Service (background): CRITICAL - Failed to update island {player_uuid} status to ERROR_CREATE: {db_e}")
            except Exception as e: # Catch other potential errors
                logger.error(f"Service (background): Generic error during LXD clone or DB update for {player_uuid}: {e}", exc_info=True)
                try:
                    await crud_island.update_status(
                        db_session_bg, 
                        player_uuid=uuid.UUID(player_uuid), 
                        status=IslandStatusEnum.ERROR,
                        extra_fields={"minecraft_ready": False}
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR and minecraft_ready=False in DB due to generic failure.")
                except Exception as db_e:
                    logger.critical(f"Service (background): CRITICAL - Failed to update island {player_uuid} status to ERROR: {db_e}")
            finally:
                await db_session_bg.close()
    async def start_island_instance(self, db_session: AsyncSession, *, player_uuid: uuid.UUID, background_tasks: BackgroundTasks) -> IslandResponse:
        """
        Handles the business logic for starting an island using CRUD.
        """
        logger.info(f"Service: Attempting to start island for player_uuid: {player_uuid}")
        island_db_model = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)

        if not island_db_model:
            logger.warning(f"Service: Island not found for start request: player_uuid {player_uuid}")
            raise ValueError("Island not found.")

        current_status = island_db_model.status
        container_name = island_db_model.container_name

        if current_status == IslandStatusEnum.RUNNING:
            logger.info(f"Service: Island {container_name} is already RUNNING.")
        elif current_status in [IslandStatusEnum.STOPPED, IslandStatusEnum.FROZEN]:
            running_islands_count = await crud_island.get_running_islands_count(db_session)
            if running_islands_count >= settings.MAX_RUNNING_SERVERS:
                logger.info(f"Service: Max running servers limit reached ({settings.MAX_RUNNING_SERVERS}). Queuing island start for player_uuid: {player_uuid}")
                from app.crud.crud_island_start_queue import crud_island_start_queue
                await crud_island_start_queue.add_to_queue(db_session, player_uuid=player_uuid, player_name=island_db_model.player_name)
                raise ValueError("Max running servers limit reached. Island start queued.")

            logger.info(f"Service: Starting island {container_name} from status {current_status.value}...")

            # Ensure minecraft_ready is set to False when initiating a start
            updated_island_db_model = await crud_island.update_status(
                db_session,
                player_uuid=player_uuid,
                status=IslandStatusEnum.PENDING_START,
                extra_fields={"minecraft_ready": False}
            )
            if not updated_island_db_model:
                logger.error(f"Service: Failed to update island {player_uuid} to PENDING_START and minecraft_ready=False, not found during update.")
                raise ValueError("Island disappeared during status update to PENDING_START.")
            island_db_model = updated_island_db_model

            background_tasks.add_task(
                self._perform_lxd_start_and_update_status,
                player_uuid=str(player_uuid),
                container_name=container_name,
                was_frozen=(current_status == IslandStatusEnum.FROZEN)
            )
            logger.info(f"Service: Background task scheduled for LXD start for island {island_db_model.id}.")

        elif current_status == IslandStatusEnum.PENDING_START:
            logger.info(f"Service: Island {container_name} is already PENDING_START.")
        else:
            logger.warning(f"Service: Island {container_name} cannot be started from status: {current_status.value}")
            raise ValueError(f"Island cannot be started from status: {current_status.value}")

        return IslandResponse.model_validate(island_db_model)

    async def _perform_lxd_start_and_update_status(self, player_uuid: str, container_name: str, was_frozen: bool):
        """
        Actual LXD start/unfreeze and DB status update, for background task.
        This method MUST acquire its own database session.
        """
        from app.db.session import AsyncSessionLocal

        async with AsyncSessionLocal() as db_session_bg:
            # island_db_model_bg = await crud_island.get_by_player_uuid(db_session_bg, player_uuid=uuid.UUID(player_uuid))
            # if not island_db_model_bg:
            #     logger.error(f"Service (background): Island not found for player_uuid {player_uuid} at start of _perform_lxd_start_and_update_status. Aborting.")
            #     return
            # The above is removed as is_server_id_configured is no longer used.
            
            try:
                logger.info(f"Service (background): Starting LXD operations for {player_uuid}, container {container_name}")

                # Start/Unfreeze container first
                if was_frozen:
                    logger.info(f"Service (background): Container {container_name} was frozen. Unfreezing...")
                    await lxd_service.unfreeze_container(container_name)
                    logger.info(f"Service (background): Container {container_name} unfrozen.")
                
                container_state_before_start_attempt = await lxd_service.get_container_state(container_name)
                if not container_state_before_start_attempt or container_state_before_start_attempt.get('status', '').lower() != 'running':
                    logger.info(f"Service (background): Attempting to start container {container_name} (current status: {container_state_before_start_attempt.get('status') if container_state_before_start_attempt else 'N/A'})...")
                    await lxd_service.start_container(container_name)
                    logger.info(f"Service (background): Container {container_name} start command issued.")
                else:
                    logger.info(f"Service (background): Container {container_name} is already running.")

                # Server_id configuration was done during _perform_lxd_clone_and_update_status using the template.
                # No need for specific Server_id logic here anymore.

                # Retrieve IP address using the dedicated method with retries
                ip_address = await lxd_service.get_container_ip(container_name)
                if not ip_address:
                    logger.error(f"Service (background): Failed to get IP address for container {container_name} after starting.")
                    await crud_island.update_status(
                        db_session_bg, 
                        player_uuid=uuid.UUID(player_uuid), 
                        status=IslandStatusEnum.ERROR_START,
                        extra_fields={"minecraft_ready": False, "internal_ip_address": None} # Ensure minecraft_ready is False and clear IP
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR_START (no IP) and minecraft_ready set to False.")
                    return # Exit the task

                internal_port = settings.DEFAULT_MC_PORT_INTERNAL # This should be the port Minecraft runs on INSIDE the container

                update_fields = {
                    "internal_ip_address": ip_address,
                    "internal_port": internal_port, 
                    "external_port": None, 
                    "minecraft_ready": False, # Explicitly set to False, server mod will signal when ready
                    "last_seen_at": datetime.now(timezone.utc)
                }
                
                await crud_island.update_status(
                    db_session_bg,
                    player_uuid=uuid.UUID(player_uuid),
                    status=IslandStatusEnum.RUNNING, # LXD container is running
                    extra_fields=update_fields
                )
                logger.info(f"Service (background): Island {player_uuid} status updated to RUNNING (LXD up). Internal IP: {ip_address}:{internal_port}. minecraft_ready set to False, awaiting signal from mod.")

            except lxd_service.LXDContainerNotFoundError:
                logger.error(f"Service (background): Container {container_name} not found during start operations for {player_uuid}.")
                await crud_island.update_status(
                    db_session_bg, 
                    player_uuid=uuid.UUID(player_uuid), 
                    status=IslandStatusEnum.ERROR_START,
                    extra_fields={"minecraft_ready": False}
                )
            except lxd_service.LXDServiceError as lxd_e:
                logger.error(f"Service (background): LXD Error during LXD start for {player_uuid}: {lxd_e}")
                try:
                    await crud_island.update_status(
                        db_session_bg, 
                        player_uuid=uuid.UUID(player_uuid), 
                        status=IslandStatusEnum.ERROR_START,
                        extra_fields={"minecraft_ready": False}
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR_START in DB due to LXD start failure, minecraft_ready set to False.")
                except Exception as db_e:
                    logger.critical(f"Service (background): CRITICAL - Failed to update island {player_uuid} status to ERROR_START: {db_e}")
            except Exception as e:
                logger.error(f"Service (background): Generic error during LXD start or DB update for {player_uuid}: {e}", exc_info=True)
                try:
                    await crud_island.update_status(
                        db_session_bg, 
                        player_uuid=uuid.UUID(player_uuid), 
                        status=IslandStatusEnum.ERROR,
                        extra_fields={"minecraft_ready": False}
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR in DB due to generic start failure, minecraft_ready set to False.")
                except Exception as db_e:
                    logger.critical(f"Service (background): CRITICAL - Failed to update island {player_uuid} status to ERROR: {db_e}")
            finally:
                await db_session_bg.close()

    async def freeze_island_instance(self, db_session: AsyncSession, *, player_uuid: uuid.UUID, background_tasks: BackgroundTasks) -> IslandResponse:
        """
        Handles the business logic for freezing an island.
        """
        logger.info(f"Service: Attempting to freeze island for player_uuid: {player_uuid}")
        island_db_model = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)

        if not island_db_model:
            logger.warning(f"Service: Island not found for freeze request: player_uuid {player_uuid}")
            raise ValueError("Island not found.")

        current_status = island_db_model.status
        container_name = island_db_model.container_name

        if current_status == IslandStatusEnum.FROZEN:
            logger.info(f"Service: Island {container_name} is already FROZEN.")
        elif current_status == IslandStatusEnum.RUNNING:
            logger.info(f"Service: Freezing island {container_name} from status {current_status.value}...")
            updated_island_db_model = await crud_island.update_status(
                db_session, player_uuid=player_uuid, status=IslandStatusEnum.PENDING_FREEZE
            )
            if not updated_island_db_model:
                logger.error(f"Service: Failed to update island {player_uuid} to PENDING_FREEZE, not found during update.")
                raise ValueError("Island disappeared during status update to PENDING_FREEZE.")
            island_db_model = updated_island_db_model

            background_tasks.add_task(
                self._perform_lxd_freeze_and_update_status,
                player_uuid=str(player_uuid),
                container_name=container_name
            )
            logger.info(f"Service: Background task scheduled for LXD freeze for island {island_db_model.id}.")
        elif current_status == IslandStatusEnum.PENDING_FREEZE:
            logger.info(f"Service: Island {container_name} is already PENDING_FREEZE.")
        else:
            logger.warning(f"Service: Island {container_name} cannot be frozen from status: {current_status.value}")
            raise ValueError(f"Island cannot be frozen from status: {current_status.value}")

        return IslandResponse.model_validate(island_db_model)

    async def _perform_lxd_freeze_and_update_status(self, player_uuid: str, container_name: str):
        """
        Actual LXD freeze and DB status update, for background task.
        This method MUST acquire its own database session.
        """
        from app.db.session import AsyncSessionLocal

        async with AsyncSessionLocal() as db_session_bg:
            try:
                logger.info(f"Service (background): Freezing LXD container {container_name} for {player_uuid}")
                await lxd_service.freeze_container(container_name)
                logger.info(f"Service (background): Container {container_name} frozen successfully via LXDService.")

                await crud_island.update_status(
                    db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.FROZEN,
                    extra_fields={
                        "last_seen_at": datetime.now(timezone.utc), # Update last_seen_at on freeze
                        "minecraft_ready": False # Explicitly set minecraft_ready to False on freeze
                    }
                )
                logger.info(f"Service (background): Island {player_uuid} status updated to FROZEN and minecraft_ready set to False.")

            except lxd_service.LXDContainerNotFoundError:
                logger.error(f"Service (background): Container {container_name} not found during freeze for {player_uuid}.")
                await crud_island.update_status(db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR, extra_fields={"minecraft_ready": False}) # Or a more specific error
            except lxd_service.LXDServiceError as lxd_e:
                logger.error(f"Service (background): LXD Error during LXD freeze for {player_uuid}: {lxd_e}")
                await crud_island.update_status(db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR, extra_fields={"minecraft_ready": False})
            except Exception as e:
                logger.error(f"Service (background): Generic error during LXD freeze or DB update for {player_uuid}: {e}", exc_info=True)
                await crud_island.update_status(db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR, extra_fields={"minecraft_ready": False})
            finally:
                await db_session_bg.close()

    async def stop_island_instance(self, db_session: AsyncSession, *, player_uuid: uuid.UUID, background_tasks: BackgroundTasks) -> IslandResponse:
        """
        Handles the business logic for stopping an island.
        """
        logger.info(f"Service: Attempting to stop island for player_uuid: {player_uuid}")
        island_db_model = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)

        if not island_db_model:
            logger.warning(f"Service: Island not found for stop request: player_uuid {player_uuid}")
            raise ValueError("Island not found.")

        current_status = island_db_model.status
        container_name = island_db_model.container_name

        if current_status == IslandStatusEnum.STOPPED:
            logger.info(f"Service: Island {container_name} is already STOPPED.")
        elif current_status in [IslandStatusEnum.RUNNING, IslandStatusEnum.FROZEN, IslandStatusEnum.PENDING_START, IslandStatusEnum.ERROR_START]:
            # Allow stopping from RUNNING, FROZEN, or even PENDING_START/ERROR_START to attempt cleanup
            logger.info(f"Service: Stopping island {container_name} from status {current_status.value}...")
            
            # If it was PENDING_START, the background task for starting might still be running.
            # LXD stop is generally safe but consider if cancellation of the start task is needed for complex scenarios.
            # For now, proceeding with stop.

            updated_island_db_model = await crud_island.update_status(
                db_session, player_uuid=player_uuid, status=IslandStatusEnum.PENDING_STOP
            )
            if not updated_island_db_model:
                logger.error(f"Service: Failed to update island {player_uuid} to PENDING_STOP, not found during update.")
                raise ValueError("Island disappeared during status update to PENDING_STOP.")
            island_db_model = updated_island_db_model

            background_tasks.add_task(
                self._perform_lxd_stop_and_update_status,
                player_uuid=str(player_uuid),
                container_name=container_name
            )
            logger.info(f"Service: Background task scheduled for LXD stop for island {island_db_model.id}.")
        elif current_status == IslandStatusEnum.PENDING_STOP:
            logger.info(f"Service: Island {container_name} is already PENDING_STOP.")
        else:
            # e.g. PENDING_CREATION, CREATING, DELETING, ERROR_CREATE - these states generally shouldn't be stopped manually this way
            # or the stop operation isn't meaningful.
            logger.warning(f"Service: Island {container_name} cannot be stopped from status: {current_status.value}")
            raise ValueError(f"Island cannot be stopped from status: {current_status.value}")

        return IslandResponse.model_validate(island_db_model)

    async def _perform_lxd_stop_and_update_status(self, player_uuid: str, container_name: str):
        """
        Actual LXD stop and DB status update, for background task.
        This method MUST acquire its own database session.
        """
        from app.db.session import AsyncSessionLocal

        async with AsyncSessionLocal() as db_session_bg:
            try:
                logger.info(f"Service (background): Stopping LXD container {container_name} for {player_uuid}")
                # LXD service's stop_container usually handles unfreezing if necessary, or stops directly.
                # If specific unfreeze logic is needed before stop, it could be added here.
                # For pylxd, container.stop(force=True) is robust.
                await lxd_service.stop_container(container_name, force=True) # Using force=True by default as per lxd_service
                logger.info(f"Service (background): Container {container_name} stopped successfully via LXDService.")

                update_fields = {
                    "internal_ip_address": None, # Clear IP on stop
                    "internal_port": None, # Clear port on stop
                    "external_port": None,
                    "minecraft_ready": False, # Explicitly set minecraft_ready to False on stop
                    "last_seen_at": datetime.now(timezone.utc) # Update last_seen_at on stop
                }
                await crud_island.update_status(
                    db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.STOPPED,
                    extra_fields=update_fields
                )
                logger.info(f"Service (background): Island {player_uuid} status updated to STOPPED, network info cleared, and minecraft_ready set to False.")
                await trigger_creation_worker()
                await trigger_start_worker()

            except lxd_service.LXDContainerNotFoundError:
                # If container not found, it might have been deleted or failed creation.
                # Consider it stopped or error state.
                logger.warning(f"Service (background): Container {container_name} not found during stop operation for {player_uuid}. Assuming effectively stopped.")
                await crud_island.update_status(
                    db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.STOPPED,
                    extra_fields={"internal_ip_address": None, "internal_port": None, "external_port": None, "minecraft_ready": False} # Ensure fields are cleared
                )
            except lxd_service.LXDServiceError as lxd_e:
                logger.error(f"Service (background): LXD Error during LXD stop for {player_uuid}: {lxd_e}")
                # Potentially set to ERROR or ERROR_STOP if we add such a state
                await crud_island.update_status(db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR, extra_fields={"minecraft_ready": False})
            except Exception as e:
                logger.error(f"Service (background): Generic error during LXD stop or DB update for {player_uuid}: {e}", exc_info=True)
                await crud_island.update_status(db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR, extra_fields={"minecraft_ready": False})
            finally:
                await db_session_bg.close()
    async def mark_island_as_ready_for_players(self, db_session: AsyncSession, *, player_uuid: uuid.UUID):
        """
        Marks an island as fully ready for players (Minecraft server loaded).
        """
        logger.info(f"Service: Attempting to mark island as ready for players for player_uuid: {player_uuid}")
        island_db_model = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)

        if not island_db_model:
            logger.warning(f"Service: Island not found for marking as ready: player_uuid {player_uuid}")
            raise ValueError("Island not found.")

        # Check current status - should ideally be RUNNING (LXD is up)
        # and not yet marked as minecraft_ready.
        if island_db_model.status != IslandStatusEnum.RUNNING:
            logger.warning(f"Service: Island {island_db_model.container_name} for {player_uuid} is not in RUNNING state (current: {island_db_model.status}). Cannot mark as ready for players.")
            raise ValueError("Island is not in a state to be marked ready for players (must be RUNNING).")

        if hasattr(island_db_model, 'minecraft_ready') and island_db_model.minecraft_ready:
            logger.info(f"Service: Island {island_db_model.container_name} for {player_uuid} is already marked as ready for players.")
            # Depending on desired idempotency, either raise an error or return successfully.
            # For now, raising an error to indicate it's already in the desired state.
            raise ValueError("Island already marked as ready for players.")

        try:
            # Assuming 'minecraft_ready' will be a new field in the model.
            # The actual update will happen via crud_island.update which needs to support this field.
            updated_island = await crud_island.update(
                db_session,
                db_obj=island_db_model,
                obj_in={"minecraft_ready": True, "last_seen_at": datetime.now(timezone.utc)} # Also update last_seen_at
            )
            logger.info(f"Service: Island {updated_island.container_name} for {player_uuid} successfully marked as ready for players (minecraft_ready=True).")
        except Exception as e: # Catch potential errors from CRUD or DB
            logger.error(f"Service: Error updating island {player_uuid} to mark as ready for players: {e}", exc_info=True)
            # This could be a more specific DB error or validation error if obj_in was complex.
            raise ValueError(f"Failed to update island to ready state: {e}")

# Instantiate the service
island_service = IslandService()
