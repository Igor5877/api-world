"""Git operations for the skyblock-updates repository.

The repo always contains the full current state of the server files
(mods/, config/, quests/, recipes/, ...). Git tags mark releases:

    v1.3.0           -> server_only (default)
    v1.3.1-both      -> mod is needed on both client and server
    v1.3.2-critical  -> critical fix, players are kicked immediately

The diff between two tags is used only to decide *how* to apply the update
(restart vs in-game reload) and what to back up — the files themselves are
always synced in full so islands that missed a campaign still converge.
"""
import asyncio
import logging
import os
import re
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

from app.core.config import settings

logger = logging.getLogger(__name__)

# Directories from the repo that are synced into the container.
# world/ is NEVER touched.
SYNC_DIRS = ["mods", "config", "quests", "recipes", "kubejs", "defaultconfigs", "scripts"]
# Fully repo-owned dirs: wiped in the container before the push so files deleted
# from the repo (old scripts, quest chapters, mod jars) actually disappear.
CLEAN_SYNC_DIRS = {"mods", "quests", "recipes", "kubejs", "defaultconfigs", "scripts"}
# Repo-owned subdirs inside dirs that also hold island-specific files:
# config/ itself is never wiped, but config/ftbquests is.
CLEAN_SYNC_SUBDIRS = {"config": ["ftbquests"]}

_TAG_RE = re.compile(r"^(?P<version>v?\d+[\w.]*?)(?:-(?P<suffix>both|critical))?$")


class GitSyncError(Exception):
    """Raised when a git operation on the updates repo fails."""
    pass


@dataclass
class UpdateManifest:
    """Everything the update worker needs to apply one campaign."""
    version: str
    previous_version: Optional[str]
    git_commit: str
    update_type: str                      # server_only | both | critical
    requires_restart: bool
    reload_commands: List[str] = field(default_factory=list)
    changed_paths: List[Tuple[str, str]] = field(default_factory=list)  # (git status, path)
    message: str = ""
    repo_local_path: str = ""


async def _run_git(*args: str, cwd: Optional[str] = None, timeout: int = 600) -> str:
    """Runs a git command and returns stdout.

    Raises:
        GitSyncError: If the command fails or times out.
    """
    cmd = ["git", *args]
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd, cwd=cwd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        proc.kill()
        raise GitSyncError(f"git command timed out: git {' '.join(args)}")
    if proc.returncode != 0:
        raise GitSyncError(f"git {' '.join(args)} failed: {stderr.decode(errors='replace').strip()}")
    return stdout.decode(errors="replace")


def _authenticated_url(repo_url: str) -> str:
    """Injects the GitHub token into an https repo URL for private repos."""
    token = settings.GITHUB_TOKEN
    if token and repo_url.startswith("https://") and "@" not in repo_url:
        return repo_url.replace("https://", f"https://x-access-token:{token}@", 1)
    return repo_url


async def clone_or_pull(repo_url: str, local_path: str):
    """Clones the updates repo, or fetches if it already exists.

    Args:
        repo_url: The repository URL (token from GITHUB_TOKEN is injected for https).
        local_path: Where the repo lives on the API host.
    """
    url = _authenticated_url(repo_url)
    if os.path.isdir(os.path.join(local_path, ".git")):
        await _run_git("fetch", "--tags", "--force", "origin", cwd=local_path)
    else:
        os.makedirs(os.path.dirname(local_path) or "/", exist_ok=True)
        await _run_git("clone", url, local_path)
        await _run_git("fetch", "--tags", "--force", "origin", cwd=local_path)


async def checkout_tag(local_path: str, tag: str):
    """Checks out a tag (detached HEAD) and cleans the working tree."""
    await _run_git("checkout", "--force", tag, cwd=local_path)
    await _run_git("clean", "-fd", cwd=local_path)


async def get_commit_for_tag(local_path: str, tag: str) -> str:
    """Returns the commit SHA a tag points to."""
    return (await _run_git("rev-list", "-n", "1", tag, cwd=local_path)).strip()


async def get_commit_message(local_path: str, tag: str) -> str:
    """Returns the subject line of the commit a tag points to."""
    return (await _run_git("log", "-1", "--format=%s", tag, cwd=local_path)).strip()


async def get_previous_tag(local_path: str, current_tag: str) -> Optional[str]:
    """Returns the tag preceding current_tag, or None if this is the first tag."""
    try:
        out = await _run_git("describe", "--tags", "--abbrev=0", f"{current_tag}^", cwd=local_path)
        prev = out.strip()
        return prev or None
    except GitSyncError:
        return None  # first tag in the repo


async def get_changed_paths(local_path: str, from_tag: str, to_tag: str) -> List[Tuple[str, str]]:
    """Returns (status, path) pairs between two tags.

    Status is git's one-letter code: A (added), M (modified), D (deleted),
    R (renamed, reported as the new path).
    """
    out = await _run_git("diff", "--name-status", f"{from_tag}..{to_tag}", cwd=local_path)
    changed: List[Tuple[str, str]] = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        status = parts[0][0]  # "R100" -> "R"
        path = parts[-1]      # for renames the last field is the new path
        changed.append((status, path))
    return changed


def parse_tag(tag: str) -> Tuple[str, str]:
    """Parses a tag into (version, update_type).

    "v1.3.1-both" -> ("v1.3.1-both", "both"); the full tag stays the version
    identifier so git commands keep working with it.

    Raises:
        GitSyncError: If the tag does not match the naming convention.
    """
    match = _TAG_RE.match(tag)
    if not match:
        raise GitSyncError(f"Tag '{tag}' does not match the expected convention (vX.Y.Z[-both|-critical]).")
    suffix = match.group("suffix")
    return tag, (suffix or "server_only")


def determine_actions(changed_paths: List[Tuple[str, str]]) -> Tuple[bool, List[str]]:
    """Decides how an update must be applied based on the changed paths.

    Returns:
        (requires_restart, reload_commands)
    """
    requires_restart = False
    reload_commands: List[str] = []

    for _status, path in changed_paths:
        if path.startswith("mods/") and path.endswith(".jar"):
            requires_restart = True
        elif path.startswith("kubejs/startup_scripts/"):
            requires_restart = True
        elif path.startswith("quests/") or path.startswith("config/ftbquests/"):
            if "ftbquests reload" not in reload_commands:
                reload_commands.append("ftbquests reload")
        elif path.startswith("kubejs/") or path.startswith("recipes/") or path.startswith("config/"):
            if "reload" not in reload_commands:
                reload_commands.append("reload")
        # defaultconfigs/ and scripts/ need no in-game action

    return requires_restart, reload_commands


async def build_manifest(tag: str) -> UpdateManifest:
    """Builds the full manifest for a tag: pulls the repo, checks the tag out,
    diffs against the previous tag and derives the required actions.

    Raises:
        GitSyncError: If UPDATES_REPO_URL is not configured or git fails.
    """
    if not settings.UPDATES_REPO_URL:
        raise GitSyncError("UPDATES_REPO_URL is not configured.")

    local_path = settings.UPDATES_REPO_LOCAL_PATH
    await clone_or_pull(settings.UPDATES_REPO_URL, local_path)
    await checkout_tag(local_path, tag)

    version, update_type = parse_tag(tag)
    git_commit = await get_commit_for_tag(local_path, tag)
    message = await get_commit_message(local_path, tag)
    previous_tag = await get_previous_tag(local_path, tag)

    if previous_tag:
        changed_paths = await get_changed_paths(local_path, previous_tag, tag)
        requires_restart, reload_commands = determine_actions(changed_paths)
    else:
        # First campaign ever: no diff — treat as a full hard update.
        changed_paths = []
        requires_restart, reload_commands = True, []
        logger.warning(f"git_sync: No previous tag before '{tag}' — treating as a full (restart) update.")

    manifest = UpdateManifest(
        version=version,
        previous_version=previous_tag,
        git_commit=git_commit,
        update_type=update_type,
        requires_restart=requires_restart,
        reload_commands=reload_commands,
        changed_paths=changed_paths,
        message=message,
        repo_local_path=local_path,
    )
    logger.info(
        f"git_sync: Manifest for {tag}: type={update_type}, restart={requires_restart}, "
        f"reload={reload_commands}, changed={len(changed_paths)} paths, prev={previous_tag}."
    )
    return manifest
