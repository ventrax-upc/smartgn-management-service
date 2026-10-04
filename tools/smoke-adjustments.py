#!/usr/bin/env python3
"""Live local acceptance of cancellation, historical tariffs and safe job monitoring."""
import importlib.util
import json
from pathlib import Path
import sys
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

spec = importlib.util.spec_from_file_location("local_smoke", Path(__file__).with_name("smoke-local.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def correlated(base, path, token, payload, correlation, expected=200):
    request = urllib.request.Request(base + path, data=json.dumps(payload).encode(), method="POST",
                                    headers={"Authorization": "Bearer " + token, "Content-Type": "application/json", "X-Correlation-ID": correlation})
    with urllib.request.urlopen(request, timeout=10) as response:
        smoke.require(response.status == expected and response.headers.get("X-Correlation-ID") == correlation, "HTTP correlation was not preserved")
        return json.loads(response.read())


def instant(value):
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def main():
    base, telemetry = "http://127.0.0.1:8081", "http://127.0.0.1:8090"
    environment = smoke.load_environment(Path(".env"))
    account = str(uuid.uuid4())
    owner = smoke.jwt(environment, account, "ADMINISTRADOR", "PRO")
    admin = smoke.jwt(environment, str(uuid.uuid4()), "SUPERADMIN")
    foreign = smoke.jwt(environment, str(uuid.uuid4()), "ADMINISTRADOR", "PRO")
    label = "ADJUST-" + uuid.uuid4().hex[:12]
    installers = []
    for index in range(2):
        installer = smoke.call(base, "/api/v1/installers", admin, {"firstName": "Adjustment", "lastName": "Installer",
            "identification": label + "-" + str(index), "phone": "123456789"}, expected=201)
        installers.append(smoke.call(base, "/api/v1/installers/" + installer["id"] + "/status", admin,
                                     {"enabled": True, "version": installer["version"]}, method="PATCH"))
    property_value = smoke.call(base, "/api/v1/properties", owner,
        {"name": label, "address": "Explicit local adjustment fixture", "propertyType": "HOUSE"}, expected=201)
    point = smoke.call(base, "/api/v1/properties/" + property_value["id"] + "/supply-points", owner,
        {"serialNumber": label, "locationName": "Kitchen"}, expected=201)
    installation = smoke.call(base, "/api/v1/installations", admin,
        {"propertyId": property_value["id"], "pointId": point["id"]}, expected=201)
    path = "/api/v1/installations/" + installation["id"]
    installation = smoke.call(base, path + "/assign", admin, {"installerId": installers[0]["id"]}, method="PATCH")
    installation = smoke.call(base, path + "/reassign", admin,
        {"installerId": installers[1]["id"], "version": installation["version"], "reason": "Original installer unavailable"}, method="PATCH")
    smoke.call(base, path + "/reassign", admin,
        {"installerId": installers[0]["id"], "version": 1, "reason": "Stale reassignment"}, method="PATCH", expected=409)
    installation = smoke.call(base, path + "/status", admin, {"status": "IN_PROGRESS"}, method="PATCH")
    correlation = str(uuid.uuid4())
    receipt = correlated(base, "/api/v1/devices", admin,
        {"installationId": installation["id"], "serialNumber": label + "-device", "location": "Kitchen", "installedAt": smoke.iso_now()}, correlation, 202)
    device = smoke.wait_device(base, owner, point["id"], receipt["device"]["id"], lambda d: d["status"] == "ACTIVE")
    installation = smoke.call(base, path, admin)
    cancellation = {"version": installation["version"], "reason": "Owner cancelled the unfinished installation"}
    smoke.call(base, path + "/cancel", admin, cancellation, expected=409)
    smoke.call(base, "/api/v1/devices/" + device["id"] + "/credentials/revoke", admin, expected=202, method="POST")
    smoke.wait_admin_device(base, admin, device["id"], lambda d: d["status"] == "REVOKED" and d["brokerConfirmed"])
    cancelled = smoke.call(base, path + "/cancel", admin, cancellation)
    smoke.require(cancelled["status"] == "CANCELLED", "Installation was not cancelled")
    smoke.require(smoke.call(base, path + "/cancel", admin, cancellation) == cancelled, "Cancellation replay changed its history/version")
    smoke.call(base, "/api/v1/installations", admin, {"propertyId": property_value["id"], "pointId": point["id"]}, expected=201)
    jobs = list(smoke.collection(base, "/api/v1/operations/outbox?deviceId=" + device["id"], admin))
    provisioning = next(job for job in jobs if job["correlationId"] == correlation)
    smoke.require(provisioning["status"] == "DONE", "Provisioning did not complete")
    smoke.require(all("payload" not in job and "claimToken" not in job for job in jobs), "Outbox exposed secret material")
    smoke.call(base, "/api/v1/operations/outbox", owner, expected=403)
    smoke.call(base, "/api/v1/operations/outbox/" + provisioning["id"] + "/retry", admin, {"reason": "Completed work cannot be replayed"}, expected=409)

    end = datetime.now(timezone.utc) - timedelta(seconds=5)
    start, change = end - timedelta(hours=2), end - timedelta(hours=1)
    tariff_path = "/api/v1/tariffs/accounts/" + account
    smoke.call(base, tariff_path, admin, {"pricePerM3": 2, "effectiveFrom": instant(start), "expectedVersion": -1}, method="PUT")
    smoke.call(base, tariff_path, admin, {"pricePerM3": 3, "effectiveFrom": instant(change), "expectedVersion": 0}, method="PUT")
    for lower, upper, volume in ((start, end, 10), (start, change, 4), (change, end, 6)):
        metric = {"pointId": point["id"], "volumeM3": volume, "pressureKpa": None, "gasDetected": None,
                  "valveState": "UNKNOWN", "connectionState": "DISCONNECTED", "validityState": "VALID",
                  "measuredAt": instant(upper - timedelta(seconds=1)), "calculatedAt": smoke.iso_now(), "gap": False}
        smoke.call(telemetry, "/fixtures/period-metrics", environment["SIMULATOR_ADMIN_TOKEN"],
                   {"accountId": account, "from": instant(lower), "to": instant(upper), "metrics": [metric]})
    query = urllib.parse.urlencode({"pointIds": point["id"], "from": instant(start), "to": instant(end)})
    report = smoke.call(base, "/api/v1/reports/consumption?" + query, owner)
    smoke.require(report["knownVolumeM3"] == 10 and report["knownEstimatedCostPen"] == 26 and report["totalsComplete"], "Historical tariff intervals were not calculated from exact volumes")
    smoke.require(len(report["tariffPeriods"]) == 2 and report["appliedTariff"] is None, "Report hides its multiple tariff intervals")
    early = smoke.call(base, "/api/v1/reports/consumption?" + urllib.parse.urlencode(
        {"pointIds": point["id"], "from": instant(start), "to": instant(change)}), owner)
    smoke.require(early["knownEstimatedCostPen"] == 8, "Historical report used the new tariff for an old period")
    smoke.call(base, "/api/v1/reports/consumption?" + query, foreign, expected=403)
    smoke.call(base, tariff_path + "/history", foreign, expected=403)
    history = smoke.call(base, "/api/v1/tariffs/me/history", owner)
    smoke.require(len(history) == 2, "Tariff history was overwritten")
    smoke.call(base, "/api/v1/properties?limit=101", owner, expected=400)
    print(json.dumps({"status": "PASS", "checks": ["installer reassignment and stale version rejection",
        "cancellation requires confirmed broker revocation", "idempotent cancellation", "new installation after cancellation",
        "HTTP/audit/outbox correlation", "safe SuperAdmin job monitoring", "completed job cannot be replayed",
        "historical tariff intervals 4x2+6x3=26", "earlier interval retains old tariff", "warm-cache account denial", "bounded pagination"],
        "telemetrySource": "EXPLICIT_SIMULATOR_FIXTURE"}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except smoke.SmokeFailure as error:
        print(json.dumps({"status": "FAIL", "reason": str(error)})); sys.exit(1)
    except Exception as error:
        print(json.dumps({"status": "FAIL", "errorType": type(error).__name__})); sys.exit(1)
