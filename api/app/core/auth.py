from typing import Optional
from fastapi import Header, HTTPException, status
from app.core.config import settings


async def require_api_key(x_api_key: Optional[str] = Header(None, alias="X-Api-Key")):
    valid_keys = {k for k in [settings.PROXY_API_KEY, settings.SPAWN_API_KEY] if k}
    if not valid_keys:
        return  # auth disabled when no keys configured
    if not x_api_key or x_api_key not in valid_keys:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API key",
        )
