import asyncio
from app.db.session import init_db
from app.db.base import Base

async def test():
    print("Testing DB initialization for market models...")
    await init_db()
    print("Market models created successfully!")

if __name__ == "__main__":
    asyncio.run(test())
