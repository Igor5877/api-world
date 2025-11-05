from typing import List
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.image_version import ImageVersionCreate, ImageVersionUpdate, ImageVersionResponse
from app.db.session import get_db_session

router = APIRouter()

@router.post("/", response_model=ImageVersionResponse, status_code=status.HTTP_201_CREATED)
async def create_image_version(
    *,
    db: AsyncSession = Depends(get_db_session),
    image_in: ImageVersionCreate,
):
    """Creates a new image version.

    Args:
        db: The database session.
        image_in: The image version data.

    Returns:
        The created image version.

    Raises:
        HTTPException: If an image version with the same alias already exists.
    """
    from app.crud.crud_image_version import crud_image_version
    existing_image = await crud_image_version.get_by_alias(db, alias=image_in.image_alias)
    if existing_image:
        raise HTTPException(
            status_code=400,
            detail="An image version with this alias already exists.",
        )
    image = await crud_image_version.create(db, obj_in=image_in)
    return image

@router.get("/", response_model=List[ImageVersionResponse])
async def read_image_versions(
    db: AsyncSession = Depends(get_db_session),
    skip: int = 0,
    limit: int = 100,
):
    """Reads a list of image versions.

    Args:
        db: The database session.
        skip: The number of image versions to skip.
        limit: The maximum number of image versions to return.

    Returns:
        A list of image versions.
    """
    from app.crud.crud_image_version import crud_image_version
    images = await crud_image_version.get_multi(db, skip=skip, limit=limit)
    return images

@router.put("/{image_id}", response_model=ImageVersionResponse)
async def update_image_version(
    *,
    db: AsyncSession = Depends(get_db_session),
    image_id: int,
    image_in: ImageVersionUpdate,
):
    """Updates an image version.

    Args:
        db: The database session.
        image_id: The ID of the image version to update.
        image_in: The updated image version data.

    Returns:
        The updated image version.

    Raises:
        HTTPException: If the image version is not found.
    """
    from app.crud.crud_image_version import crud_image_version
    image = await crud_image_version.get(db, id=image_id)
    if not image:
        raise HTTPException(
            status_code=404,
            detail="Image version not found.",
        )
    image = await crud_image_version.update(db, db_obj=image, obj_in=image_in)
    return image
