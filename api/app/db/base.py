"""This file is used to import all models into the Base.metadata scope.

It's useful for Alembic migrations or if you use Base.metadata.create_all().
"""
from app.db.base_class import Base
from app.models.island import Island, IslandQueue, IslandSetting, IslandBackup
from app.models.team import Team, TeamMember

__all__ = ["Base", "Island", "IslandQueue", "IslandSetting", "IslandBackup", "Team", "TeamMember"]
