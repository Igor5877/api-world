import asyncio
import logging
from app.core.config import settings
from app.db.session import AsyncSessionLocal
from app.crud.crud_island import crud_island
from app.crud.crud_island_start_queue import crud_island_start_queue
from app.schemas.island import IslandStatusEnum
from app.models.island import QueueItemStatusEnum

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

async def trigger_start_worker():
    """
    Processes the island start queue.
    """
    from app.services.island_service import island_service
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
                        island = await crud_island.get_by_player_uuid(db_session, player_uuid=next_in_queue.player_uuid)
                        if island:
                            await island_service._perform_solo_lxd_start_and_update_status(
                                player_uuid_str=island.player_uuid,
                                container_name=island.container_name,
                                was_frozen=(island.status == IslandStatusEnum.FROZEN)
                            )

                            await crud_island_start_queue.remove_from_queue(
                                db_session, player_uuid=next_in_queue.player_uuid
                            )
                            logger.info(f"Successfully processed start queue item for player {next_in_queue.player_uuid}")
                        else:
                            logger.error(f"Island not found for player {next_in_queue.player_uuid} in start queue.")
                            await crud_island_start_queue.update_queue_item_status(
                                db_session,
                                player_uuid=next_in_queue.player_uuid,
                                status=QueueItemStatusEnum.FAILED,
                            )

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
