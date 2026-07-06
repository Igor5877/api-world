"""Tests for server-core update support (libraries/, wrapper, run.sh)."""
from app.services.git_sync import determine_actions
from app.services.update_service import update_service
from app.models.update import UpdateCampaign


def _campaign(changed_paths):
    return UpdateCampaign(version="v9.9.9", changed_paths=changed_paths)


def test_core_file_change_forces_restart():
    requires_restart, reload_commands = determine_actions([("M", "run.sh")])
    assert requires_restart is True


def test_libraries_change_forces_restart():
    requires_restart, _ = determine_actions(
        [("A", "libraries/net/minecraftforge/forge/1.20.1-47.5.0/forge.jar")])
    assert requires_restart is True


def test_config_change_still_soft():
    requires_restart, reload_commands = determine_actions([("M", "config/somemod.toml")])
    assert requires_restart is False
    assert "reload" in reload_commands


def test_backupable_covers_core_files_but_not_libraries():
    assert update_service._backupable("run.sh") is True
    assert update_service._backupable("ServerWrapper.jar") is True
    assert update_service._backupable("mods/somemod.jar") is True
    # libraries/ відкочується лише LXD-снапшотом — файлово не бекапиться
    assert update_service._backupable("libraries/some/lib.jar") is False
    # island-owned файли не чіпаємо
    assert update_service._backupable("server.properties") is False
    assert update_service._backupable("world/level.dat") is False


def test_core_dirs_changed_detection():
    assert update_service._core_dirs_changed(_campaign([("M", "mods/a.jar")])) == []
    assert update_service._core_dirs_changed(
        _campaign([("M", "libraries/x/y.jar"), ("M", "mods/a.jar")])) == ["libraries"]
    assert update_service._core_dirs_changed(None) == []
