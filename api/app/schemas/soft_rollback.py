from pydantic import BaseModel

class SoftRollbackRequest(BaseModel):
    target_version_id: int
