"""Linux child lifetime primitive for NativeCodingRunner; no model/network/policy logic.

Subreaper adoption + waitpid(ECHILD), unlike process-group or sampled-descendant
checks, accounts for double-forked/setsid descendants. Runs without privileges.
"""
import ctypes
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def identity(pid):
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return {"pid": pid, "start_ticks": fields[19]}


def emergency_cleanup():
    """Best effort after helper failure; never promoted to positive receipt evidence."""
    deadline = time.monotonic() + 3
    children = Path(f"/proc/self/task/{os.getpid()}/children")
    while time.monotonic() < deadline:
        try:
            while os.waitpid(-1, os.WNOHANG)[0]:
                pass
        except ChildProcessError:
            return
        for text in children.read_text().split():
            fd = os.pidfd_open(int(text))
            try:
                signal.pidfd_send_signal(fd, signal.SIGKILL)
            except ProcessLookupError:
                pass
            finally:
                os.close(fd)
        time.sleep(0.01)


def run(config):
    # Must succeed BEFORE any Codex child is started.
    if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        raise RuntimeError("Linux pidfd support required")
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
        raise OSError(ctypes.get_errno(), "subreaper unavailable")
    boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    machine = Path("/etc/machine-id").read_text().strip()
    me = identity(os.getpid())
    children_file = Path(f"/proc/self/task/{os.getpid()}/children")
    children_file.read_text()  # Fail before spawn if procfs ownership evidence is unavailable.
    cause = None

    def stop(_signum, _frame):
        nonlocal cause
        cause = cause or "stop_requested"

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    started = time.monotonic()
    environment = dict(config["env"])
    for name in config.get("private_env_names", []):
        if name != "DEVORCH_PUBLISH_TOKEN":
            raise ValueError("Unsupported private environment")
        environment[name] = os.environ[name]
    child = subprocess.Popen(config["argv"], cwd=config["cwd"], env=environment,
                             start_new_session=True)  # stdin/out/err inherited from Kotlin pipes
    root = identity(child.pid)
    root_exit = None
    root_exit_at = None
    reaped = 0
    cleanup_deadline = None
    clean = False
    while True:
        # No other thread reaps, so unreaped child PID cannot be recycled between
        # reading /proc children and pidfd_open. Signal only those direct children.
        while True:
            try:
                pid, status = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:  # ECHILD is the positive cleanup evidence.
                clean = True
                break
            if pid == 0:
                break
            reaped += 1
            if pid == child.pid:
                root_exit = os.waitstatus_to_exitcode(status)
                root_exit_at = time.monotonic()
                child.returncode = root_exit
        if clean:
            break
        if time.monotonic() - started >= config["timeout_ms"] / 1000:
            cause = cause or "timeout"
        # bwrap's namespace monitor can outlive its outer parent briefly. Wait for
        # ECHILD, never mistake a normal namespace teardown race for a live worker.
        grace = 0.5 if config.get("namespace_boundary") else 0
        if root_exit is not None and time.monotonic() - root_exit_at >= grace:
            cause = cause or "descendants_after_exit"
        if cause:
            cleanup_deadline = cleanup_deadline or time.monotonic() + 5
            for text in children_file.read_text().split():
                fd = os.pidfd_open(int(text))
                try:
                    signal.pidfd_send_signal(fd, signal.SIGKILL)
                except ProcessLookupError:
                    pass  # The next waitpid must still reap/establish ECHILD.
                finally:
                    os.close(fd)
            if time.monotonic() >= cleanup_deadline:
                break
        time.sleep(0.01)
    return {"boot_id": boot, "machine_id": machine, "supervisor": me, "child": root,
            "exit_code": root_exit, "cleanup_echild": clean, "reaped": reaped,
            "cause": cause or "exited"}


if __name__ == "__main__":
    config = json.loads(Path(sys.argv[1]).read_text())
    try:
        receipt = run(config)
    except BaseException:
        try:
            emergency_cleanup()
        except BaseException:
            pass
        # Missing/failed evidence must not be fabricated as a clean terminal result.
        receipt = {"cleanup_echild": False, "cause": "supervisor_error"}
    target = Path(config["receipt"])
    pending = target.with_suffix(".pending")
    with pending.open("x") as output:
        json.dump(receipt, output)
        output.flush()
        os.fsync(output.fileno())
    os.replace(pending, target)
    descriptor = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
    os.fsync(descriptor)
    os.close(descriptor)
