"""Business logic for the island auto-update system.

Applies one campaign (git tag) to islands: file sync, snapshots, backups,
in-game reload commands, rollbacks and the template container refresh.
world/ is NEVER touched — neither on update nor on rollback.
"""
import logging
import os
import pathlib
from typing import List, Optional, Tuple

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.crud.crud_island import crud_island
from app.crud.crud_update import crud_update_campaign, crud_island_pending_command, crud_island_backup_ops
from app.models.island import Island as IslandModel
from app.models.team import Team as TeamModel
from app.models.update import UpdateCampaign as UpdateCampaignModel, CampaignStatusEnum
from app.schemas.island import IslandStatusEnum
from app.services import git_sync
from app.services.git_sync import SYNC_DIRS, DELETE_EXTRA_DIRS
from app.services.lxd_service import lxd_service
from app.services.websocket_manager import manager as websocket_manager

logger = logging.getLogger(__name__)


class UpdateServiceError(Exception):
    """Raised when applying or rolling back an update fails."""
    pass


class UpdateService:
    """Applies update campaigns to island containers."""

    # ── helpers ───────────────────────────────────────────────────────

    async def get_island_owner_uuid(self, db_session: AsyncSession, island: IslandModel) -> Optional[str]:
        """Returns the UUID used as the island's identity (WS client id, endpoints).

        Team islands use the team owner's UUID; legacy solo islands use player_uuid.
        """
        if island.player_uuid:
            return str(island.player_uuid)
        if island.team_id:
            result = await db_session.execute(select(TeamModel).where(TeamModel.id == island.team_id))
            team = result.scalars().first()
            if team:
                return str(team.owner_uuid)
        return None

    def _repo_sync_dirs(self) -> List[pathlib.Path]:
        """Returns the whitelisted repo directories that actually exist."""
        repo = pathlib.Path(settings.UPDATES_REPO_LOCAL_PATH)
        return [repo / d for d in SYNC_DIRS if (repo / d).is_dir()]

    def _backup_dir_for(self, island: IslandModel, version: str) -> str:
        """Host-side backup directory for one island and campaign version."""
        return os.path.join(settings.UPDATES_BACKUP_DIR, island.container_name, version)

    def _paths_to_back_up(self, campaign: UpdateCampaignModel) -> List[str]:
        """Container paths (modified or deleted by the campaign) that must be saved."""
        target = settings.UPDATES_TARGET_DIR.rstrip("/")
        paths = []
        for entry in (campaign.changed_paths or []):
            status, path = entry[0], entry[1]
            top = path.split("/", 1)[0]
            if top not in SYNC_DIRS:
                continue
            if status in ("M", "D", "R"):  # files that existed before the update
                paths.append(f"{target}/{path}")
        return paths

    def _paths_added_by(self, campaign: UpdateCampaignModel) -> List[str]:
        """Container paths added by the campaign (deleted again on rollback)."""
        target = settings.UPDATES_TARGET_DIR.rstrip("/")
        return [
            f"{target}/{entry[1]}"
            for entry in (campaign.changed_paths or [])
            if entry[0] == "A" and entry[1].split("/", 1)[0] in SYNC_DIRS
        ]

    async def _push_repo_to_container(self, container_name: str):
        """Syncs all whitelisted repo directories into the container.

        The full state is always pushed (not just the diff) so islands that
        missed earlier campaigns converge. Stale files are deleted only in
        mods/ — config/ may hold island-specific files the repo doesn't know.
        """
        target = settings.UPDATES_TARGET_DIR
        for local_dir in self._repo_sync_dirs():
            await lxd_service.push_directory(
                container_name,
                str(local_dir),
                target,
                delete_extra=(local_dir.name in DELETE_EXTRA_DIRS),
            )

    async def _queue_reload_commands(self, db_session: AsyncSession, island: IslandModel,
                                     owner_uuid: str, campaign: UpdateCampaignModel):
        """Queues the campaign's reload commands and pushes them over WebSocket.

        The command stays in island_pending_commands until the mod acknowledges
        it, so a dropped socket doesn't lose the reload.
        """
        for command in (campaign.reload_commands or []):
            pending = await crud_island_pending_command.add(
                db_session, island_id=island.id, player_uuid=owner_uuid,
                command=command, campaign_id=campaign.id,
            )
            await websocket_manager.send_personal_message(
                {"type": "execute_command", "command": command, "pending_id": pending.id},
                f"island_{owner_uuid}",
            )

    async def notify_island(self, owner_uuid: str, message: str, kick: bool = False):
        """Sends a PENDING_UPDATE chat message (optionally a kick) to an island server."""
        await websocket_manager.send_personal_message(
            {"type": "pending_update", "message": message, "kick": kick},
            f"island_{owner_uuid}",
        )

    # ── campaign creation ─────────────────────────────────────────────

    async def create_campaign_from_tag(self, db_session: AsyncSession, *, tag: str,
                                       islands: Optional[List[str]] = None) -> UpdateCampaignModel:
        """Builds the manifest for a tag and creates the campaign with its queue.

        Args:
            db_session: The database session.
            tag: The git tag to roll out.
            islands: Player UUIDs to limit the rollout to; None means all islands.

        Returns:
            The created campaign.

        Raises:
            ValueError: If the tag was already rolled out or another campaign is active.
        """
        from app.crud.crud_update_queue import crud_update_queue

        if await crud_update_campaign.get_by_version(db_session, version=tag):
            raise ValueError(f"Campaign for tag '{tag}' already exists.")
        active = await crud_update_campaign.get_active(db_session)
        if active:
            raise ValueError(f"Campaign '{active.version}' is still active. Only one campaign can run at a time.")

        manifest = await git_sync.build_manifest(tag)

        campaign = await crud_update_campaign.create(
            db_session,
            version=manifest.version,
            previous_version=manifest.previous_version,
            git_commit=manifest.git_commit,
            update_type=manifest.update_type,
            requires_restart=manifest.requires_restart,
            reload_commands=manifest.reload_commands,
            changed_paths=[list(p) for p in manifest.changed_paths],
            message=manifest.message,
            status=CampaignStatusEnum.PENDING,
        )

        if islands:
            targets = []
            for player_uuid in islands:
                island = await crud_island.get_by_player_uuid(db_session, player_uuid=player_uuid)
                if island:
                    targets.append(island)
        else:
            targets = await crud_island.get_multi(db_session, skip=0, limit=100000)

        queued = 0
        for island in targets:
            if island.skip_auto_updates:
                continue
            if island.status in (IslandStatusEnum.DELETING, IslandStatusEnum.ARCHIVED):
                continue
            await crud_update_queue.add_island(db_session, campaign_id=campaign.id, island=island)
            queued += 1

        logger.info(f"UpdateService: Campaign '{tag}' created (id={campaign.id}), {queued} islands queued.")
        return campaign

    # ── applying an update to one island ──────────────────────────────

    async def perform_island_update(self, db_session: AsyncSession, island: IslandModel,
                                    campaign: UpdateCampaignModel):
        """Applies a campaign to a single island.

        The caller (update worker) guarantees the island is in an updatable
        state: for hard updates the container is stopped here if needed; soft
        updates run against live servers.

        Raises:
            UpdateServiceError: If any step fails (the island is left in
                UPDATE_FAILED and the container is not restarted).
        """
        container_name = island.container_name
        owner_uuid = await self.get_island_owner_uuid(db_session, island)
        previous_status = island.status

        state = await lxd_service.get_container_state(container_name)
        if state is None:
            raise UpdateServiceError(f"Container '{container_name}' not found in LXD.")

        await crud_island.update_by_id(db_session, island_id=island.id,
                                       obj_in={"status": IslandStatusEnum.UPDATING})
        try:
            backup_dir = self._backup_dir_for(island, campaign.version)
            paths_to_save = self._paths_to_back_up(campaign)

            if campaign.requires_restart:
                # Hard update: container must be stopped (world consistency).
                lxd_status = (state.get("status") or "").lower()
                if lxd_status == "frozen":
                    await lxd_service.unfreeze_container(container_name)
                    lxd_status = "running"
                if lxd_status == "running":
                    # Graceful first (systemd stops minecraft cleanly, world is saved).
                    try:
                        await lxd_service.stop_container(container_name, force=False)
                    except Exception:
                        logger.warning(f"UpdateService: Graceful stop of '{container_name}' failed, forcing.")
                        await lxd_service.stop_container(container_name, force=True)

                # Safety snapshot for manual emergency recovery only.
                snapshot_name = f"pre-update-{campaign.version}"
                await lxd_service.create_snapshot(container_name, snapshot_name,
                                                  expiry_days=settings.SNAPSHOT_RETENTION_DAYS)
                await crud_island_backup_ops.create(
                    db_session, island_id=island.id, snapshot_name=snapshot_name,
                    backup_type="snapshot", version=campaign.version, campaign_id=campaign.id,
                    description=f"Auto snapshot before update {campaign.version}",
                )

                backed_up = await lxd_service.backup_files(container_name, paths_to_save, backup_dir)
                await crud_island_backup_ops.create(
                    db_session, island_id=island.id, snapshot_name=f"files-{campaign.version}",
                    backup_type="files", version=campaign.version, campaign_id=campaign.id,
                    backup_path=backup_dir, changed_paths=campaign.changed_paths,
                    description=f"File backup before update {campaign.version} ({len(backed_up)} files)",
                )

                await self._push_repo_to_container(container_name)

                # The container stays stopped — islands start on demand when the
                # player joins, so no restart is needed here.
                final_status = IslandStatusEnum.STOPPED
                extra = {"internal_ip_address": None, "minecraft_ready": False}
            else:
                # Soft update: files + in-game reload, the player never leaves.
                backed_up = await lxd_service.backup_files(container_name, paths_to_save, backup_dir)
                await crud_island_backup_ops.create(
                    db_session, island_id=island.id, snapshot_name=f"files-{campaign.version}",
                    backup_type="files", version=campaign.version, campaign_id=campaign.id,
                    backup_path=backup_dir, changed_paths=campaign.changed_paths,
                    description=f"File backup before soft update {campaign.version} ({len(backed_up)} files)",
                )

                await self._push_repo_to_container(container_name)

                if owner_uuid and previous_status == IslandStatusEnum.RUNNING:
                    await self._queue_reload_commands(db_session, island, owner_uuid, campaign)

                final_status = previous_status
                extra = {}

            await crud_island.update_by_id(
                db_session, island_id=island.id,
                obj_in={"status": final_status, "current_version": campaign.version, **extra},
            )
            logger.info(f"UpdateService: Island {island.id} ({container_name}) updated to {campaign.version}.")

        except Exception as e:
            logger.error(f"UpdateService: Update of island {island.id} failed: {e}", exc_info=True)
            await crud_island.update_by_id(db_session, island_id=island.id,
                                           obj_in={"status": IslandStatusEnum.UPDATE_FAILED})
            raise UpdateServiceError(str(e)) from e

    # ── rollback ──────────────────────────────────────────────────────

    async def rollback_island(self, db_session: AsyncSession, island: IslandModel,
                              campaign: UpdateCampaignModel):
        """Rolls one island back to the state before a campaign.

        Restores the backed-up files, deletes files the campaign added, and
        either stops the container (hard) or queues reload commands (soft).
        world/ is not touched.

        Raises:
            UpdateServiceError: If no file backup exists or the restore fails.
        """
        backup = await crud_island_backup_ops.get_latest_files_backup(
            db_session, island_id=island.id, campaign_id=campaign.id)
        if not backup or not backup.backup_path:
            raise UpdateServiceError(f"No file backup found for island {island.id} and campaign {campaign.id}.")

        container_name = island.container_name
        owner_uuid = await self.get_island_owner_uuid(db_session, island)
        previous_status = island.status

        try:
            if campaign.requires_restart:
                state = await lxd_service.get_container_state(container_name)
                lxd_status = ((state or {}).get("status") or "").lower()
                if lxd_status == "frozen":
                    await lxd_service.unfreeze_container(container_name)
                    lxd_status = "running"
                if lxd_status == "running":
                    try:
                        await lxd_service.stop_container(container_name, force=False)
                    except Exception:
                        await lxd_service.stop_container(container_name, force=True)

            # Delete files the campaign added, then restore the previous versions.
            for path in self._paths_added_by(campaign):
                await lxd_service.delete_file(container_name, path)
            await lxd_service.restore_files(container_name, backup.backup_path,
                                            self._paths_to_back_up(campaign))

            if campaign.requires_restart:
                await crud_island.update_by_id(
                    db_session, island_id=island.id,
                    obj_in={"status": IslandStatusEnum.STOPPED, "internal_ip_address": None,
                            "minecraft_ready": False, "current_version": campaign.previous_version},
                )
            else:
                if owner_uuid and previous_status == IslandStatusEnum.RUNNING:
                    await self._queue_reload_commands(db_session, island, owner_uuid, campaign)
                await crud_island.update_by_id(
                    db_session, island_id=island.id,
                    obj_in={"current_version": campaign.previous_version},
                )
            logger.info(f"UpdateService: Island {island.id} rolled back to {campaign.previous_version}.")
        except UpdateServiceError:
            raise
        except Exception as e:
            logger.error(f"UpdateService: Rollback of island {island.id} failed: {e}", exc_info=True)
            raise UpdateServiceError(str(e)) from e

    async def rollback_campaign(self, db_session: AsyncSession, campaign: UpdateCampaignModel) -> Tuple[int, int]:
        """Rolls back every island that has a file backup for the campaign.

        Returns:
            (rolled_back, failed) counts.
        """
        backups = await crud_island_backup_ops.get_files_backups_for_campaign(db_session, campaign_id=campaign.id)
        ok, failed = 0, 0
        for backup in backups:
            island = await crud_island.get(db_session, island_id=backup.island_id)
            if not island:
                continue
            try:
                await self.rollback_island(db_session, island, campaign)
                ok += 1
            except UpdateServiceError as e:
                logger.error(f"UpdateService: Campaign rollback failed for island {backup.island_id}: {e}")
                failed += 1
        await crud_update_campaign.set_status(db_session, campaign_id=campaign.id,
                                              status=CampaignStatusEnum.ROLLED_BACK)
        return ok, failed

    # ── template container ────────────────────────────────────────────

    async def update_template_container(self, campaign_version: str):
        """Pushes the update into the template container and republishes the image.

        New islands are cloned from the published image (LXD_BASE_IMAGE), so
        publishing is what actually makes new islands start with fresh files.
        """
        template = settings.TEMPLATE_CONTAINER_NAME
        state = await lxd_service.get_container_state(template)
        if state is None:
            logger.warning(f"UpdateService: Template container '{template}' not found — skipping template update.")
            return
        if (state.get("status") or "").lower() == "running":
            try:
                await lxd_service.stop_container(template, force=False)
            except Exception:
                await lxd_service.stop_container(template, force=True)

        await self._push_repo_to_container(template)
        await lxd_service.publish_container_image(template, settings.LXD_BASE_IMAGE)
        logger.info(f"UpdateService: Template '{template}' updated to {campaign_version} and image "
                    f"'{settings.LXD_BASE_IMAGE}' republished.")


update_service = UpdateService()
