"""This file is used to import all models into the Base.metadata scope.

It's useful for Alembic migrations or if you use Base.metadata.create_all().
"""
from app.db.base_class import Base
from app.models.island import Island, IslandQueue, IslandBackup, IslandEvent
from app.models.island_start_queue import IslandStartQueue
from app.models.team import Team, TeamMember
from app.models.market import MarketItem, MarketTransaction, MarketPendingExtraction, WarpPendingCommand
from app.models.analytics import MarketStatsHourly, EconomySnapshot, MarketAnomaly
from app.models.update import UpdateCampaign, UpdateQueue, IslandPendingCommand

__all__ = ["Base", "Island", "IslandQueue", "IslandBackup", "IslandEvent", "IslandStartQueue", "Team", "TeamMember", "MarketItem", "MarketTransaction", "MarketPendingExtraction", "WarpPendingCommand", "MarketStatsHourly", "EconomySnapshot", "MarketAnomaly", "UpdateCampaign", "UpdateQueue", "IslandPendingCommand"]
