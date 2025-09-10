from typing import Any, Dict, Optional, Union, List
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy import update as sqlalchemy_update, delete as sqlalchemy_delete, func
from sqlalchemy.exc import SQLAlchemyError
import logging


from app.models.island import Island as IslandModel
from app.schemas.island import IslandCreate, IslandUpdate, IslandStatusEnum # Pydantic schemas

logger = logging.getLogger(__name__)

class CRUDisland:
    async def get(self, db_session: AsyncSession, *, island_id: int) -> Optional[IslandModel]:
        """
        Get an island by its ID.
        """
        try:
            result = await db_session.execute(select(IslandModel).filter(IslandModel.id == island_id))
            return result.scalars().first()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get for id {island_id}: {e}")
            # Depending on policy, you might re-raise, raise a custom DB error, or return None
            raise

    async def get_by_player_uuid(self, db_session: AsyncSession, *, player_uuid: UUID) -> Optional[IslandModel]:
        """
        Get an island by player UUID. This is now a legacy-support method.
        The primary method should be to get island via team.
        """
        try:
            result = await db_session.execute(
                select(IslandModel).filter(IslandModel.player_uuid == str(player_uuid))
            )
            return result.scalars().first()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get_by_player_uuid for player_uuid {player_uuid}: {e}")
            raise

    async def get_by_team_id(self, db_session: AsyncSession, *, team_id: int) -> Optional[IslandModel]:
        """
        Get an island by team ID.
        """
        try:
            result = await db_session.execute(
                select(IslandModel).filter(IslandModel.team_id == team_id)
            )
            return result.scalars().first()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get_by_team_id for team_id {team_id}: {e}")
            raise

    async def create(self, db_session: AsyncSession, *, team_id: int, container_name: str, initial_status: IslandStatusEnum = IslandStatusEnum.PENDING_CREATION) -> IslandModel:
        """
        Create a new island for a team. Does NOT commit.
        """
        db_obj_data = {
            "team_id": team_id,
            "container_name": container_name,
            "status": initial_status
        }

        db_obj = IslandModel(**db_obj_data)
        db_session.add(db_obj)
        # The service layer is responsible for committing.
        return db_obj

    async def update(
        self, 
        db_session: AsyncSession, 
        *, 
        db_obj: IslandModel, # The SQLAlchemy model instance to update
        obj_in: Union[IslandUpdate, Dict[str, Any]] # Pydantic schema or dict with update data
    ) -> IslandModel:
        """
        Update an existing island.
        `db_obj` is the current database object.
        `obj_in` contains the fields to update.
        """
        if isinstance(obj_in, dict):
            update_data = obj_in
        else:
            # For Pydantic models, .model_dump(exclude_unset=True) is useful to only update provided fields
            update_data = obj_in.model_dump(exclude_unset=True)

        # Ensure player_uuid, if present in update_data, is stringified
        if 'player_uuid' in update_data and isinstance(update_data['player_uuid'], UUID):
            update_data['player_uuid'] = str(update_data['player_uuid'])

        for field, value in update_data.items():
            if hasattr(db_obj, field):
                setattr(db_obj, field, value)
            else:
                logger.warning(f"Attempted to update non-existent field '{field}' in IslandModel.")
        
        # Note: `updated_at` should be handled by the database `onupdate=func.now()` if configured,
        # or can be manually set here: db_obj.updated_at = datetime.utcnow()
        
        try:
            db_session.add(db_obj) # Add the modified object to the session
            await db_session.commit()
            await db_session.refresh(db_obj)
            return db_obj
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.update for island id {db_obj.id}: {e}")
            await db_session.rollback()
            raise
            
    async def update_status(
        self, db_session: AsyncSession, *, player_uuid: UUID, status: IslandStatusEnum, 
        extra_fields: Optional[Dict[str, Any]] = None
    ) -> Optional[IslandModel]:
        """
        Atomically update the status of an island and optionally other fields.
        This uses an UPDATE statement directly for efficiency, especially for status changes.
        """
        values_to_update = {"status": status}
        if extra_fields:
            values_to_update.update(extra_fields)
        
        # Ensure player_uuid is string for the query
        player_uuid_str = str(player_uuid)

        try:
            # First, perform the update
            stmt = (
                sqlalchemy_update(IslandModel)
                .where(IslandModel.player_uuid == player_uuid_str)
                .values(**values_to_update)
            )
            await db_session.execute(stmt)
            await db_session.commit()

            # Then, fetch the updated island
            # Using the existing get_by_player_uuid method might be cleaner if it doesn't cause issues with session state
            # For now, a direct select:
            updated_island_result = await db_session.execute(
                select(IslandModel).filter(IslandModel.player_uuid == player_uuid_str)
            )
            updated_island = updated_island_result.scalars().first()
            
            if not updated_island:
                 logger.warning(f"No island found with player_uuid {player_uuid_str} after status update.")
            return updated_island
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.update_status for player_uuid {player_uuid_str}: {e}")
            await db_session.rollback()
            raise


    async def remove_by_player_uuid(self, db_session: AsyncSession, *, player_uuid: UUID) -> Optional[IslandModel]:
        """
        Remove an island by player UUID. Returns the removed island object or None if not found.
        """
        # First, get the object to ensure it exists and to return it
        island_to_delete = await self.get_by_player_uuid(db_session, player_uuid=player_uuid)
        if not island_to_delete:
            return None # Or raise an exception if preferred

        try:
            await db_session.delete(island_to_delete)
            await db_session.commit()
            return island_to_delete # The object is now detached but contains the data before deletion
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.remove_by_player_uuid for player_uuid {player_uuid}: {e}")
            await db_session.rollback()
            raise
            
    async def remove_by_id(self, db_session: AsyncSession, *, island_id: int) -> Optional[IslandModel]:
        """
        Remove an island by its ID. Returns the removed island object or None if not found.
        """
        island_to_delete = await self.get(db_session, island_id=island_id)
        if not island_to_delete:
            return None

        try:
            await db_session.delete(island_to_delete)
            await db_session.commit()
            return island_to_delete
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.remove_by_id for island_id {island_id}: {e}")
            await db_session.rollback()
            raise


    async def get_multi(
        self, db_session: AsyncSession, *, skip: int = 0, limit: int = 100
    ) -> List[IslandModel]:
        """
        Get multiple islands with pagination.
        """
        try:
            result = await db_session.execute(
                select(IslandModel).offset(skip).limit(limit).order_by(IslandModel.id)
            )
            return result.scalars().all()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get_multi: {e}")
            raise

    async def get_islands_by_status(self, db_session: AsyncSession, *, status: IslandStatusEnum, limit: int = 100) -> List[IslandModel]:
        """
        Get islands by a specific status.
        """
        try:
            result = await db_session.execute(
                select(IslandModel).filter(IslandModel.status == status).limit(limit).order_by(IslandModel.updated_at) # Or by created_at
            )
            return result.scalars().all()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get_islands_by_status for status {status}: {e}")
            raise

    async def get_islands_by_statuses(self, db_session: AsyncSession, *, statuses: List[IslandStatusEnum], limit: int = 1000) -> List[IslandModel]:
        """
        Get islands by a list of specific statuses.
        """
        try:
            # Ensure statuses are actual enum members if string names are passed, though type hint should enforce IslandStatusEnum
            # valid_statuses = [s.value if isinstance(s, IslandStatusEnum) else s for s in statuses]
            # Using .in_ operator for a list of statuses
            result = await db_session.execute(
                select(IslandModel).filter(IslandModel.status.in_(statuses)).limit(limit).order_by(IslandModel.updated_at)
            )
            return result.scalars().all()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get_islands_by_statuses for statuses {statuses}: {e}")
            raise

    async def get_running_islands_count(self, db_session: AsyncSession) -> int:
        """
        Get the number of islands that are currently running.
        """
        try:
            result = await db_session.execute(
                select(func.count(IslandModel.id)).filter(IslandModel.status == IslandStatusEnum.RUNNING)
            )
            return result.scalar_one()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get_running_islands_count: {e}")
            raise

# Instantiate the CRUD object for islands
crud_island = CRUDisland()
