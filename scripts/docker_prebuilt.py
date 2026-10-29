#!/usr/bin/env python3
"""Package already-built Maven jars into runtime images without an in-Docker Maven cache.

Run mvn package first. Never starts/stops containers, removes images, or touches volumes.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = {"fx": "ledgerline-fx", "ai-gateway": "ledgerline-ai-gateway", "reporting": "ledgerline-reporting"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("services", nargs="*", metavar="{fx,ai-gateway,reporting}")
    args = parser.parse_args()
    services = args.services or list(MODULES)
    if set(services) - MODULES.keys():
        parser.error("Services must be fx, ai-gateway, or reporting")
    for service in services:
        jar = ROOT / MODULES[service] / "target" / f"{MODULES[service]}-0.1.0.jar"
        if not jar.is_file():
            parser.error(f"Missing {jar}. Run mvn package first.")
    (ROOT / ".run").mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="prebuilt-", dir=ROOT / ".run"))
    print(f"Prebuilt image artifacts: {directory}", flush=True)
    for service in services:
        module = MODULES[service]
        shutil.copy2(ROOT / module / "target" / f"{module}-0.1.0.jar", directory)
    subprocess.run(["javac", "--release", "21", "-d", str(directory), str(ROOT / "docker/Healthcheck.java")], check=True)
    config = json.loads(subprocess.check_output(["docker", "compose", "config", "--format", "json"], cwd=ROOT, text=True))
    for service in services:
        subprocess.run(["docker", "build", "--target", "prebuilt", "--build-context", f"artifacts={directory}",
                        "--build-arg", f"MODULE={MODULES[service]}", "-t", f"{config['name']}-{service}", "."], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
