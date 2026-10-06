"""Repo → container sync that pushes only what differs (rsync quick-check).

Until 2026-10 every hard update wiped and re-pushed mods/, kubejs/, … in full,
because files on an island can be changed by hand (lxc file push, console)
outside git and the API, and only a full push guaranteed "the island holds
exactly the repo". A campaign that changed 4 jars still pushed ~200 files per
island (~2.5 min, Spawn down for minutes).

Same guarantee, far less work — three states are compared:

  * expected — the repo checkout: path → sha256 of the local file;
  * manifest — what we pushed last time: path → (size, mtime) as stat'ed right
    after that push, plus the sha256 pushed (host file, not in the container);
  * actual   — what the container holds now: path → (size, mtime), read by the
    crash agent over SFTP (stopped containers too), without file contents.

A file is pushed when it is missing, changed in the repo (sha ≠ manifest), or
changed by hand (actual size/mtime ≠ manifest). A file the repo doesn't have is
deleted — only inside repo-owned dirs (CLEAN_SYNC_DIRS, CLEAN_SYNC_SUBDIRS), as
the full sync did; island-specific config/ files survive. No manifest or no
agent → everything counts as differing, i.e. the old full sync, after which a
manifest is recorded.
"""
import hashlib
import json
import logging
import os
import pathlib
from dataclasses import dataclass
from typing import Awaitable, Callable, Dict, Optional, Tuple

from app.core.config import settings
from app.services import crash_agent
from app.services.git_sync import CLEAN_SYNC_DIRS, CLEAN_SYNC_SUBDIRS, SYNC_DIRS
from app.services.lxd_service import lxd_service

logger = logging.getLogger(__name__)

MANIFEST_NAME = "sync-manifest.json"


@dataclass
class SyncResult:
    pushed_changed: int = 0     # змінились у репо (або нові)
    repaired: int = 0           # були поправлені вручну — перезалито
    deleted: int = 0            # зайві (немає в репо) — видалено
    unchanged: int = 0
    full: bool = False          # не було маніфесту / агента — порівняння «все нове»
    note: str = ""

    def summary(self) -> str:
        parts = [f"залито {self.pushed_changed}"]
        if self.repaired:
            parts.append(f"виправлено ручних змін {self.repaired}")
        if self.deleted:
            parts.append(f"видалено {self.deleted}")
        parts.append(f"без змін {self.unchanged}")
        text = ", ".join(parts)
        return text + (" (повна перевірка: " + self.note + ")" if self.full and self.note else "")


def _owned(relpath: str) -> bool:
    """Is this path repo-owned (extra files there get deleted)?"""
    parts = relpath.split("/")
    if parts[0] in CLEAN_SYNC_DIRS:
        return True
    subs = CLEAN_SYNC_SUBDIRS.get(parts[0], [])
    return len(parts) > 2 and parts[1] in subs


def _sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _expected(repo: pathlib.Path) -> Dict[str, Tuple[pathlib.Path, str]]:
    out = {}
    for d in SYNC_DIRS:
        base = repo / d
        if not base.is_dir():
            continue
        for p in base.rglob("*"):
            if p.is_file() and not p.is_symlink():
                out[p.relative_to(repo).as_posix()] = (p, _sha256(p))
    return out


def _manifest_path(container_name: str) -> pathlib.Path:
    return pathlib.Path(settings.UPDATES_BACKUP_DIR) / container_name / MANIFEST_NAME


def _load_manifest(container_name: str) -> Optional[dict]:
    try:
        return json.loads(_manifest_path(container_name).read_text())["files"]
    except (OSError, ValueError, KeyError):
        return None


def _save_manifest(container_name: str, files: dict):
    path = _manifest_path(container_name)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps({"version": 1, "files": files}))
    os.replace(tmp, path)


def forget_manifest(container_name: str):
    """Next sync of this container compares against nothing → full sync."""
    try:
        _manifest_path(container_name).unlink()
    except OSError:
        pass


async def _record_manifest(container_name: str, target: str, dirs, expected) -> bool:
    """Відбиток: розмір і mtime ПІСЛЯ заливки (mtime ставить LXD при push)."""
    after = await crash_agent.stat(container_name, target, dirs)
    if after is None:
        forget_manifest(container_name)  # без відбитка наступного разу — повна
        return False
    _save_manifest(container_name, {
        rel: {"size": after[rel][0], "mtime": after[rel][1], "sha": sha}
        for rel, (_, sha) in expected.items() if rel in after})
    return True


async def sync_repo(container_name: str, full_push: Callable[[], Awaitable[None]]) -> SyncResult:
    """Pushes only what differs. full_push — the proven wipe-and-push of all
    repo dirs, used when there is nothing reliable to compare against."""
    repo = pathlib.Path(settings.UPDATES_REPO_LOCAL_PATH)
    target = settings.UPDATES_TARGET_DIR.rstrip("/")
    dirs = [d for d in SYNC_DIRS if (repo / d).is_dir()]
    expected = _expected(repo)
    result = SyncResult()

    actual = await crash_agent.stat(container_name, target, dirs)
    manifest = _load_manifest(container_name) if actual is not None else None
    if actual is None or manifest is None:
        result.full = True
        result.note = "агент недоступний" if actual is None else "перший раз — немає відбитка"
        await full_push()
        result.pushed_changed = len(expected)
        if actual is not None:
            result.deleted = sum(1 for rel in actual if rel not in expected and _owned(rel))
        await _record_manifest(container_name, target, dirs, expected)
        logger.info(f"RepoSync: '{container_name}': full sync ({result.note}), {len(expected)} files.")
        return result

    for rel, (local, sha) in expected.items():
        have = actual.get(rel)
        last = manifest.get(rel)
        if have is not None and last is not None and last.get("sha") == sha \
                and (have[0], have[1]) == (last["size"], last["mtime"]):
            result.unchanged += 1
            continue
        mode = local.stat().st_mode & 0o777 or 0o644
        await lxd_service.push_local_file(container_name, str(local), f"{target}/{rel}", mode=mode)
        if have is not None and last is not None and last.get("sha") == sha:
            result.repaired += 1   # репо не мінялось, а файл на острові інший → ручна правка
            logger.warning(f"RepoSync: '{container_name}': {rel} was changed by hand — restored from the repo.")
        else:
            result.pushed_changed += 1

    for rel in actual:
        if rel not in expected and _owned(rel):
            await lxd_service.delete_file(container_name, f"{target}/{rel}")
            result.deleted += 1
            if rel not in manifest:
                logger.warning(f"RepoSync: '{container_name}': {rel} was added by hand — deleted.")

    await _record_manifest(container_name, target, dirs, expected)
    logger.info(f"RepoSync: '{container_name}': {result.summary()}.")
    return result
