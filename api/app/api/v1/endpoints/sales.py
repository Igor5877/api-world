from typing import Any, List
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy import delete

from app.db.session import get_db_session
from app.models.sales import SalesItem, Transaction, TransactionStatusEnum
from app.schemas.sales import SalesSync, TransactionCreate, PendingRemoval
from app.models.island import Island

router = APIRouter()

@router.post("/sync/{island_id}")
async def sync_sales(
    island_id: int,
    sales_sync: SalesSync,
    db_session: AsyncSession = Depends(get_db_session),
    # In a real app, add auth dependency to ensure the island owns this ID
):
    """
    Called by the Island (Sales Addon) to push current inventory.
    This overwrites the current cache for this island's sales items.
    """
    result = await db_session.execute(select(Island).where(Island.id == island_id))
    island = result.scalar_first()
    if not island:
        raise HTTPException(status_code=404, detail="Island not found")

    # Clear existing items for this island
    await db_session.execute(delete(SalesItem).where(SalesItem.island_id == island_id))

    for item in sales_sync.items:
        db_item = SalesItem(
            island_id=island_id,
            item_id=item.item_id,
            item_name=item.item_name,
            item_nbt=item.item_nbt,
            quantity=item.quantity,
            price=item.price
        )
        db_session.add(db_item)

    await db_session.commit()
    return {"status": "synced", "count": len(sales_sync.items)}

@router.post("/purchase")
async def purchase_item(
    transaction_in: TransactionCreate,
    db_session: AsyncSession = Depends(get_db_session)
):
    """
    Liability Shift Transaction Flow
    """
    # 1. Check Availability
    result = await db_session.execute(
        select(SalesItem).where(
            SalesItem.island_id == transaction_in.island_id,
            SalesItem.item_id == transaction_in.item_id
        )
    )
    sales_item = result.scalar_first()

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
    db_session.add(transaction)
    await db_session.commit()
    await db_session.refresh(transaction)

    try:
        # 3. Optimistically update local cache to prevent double-spending immediately
        sales_item.quantity -= transaction_in.quantity
        db_session.add(sales_item)

        # 4. Mark as 'Funds Deducted' (Simulation)
        transaction.funds_deducted = True

        # 5. Mark as 'Seller Credited' (Simulation)
        transaction.seller_credited = True

        # 6. Check Island Status
        # Island connection check could be implemented here via WebSockets,
        # but for Liability Shift MVP, we assume the remote extraction is queued.

        transaction.status = TransactionStatusEnum.COMPLETED
        # Need to import func from sqlalchemy.sql if we use it, or just let DB default handle timestamps if applicable.
        # Actually completed_at isn't strictly necessary to set manually if it defaults or we omit it for MVP

        await db_session.commit()

        return {"status": "success", "transaction_id": transaction.id}

    except Exception as e:
        await db_session.rollback()
        transaction.status = TransactionStatusEnum.FAILED
        transaction.error_log = str(e)
        db_session.add(transaction)
        await db_session.commit()
        raise HTTPException(status_code=500, detail=f"Transaction failed: {str(e)}")

@router.get("/pending/{island_id}", response_model=List[PendingRemoval])
async def get_pending_removals(
    island_id: int,
    db_session: AsyncSession = Depends(get_db_session)
):
    """
    Called by the Island (Sales Addon) on startup/periodically.
    Returns list of items sold while the island was offline.
    """
    result = await db_session.execute(
        select(Transaction).where(
            Transaction.seller_island_id == island_id,
            Transaction.status == TransactionStatusEnum.COMPLETED,
            Transaction.inventory_removed == False
        )
    )
    pending_txs = result.scalars().all()

    results = []
    for tx in pending_txs:
        results.append(PendingRemoval(
            transaction_id=tx.id,
            item_id=tx.item_id,
            quantity=tx.quantity,
            item_nbt=None
        ))
    return results

@router.post("/confirm_removal/{transaction_id}")
async def confirm_removal(
    transaction_id: int,
    db_session: AsyncSession = Depends(get_db_session)
):
    """
    Called by the Island after successfully removing items from AE2.
    """
    result = await db_session.execute(select(Transaction).where(Transaction.id == transaction_id))
    tx = result.scalar_first()

    if not tx:
        raise HTTPException(status_code=404, detail="Transaction not found")

    tx.inventory_removed = True
    await db_session.commit()
    return {"status": "confirmed"}
