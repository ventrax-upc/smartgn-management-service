#!/usr/bin/env python3
"""Local open-loop QAS8 workload; Management/SQL/Redis are real, Telemetry is a fixture.

Creates labeled synthetic records in the Management database; never deletes existing data.
Default: 500 distinct accounts, 100 points each, 100 requests/s for 900 seconds.
Local signed tokens bypass IAM login. No credentials are written to the result.
Latency includes scheduling/queue delay; generator overload counts as errors.
"""
import argparse
import concurrent.futures
import http.client
import importlib.util
import json
import math
from pathlib import Path
import subprocess
import threading
import time
import urllib.parse
import uuid
from datetime import datetime, timezone
spec = importlib.util.spec_from_file_location("local_smoke", Path(__file__).with_name("smoke-local.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def setup_fixture(environment, args):
    manifest = Path(args.fixture_file)
    if manifest.exists():
        fixture = json.loads(manifest.read_text(encoding="utf-8"))
        if len(fixture["accounts"]) != args.users or any(len(a["pointIds"]) != args.meters for a in fixture["accounts"]):
            raise RuntimeError("Existing load fixture dimensions differ; choose a new fixture file")
    else:
        label = "LOAD-" + uuid.uuid4().hex[:12]
        accounts = [{"id": str(uuid.uuid4()), "propertyId": str(uuid.uuid4()),
                     "pointIds": [str(uuid.uuid4()) for _ in range(args.meters)]} for _ in range(args.users)]
        role = environment["SPRING_DATASOURCE_USERNAME"]
        if not role.replace("_", "a").isalnum() or not role[0].isalpha():
            raise RuntimeError("Unsupported database role identifier")
        lines = ["BEGIN;", "SET LOCAL ROLE " + role + ";",
                 "COPY management.properties (id,account_id,name,address,property_type,active,version) FROM STDIN;"]
        for index, account in enumerate(accounts):
            lines.append("\t".join((account["propertyId"], account["id"], label + "-" + str(index), "Explicit local load fixture", "BUILDING", "true", "0")))
        lines += ["\\.", "COPY management.supply_points (id,property_id,serial_number,location_name,active,version) FROM STDIN;"]
        for account_index, account in enumerate(accounts):
            for point_index, point in enumerate(account["pointIds"]):
                lines.append("\t".join((point, account["propertyId"], label + "-" + str(account_index) + "-" + str(point_index), "Explicit load meter", "true", "0")))
        lines += ["\\.", "COMMIT;"]
        result = subprocess.run(["docker", "exec", "-i", "smartgn-postgres", "psql", "-U", "postgres", "-d", "smartgn-management-db", "-v", "ON_ERROR_STOP=1"],
                                input="\n".join(lines) + "\n", text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError("Cannot create isolated, labeled load records in the Management database")
        fixture = {"label": label, "accounts": accounts, "telemetrySource": "EXPLICIT_SIMULATOR_FIXTURE"}
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(json.dumps(fixture), encoding="utf-8")
    timestamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    for account in fixture["accounts"]:
        metrics = [{"pointId": point, "volumeM3": 1.25, "pressureKpa": 1.5, "gasDetected": False,
                    "valveState": "OPEN", "connectionState": "CONNECTED", "validityState": "VALID",
                    "measuredAt": timestamp, "calculatedAt": timestamp, "gap": False} for point in account["pointIds"]]
        # Explicit fixture clock only keeps synthetic snapshots fresh during the workload.
        smoke.call(args.telemetry, "/fixtures/metrics", environment["SIMULATOR_ADMIN_TOKEN"],
                   {"accountId": account["id"], "metrics": metrics, "refreshTimestamps": True})
    return fixture


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8081")
    parser.add_argument("--telemetry", default="http://127.0.0.1:8090")
    parser.add_argument("--duration", type=int, default=900)
    parser.add_argument("--rps", type=int, default=100)
    parser.add_argument("--users", type=int, default=500)
    parser.add_argument("--meters", type=int, default=100)
    parser.add_argument("--workers", type=int, default=256)
    parser.add_argument("--fixture-file", default=".local/load-fixture.json")
    parser.add_argument("--output", default=".local/load-result.json")
    parser.add_argument("--compose-network", action="store_true", help="Allow only this local Compose network's management/telemetry hostnames")
    args = parser.parse_args()
    for value in (args.base, args.telemetry):
        allowed = ("127.0.0.1", "localhost", "management", "telemetry") if args.compose_network else ("127.0.0.1", "localhost")
        if urllib.parse.urlparse(value).hostname not in allowed:
            parser.error("This utility is restricted to the local development stack")
    if not 1 <= args.meters <= 100 or not 1 <= args.duration <= 3600 or args.users < 1 or args.rps < 1 or args.workers < 1:
        parser.error("Invalid workload dimensions")
    environment = smoke.load_environment(Path(".env"))
    print(json.dumps({"phase": "PREPARING", "users": args.users, "metersPerView": args.meters}), flush=True)
    fixture = setup_fixture(environment, args)
    accounts = fixture["accounts"]
    tokens = [smoke.jwt(environment, a["id"], "ADMINISTRADOR", "FREE") for a in accounts]
    expected = [frozenset(a["pointIds"]) for a in accounts]
    destination = urllib.parse.urlparse(args.base)
    local = threading.local()
    capacity = threading.BoundedSemaphore(args.workers * 2)
    lock = threading.Lock()
    latencies, service_times = [], []
    failures = {}
    completed = 0

    def execute(index, scheduled):
        nonlocal completed
        started = time.perf_counter()
        reason = None
        try:
            if not hasattr(local, "connection"):
                local.connection = http.client.HTTPConnection(destination.hostname, destination.port or 80, timeout=10)
            local.connection.request("GET", destination.path.rstrip("/") + "/api/v1/multi-meter",
                                     headers={"Authorization": "Bearer " + tokens[index]})
            response = local.connection.getresponse()
            data = response.read()
            if response.status != 200:
                reason = "HTTP_" + str(response.status)
            else:
                rows = json.loads(data)["rows"]
                if len(rows) != args.meters or frozenset(row["pointId"] for row in rows) != expected[index]:
                    reason = "WRONG_POINT_SCOPE"
                elif any(row["source"] is None or row["knownVolumeM3"] is None or not row["complete"] for row in rows):
                    reason = "MISSING_OR_STALE_FIXTURE"
        except Exception as error:
            reason = type(error).__name__
            if hasattr(local, "connection"):
                local.connection.close()
                del local.connection
        finally:
            finished = time.perf_counter()
            with lock:
                completed += 1
                latencies.append(finished - scheduled)
                service_times.append(finished - started)
                if reason:
                    failures[reason] = failures.get(reason, 0) + 1
            capacity.release()

    total = args.duration * args.rps
    offered, dropped = 0, 0
    interrupted = False
    start = time.perf_counter()
    next_progress = start + 60
    print(json.dumps({"phase": "RUNNING", "plannedRequests": total, "durationSeconds": args.duration,
                      "requestsPerSecond": args.rps, "telemetrySource": fixture["telemetrySource"]}), flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        try:
            for index in range(total):
                scheduled = start + index / args.rps
                delay = scheduled - time.perf_counter()
                if delay > 0:
                    time.sleep(delay)
                offered += 1
                if capacity.acquire(blocking=False):
                    pool.submit(execute, index % args.users, scheduled)
                else:
                    dropped += 1
                if time.perf_counter() >= next_progress:
                    with lock:
                        print(json.dumps({"phase": "PROGRESS", "elapsedSeconds": round(time.perf_counter() - start),
                                          "offered": offered, "completed": completed, "errors": sum(failures.values()) + dropped,
                                          "errorTypes": dict(failures), "generatorDropped": dropped,
                                          "p95Seconds": percentile(latencies, .95)}), flush=True)
                    next_progress += 60
            remaining = start + args.duration - time.perf_counter()
            if remaining > 0:
                time.sleep(remaining)
        except KeyboardInterrupt:
            interrupted = True
    elapsed = time.perf_counter() - start
    errors = sum(failures.values()) + dropped
    p95 = percentile(latencies, .95)
    passed = not interrupted and offered == total and p95 is not None and p95 <= 2 and errors / offered <= .01
    result = {"status": "INTERRUPTED" if interrupted else "PASS" if passed else "FAIL",
              "workload": {"users": args.users, "metersPerView": args.meters, "targetRps": args.rps,
                           "plannedDurationSeconds": args.duration, "actualDurationSeconds": round(elapsed, 3), "generatorWorkers": args.workers},
              "offered": offered, "completed": completed, "generatorDropped": dropped,
              "errors": errors, "errorRate": errors / offered if offered else None, "errorTypes": failures,
              "p50Seconds": percentile(latencies, .50), "p95Seconds": p95, "p99Seconds": percentile(latencies, .99),
              "requestOnlyP95Seconds": percentile(service_times, .95), "achievedRps": completed / elapsed,
              "fullQas8Dimensions": args.users == 500 and args.meters == 100 and args.rps == 100 and args.duration == 900,
              "fixtureLabel": fixture["label"], "telemetrySource": fixture["telemetrySource"], "iamLoginExercised": False,
              "latencyIncludesSchedulingDelay": True}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2), flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({"status": "FAIL", "phase": "SETUP", "errorType": type(error).__name__}), flush=True)
        raise SystemExit(1)
