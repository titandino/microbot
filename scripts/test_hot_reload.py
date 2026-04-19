#!/usr/bin/env python3
"""
Test: dynamic script hot-reload via the agent server HTTP API.

Verifies the full lifecycle:
  1. Deploy a Java plugin at runtime (version 1)
  2. Confirm it appears in the deployment list
  3. Modify the source (version 2)
  4. Reload — new class must be compiled and loaded
  5. Confirm the plugin descriptor reflects v2 (proves new code is active)
  6. Undeploy and confirm it is gone

Requires the Microbot client to be running with the Agent Server plugin active.
Run with:  python3 scripts/test_hot_reload.py
"""

import json
import os
import sys
import time
import tempfile
import urllib.request
import urllib.error
from pathlib import Path

HOST = os.environ.get("MICROBOT_HOST", "127.0.0.1")
PORT = os.environ.get("MICROBOT_PORT", "8081")
BASE_URL = f"http://{HOST}:{PORT}"
SCRIPT_NAME = "hot-reload-test"

V1_SOURCE = """\
package hotreload;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@PluginDescriptor(
    name = "HotReloadTest v1",
    description = "Dynamic script framework test - version 1",
    enabledByDefault = false
)
public class HotReloadTestPlugin extends Plugin {
    private static final Logger log = Logger.getLogger(HotReloadTestPlugin.class.getName());
    private ScheduledExecutorService executor;

    @Override
    protected void startUp() {
        log.info("[HotReloadTest] v1 started");
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(
            () -> log.fine("[HotReloadTest] v1 tick"),
            0, 5, TimeUnit.SECONDS
        );
    }

    @Override
    protected void shutDown() {
        log.info("[HotReloadTest] v1 stopped");
        if (executor != null) executor.shutdownNow();
    }
}
"""

V2_SOURCE = """\
package hotreload;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@PluginDescriptor(
    name = "HotReloadTest v2",
    description = "Dynamic script framework test - version 2",
    enabledByDefault = false
)
public class HotReloadTestPlugin extends Plugin {
    private static final Logger log = Logger.getLogger(HotReloadTestPlugin.class.getName());
    private ScheduledExecutorService executor;

    @Override
    protected void startUp() {
        log.info("[HotReloadTest] v2 started");
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(
            () -> log.fine("[HotReloadTest] v2 tick"),
            0, 5, TimeUnit.SECONDS
        );
    }

    @Override
    protected void shutDown() {
        log.info("[HotReloadTest] v2 stopped");
        if (executor != null) executor.shutdownNow();
    }
}
"""


def get_token() -> str | None:
    for candidate in (
        Path.home() / ".runelite" / ".agent-token",
        Path.home() / ".runelite" / "microbot" / "agent-token",
        Path.home() / ".microbot" / "agent-token",
    ):
        if candidate.exists():
            value = candidate.read_text().strip()
            if value:
                return value
    return None


def api(method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
    url = BASE_URL + path
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json", "Host": HOST}
    token = get_token()
    if token:
        headers["X-Agent-Token"] = token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, {"error": str(e)}
    except Exception as e:
        return 0, {"error": str(e)}


def check(label: str, condition: bool, detail: str = "") -> bool:
    if condition:
        print(f"  PASS  {label}")
        return True
    msg = f"  FAIL  {label}"
    if detail:
        msg += f"\n        {detail}"
    print(msg)
    return False


def cleanup(name: str):
    status, resp = api("GET", "/scripts/deploy")
    deployments = resp.get("deployments", [])
    if any(d["name"] == name for d in deployments):
        api("POST", "/scripts/deploy/undeploy", {"name": name})


def assert_server_reachable():
    status, resp = api("GET", "/scripts/deploy")
    if status == 0:
        print(f"ERROR: Cannot reach agent server at {BASE_URL}")
        print("       Start the Microbot client and enable the Agent Server plugin, then retry.")
        sys.exit(2)


def main():
    print(f"Testing dynamic script hot-reload against {BASE_URL}\n")
    assert_server_reachable()

    failures: list[str] = []

    with tempfile.TemporaryDirectory(prefix="microbot-hotreload-") as tmpdir:
        src_dir = Path(tmpdir)
        java_file = src_dir / "HotReloadTestPlugin.java"

        cleanup(SCRIPT_NAME)

        # ── 1. Deploy v1 ──────────────────────────────────────────────────
        print("Step 1: deploy v1")
        java_file.write_text(V1_SOURCE)
        status, resp = api("POST", "/scripts/deploy", {"name": SCRIPT_NAME, "sourcePath": str(src_dir)})

        if not check("deploy returns HTTP 200", status == 200, f"got {status}: {resp}"):
            failures.append("deploy v1 HTTP status")
        if not check("deploy reports success", resp.get("success") is True, str(resp)):
            failures.append("deploy v1 success field")

        plugin_v1 = resp.get("plugin", "")
        deployed_at_v1 = resp.get("deployedAt", "")

        if not check("plugin descriptor contains 'v1'", "v1" in plugin_v1, f"plugin='{plugin_v1}'"):
            failures.append("deploy v1 descriptor name")

        # ── 2. Verify listed ──────────────────────────────────────────────
        print("\nStep 2: verify deployment list")
        status, resp = api("GET", "/scripts/deploy")
        deployments = resp.get("deployments", [])
        found = any(d["name"] == SCRIPT_NAME for d in deployments)

        if not check(f"'{SCRIPT_NAME}' appears in GET /scripts/deploy", found, str(deployments)):
            failures.append("list after deploy")

        active = next((d.get("active") for d in deployments if d["name"] == SCRIPT_NAME), None)
        if not check("deployment is active", active is True, f"active={active}"):
            failures.append("plugin active after deploy")

        # ── 3. Reload with v2 source ──────────────────────────────────────
        print("\nStep 3: update source and reload")
        time.sleep(0.1)
        java_file.write_text(V2_SOURCE)
        status, resp = api("POST", "/scripts/deploy/reload", {"name": SCRIPT_NAME})

        if not check("reload returns HTTP 200", status == 200, f"got {status}: {resp}"):
            failures.append("reload HTTP status")
        if not check("reload reports success", resp.get("success") is True, str(resp)):
            failures.append("reload success field")

        plugin_v2 = resp.get("plugin", "")
        deployed_at_v2 = resp.get("deployedAt", "")

        if not check(
            "plugin descriptor changed to 'v2' (new class was loaded)",
            "v2" in plugin_v2,
            f"plugin='{plugin_v2}' — expected 'v2', got old code or no change"
        ):
            failures.append("reload descriptor name changed to v2")

        if not check(
            "deployedAt timestamp changed after reload",
            deployed_at_v2 != deployed_at_v1,
            f"both show '{deployed_at_v1}'"
        ):
            failures.append("deployedAt changed after reload")

        # ── 4. Undeploy ───────────────────────────────────────────────────
        print("\nStep 4: undeploy")
        status, resp = api("POST", "/scripts/deploy/undeploy", {"name": SCRIPT_NAME})

        if not check("undeploy returns HTTP 200", status == 200, f"got {status}: {resp}"):
            failures.append("undeploy HTTP status")
        if not check("undeploy reports success", resp.get("success") is True, str(resp)):
            failures.append("undeploy success field")

        # ── 5. Verify gone ────────────────────────────────────────────────
        print("\nStep 5: verify cleanup")
        status, resp = api("GET", "/scripts/deploy")
        deployments = resp.get("deployments", [])
        still_there = any(d["name"] == SCRIPT_NAME for d in deployments)

        if not check(f"'{SCRIPT_NAME}' removed from deployment list", not still_there, str(deployments)):
            failures.append("post-undeploy list check")

    # ── Summary ───────────────────────────────────────────────────────────
    print()
    if failures:
        print(f"FAILED — {len(failures)} check(s) failed:")
        for f in failures:
            print(f"  • {f}")
        sys.exit(1)
    else:
        print("All checks passed — dynamic script hot-reload is working correctly.")


if __name__ == "__main__":
    main()
