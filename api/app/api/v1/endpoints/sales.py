from typing import Any, List
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.api import deps
from app.models.sales import SalesItem, Transaction, TransactionStatusEnum
from app.schemas.sales import SalesSync, TransactionCreate, PendingRemoval
from app.models.island import Island

router = APIRouter()

@router.post("/sync/{island_id}")
def sync_sales(
    island_id: int,
    sales_sync: SalesSync,
    db: Session = Depends(deps.get_db),
    # In a real app, add auth dependency to ensure the island owns this ID
):
    """
    Called by the Island (Sales Addon) to push current inventory.
    This overwrites the current cache for this island's sales items.
    """
    island = db.query(Island).filter(Island.id == island_id).first()
    if not island:
        raise HTTPException(status_code=404, detail="Island not found")

    # Clear existing items for this island (simple approach for now)
    # In a production system, you might want to diff/update to keep history or optimize
    db.query(SalesItem).filter(SalesItem.island_id == island_id).delete()

    for item in sales_sync.items:
        db_item = SalesItem(
            island_id=island_id,
            item_id=item.item_id,
            item_name=item.item_name,
            item_nbt=item.item_nbt,
            quantity=item.quantity,
            price=item.price
        )
        db.add(db_item)

    db.commit()
    return {"status": "synced", "count": len(sales_sync.items)}

@router.post("/purchase")
def purchase_item(
    transaction_in: TransactionCreate,
    db: Session = Depends(deps.get_db)
):
    """
    Liability Shift Transaction Flow:
    1. Check Availability (Cache)
    2. Reserve (Create PENDING Transaction)
    3. TODO: Call Spawn to Give Item (This step is assumed successful if this endpoint is called, or handled by caller)
       In this MVP, we assume the caller (Spawn Server) calls this endpoint *after* verifying intent,
       but ideally this endpoint *orchestrates* the exchange.

       Let's implement the 'Liability Shift' logic:
       The Spawn Server calls this.
    """

    # 1. Check Availability
    sales_item = db.query(SalesItem).filter(
        SalesItem.island_id == transaction_in.island_id,
        SalesItem.item_id == transaction_in.item_id
    ).first()

    if not sales_item or sales_item.quantity < transaction_in.quantity:
        raise HTTPException(status_code=400, detail="Item not available or insufficient quantity")

    # 2. Create Transaction Record (Liability Shift Start)
    transaction = Transaction(
        buyer_uuid=transaction_in.buyer_uuid,
        seller_island_id=transaction_in.island_id,
        item_id=transaction_in.item_id,
        quantity=transaction_in.quantity,
        total_price=sales_item.price * transaction_in.quantity,
        status=TransactionStatusEnum.PENDING
    )
    db.add(transaction)
    db.commit()
    db.refresh(transaction)

    try:
        # 3. Optimistically update local cache to prevent double-spending immediately
        sales_item.quantity -= transaction_in.quantity
        db.add(sales_item)

        # 4. Mark as 'Funds Deducted' (Simulation)
        # In a real system, you would call an Economy Service here.
        transaction.funds_deducted = True

        # 5. Mark as 'Seller Credited' (Simulation)
        transaction.seller_credited = True

        # 6. Check Island Status
        island = db.query(Island).filter(Island.id == transaction_in.island_id).first()
        if island and island.status == "RUNNING": # Simplified status check
             # Ideally, we would try to contact the island via WebSocket here to remove the item immediately.
             # If successful -> transaction.inventory_removed = True
             # For now, we assume it's pending and let the island pull it later or handle it via a separate async task.
             pass

        transaction.status = TransactionStatusEnum.COMPLETED
        transaction.completed_at = func.now()
        db.commit()

        return {"status": "success", "transaction_id": transaction.id}

    except Exception as e:
        db.rollback()
        transaction.status = TransactionStatusEnum.FAILED
        transaction.error_log = str(e)
        db.add(transaction)
        db.commit()
        raise HTTPException(status_code=500, detail=f"Transaction failed: {str(e)}")

@router.get("/pending/{island_id}", response_model=List[PendingRemoval])
def get_pending_removals(
    island_id: int,
    db: Session = Depends(deps.get_db)
):
    """
    Called by the Island (Sales Addon) on startup/periodically.
    Returns list of items sold while the island was offline (or not yet synced).
    """
    pending_txs = db.query(Transaction).filter(
        Transaction.seller_island_id == island_id,
        Transaction.status == TransactionStatusEnum.COMPLETED,
        Transaction.inventory_removed == False
    ).all()

    results = []
    for tx in pending_txs:
        results.append(PendingRemoval(
            transaction_id=tx.id,
            item_id=tx.item_id,
            quantity=tx.quantity,
            item_nbt=None # TODO: Fetch from SalesItem history if needed
        ))
    return results

@router.post("/confirm_removal/{transaction_id}")
def confirm_removal(
    transaction_id: int,
    db: Session = Depends(deps.get_db)
):
    """
    Called by the Island after successfully removing items from AE2.
    """
    tx = db.query(Transaction).filter(Transaction.id == transaction_id).first()
    if not tx:
        raise HTTPException(status_code=404, detail="Transaction not found")

    tx.inventory_removed = True
    db.commit()
    return {"status": "confirmed"}
