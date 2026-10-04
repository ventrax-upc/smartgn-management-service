#!/usr/bin/env python3
"""Read persisted smoke fixtures after cache failure or service restart."""
import importlib.util
import json
import sys
import time
import urllib.parse
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

spec = importlib.util.spec_from_file_location("local_smoke", Path(__file__).with_name("smoke-local.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def main():
    root = Path(__file__).resolve().parents[1]
    state = json.loads((root / ".local/reporting-result.json").read_text(encoding="utf-8-sig"))
    smoke.require(state["status"] == "PASS", "Run the reporting smoke check first")
    env = smoke.load_environment(root / ".env")
    token = smoke.jwt(env, state["accountId"], "ADMINISTRADOR", "PRO")
    now = datetime.now(timezone.utc)
    query = urllib.parse.urlencode({"pointIds": ",".join(state["pointIds"]),
        "from": (now - timedelta(hours=1)).isoformat(), "to": now.isoformat()})
    began = time.monotonic()
    result = smoke.call("http://127.0.0.1:8081", "/api/v1/reports/consumption?" + query, token)
    smoke.require(float(result["knownVolumeM3"]) == 12.5 and float(result["knownEstimatedCostPen"]) == 28.75,
        "Persisted explicit reading or database tariff was lost")
    foreign = smoke.jwt(env, str(uuid.uuid4()), "ADMINISTRADOR", "PRO")
    smoke.call("http://127.0.0.1:8081", "/api/v1/reports/consumption?" + query, foreign, expected=403)
    print(json.dumps({"status": "PASS", "checks": ["persisted property/points/tariff", "persistent simulator fixture", "account isolation"],
        "reportSeconds": round(time.monotonic() - began, 3)}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except smoke.SmokeFailure as error:
        print(json.dumps({"status": "FAIL", "reason": str(error)})); sys.exit(1)
    except Exception as error:
        print(json.dumps({"status": "FAIL", "errorType": type(error).__name__})); sys.exit(1)
