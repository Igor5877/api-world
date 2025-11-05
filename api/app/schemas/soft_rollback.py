from pydantic import BaseModel

class SoftRollbackRequest(BaseModel):
    """Schema for a soft rollback request.

    Attributes:
        target_version_id: The ID of the target version to roll back to.
    """
    target_version_id: int
