import asyncio
import logging
from app.core.config import settings
from app.db.session import AsyncSessionLocal
from app.crud.crud_island import crud_island
from app.crud.crud_island_queue_ops import crud_main_island_queue
from app.services.island_service import island_service
from app.schemas.island import IslandCreate, IslandStatusEnum
from app.models.island import QueueItemStatusEnum

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

async def trigger_creation_worker():
    """
    Processes the island creation queue.
    """
    logger.info("Creation queue worker started.")
    try:
        async with AsyncSessionLocal() as db_session:
            running_islands_count = await crud_island.get_running_islands_count(db_session)
            logger.info(f"Running islands: {running_islands_count}/{settings.MAX_RUNNING_SERVERS}")

            if running_islands_count < settings.MAX_RUNNING_SERVERS:
                next_in_queue = await crud_main_island_queue.get_next_in_queue(db_session)

                if next_in_queue:
                    logger.info(f"Processing queue item for player {next_in_queue.player_uuid}")
                    await crud_main_island_queue.update_queue_item_status(
                        db_session,
                        player_uuid=next_in_queue.player_uuid,
                        status=QueueItemStatusEnum.PROCESSING,
                    )

                    try:
                        # We need to create a new background tasks object to pass to the service
                        from fastapi import BackgroundTasks
                        background_tasks = BackgroundTasks()

                        await island_service.create_new_island(
                            db_session=db_session,
                            island_create_data=IslandCreate(
                                player_uuid=next_in_queue.player_uuid,
                                player_name=next_in_queue.player_name,
                            ),
                            background_tasks=background_tasks,
                        )

                        # Remove the item from the queue after successful processing
                        await crud_main_island_queue.remove_from_queue(
                            db_session, player_uuid=next_in_queue.player_uuid
                        )
                        logger.info(f"Successfully processed queue item for player {next_in_queue.player_uuid}")

                    except Exception as e:
                        logger.error(f"Error processing queue item for player {next_in_queue.player_uuid}: {e}")
                        await crud_main_island_queue.update_queue_item_status(
                            db_session,
                            player_uuid=next_in_queue.player_uuid,
                            status=QueueItemStatusEnum.FAILED,
                        )
            else:
                logger.info("Max running servers limit reached. No new islands will be created.")

    except Exception as e:
        logger.error(f"An error occurred in the creation queue worker: {e}", exc_info=True)

async def start_creation_worker():
    """
    Starts the creation queue worker in the background.
    """
    pass
