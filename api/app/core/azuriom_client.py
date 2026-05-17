import logging
import httpx
from app.core.config import settings

logger = logging.getLogger(__name__)

# Singleton per-worker — reuses TCP+TLS connections instead of creating per request.
# Eliminates TIME_WAIT accumulation that causes ephemeral port exhaustion over time.
_client: httpx.AsyncClient | None = None


def _get_client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            timeout=httpx.Timeout(5.0, connect=5.0),
            limits=httpx.Limits(max_connections=10, max_keepalive_connections=5),
        )
    return _client


async def close_client() -> None:
    """Call on app shutdown to gracefully close connections."""
    global _client
    if _client and not _client.is_closed:
        await _client.aclose()
        _client = None


async def _call_azuriom(method: str, path: str, **kwargs) -> httpx.Response | None:
    if not settings.AZURIOM_API_URL or not settings.AZURIOM_LINK_TOKEN:
        logger.warning("Azuriom API not configured")
        return None
    url = f"{settings.AZURIOM_API_URL.rstrip('/')}/{path.lstrip('/')}"
    headers = {"Azuriom-Link-Token": settings.AZURIOM_LINK_TOKEN}
    for attempt in range(2):
        try:
            return await _get_client().request(method, url, headers=headers, **kwargs)
        except Exception as e:
            if attempt == 0:
                logger.warning(f"Azuriom request failed (attempt 1), retrying: {e}")
            else:
                logger.error(f"Azuriom request error: {e}")
    return None


async def _modify_money(azuriom_id: int | None, amount: float, label: str) -> bool:
    if not azuriom_id or azuriom_id == -1:
        logger.warning(f"Invalid Azuriom ID ({azuriom_id}) — {label} skipped")
        return False
    action = "add" if amount >= 0 else "remove"
    resp = await _call_azuriom(
        "POST", f"/azlink/user/{azuriom_id}/money/{action}",
        json={"amount": round(abs(float(amount)), 2)},
    )
    if resp is None:
        return False
    if resp.status_code == 200:
        logger.info(f"{label}: user={azuriom_id} amount={amount:+.2f}")
        return True
    logger.error(f"Azuriom {label} failed: HTTP {resp.status_code} — {resp.text}")
    return False


async def get_balance(azuriom_id: int | None) -> float | None:
    """Повертає баланс гравця або None якщо запит не вдався."""
    if not azuriom_id or azuriom_id == -1:
        return None
    resp = await _call_azuriom("GET", f"/azlink/user/{azuriom_id}")
    if resp is None or resp.status_code != 200:
        return None
    try:
        return float(resp.json().get("money", 0))
    except Exception:
        return None


async def credit_seller(seller_azuriom_id: int | None, amount: float) -> bool:
    return await _modify_money(seller_azuriom_id, amount, "credit_seller")


async def refund_buyer(buyer_azuriom_id: int | None, amount: float) -> bool:
    return await _modify_money(buyer_azuriom_id, amount, "refund_buyer")


async def deduct_buyer(buyer_azuriom_id: int | None, amount: float) -> bool:
    return await _modify_money(buyer_azuriom_id, -amount, "deduct_buyer")
