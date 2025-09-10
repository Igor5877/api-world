import asyncio
import logging
from sqlalchemy.ext.asyncio import AsyncSession
from app.db.session import AsyncSessionLocal
from app.crud.crud_update_queue import crud_update_queue
from app.services.island_service import island_service

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# This would be a more robust implementation of a singleton worker
# to prevent multiple workers from running concurrently.
_worker_running = False
_worker_task = None

async def update_worker_loop():
    """
    The main loop for the update worker.
    Continuously checks the queue and processes one island at a time.
    """
    global _worker_running
    _worker_running = True
    logger.info("Update worker loop started.")

    while _worker_running:
        try:
            async with AsyncSessionLocal() as db_session:
                await process_next_in_queue(db_session)
        except Exception as e:
            logger.error(f"Worker loop encountered an unhandled exception: {e}", exc_info=True)
        
        await asyncio.sleep(10) # Wait for 10 seconds before checking the queue again

    logger.info("Update worker loop stopped.")

async def process_next_in_queue(db_session: AsyncSession):
    """
    Fetches and processes the next pending island from the update queue.
    """
    logger.info("Checking for pending updates...")
    
    # Check if an item is already being processed to ensure only one at a time
    # This is a simple lock mechanism; a more robust one might use a DB field
    # if multiple worker instances could run.
    # For now, we assume a single worker process.
    
    next_item = await crud_update_queue.get_next_pending_island(db_session)
    
    if next_item:
        logger.info(f"Found pending update for island ID: {next_item.island_id}. Starting processing.")
        
        try:
            # Mark as processing to prevent other workers from picking it up
            await crud_update_queue.set_status_processing(db_session, queue_entry_id=next_item.id)
            
            # Here you would call the main update logic from island_service
            # This logic needs to be created in island_service.py
            await island_service.perform_island_update(
                db_session=db_session, 
                queue_entry=next_item
            )

            # If the update is successful, mark as completed
            await crud_update_queue.set_status_completed(db_session, queue_entry_id=next_item.id)
            logger.info(f"Successfully processed update for island ID: {next_item.island_id}.")

        except Exception as e:
            logger.error(f"Failed to process update for island ID {next_item.island_id}: {e}", exc_info=True)
            # Mark as failed
            await crud_update_queue.set_status_failed(
                db_session,
                queue_entry_id=next_item.id,
                error_message=str(e),
                retry_count=next_item.retry_count + 1
            )
    else:
        logger.info("No pending updates found.")

def start_update_worker():
    """
    Starts the update worker in a background asyncio task.
    """
    global _worker_task, _worker_running
    if _worker_task is None or _worker_task.done():
        logger.info("Starting background update worker.")
        _worker_running = True
        _worker_task = asyncio.create_task(update_worker_loop())
    else:
        logger.warning("Update worker is already running.")

def stop_update_worker():
    """
    Stops the background update worker.
    """
    global _worker_running
    if _worker_running:
        logger.info("Stopping background update worker.")
        _worker_running = False
        # The task will exit its loop on the next iteration.
        # For immediate shutdown, you might use task.cancel().
    else:
        logger.warning("Update worker is not running.")
