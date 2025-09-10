# This file is used to import all models into the Base.metadata scope.
# It's useful for Alembic migrations or if you use Base.metadata.create_all().

# This file is used to import all models into the Base.metadata scope.
# It's useful for Alembic migrations or if you use Base.metadata.create_all().

from app.db.base_class import Base
from app.models.island import Island, IslandQueue, IslandSetting, IslandBackup # noqa F401 - All models from island.py, including the corrected IslandQueue
from app.models.team import Team, TeamMember # noqa F401

# You would import other models here as you create them, e.g.:
# from app.models.user import User # noqa F401
# from app.models.item import Item # noqa F401

# This ensures that SQLAlchemy's metadata is aware of all your tables
# when you run something like `Base.metadata.create_all(engine)` or when Alembic
# auto-generates migration scripts.

# You would import other models here as you create them, e.g.:
# from app.models.user import User # noqa F401
# from app.models.item import Item # noqa F401

# This ensures that SQLAlchemy's metadata is aware of all your tables
# when you run something like `Base.metadata.create_all(engine)` or when Alembic
# auto-generates migration scripts.
