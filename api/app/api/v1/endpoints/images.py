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
    from app.crud.crud_image_version import crud_image_version
    image = await crud_image_version.get(db, id=image_id)
    if not image:
        raise HTTPException(
            status_code=404,
            detail="Image version not found.",
        )
    image = await crud_image_version.update(db, db_obj=image, obj_in=image_in)
    return image
