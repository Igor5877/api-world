from typing import List
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import delete, select, tuple_, not_, func, update as sqlalchemy_update
from app.models.market import MarketItem, MarketPendingExtraction, MarketTransaction
from app.schemas.market import MarketItemCreate
import logging
from sqlalchemy.dialects.mysql import insert as mysql_insert

logger = logging.getLogger(__name__)

class CRUDMarketItem:
    async def sync_island_inventory(self, db_session: AsyncSession, team_id: int, items: List[MarketItemCreate]) -> list[dict]:
        """
        Syncs the AE2 inventory using bulk UPSERT + single DELETE.
        Враховує pending extractions (борги продавця):
        - Якщо AE2 має предмети що є в pending → повертає список для негайного re-triggering extraction
        - Якщо AE2 < pending → борг залишається, extraction буде повторено пізніше

        Повертає список {pending_id, item_id, quantity} які треба відправити острову через WS.
        """
        try:
            triggered_extractions: list[dict] = []

            if items:
                # Отримати всі pending extractions для цієї команди
                pending_rows_result = await db_session.execute(
                    select(MarketPendingExtraction)
                    .where(MarketPendingExtraction.team_id == team_id)
                    .order_by(MarketPendingExtraction.created_at.asc())  # найстаріші першими
                )
                pending_rows = pending_rows_result.scalars().all()

                # Згрупувати суму по item_id
                pending_by_item: dict[str, int] = {}
                for row in pending_rows:
                    pending_by_item[row.item_id] = pending_by_item.get(row.item_id, 0) + row.quantity

                # Побудувати мапи AE2 кількостей
                ae2_by_item: dict[str, int] = {item.item_id: item.quantity for item in items}

                # Знайти pending які тепер можна виконати (AE2 нарешті має предмети)
                # O(N) з накопичувальним словником замість O(N²) вкладеного sum()
                cumulative: dict[str, int] = {}
                for row in pending_rows:  # вже відсортовано по created_at.asc()
                    already_reserved = cumulative.get(row.item_id, 0)
                    ae2_qty = ae2_by_item.get(row.item_id, 0)
                    if ae2_qty > already_reserved:
                        triggered_extractions.append({
                            "pending_id": row.id,
                            "item_id": row.item_id,
                            "quantity": row.quantity,
                        })
                    cumulative[row.item_id] = already_reserved + row.quantity

                # Розрахувати ефективну кількість для вітрини (AE2 - весь борг)
                adjusted_items = []
                for item in items:
                    reserved = pending_by_item.get(item.item_id, 0)
                    effective_qty = item.quantity - reserved
                    if effective_qty > 0:
                        adjusted_items.append(item.model_copy(update={"quantity": effective_qty}))
                    # effective_qty <= 0 → предмет повністю в боргу, не показуємо на вітрині

                if adjusted_items:
                    values = [{"team_id": team_id, **item.model_dump()} for item in adjusted_items]
                    stmt = mysql_insert(MarketItem).values(values)
                    stmt = stmt.on_duplicate_key_update(
                        quantity=stmt.inserted.quantity,
                        price=stmt.inserted.price,
                        is_for_sale=stmt.inserted.is_for_sale,
                        seller_azuriom_id=stmt.inserted.seller_azuriom_id,
                        version=stmt.inserted.version,
                    )
                    await db_session.execute(stmt)

                # Видалити предмети яких більше нема в AE2 або повністю зарезервовані
                # Використовуємо adjusted_items (вже без зарезервованих)
                visible_pairs = [(item.item_id, item.item_nbt) for item in adjusted_items]
                if visible_pairs:
                    await db_session.execute(
                        delete(MarketItem).where(
                            MarketItem.team_id == team_id,
                            not_(
                                tuple_(MarketItem.item_id, MarketItem.item_nbt).in_(visible_pairs)
                            ),
                        )
                    )
                else:
                    # Всі предмети зарезервовані pending-ами — очистити вітрину
                    await db_session.execute(
                        delete(MarketItem).where(MarketItem.team_id == team_id)
                    )
            else:
                await db_session.execute(
                    delete(MarketItem).where(MarketItem.team_id == team_id)
                )

            await db_session.commit()
            logger.debug(
                f"Synced {len(items)} items for team {team_id}. "
                f"Triggered {len(triggered_extractions)} debt extractions."
            )
            return triggered_extractions
        except Exception as e:
            await db_session.rollback()
            logger.error(f"Error syncing market inventory for team {team_id}: {e}", exc_info=True)
            raise

    async def get_island_inventory(self, db_session: AsyncSession, team_id: int) -> List[MarketItem]:
        result = await db_session.execute(
            select(MarketItem).where(MarketItem.team_id == team_id)
        )
        return result.scalars().all()

    async def purchase_item(
        self,
        db_session: AsyncSession,
        team_id: int,
        item_id: str,
        quantity: int,
        buyer_azuriom_id: int = None,
    ) -> dict:
        """
        Deducts quantity after a purchase.
        Raises ValueError if item not found or not enough stock.
        """
        result = await db_session.execute(
            select(MarketItem).where(
                MarketItem.team_id == team_id,
                MarketItem.item_id == item_id,
                MarketItem.is_for_sale == True,
            ).with_for_update()  # Row-level lock — захист від concurrent purchases
        )
        item = result.scalars().first()

        if item is None:
            raise ValueError(f"Item '{item_id}' not found or not for sale.")
        if item.quantity < quantity:
            raise ValueError(f"Not enough stock. Available: {item.quantity}, requested: {quantity}.")

        unit_price = item.price
        seller_azuriom_id = item.seller_azuriom_id

        item.quantity -= quantity
        if item.quantity <= 0:
            await db_session.delete(item)

        tx = MarketTransaction(
            team_id=team_id,
            item_id=item_id,
            quantity=quantity,
            unit_price=unit_price,
            total_price=unit_price * quantity,
            buyer_azuriom_id=buyer_azuriom_id,
            seller_azuriom_id=seller_azuriom_id,
        )
        db_session.add(tx)
        await db_session.commit()
        return {
            "item_id": item_id,
            "purchased": quantity,
            "remaining": max(0, item.quantity),
            "unit_price": float(unit_price),
            "total_price": float(unit_price * quantity),
        }

    async def get_item(self, db_session: AsyncSession, team_id: int, item_id: str) -> MarketItem | None:
        """Повертає предмет з вітрини без блокування — для перевірки ціни перед покупкою."""
        result = await db_session.execute(
            select(MarketItem).where(
                MarketItem.team_id == team_id,
                MarketItem.item_id == item_id,
                MarketItem.is_for_sale == True,
            )
        )
        return result.scalars().first()

    async def record_sell_transaction(
        self, db_session: AsyncSession, team_id: int, item_id: str,
        quantity: int, unit_price: float, seller_azuriom_id: int,
    ) -> None:
        """Записує продаж предметів гравцем на маркет (без pending extraction)."""
        tx = MarketTransaction(
            team_id=team_id,
            item_id=item_id,
            quantity=quantity,
            unit_price=unit_price,
            total_price=round(unit_price * quantity, 2),
            buyer_azuriom_id=None,
            seller_azuriom_id=seller_azuriom_id,
            seller_paid=True,
        )
        db_session.add(tx)
        await db_session.commit()

    # ── Transactions ──────────────────────────────────────────────────────

    async def get_island_transactions(
        self, db_session: AsyncSession, team_id: int, skip: int = 0, limit: int = 100
    ) -> List[MarketTransaction]:
        result = await db_session.execute(
            select(MarketTransaction)
            .where(MarketTransaction.team_id == team_id)
            .order_by(MarketTransaction.created_at.desc())
            .offset(skip)
            .limit(limit)
        )
        return result.scalars().all()

    # ── Pending extractions ───────────────────────────────────────────────

    async def create_pending_extraction(
        self, db_session: AsyncSession, team_id: int, item_id: str, quantity: int
    ) -> MarketPendingExtraction:
        record = MarketPendingExtraction(team_id=team_id, item_id=item_id, quantity=quantity)
        db_session.add(record)
        await db_session.commit()
        await db_session.refresh(record)
        return record

    async def get_pending_extractions(
        self, db_session: AsyncSession, team_id: int, limit: int = 100
    ) -> List[MarketPendingExtraction]:
        result = await db_session.execute(
            select(MarketPendingExtraction)
            .where(MarketPendingExtraction.team_id == team_id)
            .limit(limit)
        )
        return result.scalars().all()

    async def confirm_extraction(
        self, db_session: AsyncSession, pending_id: int, team_id: int
    ) -> dict | None:
        """
        Острів підтвердив extraction. Знаходимо транзакцію і кредитуємо продавця.
        Повертає {seller_azuriom_id, total_price} для виклику Azuriom API.
        """
        result = await db_session.execute(
            select(MarketPendingExtraction).where(
                MarketPendingExtraction.id == pending_id,
                MarketPendingExtraction.team_id == team_id,
            ).with_for_update()
        )
        record = result.scalars().first()
        if record is None:
            return None

        # Знайти найстарішу неоплачену транзакцію для цього предмету
        tx_result = await db_session.execute(
            select(MarketTransaction).where(
                MarketTransaction.team_id == team_id,
                MarketTransaction.item_id == record.item_id,
                MarketTransaction.seller_paid == False,
            )
            .order_by(MarketTransaction.created_at.asc())
            .limit(1)
            .with_for_update()
        )
        tx = tx_result.scalars().first()

        seller_info = None
        if tx:
            tx.seller_paid = True
            seller_info = {
                "seller_azuriom_id": tx.seller_azuriom_id,
                "total_price": float(tx.total_price),
            }

        await db_session.delete(record)
        await db_session.commit()
        return seller_info or {}

    async def cancel_purchase(
        self, db_session: AsyncSession, pending_id: int, team_id: int
    ) -> dict | None:
        """
        Скасовує покупку — відновлює кількість в market_items і видаляє pending.
        Викликається якщо Azuriom не зміг зняти гроші з покупця після резервування в API.
        Повертає {buyer_azuriom_id, total_price} якщо потрібен рефанд.
        """
        result = await db_session.execute(
            select(MarketPendingExtraction).where(
                MarketPendingExtraction.id == pending_id,
                MarketPendingExtraction.team_id == team_id,
            ).with_for_update()
        )
        record = result.scalars().first()
        if record is None:
            return None

        # Знайти транзакцію (якщо вже записана)
        tx_result = await db_session.execute(
            select(MarketTransaction).where(
                MarketTransaction.team_id == team_id,
                MarketTransaction.item_id == record.item_id,
                MarketTransaction.seller_paid == False,
            )
            .order_by(MarketTransaction.created_at.desc())
            .limit(1)
            .with_for_update()
        )
        tx = tx_result.scalars().first()

        refund_info = None
        if tx:
            refund_info = {
                "buyer_azuriom_id": tx.buyer_azuriom_id,
                "total_price": float(tx.total_price),
            }
            await db_session.delete(tx)

        # Відновити кількість атомарним UPDATE (захист від concurrent cancel race)
        await db_session.execute(
            sqlalchemy_update(MarketItem)
            .where(
                MarketItem.team_id == team_id,
                MarketItem.item_id == record.item_id,
            )
            .values(quantity=MarketItem.quantity + record.quantity)
        )
        # Якщо item вже видалений (була куплена остання штука) — не відновлюємо,
        # наступний sync відновить з AE2

        await db_session.delete(record)
        await db_session.commit()

        logger.info(f"Purchase cancelled: pending {pending_id}, team {team_id}")
        return refund_info


crud_market = CRUDMarketItem()
