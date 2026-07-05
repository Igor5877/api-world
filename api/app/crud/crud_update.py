from typing import List, Optional
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy import update as sqlalchemy_update
from datetime import datetime

from app.models.update import (
    UpdateCampaign as UpdateCampaignModel,
    IslandPendingCommand as IslandPendingCommandModel,
    CampaignStatusEnum,
)
from app.models.island import IslandBackup as IslandBackupModel


class CRUDUpdateCampaign:
    """CRUD operations for update campaigns."""

    async def create(self, db_session: AsyncSession, **fields) -> UpdateCampaignModel:
        """Creates a campaign row."""
        campaign = UpdateCampaignModel(**fields)
        db_session.add(campaign)
        await db_session.commit()
        await db_session.refresh(campaign)
        return campaign

    async def get(self, db_session: AsyncSession, *, campaign_id: int) -> Optional[UpdateCampaignModel]:
        """Gets a campaign by id."""
        result = await db_session.execute(
            select(UpdateCampaignModel).filter(UpdateCampaignModel.id == campaign_id)
        )
        return result.scalars().first()

    async def get_by_version(self, db_session: AsyncSession, *, version: str) -> Optional[UpdateCampaignModel]:
        """Gets a campaign by its version tag (webhook dedup)."""
        result = await db_session.execute(
            select(UpdateCampaignModel).filter(UpdateCampaignModel.version == version)
        )
        return result.scalars().first()

    async def get_active(self, db_session: AsyncSession) -> Optional[UpdateCampaignModel]:
        """Gets the campaign currently being rolled out (PENDING or IN_PROGRESS)."""
        result = await db_session.execute(
            select(UpdateCampaignModel)
            .filter(UpdateCampaignModel.status.in_([CampaignStatusEnum.PENDING, CampaignStatusEnum.IN_PROGRESS]))
            .order_by(UpdateCampaignModel.created_at)
            .limit(1)
        )
        return result.scalars().first()

    async def list_all(self, db_session: AsyncSession, *, limit: int = 50) -> List[UpdateCampaignModel]:
        """Lists campaigns, newest first."""
        result = await db_session.execute(
            select(UpdateCampaignModel).order_by(UpdateCampaignModel.created_at.desc()).limit(limit)
        )
        return list(result.scalars().all())

    async def set_status(self, db_session: AsyncSession, *, campaign_id: int,
                         status: CampaignStatusEnum, error_message: Optional[str] = None) -> None:
        """Updates the campaign lifecycle status."""
        values: dict = {"status": status}
        if status in (CampaignStatusEnum.COMPLETED, CampaignStatusEnum.FAILED, CampaignStatusEnum.ROLLED_BACK):
            values["completed_at"] = datetime.utcnow()
        if error_message is not None:
            values["error_message"] = error_message
        await db_session.execute(
            sqlalchemy_update(UpdateCampaignModel)
            .where(UpdateCampaignModel.id == campaign_id)
            .values(**values)
        )
        await db_session.commit()


class CRUDIslandPendingCommand:
    """CRUD operations for commands waiting to be executed on island servers."""

    async def add(self, db_session: AsyncSession, *, island_id: int, player_uuid: str,
                  command: str, campaign_id: Optional[int] = None) -> IslandPendingCommandModel:
        """Queues a command for an island."""
        entry = IslandPendingCommandModel(
            island_id=island_id, player_uuid=player_uuid,
            command=command, campaign_id=campaign_id,
        )
        db_session.add(entry)
        await db_session.commit()
        await db_session.refresh(entry)
        return entry

    async def get_undelivered(self, db_session: AsyncSession, *, player_uuid: str) -> List[IslandPendingCommandModel]:
        """Gets undelivered commands for an island (sent on WebSocket reconnect)."""
        result = await db_session.execute(
            select(IslandPendingCommandModel)
            .filter(IslandPendingCommandModel.player_uuid == player_uuid,
                    IslandPendingCommandModel.delivered.is_(False))
            .order_by(IslandPendingCommandModel.created_at)
        )
        return list(result.scalars().all())

    async def mark_delivered(self, db_session: AsyncSession, *, command_id: int) -> None:
        """Marks a command as executed (mod sent an ack)."""
        await db_session.execute(
            sqlalchemy_update(IslandPendingCommandModel)
            .where(IslandPendingCommandModel.id == command_id)
            .values(delivered=True)
        )
        await db_session.commit()

    async def mark_all_delivered_for_player(self, db_session: AsyncSession, *, player_uuid: str) -> int:
        """Closes all undelivered commands of an island.

        Called when the island reconnects after a restart: the fresh server
        already loaded the updated files, so queued reload commands are moot.

        Returns:
            The number of commands closed.
        """
        result = await db_session.execute(
            sqlalchemy_update(IslandPendingCommandModel)
            .where(IslandPendingCommandModel.player_uuid == player_uuid,
                   IslandPendingCommandModel.delivered.is_(False))
            .values(delivered=True)
        )
        await db_session.commit()
        return result.rowcount or 0


class CRUDIslandBackupOps:
    """Backup bookkeeping for the update system."""

    async def create(self, db_session: AsyncSession, *, island_id: int, snapshot_name: str,
                     backup_type: str, version: str, campaign_id: Optional[int] = None,
                     backup_path: Optional[str] = None, changed_paths: Optional[list] = None,
                     description: Optional[str] = None) -> IslandBackupModel:
        """Records a backup (LXD snapshot or host-side file backup)."""
        backup = IslandBackupModel(
            island_id=island_id, snapshot_name=snapshot_name, backup_type=backup_type,
            version=version, campaign_id=campaign_id, backup_path=backup_path,
            changed_paths=changed_paths, description=description,
        )
        db_session.add(backup)
        await db_session.commit()
        await db_session.refresh(backup)
        return backup

    async def get_latest_files_backup(self, db_session: AsyncSession, *, island_id: int,
                                      campaign_id: Optional[int] = None) -> Optional[IslandBackupModel]:
        """Gets the newest file-level backup for an island (optionally per campaign)."""
        stmt = (
            select(IslandBackupModel)
            .filter(IslandBackupModel.island_id == island_id, IslandBackupModel.backup_type == "files")
        )
        if campaign_id is not None:
            stmt = stmt.filter(IslandBackupModel.campaign_id == campaign_id)
        stmt = stmt.order_by(IslandBackupModel.created_at.desc()).limit(1)
        result = await db_session.execute(stmt)
        return result.scalars().first()

    async def get_files_backups_for_campaign(self, db_session: AsyncSession, *, campaign_id: int) -> List[IslandBackupModel]:
        """Gets all file-level backups of a campaign (campaign rollback)."""
        result = await db_session.execute(
            select(IslandBackupModel)
            .filter(IslandBackupModel.campaign_id == campaign_id, IslandBackupModel.backup_type == "files")
        )
        return list(result.scalars().all())


crud_update_campaign = CRUDUpdateCampaign()
crud_island_pending_command = CRUDIslandPendingCommand()
crud_island_backup_ops = CRUDIslandBackupOps()
