import logging
import httpx
from app.core.config import settings

logger = logging.getLogger(__name__)

async def credit_seller(seller_azuriom_id: int, amount: float) -> bool:
    """Переказує гроші продавцю через Azuriom Link API."""
    if not settings.AZURIOM_API_URL or not settings.AZURIOM_LINK_TOKEN:
        logger.warning("Azuriom API not configured — seller credit skipped")
        return False
    if seller_azuriom_id == -1:
        logger.warning("Seller Azuriom ID unknown — cannot credit")
        return False

    url = f"{settings.AZURIOM_API_URL.rstrip('/')}/user/{seller_azuriom_id}/money/add"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(
                url,
                json={"amount": round(float(amount), 2)},
                headers={"Azuriom-Link-Token": settings.AZURIOM_LINK_TOKEN},
            )
        if resp.status_code == 200:
            logger.info(f"Credited seller {seller_azuriom_id}: +{amount}")
            return True
        logger.error(f"Azuriom credit failed: HTTP {resp.status_code} — {resp.text}")
        return False
    except Exception as e:
        logger.error(f"Azuriom credit error: {e}")
        return False


async def refund_buyer(buyer_azuriom_id: int, amount: float) -> bool:
    """Повертає гроші покупцю (при скасуванні або провалі)."""
    if not settings.AZURIOM_API_URL or not settings.AZURIOM_LINK_TOKEN:
        logger.warning("Azuriom API not configured — refund skipped")
        return False
    if buyer_azuriom_id == -1:
        logger.warning("Buyer Azuriom ID unknown — cannot refund")
        return False

    url = f"{settings.AZURIOM_API_URL.rstrip('/')}/user/{buyer_azuriom_id}/money/add"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(
                url,
                json={"amount": round(float(amount), 2)},
                headers={"Azuriom-Link-Token": settings.AZURIOM_LINK_TOKEN},
            )
        if resp.status_code == 200:
            logger.info(f"Refunded buyer {buyer_azuriom_id}: +{amount}")
            return True
        logger.error(f"Azuriom refund failed: HTTP {resp.status_code} — {resp.text}")
        return False
    except Exception as e:
        logger.error(f"Azuriom refund error: {e}")
        return False
