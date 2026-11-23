"""
train_offline.py
================

Stop the model server, train one adapter, start the server again.

The server and a train.py each load their own 4-bit copy of the base model, and
the two together need more than the 16GB card has (~7.5GB serving + ~10.5GB
training, measured). Sharing one copy would fit, but every CRM answer would then
wait behind training steps. So training runs with the GPU to itself, and this
script makes that one command instead of three.

The server is restarted even when training fails or is interrupted with
Ctrl+C, so the CRM always comes back — on the previous weights if the run did
not finish.

Run (from ai/, with the venv active):
    python tools/train_offline.py lead_meeting
    python tools/train_offline.py marketing --resume       # extra args go to train.py
    python tools/train_offline.py deal_state --no-restart  # leave the server stopped

The restarted server's output goes to ai/logs/server.log.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

import psutil

AI_ROOT = Path(__file__).resolve().parent.parent
ADAPTERS = ("deal_state", "lead_meeting", "lead_scoring", "marketing")
PORT = int(os.getenv("CRM_PORT", "8001"))
SERVER_LOG = AI_ROOT / "logs" / "server.log"

# Loading 8B weights in 4-bit plus four adapters takes ~30-60s on this machine;
# the margin covers a cold disk cache.
SERVER_READY_TIMEOUT_S = 300


def log(message: str) -> None:
    print(f"[train_offline] {message}", flush=True)


def is_model_server(proc: psutil.Process) -> bool:
    """True only for a process running server/main.py — the script must never
    kill something else that happens to hold the port."""
    try:
        cmdline = " ".join(proc.cmdline()).replace("\\", "/")
    except (psutil.NoSuchProcess, psutil.AccessDenied):
        return False
    return "server/main.py" in cmdline or "server.main:app" in cmdline


def find_server() -> Optional[psutil.Process]:
    """The process listening on PORT, or None if nothing is."""
    for conn in psutil.net_connections(kind="tcp"):
        if conn.status == psutil.CONN_LISTEN and conn.laddr and conn.laddr.port == PORT and conn.pid:
            try:
                return psutil.Process(conn.pid)
            except psutil.NoSuchProcess:
                return None
    return None


def stop_server(proc: psutil.Process) -> dict:
    """Stop the server and return what is needed to start it the same way."""
    launch = {"cmdline": proc.cmdline(), "cwd": proc.cwd()}
    try:
        # Keeps CRM_* overrides the server was started with (model paths, port).
        launch["env"] = proc.environ()
    except (psutil.AccessDenied, OSError):
        launch["env"] = dict(os.environ)

    # uvicorn --reload runs the app in a child process; that child holds the GPU.
    targets = proc.children(recursive=True) + [proc]
    for p in targets:
        try:
            p.terminate()
        except psutil.NoSuchProcess:
            pass
    _, alive = psutil.wait_procs(targets, timeout=30)
    for p in alive:
        p.kill()
    psutil.wait_procs(alive, timeout=10)
    log(f"Server (pid {proc.pid}) stopped; GPU memory released.")
    return launch


def start_server(launch: dict, port: int = PORT, log_path: Path = SERVER_LOG) -> Optional[subprocess.Popen]:
    """Start the server detached and wait for /health. Returns the process (ready,
    or still loading after the timeout), or None if it exited."""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    out = open(log_path, "a", encoding="utf-8")  # noqa: SIM115 - handed to the child
    out.write(f"\n===== restarted by train_offline.py at {time.strftime('%Y-%m-%d %H:%M:%S')} =====\n")
    out.flush()

    kwargs = {}
    if os.name == "nt":
        # A hidden console of its own, not DETACHED_PROCESS: the venv's python.exe
        # is a launcher that starts the real interpreter as a child, and a child
        # of a console-less process gets a fresh console window, which killed
        # the server at startup with STATUS_CONTROL_C_EXIT when that window
        # closed. The new process group keeps Ctrl+C in this console from
        # reaching the server, so it still outlives this script.
        kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    proc = subprocess.Popen(launch["cmdline"], cwd=launch["cwd"], env=launch["env"],
                            stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, **kwargs)
    log(f"Server starting on port {port} (pid {proc.pid}); output -> {log_path}")

    deadline = time.time() + SERVER_READY_TIMEOUT_S
    while time.time() < deadline:
        if proc.poll() is not None:
            log(f"Server exited with code {proc.returncode} during startup — see {log_path}")
            return None
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=5) as resp:
                health = json.load(resp)
            if health.get("status") == "ok":
                log(f"Server ready. Adapters: {health.get('adapters')}")
                return proc
        except (urllib.error.URLError, ConnectionError, TimeoutError, ValueError):
            pass
        time.sleep(3)
    log(f"Server did not report ready within {SERVER_READY_TIMEOUT_S}s — see {log_path}")
    # Still running, so still returned: the caller may need to stop it.
    return proc


def default_launch() -> dict:
    """How to start the server when it was not running beforehand."""
    return {"cmdline": [sys.executable, str(AI_ROOT / "server" / "main.py")],
            "cwd": str(AI_ROOT), "env": dict(os.environ)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("adapter", choices=ADAPTERS)
    parser.add_argument("--no-restart", action="store_true",
                        help="leave the server stopped after training")
    parser.add_argument("--start-server", action="store_true",
                        help="start the server afterwards even if it was not running before")
    args, train_args = parser.parse_known_args()

    train_script = AI_ROOT / "adapters" / args.adapter / "train.py"

    launch = None
    server = find_server()
    if server is not None:
        if not is_model_server(server):
            log(f"Port {PORT} is held by pid {server.pid} ({' '.join(server.cmdline())}), which is not "
                "the model server. Refusing to stop it; free the GPU yourself and rerun.")
            return 2
        launch = stop_server(server)
    else:
        log(f"No server on port {PORT}; the GPU is already free.")
        if args.start_server:
            launch = default_launch()

    started = time.time()
    code = 1
    try:
        log(f"Training {args.adapter}: {train_script.relative_to(AI_ROOT)} {' '.join(train_args)}")
        code = subprocess.call([sys.executable, str(train_script), *train_args], cwd=str(AI_ROOT))
        log(f"Training {'finished' if code == 0 else f'FAILED (exit {code})'} "
            f"after {(time.time() - started) / 60:.1f} min.")
    except KeyboardInterrupt:
        log("Interrupted.")
        code = 130
    finally:
        if launch is not None and not args.no_restart:
            if code != 0:
                log("Restarting the server on the previous weights.")
            start_server(launch)
        elif launch is not None:
            log("--no-restart given; the server stays stopped.")
    return code


if __name__ == "__main__":
    sys.exit(main())
