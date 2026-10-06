"""Repo sync «лише різниця»: кожен рядок таблиці рішень з SYNC_DIFF_TODO.md."""
import pathlib
from unittest.mock import AsyncMock, patch

import pytest

from app.services import repo_sync


class _Container:
    """Стан контейнера: шлях → (розмір, mtime). Заливка ставить новий mtime."""

    def __init__(self, files=None):
        self.files = dict(files or {})
        self.clock = 1000
        self.pushed, self.deleted = [], []

    async def stat(self, container, root, dirs):
        return {p: v for p, v in self.files.items() if p.split("/")[0] in dirs}

    async def push(self, container, local, remote, mode=0o644):
        rel = remote.split("/opt/minecraft/", 1)[1]
        self.clock += 1
        self.files[rel] = (pathlib.Path(local).stat().st_size, self.clock)
        self.pushed.append(rel)

    async def delete(self, container, remote):
        rel = remote.split("/opt/minecraft/", 1)[1]
        self.files.pop(rel, None)
        self.deleted.append(rel)


@pytest.fixture
def env(tmp_path):
    repo, backups = tmp_path / "repo", tmp_path / "backups"
    (repo / "mods").mkdir(parents=True)
    (repo / "config" / "ftbquests").mkdir(parents=True)
    (repo / "mods" / "a.jar").write_bytes(b"AAAA")
    (repo / "mods" / "b.jar").write_bytes(b"BB")
    (repo / "config" / "ftbquests" / "q.snbt").write_text("q")
    (repo / "config" / "shared.toml").write_text("x=1")
    ct = _Container()
    lxd = AsyncMock()
    lxd.push_local_file.side_effect = ct.push
    lxd.delete_file.side_effect = ct.delete
    with patch.object(repo_sync.settings, "UPDATES_REPO_LOCAL_PATH", str(repo)), \
         patch.object(repo_sync.settings, "UPDATES_BACKUP_DIR", str(backups)), \
         patch.object(repo_sync.settings, "UPDATES_TARGET_DIR", "/opt/minecraft"), \
         patch("app.services.repo_sync.lxd_service", lxd), \
         patch("app.services.repo_sync.crash_agent.stat", side_effect=ct.stat):
        yield repo, ct


async def _full_push_into(ct, repo):
    """Імітація повної заливки: усе з репо, зайве в mods/ стерто."""
    for rel in list(ct.files):
        if rel.startswith("mods/") or rel.startswith("config/ftbquests/"):
            ct.files.pop(rel)
    for p in repo.rglob("*"):
        if p.is_file():
            ct.clock += 1
            ct.files[p.relative_to(repo).as_posix()] = (p.stat().st_size, ct.clock)


async def _first_sync(repo, ct):
    async def do_full():
        await _full_push_into(ct, repo)
    full = AsyncMock(side_effect=do_full)
    r = await repo_sync.sync_repo("isl", full_push=full)
    return r, full


@pytest.mark.asyncio
async def test_no_manifest_means_full_sync_then_manifest(env):
    repo, ct = env
    r, full = await _first_sync(repo, ct)
    full.assert_awaited_once()
    assert r.full and "перший раз" in r.note
    assert repo_sync._load_manifest("isl")["mods/a.jar"]["size"] == 4


@pytest.mark.asyncio
async def test_nothing_changed_pushes_nothing(env):
    repo, ct = env
    await _first_sync(repo, ct)
    ct.pushed.clear()
    full = AsyncMock()
    r = await repo_sync.sync_repo("isl", full_push=full)
    full.assert_not_awaited()
    assert (r.pushed_changed, r.repaired, r.deleted, r.unchanged) == (0, 0, 0, 4)
    assert ct.pushed == []


@pytest.mark.asyncio
async def test_only_files_changed_in_repo_are_pushed(env):
    repo, ct = env
    await _first_sync(repo, ct)
    (repo / "mods" / "b.jar").write_bytes(b"BB-new")      # оновлений мод
    (repo / "mods" / "c.jar").write_bytes(b"CCC")          # новий мод
    (repo / "mods" / "a.jar").unlink()                     # видалений мод
    r = await repo_sync.sync_repo("isl", full_push=AsyncMock())
    assert sorted(ct.pushed[-2:]) == ["mods/b.jar", "mods/c.jar"]
    assert ct.deleted == ["mods/a.jar"]
    assert (r.pushed_changed, r.deleted, r.unchanged, r.repaired) == (2, 1, 2, 0)


@pytest.mark.asyncio
async def test_hand_edit_is_restored_and_counted(env):
    repo, ct = env
    await _first_sync(repo, ct)
    ct.files["mods/a.jar"] = (999, 5000)                   # хтось підмінив jar вручну
    r = await repo_sync.sync_repo("isl", full_push=AsyncMock())
    assert r.repaired == 1 and "mods/a.jar" in ct.pushed[-1:]
    assert "виправлено ручних змін 1" in r.summary()


@pytest.mark.asyncio
async def test_extra_files_deleted_only_in_repo_owned_dirs(env):
    repo, ct = env
    await _first_sync(repo, ct)
    ct.files["mods/cheat.jar"] = (10, 6000)                # підкинутий мод — видалити
    ct.files["config/ftbquests/extra.snbt"] = (1, 6000)    # репо володіє config/ftbquests — видалити
    ct.files["config/island-own.toml"] = (5, 6000)          # власний конфіг острова — лишити
    r = await repo_sync.sync_repo("isl", full_push=AsyncMock())
    assert sorted(ct.deleted) == ["config/ftbquests/extra.snbt", "mods/cheat.jar"]
    assert "config/island-own.toml" in ct.files and r.deleted == 2


@pytest.mark.asyncio
async def test_agent_unavailable_falls_back_to_full_and_forgets_manifest(env):
    repo, ct = env
    await _first_sync(repo, ct)
    full = AsyncMock()
    with patch("app.services.repo_sync.crash_agent.stat", AsyncMock(return_value=None)):
        r = await repo_sync.sync_repo("isl", full_push=full)
    full.assert_awaited_once()
    assert r.full and "агент" in r.note
    assert repo_sync._load_manifest("isl") is None         # наступного разу знову повна


def test_owned_rules_match_full_sync():
    assert repo_sync._owned("mods/x.jar") and repo_sync._owned("kubejs/s/a.js")
    assert repo_sync._owned("config/ftbquests/q.snbt")
    assert not repo_sync._owned("config/mekanism.toml")
