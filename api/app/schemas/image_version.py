from pydantic import BaseModel, Field
from typing import Optional
from datetime import datetime

class ImageVersionBase(BaseModel):
    image_alias: str = Field(..., description="Alias of the image in LXD")
    version_name: str = Field(..., description="User-friendly version name")
    status: str = Field("beta", description="Status of the image (e.g., stable, latest, beta, deprecated)")

class ImageVersionCreate(ImageVersionBase):
    pass

class ImageVersionUpdate(BaseModel):
    version_name: Optional[str] = None
    status: Optional[str] = None

class ImageVersionResponse(ImageVersionBase):
    id: int
    created_at: datetime

    class Config:
        from_attributes = True
