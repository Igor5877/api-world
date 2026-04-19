from typing import List, Optional
from decimal import Decimal
from datetime import datetime
from pydantic import BaseModel, Field

class MarketItemCreate(BaseModel):
    """Payload for a single item sent by the Forge mod during sync."""
    item_id: str = Field(..., description="Minecraft registry ID (e.g. 'minecraft:diamond').")
    item_nbt: str = Field('', description="NBT tags as JSON string. Empty string = no NBT.")
    quantity: int = Field(..., description="Total quantity available in the ME network.")
    price: Decimal = Field(Decimal('10.00'), description="Price per unit.")
    is_for_sale: bool = Field(True, description="Whether the item is for sale.")
    version: int = Field(1, description="Data schema version.")
    seller_azuriom_id: Optional[int] = Field(None, description="Azuriom user ID of the seller.")

class MarketItemSync(BaseModel):
    """Payload for a full inventory sync from RealMarket mod."""
    items: List[MarketItemCreate]

class MarketItemInDB(BaseModel):
    """Market item as stored in DB."""
    id: int
    team_id: int
    item_id: str
    item_nbt: str
    quantity: int
    price: Decimal
    is_for_sale: bool
    version: int
    seller_azuriom_id: Optional[int]

    model_config = {"from_attributes": True}

class PurchaseRequest(BaseModel):
    """Payload for purchasing items from an island's market."""
    item_id: str = Field(..., description="Minecraft registry ID of the item to purchase.")
    quantity: int = Field(..., gt=0, description="Amount to purchase.")
    buyer_azuriom_id: Optional[int] = Field(None, description="Azuriom user ID of the buyer.")

class MarketTransactionInDB(BaseModel):
    id: int
    team_id: int
    item_id: str
    quantity: int
    unit_price: Decimal
    total_price: Decimal
    buyer_azuriom_id: Optional[int]
    seller_azuriom_id: Optional[int]
    seller_paid: bool
    created_at: Optional[datetime]

    model_config = {"from_attributes": True}
