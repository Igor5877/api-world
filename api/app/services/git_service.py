import asyncio
import logging
import shutil
from pathlib import Path

from app.core.config import settings

logger = logging.getLogger(__name__)

class GitServiceError(Exception):
    """Custom exception for GitService errors."""
    pass

class GitService:
    """Provides functionality to clone and manage a Git repository for updates."""

    def __init__(self, repo_url: str, clone_path: str):
        """Initializes the GitService.

        Args:
            repo_url: The URL of the Git repository.
            clone_path: The local path to clone the repository into.
        """
        if not repo_url:
            raise ValueError("Git repository URL is not configured.")
        self.repo_url = repo_url
        self.clone_path = Path(clone_path)

    async def _run_command(self, *args: str, cwd: str | Path) -> None:
        """Runs a shell command asynchronously.

        Args:
            *args: The command and its arguments.
            cwd: The working directory for the command.

        Raises:
            GitServiceError: If the command returns a non-zero exit code.
        """
        process = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=cwd
        )
        stdout, stderr = await process.communicate()

        if process.returncode != 0:
            error_message = stderr.decode().strip()
            logger.error(f"Git command '{' '.join(args)}' failed: {error_message}")
            raise GitServiceError(f"Git command failed: {error_message}")

        logger.info(f"Git command '{' '.join(args)}' executed successfully.")

    async def clone_or_update_repo(self) -> None:
        """Clones the repository if it doesn't exist, or fetches updates if it does."""
        if self.clone_path.exists():
            logger.info(f"Repository already exists at {self.clone_path}. Fetching updates...")
            await self._run_command("git", "fetch", "--all", "--tags", "--prune", cwd=self.clone_path)
        else:
            logger.info(f"Cloning repository {self.repo_url} to {self.clone_path}...")
            self.clone_path.mkdir(parents=True, exist_ok=True)
            await self._run_command("git", "clone", self.repo_url, ".", cwd=self.clone_path)

    async def checkout_tag(self, tag: str) -> None:
        """Checks out a specific tag in the repository.

        Args:
            tag: The tag to check out.

        Raises:
            GitServiceError: If the tag does not exist or checkout fails.
        """
        logger.info(f"Checking out tag '{tag}' in {self.clone_path}...")
        await self._run_command("git", "checkout", f"tags/{tag}", cwd=self.clone_path)

    def get_version_files_path(self) -> Path:
        """Returns the path to the checked-out files.

        Returns:
            The Path object for the local repository clone.
        """
        return self.clone_path

    async def cleanup(self) -> None:
        """Removes the cloned repository directory."""
        logger.info(f"Cleaning up repository at {self.clone_path}...")
        if self.clone_path.exists():
            await asyncio.to_thread(shutil.rmtree, self.clone_path)

# Singleton instance of the GitService
git_service = GitService(
    repo_url=settings.UPDATE_GIT_REPOSITORY_URL,
    clone_path=settings.UPDATE_TEMP_CLONE_PATH
)
