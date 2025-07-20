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
_queue_event = asyncio.Event()

def signal_new_task_in_queue():
    """Signals the worker that there's a new task."""
    _queue_event.set()

async def update_worker_loop():
    """
    The main loop for the update worker.
    Waits for a signal and then processes the entire queue.
    """
    global _worker_running
    _worker_running = True
    logger.info("Update worker loop started and is waiting for tasks.")

    while _worker_running:
        await _queue_event.wait() # Wait here until signaled
        
        if not _worker_running: # Check again after waking up
            break

        logger.info("Worker awakened. Processing queue...")
        try:
            async with AsyncSessionLocal() as db_session:
                # Process all items currently in the queue
                while True:
                    processed_item = await process_next_in_queue(db_session)
                    if not processed_item:
                        break # Queue is empty
        except Exception as e:
            logger.error(f"Worker loop encountered an unhandled exception: {e}", exc_info=True)
        
        _queue_event.clear() # Reset the event, wait for new signal
        logger.info("Queue processing finished. Worker is now idle.")

    logger.info("Update worker loop stopped.")

async def process_next_in_queue(db_session: AsyncSession) -> bool:
    """
    Fetches and processes the next pending island. Returns True if an item was processed, False otherwise.
    """
    next_item = await crud_update_queue.get_next_pending_island(db_session)
    
    if not next_item:
        return False # No items to process

    logger.info(f"Found pending update for island ID: {next_item.island_id}. Starting processing.")
    
    try:
        await crud_update_queue.set_status_processing(db_session, queue_entry_id=next_item.id)
        
        await island_service.perform_island_update(
            db_session=db_session, 
            queue_entry=next_item
        )

        await crud_update_queue.set_status_completed(db_session, queue_entry_id=next_item.id)
        logger.info(f"Successfully processed update for island ID: {next_item.island_id}.")

    except Exception as e:
        logger.error(f"Failed to process update for island ID {next_item.island_id}: {e}", exc_info=True)
        await crud_update_queue.set_status_failed(
            db_session,
            queue_entry_id=next_item.id,
            error_message=str(e),
            retry_count=next_item.retry_count + 1
        )
    
    return True # An item was processed

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
