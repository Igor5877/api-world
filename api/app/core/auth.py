from typing import Optional
from fastapi import Header, HTTPException, status, Query
from app.core.config import settings


async def require_api_key(x_api_key: Optional[str] = Header(None, alias="X-Api-Key")):
    # LAUNCHER_API_KEY is deliberately NOT accepted here — it authorizes only
    # POST /islands/launcher/start (see require_launcher_token below), never
    # the rest of the islands router (stop/freeze/create/etc for arbitrary
    # players). A leaked launcher key must not become a general API key.
    valid_keys = {k for k in [settings.PROXY_API_KEY, settings.SPAWN_API_KEY] if k}
    if not valid_keys:
        return  # auth disabled when no keys configured
    if not x_api_key or x_api_key not in valid_keys:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API key",
        )


async def require_launcher_token(x_launcher_token: Optional[str] = Header(None, alias="X-Launcher-Token")):
    """Auth for POST /islands/launcher/start only — a distinct header/key
    from require_api_key, deliberately scoped to this one action."""
    if not settings.LAUNCHER_API_KEY:
        return  # auth disabled when no key configured
    if not x_launcher_token or x_launcher_token != settings.LAUNCHER_API_KEY:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing launcher token",
        )


async def require_prometheus_sd_token(token: Optional[str] = Query(None),
                                     authorization: Optional[str] = Header(None)):
    """Auth for /prometheus/sd: `Authorization: Bearer <token>` (Prometheus
    http_sd_configs → authorization:) or, for older configs, `?token=`.
    The header is preferred — a token in the URL ends up in access logs."""
    if not settings.PROMETHEUS_SD_TOKEN:
        return  # auth disabled when no token configured
    bearer = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if settings.PROMETHEUS_SD_TOKEN not in (bearer, token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing token",
        )
