import asyncio
import logging
from app.core.config import settings
from app.db.session import AsyncSessionLocal
from app.crud.crud_island import crud_island
from app.crud.crud_island_start_queue import crud_island_start_queue
from app.services.island_service import island_service
from app.schemas.island import IslandStatusEnum
from app.models.island import QueueItemStatusEnum

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

async def trigger_start_worker():
    """
    Processes the island start queue.
    """
    logger.info("Start queue worker started.")
    try:
        async with AsyncSessionLocal() as db_session:
            running_islands_count = await crud_island.get_running_islands_count(db_session)
            logger.info(f"Running islands: {running_islands_count}/{settings.MAX_RUNNING_SERVERS}")

            if running_islands_count < settings.MAX_RUNNING_SERVERS:
                next_in_queue = await crud_island_start_queue.get_next_in_queue(db_session)

                if next_in_queue:
                    logger.info(f"Processing start queue item for player {next_in_queue.player_uuid}")
                    await crud_island_start_queue.update_queue_item_status(
                        db_session,
                        player_uuid=next_in_queue.player_uuid,
                        status=QueueItemStatusEnum.PROCESSING,
                    )

                    try:
                        from fastapi import BackgroundTasks
                        background_tasks = BackgroundTasks()

                        await island_service.start_island_instance(
                            db_session=db_session,
                            player_uuid=next_in_queue.player_uuid,
                            background_tasks=background_tasks,
                        )

                        await crud_island_start_queue.remove_from_queue(
                            db_session, player_uuid=next_in_queue.player_uuid
                        )
                        logger.info(f"Successfully processed start queue item for player {next_in_queue.player_uuid}")

                    except Exception as e:
                        logger.error(f"Error processing start queue item for player {next_in_queue.player_uuid}: {e}")
                        await crud_island_start_queue.update_queue_item_status(
                            db_session,
                            player_uuid=next_in_queue.player_uuid,
                            status=QueueItemStatusEnum.FAILED,
                        )
            else:
                logger.info("Max running servers limit reached. No new islands will be started.")

    except Exception as e:
        logger.error(f"An error occurred in the start queue worker: {e}", exc_info=True)

async def start_start_worker():
    """
    Starts the start queue worker in the background.
    """
    pass
