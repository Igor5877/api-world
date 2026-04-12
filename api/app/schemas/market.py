from typing import List, Optional
from pydantic import BaseModel, Field

class MarketItemBase(BaseModel):
    """Shared properties for Market Item."""
    island_uuid: str = Field(..., description="UUID of the island or team.")
    item_id: str = Field(..., description="Minecraft registry ID (e.g. 'minecraft:diamond').")
    item_nbt: Optional[str] = Field(None, description="NBT tags as JSON string.")
    quantity: int = Field(..., description="Total quantity available in the ME network.")
    price: float = Field(10.0, description="Price per unit.")
    is_for_sale: bool = Field(True, description="Whether the item is for sale.")
    version: int = Field(1, description="Data schema version.")

class MarketItemCreate(MarketItemBase):
    """Properties to receive via API on creation."""
    pass

class MarketItemSync(BaseModel):
    """Payload representing a full inventory sync from RealMarket."""
    items: List[MarketItemCreate]

class MarketItemInDB(MarketItemBase):
    """Properties stored in DB."""
    id: int

    model_config = {"from_attributes": True}

class PurchaseRequest(BaseModel):
    """Payload for purchasing items from an island's market."""
    item_id: str = Field(..., description="Minecraft registry ID of the item to purchase.")
    quantity: int = Field(..., gt=0, description="Amount to purchase.")
