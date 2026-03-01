from typing import List, Optional
from pydantic import BaseModel
from datetime import datetime

class SalesItemBase(BaseModel):
    item_id: str
    item_name: Optional[str] = None
    item_nbt: Optional[str] = None
    quantity: int
    price: float

class SalesItemCreate(SalesItemBase):
    pass

class SalesItemUpdate(SalesItemBase):
    pass

class SalesItem(SalesItemBase):
    id: int
    island_id: int
    updated_at: datetime

    class Config:
        from_attributes = True

class SalesSync(BaseModel):
    items: List[SalesItemCreate]

class TransactionCreate(BaseModel):
    buyer_uuid: str
    item_id: str
    quantity: int
    island_id: int

class TransactionStatus(BaseModel):
    id: int
    status: str
    message: Optional[str] = None

class PendingRemoval(BaseModel):
    transaction_id: int
    item_id: str
    quantity: int
    item_nbt: Optional[str] = None
