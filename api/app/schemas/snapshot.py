from pydantic import BaseModel

class SnapshotRestore(BaseModel):
    """
    Schema for restoring a snapshot.
    """
    snapshot_name: str

class SnapshotCreate(BaseModel):
    """
    Schema for creating a snapshot.
    """
    snapshot_name: str
