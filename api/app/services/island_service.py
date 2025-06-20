from sqlalchemy.ext.asyncio import AsyncSession
from fastapi import BackgroundTasks
from app.core.config import settings
from app.services.lxd_service import lxd_service
from app.crud.crud_island import crud_island
from app.schemas.island import IslandCreate, IslandResponse, IslandUpdate, IslandStatusEnum
from app.models.island import Island as IslandModel
import uuid
import asyncio
from datetime import datetime, timezone
import logging

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

                # After cloning, the container is created but not started. Its status in DB should be STOPPED.
                await crud_island.update_status(
                    db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.STOPPED
                )
                logger.info(f"Service (background): Island {player_uuid} status updated to STOPPED in DB post-cloning.")

            except lxd_service.LXDServiceError as lxd_e: # More specific error handling
                logger.error(f"Service (background): LXD Error during LXD clone for {player_uuid}: {lxd_e}")
                try:
                    await crud_island.update_status(
                        db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_CREATE
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR_CREATE in DB due to LXD clone failure.")
                except Exception as db_e:
                    logger.critical(f"Service (background): CRITICAL - Failed to update island {player_uuid} status to ERROR_CREATE: {db_e}")
            except Exception as e: # Catch other potential errors
                logger.error(f"Service (background): Generic error during LXD clone or DB update for {player_uuid}: {e}", exc_info=True)
                try:
                    await crud_island.update_status(
                        db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR in DB due to generic failure.")
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
            logger.info(f"Service: Starting island {container_name} from status {current_status.value}...")

            updated_island_db_model = await crud_island.update_status(
                db_session, player_uuid=player_uuid, status=IslandStatusEnum.PENDING_START
            )
            if not updated_island_db_model:
                logger.error(f"Service: Failed to update island {player_uuid} to PENDING_START, not found during update.")
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
            try:
                logger.info(f"Service (background): Starting LXD operations for {player_uuid}, container {container_name}")
                if was_frozen:
                    logger.info(f"Service (background): Container {container_name} was frozen. Unfreezing...")
                    await lxd_service.unfreeze_container(container_name)
                    logger.info(f"Service (background): Container {container_name} unfrozen.")
                
                logger.info(f"Service (background): Attempting to start container {container_name}...")
                await lxd_service.start_container(container_name)
                logger.info(f"Service (background): Container {container_name} started successfully via LXDService.")

                # Retrieve IP address using the dedicated method with retries
                ip_address = await lxd_service.get_container_ip(container_name)
                if not ip_address:
                    logger.error(f"Service (background): Failed to get IP address for container {container_name} after starting.")
                    # Set status to ERROR_START or a specific no-IP error status
                    await crud_island.update_status(
                        db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_START  # Or a new status like ERROR_NO_IP
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR_START due to IP retrieval failure.")
                    return # Exit the task

                internal_port = settings.DEFAULT_MC_PORT_INTERNAL # This should be the port Minecraft runs on INSIDE the container

                update_fields = {
                    "internal_ip_address": ip_address,
                    "internal_port": internal_port, # Default Minecraft port inside container
                    "external_port": None, # External port will be managed by Velocity or another proxy mechanism, not directly known here unless LXD proxy device is used.
                    "last_seen_at": datetime.now(timezone.utc)
                }
                await crud_island.update_status(
                    db_session_bg,
                    player_uuid=uuid.UUID(player_uuid),
                    status=IslandStatusEnum.RUNNING,
                    extra_fields=update_fields
                )
                logger.info(f"Service (background): Island {player_uuid} status updated to RUNNING. Internal IP: {ip_address}:{internal_port}")

            except lxd_service.LXDContainerNotFoundError:
                logger.error(f"Service (background): Container {container_name} not found during start operations for {player_uuid}.")
                # Status should already be PENDING_START or similar, this is an unexpected state.
                # Consider setting to a specific error state.
                await crud_island.update_status(db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_START)
            except lxd_service.LXDServiceError as lxd_e:
                logger.error(f"Service (background): LXD Error during LXD start for {player_uuid}: {lxd_e}")
                try:
                    await crud_island.update_status(
                        db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR_START
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR_START in DB due to LXD start failure.")
                except Exception as db_e:
                    logger.critical(f"Service (background): CRITICAL - Failed to update island {player_uuid} status to ERROR_START: {db_e}")
            except Exception as e:
                logger.error(f"Service (background): Generic error during LXD start or DB update for {player_uuid}: {e}", exc_info=True)
                try:
                    await crud_island.update_status(
                        db_session_bg, player_uuid=uuid.UUID(player_uuid), status=IslandStatusEnum.ERROR
                    )
                    logger.info(f"Service (background): Island {player_uuid} status updated to ERROR in DB due to generic start failure.")
                except Exception as db_e:
                    logger.critical(f"Service (background): CRITICAL - Failed to update island {player_uuid} status to ERROR: {db_e}")
            finally:
                await db_session_bg.close()

# Instantiate the service
island_service = IslandService()
