# PlatformIO pre-build script (bridge env): defines BRIDGE_GIT_COMMIT as the
# short commit hash, with "-dirty" if tracked files have uncommitted changes, so
# the boot banner says exactly which code is on the board. Works on Windows too.
import subprocess

Import("env")  # noqa: F821 (provided by PlatformIO)


def git(*args):
    try:
        return subprocess.check_output(
            ["git", *args], cwd=env.subst("$PROJECT_DIR"), stderr=subprocess.DEVNULL
        ).decode().strip()
    except Exception:
        return ""


commit = git("rev-parse", "--short", "HEAD") or "unknown"
if git("status", "--porcelain", "--untracked-files=no"):
    commit += "-dirty"
env.Append(CPPDEFINES=[("BRIDGE_GIT_COMMIT", env.StringifyMacro(commit))])
