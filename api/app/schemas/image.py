from pydantic import BaseModel

class ImageCreate(BaseModel):
    """
    Schema for creating a new LXD image.
    """
    source_container_name: str
    new_image_alias: str
    description: str | None = None
    public: bool = False
