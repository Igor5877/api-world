import asyncio
from app.db.session import engine
from app.db.base_class import Base

# Imports needed to populate Base.metadata
import app.models.island
import app.models.team
import app.models.island_start_queue
import app.models.sales

async def reset_db():
    print("Підключення до бази даних та видалення старих таблиць...")
    async with engine.begin() as conn:
        # УВАГА: Це видалить всі дані! Використовувати тільки для локального тестування
        await conn.run_sync(Base.metadata.drop_all)
        print("Створення нових таблиць з актуальною схемою...")
        await conn.run_sync(Base.metadata.create_all)
    print("Готово! Тепер можна запускати uvicorn.")

if __name__ == "__main__":
    asyncio.run(reset_db())
