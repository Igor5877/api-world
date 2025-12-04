from fastapi import APIRouter, Depends, BackgroundTasks, Security
from sqlalchemy.ext.asyncio import AsyncSession

import logging
from app.api.dependencies import get_api_key
from app.schemas.image import ImageCreate
from app.services.lxd_service import lxd_service, LXDContainerNotFoundError

router = APIRouter()
logger = logging.getLogger(__name__)

@router.post("/create", status_code=202)
async def create_image_from_container(
    *,
    db: AsyncSession = Depends(get_api_key),
    image_in: ImageCreate,
    background_tasks: BackgroundTasks,
    api_key: str = Security(get_api_key),
):
    """
    Creates a new LXD image from an existing container.
    This is a long-running operation and is executed in the background.
    """

    async def creation_task():
        try:
            await lxd_service.create_image_from_container(
                container_name=image_in.source_container_name,
                image_alias=image_in.new_image_alias,
                public=image_in.public,
                description=image_in.description or f"Image created from {image_in.source_container_name}"
            )
        except LXDContainerNotFoundError as e:
            logger.error(f"Failed to create image from container '{image_in.source_container_name}': Container not found.", exc_info=True)
        except Exception as e:
            logger.error(f"An unexpected error occurred during image creation for '{image_in.new_image_alias}': {e}", exc_info=True)

    background_tasks.add_task(creation_task)

    return {"message": f"Image creation for '{image_in.new_image_alias}' from container '{image_in.source_container_name}' has been initiated."}
