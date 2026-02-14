import asyncio
from unittest.mock import AsyncMock, MagicMock
from fastapi import HTTPException
import sys
import os

# Mock the database service import
sys.modules['app.services.island_service'] = MagicMock()
sys.modules['app.db.session'] = MagicMock()

# Now import the module we want to test
from app.api.v1.endpoints.islands import verify_launcher_token
from app.core.config import settings

async def test_endpoint():
    print("Testing verify_launcher_token...")

    # Setup
    settings.LAUNCHER_API_KEY = "test-key"

    # Test 1: Invalid Token
    try:
        await verify_launcher_token("wrong-key")
        print("FAIL: Invalid token should raise exception")
    except HTTPException as e:
        if e.status_code == 403:
            print("PASS: Invalid token rejected (403)")
        else:
            print(f"FAIL: Unexpected status code {e.status_code}")
    except Exception as e:
        print(f"FAIL: Unexpected exception type: {type(e)}")

    # Test 2: Valid Token
    try:
        await verify_launcher_token("test-key")
        print("PASS: Valid token accepted")
    except Exception as e:
        print(f"FAIL: Valid token raised exception: {e}")

if __name__ == "__main__":
    asyncio.run(test_endpoint())
