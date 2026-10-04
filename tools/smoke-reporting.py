#!/usr/bin/env python3
"""Live local reporting/support acceptance using explicit simulated source data."""
import csv
import importlib.util
import io
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

spec = importlib.util.spec_from_file_location("local_smoke", Path(__file__).with_name("smoke-local.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def instant(value):
    return value.isoformat().replace("+00:00", "Z")


def main():
    env = smoke.load_environment(Path(__file__).resolve().parents[1] / ".env")
    base, simulator = "http://127.0.0.1:8081", "http://127.0.0.1:8090"
    account = str(uuid.uuid4())
    admin = smoke.jwt(env, str(uuid.uuid4()), "SUPERADMIN")
    pro = smoke.jwt(env, account, "ADMINISTRADOR", "PRO")
    free = smoke.jwt(env, account, "ADMINISTRADOR", "FREE")
    foreign = smoke.jwt(env, str(uuid.uuid4()), "ADMINISTRADOR", "PRO")
    label = str(uuid.uuid4())
    property_value = smoke.call(base, "/api/v1/properties", pro,
        {"name": "Report smoke " + label, "address": "Explicit local reporting fixture", "propertyType": "BUILDING"}, expected=201)
    points = [smoke.call(base, "/api/v1/properties/" + property_value["id"] + "/supply-points", pro,
        {"serialNumber": label + "-" + str(index), "locationName": "=1+1" if index == 0 else "Missing explicit fixture"}, expected=201) for index in range(2)]
    now = datetime.now(timezone.utc)
    rate = smoke.call(base, "/api/v1/tariffs/accounts/" + account, admin,
        {"pricePerM3": "2.30", "effectiveFrom": instant(now - timedelta(days=1)), "expectedVersion": -1}, method="PUT")
    smoke.require(rate["accountId"] == account, "Tariff account differs")
    metric = {"pointId": points[0]["id"], "volumeM3": "12.5", "pressureKpa": "2.5", "gasDetected": False,
        "valveState": "CLOSED", "connectionState": "CONNECTED", "validityState": "VALID", "gap": False,
        "measuredAt": instant(now - timedelta(seconds=1)), "calculatedAt": instant(now - timedelta(seconds=1))}
    smoke.call(simulator, "/fixtures/metrics", env["SIMULATOR_ADMIN_TOKEN"], {"accountId": account, "metrics": [metric]})
    query = urllib.parse.urlencode({"pointIds": ",".join(p["id"] for p in points),
        "from": instant(now - timedelta(hours=1)), "to": instant(now)})
    report_path = "/api/v1/reports/consumption?" + query
    report = smoke.call(base, report_path, pro)
    smoke.require(float(report["knownVolumeM3"]) == 12.5 and float(report["knownEstimatedCostPen"]) == 28.75,
        "Known report totals differ from the explicit reading/rate")
    smoke.require(not report["totalsComplete"] and any(r["source"] is None and r["knownVolumeM3"] is None for r in report["rows"]),
        "Missing telemetry was fabricated or not marked partial")
    smoke.call(base, report_path, free, expected=403)
    smoke.call(base, report_path, foreign, expected=403)
    comparison = smoke.call(base, "/api/v1/comparisons/meters?" + query, pro)
    smoke.require(len(comparison["rows"]) == 2, "Comparison lost requested rows")
    view = smoke.call(base, "/api/v1/multi-meter?propertyIds=" + property_value["id"], free)
    smoke.require(len(view["rows"]) == 2, "Free administrator multi-meter view is unavailable")
    req = urllib.request.Request(base + "/api/v1/reports/consumption.csv?" + query, headers={"Authorization": "Bearer " + pro})
    with urllib.request.urlopen(req, timeout=8) as response:
        smoke.require(response.status == 200 and response.headers.get("Cache-Control") == "no-store", "CSV response is not an uncached attachment")
        smoke.require("attachment" in response.headers.get("Content-Disposition", ""), "CSV attachment header missing")
        rows = list(csv.DictReader(io.StringIO(response.read().decode("utf-8"))))
        smoke.require(len(rows) == 2 and rows[0]["locationName"] == "[text] =1+1", "CSV formula prefix is not safely marked as text")
    technician = smoke.call(base, "/api/v1/technicians", admin, {"firstName": "Report", "lastName": "Smoke professional",
        "identification": label, "specialty": "Residential gas", "accreditation": "EXPLICIT-LOCAL-FIXTURE",
        "phone": "+51999000000", "email": "fixture@example.test"}, expected=201)
    tech_path = "/api/v1/technicians/" + technician["id"]
    smoke.call(base, tech_path + "/status", admin, {"enabled": True, "version": technician["version"]}, method="PATCH", expected=409)
    technician = smoke.call(base, tech_path + "/verification", admin,
        {"verified": True, "reference": "Explicit local manual review fixture", "version": technician["version"]}, method="PATCH")
    smoke.call(base, tech_path + "/status", admin, {"enabled": True, "version": technician["version"]}, method="PATCH")
    directory = smoke.collection(base, "/api/v1/directory/technicians", pro)
    entry = next(value for value in directory if value["id"] == technician["id"])
    smoke.require("identification" not in entry and "verifiedBy" not in entry, "Private technician verification data leaked into directory")
    body = {"propertyId": property_value["id"], "pointId": points[0]["id"], "technicianId": technician["id"],
        "performedAt": instant(now - timedelta(minutes=1)), "description": "Explicit local regulator inspection"}
    maintenance = smoke.call(base, "/api/v1/maintenance", pro, body, expected=201)
    body["description"] = "Corrected explicit local regulator inspection"
    smoke.call(base, "/api/v1/maintenance/" + maintenance["id"], pro,
        {"maintenance": body, "version": maintenance["version"]}, method="PUT")
    history = smoke.call(base, "/api/v1/maintenance/" + maintenance["id"] + "/history", pro)
    smoke.require(len(history) == 2, "Maintenance original/correction history missing")
    smoke.call(base, "/api/v1/maintenance/" + maintenance["id"], foreign, expected=403)
    result = {"status": "PASS", "accountId": account, "propertyId": property_value["id"], "pointIds": [p["id"] for p in points],
        "checks": ["explicit tariff and reading totals", "missing readings remain null", "partial totals", "warm cache plan denial",
        "warm cache account denial", "Pro comparison", "Free multi-meter", "CSV attachment and formula safety", "manual technician verification",
        "directory privacy", "maintenance correction history", "foreign maintenance denial"], "telemetrySource": "EXPLICIT_SIMULATOR_FIXTURE"}
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except smoke.SmokeFailure as error:
        print(json.dumps({"status": "FAIL", "reason": str(error)})); sys.exit(1)
    except Exception as error:
        print(json.dumps({"status": "FAIL", "errorType": type(error).__name__})); sys.exit(1)
