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
    """CRUD operations for islands."""
    async def get(self, db_session: AsyncSession, *, island_id: int) -> Optional[IslandModel]:
        """Gets an island by its ID.

        Args:
            db_session: The database session.
            island_id: The ID of the island to get.

        Returns:
            The island, or None if not found.

        Raises:
            SQLAlchemyError: If a database error occurs.
        """
        try:
            result = await db_session.execute(select(IslandModel).filter(IslandModel.id == island_id))
            return result.scalars().first()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get for id {island_id}: {e}")
            # Depending on policy, you might re-raise, raise a custom DB error, or return None
            raise

    async def get_by_player_uuid(self, db_session: AsyncSession, *, player_uuid: str) -> Optional[IslandModel]:
        """Gets an island by player UUID.

        This is now a legacy-support method. The primary method should be to get
        the island via the team.

        Args:
            db_session: The database session.
            player_uuid: The UUID of the player.

        Returns:
            The island, or None if not found.

        Raises:
            SQLAlchemyError: If a database error occurs.
        """
        try:
            result = await db_session.execute(
                select(IslandModel).filter(IslandModel.player_uuid == player_uuid)
            )
            return result.scalars().first()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get_by_player_uuid for player_uuid {player_uuid}: {e}")
            raise

    async def get_by_team_id(self, db_session: AsyncSession, *, team_id: int) -> Optional[IslandModel]:
        """Gets an island by team ID.

        Args:
            db_session: The database session.
            team_id: The ID of the team.

        Returns:
            The island, or None if not found.

        Raises:
            SQLAlchemyError: If a database error occurs.
        """
        try:
            result = await db_session.execute(
                select(IslandModel).filter(IslandModel.team_id == team_id)
            )
            return result.scalars().first()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get_by_team_id for team_id {team_id}: {e}")
            raise

    async def create(self, db_session: AsyncSession, *, team_id: int, container_name: str, player_uuid: Optional[str] = None, player_name: Optional[str] = None, initial_status: IslandStatusEnum = IslandStatusEnum.PENDING_CREATION) -> IslandModel:
        """Creates a new island for a team.

        This method does not commit the transaction.

        Args:
            db_session: The database session.
            team_id: The ID of the team.
            container_name: The name of the container.
            player_uuid: The UUID of the player.
            player_name: The name of the player.
            initial_status: The initial status of the island.

        Returns:
            The created island.
        """
        db_obj_data = {
            "team_id": team_id,
            "container_name": container_name,
            "status": initial_status,
            "player_uuid": player_uuid,
            "player_name": player_name,
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
        """Updates an existing island.

        Args:
            db_session: The database session.
            db_obj: The island to update.
            obj_in: The data to update the island with.

        Returns:
            The updated island.

        Raises:
            SQLAlchemyError: If a database error occurs.
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
            
    async def update_status_by_team_id(
        self, db_session: AsyncSession, *, team_id: int, status: IslandStatusEnum,
        extra_fields: Optional[Dict[str, Any]] = None
    ) -> Optional[IslandModel]:
        """Atomically updates the status of an island and optionally other fields using team_id.

        This uses an UPDATE statement directly for efficiency and correctness.

        Args:
            db_session: The database session.
            team_id: The ID of the team owning the island.
            status: The new status.
            extra_fields: A dictionary of extra fields to update.

        Returns:
            The updated island, or None if not found.

        Raises:
            SQLAlchemyError: If a database error occurs.
        """
        values_to_update = {"status": status}
        if extra_fields:
            values_to_update.update(extra_fields)
        
        try:
            stmt = (
                sqlalchemy_update(IslandModel)
                .where(IslandModel.team_id == team_id)
                .values(**values_to_update)
            )
            result = await db_session.execute(stmt)

            # Check if any row was actually updated
            if result.rowcount == 0:
                logger.warning(f"No island found for team_id {team_id} during status update. No rows updated.")
                # We still commit because the transaction was successful, even if it did nothing.
                await db_session.commit()
                return None

            await db_session.commit()

            # Fetch the updated island to return it
            updated_island_result = await db_session.execute(
                select(IslandModel).filter(IslandModel.team_id == team_id)
            )
            updated_island = updated_island_result.scalars().first()
            
            return updated_island
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.update_status_by_team_id for team_id {team_id}: {e}")
            await db_session.rollback()
            raise


    async def remove_by_player_uuid(self, db_session: AsyncSession, *, player_uuid: str) -> Optional[IslandModel]:
        """Removes an island by player UUID.

        Args:
            db_session: The database session.
            player_uuid: The UUID of the player.

        Returns:
            The removed island object, or None if not found.

        Raises:
            SQLAlchemyError: If a database error occurs.
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
        """Removes an island by its ID.

        Args:
            db_session: The database session.
            island_id: The ID of the island to remove.

        Returns:
            The removed island object, or None if not found.

        Raises:
            SQLAlchemyError: If a database error occurs.
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
        """Gets multiple islands with pagination.

        Args:
            db_session: The database session.
            skip: The number of islands to skip.
            limit: The maximum number of islands to return.

        Returns:
            A list of islands.

        Raises:
            SQLAlchemyError: If a database error occurs.
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
        """Gets islands by a specific status.

        Args:
            db_session: The database session.
            status: The status to filter by.
            limit: The maximum number of islands to return.

        Returns:
            A list of islands.

        Raises:
            SQLAlchemyError: If a database error occurs.
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
        """Gets islands by a list of specific statuses.

        Args:
            db_session: The database session.
            statuses: The statuses to filter by.
            limit: The maximum number of islands to return.

        Returns:
            A list of islands.

        Raises:
            SQLAlchemyError: If a database error occurs.
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
        """Gets the number of islands that are currently running.

        Args:
            db_session: The database session.

        Returns:
            The number of running islands.

        Raises:
            SQLAlchemyError: If a database error occurs.
        """
        try:
            result = await db_session.execute(
                select(func.count(IslandModel.id)).filter(IslandModel.status == IslandStatusEnum.RUNNING)
            )
            return result.scalar_one()
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.get_running_islands_count: {e}")
            raise

    async def update_by_id(self, db_session: AsyncSession, *, island_id: int, obj_in: Union[IslandUpdate, Dict[str, Any]]) -> Optional[IslandModel]:
        """Updates an island by its ID.

        Args:
            db_session: The database session.
            island_id: The ID of the island to update.
            obj_in: The data to update the island with.

        Returns:
            The updated island, or None if not found.

        Raises:
            SQLAlchemyError: If a database error occurs.
        """
        if isinstance(obj_in, dict):
            update_data = obj_in
        else:
            update_data = obj_in.model_dump(exclude_unset=True)

        if not update_data:
            return await self.get(db_session, island_id=island_id)

        try:
            stmt = (
                sqlalchemy_update(IslandModel)
                .where(IslandModel.id == island_id)
                .values(**update_data)
            )
            await db_session.execute(stmt)
            await db_session.commit()
            return await self.get(db_session, island_id=island_id)
        except SQLAlchemyError as e:
            logger.error(f"Database error in CRUDisland.update_by_id for island_id {island_id}: {e}")
            await db_session.rollback()
            raise

# Instantiate the CRUD object for islands
crud_island = CRUDisland()
