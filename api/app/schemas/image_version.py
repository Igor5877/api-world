from pydantic import BaseModel, Field
from typing import Optional
from datetime import datetime

class ImageVersionBase(BaseModel):
    """Base schema for an image version.

    Attributes:
        image_alias: The alias of the image in LXD.
        version_name: The user-friendly version name.
        status: The status of the image (e.g., stable, latest, beta,
            deprecated).
    """
    image_alias: str = Field(..., description="Alias of the image in LXD")
    version_name: str = Field(..., description="User-friendly version name")
    status: str = Field("beta", description="Status of the image (e.g., stable, latest, beta, deprecated)")

class ImageVersionCreate(ImageVersionBase):
    """Schema for creating an image version."""
    pass

class ImageVersionUpdate(BaseModel):
    """Schema for updating an image version.

    Attributes:
        version_name: The user-friendly version name.
        status: The status of the image.
    """
    version_name: Optional[str] = None
    status: Optional[str] = None

class ImageVersionResponse(ImageVersionBase):
    """Schema for an image version response.

    Attributes:
        id: The unique identifier for the image version.
        created_at: The timestamp when the image version was created.
    """
    id: int
    created_at: datetime

    class Config:
        """Pydantic configuration."""
        from_attributes = True
