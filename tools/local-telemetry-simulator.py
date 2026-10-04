#!/usr/bin/env python3
"""Explicit development simulator; not the SmartGN Telemetry implementation.

Only administrator-provided fixtures supply measurements or buffer confirmations.
Associations and fixtures survive process/container restart in SQLite.
"""
import hmac
import json
import os
import sqlite3
import socket
import ssl
import threading
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATABASE = os.environ.get("SIMULATOR_DB", "/data/telemetry.sqlite")
SERVICE_TOKEN = os.environ["TELEMETRY_SERVICE_TOKEN"]
ADMIN_TOKEN = os.environ["SIMULATOR_ADMIN_TOKEN"]
LOCK = threading.RLock()
CONNECTION = None


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def identifier(value):
    return str(uuid.UUID(str(value)))


def initialize():
    global CONNECTION
    os.makedirs(os.path.dirname(os.path.abspath(DATABASE)), exist_ok=True)
    # A single synchronized connection avoids contending WAL connection teardown
    # under concurrent fixture queries. This is a development-only data source.
    CONNECTION = sqlite3.connect(DATABASE, check_same_thread=False)
    with CONNECTION as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("CREATE TABLE IF NOT EXISTS associations (device TEXT, version INTEGER, payload TEXT, PRIMARY KEY(device,version))")
        db.execute("CREATE TABLE IF NOT EXISTS metrics (account TEXT, point TEXT, payload TEXT, PRIMARY KEY(account,point))")
        db.execute("CREATE TABLE IF NOT EXISTS buffers (device TEXT, version INTEGER, pending INTEGER, evidence TEXT, PRIMARY KEY(device,version))")
        db.execute("CREATE TABLE IF NOT EXISTS operational_status (account TEXT, device TEXT, payload TEXT, PRIMARY KEY(account,device))")
        db.execute("CREATE TABLE IF NOT EXISTS period_metrics (account TEXT, point TEXT, lower TEXT, upper TEXT, payload TEXT, PRIMARY KEY(account,point,lower,upper))")
        db.execute("CREATE INDEX IF NOT EXISTS association_point_idx ON associations(json_extract(payload,'$.pointId'))")


class Handler(BaseHTTPRequestHandler):
    # One response per connection keeps this stdlib simulator bounded under load.
    # Production Telemetry will use its own managed HTTP server and connection pool.
    protocol_version = "HTTP/1.0"
    def setup(self):
        super().setup()
        self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.connection.settimeout(5)
    def log_message(self, fmt, *args):
        # Request bodies and authorization headers are never logged.
        pass

    def reply(self, status, payload):
        value = json.dumps(payload, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(value)))
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(value)

    def authenticated(self, expected):
        return hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + expected)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "UP", "mode": "DEVELOPMENT_SIMULATOR", "realTelemetry": False})
        else:
            self.reply(404, {"error": "Not found"})

    def do_POST(self):
        token = ADMIN_TOKEN if self.path.startswith("/fixtures/") else SERVICE_TOKEN
        if not self.authenticated(token):
            self.close_connection = True
            self.reply(401, {"error": "Unauthorized"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > 1_000_000:
                self.close_connection = True
                self.reply(400, {"error": "Invalid body size"})
                return
            payload = json.loads(self.rfile.read(size))
            with LOCK, CONNECTION:
                status, result = self.route(CONNECTION, payload)
            self.reply(status, result)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            # A disconnected client must not trigger a second attempted response.
            self.close_connection = True
        except (ValueError, KeyError, TypeError):
            self.reply(400, {"error": "Invalid request"})
        except Exception:
            self.reply(500, {"error": "Simulator operation failed"})

    def route(self, db, payload):
        if self.path == "/internal/v1/device-associations":
            device = identifier(payload["deviceId"])
            version = int(payload["version"])
            if version <= 0:
                raise ValueError()
            for field in ("accountId", "propertyId", "pointId"):
                payload[field] = identifier(payload[field])
            datetime.fromisoformat(payload["validFrom"].replace("Z", "+00:00"))
            payload["deviceId"] = device
            payload["version"] = version
            existing = db.execute("SELECT payload FROM associations WHERE device=? AND version=?", (device, version)).fetchone()
            if existing:
                previous = json.loads(existing[0])
                # Closing a validity interval is allowed; identity fields are immutable.
                identity = ("deviceId", "version", "accountId", "propertyId", "pointId", "validFrom")
                if any(previous.get(key) != payload.get(key) for key in identity):
                    return 409, {"error": "Association version conflict"}
                if previous.get("validTo") is not None and previous.get("validTo") != payload.get("validTo"):
                    return 409, {"error": "Closed association cannot be reopened"}
            db.execute("INSERT OR REPLACE INTO associations VALUES(?,?,?)", (device, version, json.dumps(payload)))
            return 200, {"deviceId": device, "version": version, "confirmed": True}

        if self.path == "/internal/v1/telemetry/batch-query":
            return self.batch(db, payload)

        if self.path == "/internal/v1/telemetry/periods-query":
            periods = payload["periods"]
            if not periods or len(periods) > 101:
                return 400, {"error": "Request 1..101 exact periods"}
            results = []
            for period in periods:
                lower, upper = period["from"], period["to"]
                if timestamp(lower) >= timestamp(upper):
                    raise ValueError()
                status, snapshot = self.batch(db, {"accountId": payload["accountId"], "pointIds": payload["pointIds"],
                                                  "from": lower, "to": upper}, exact_only=True)
                if status != 200:
                    return status, snapshot
                results.append({"from": lower, "to": upper, "snapshot": snapshot})
            return 200, {"periods": results}

        if self.path == "/internal/v1/devices/status-query":
            account = identifier(payload["accountId"])
            if payload.get("purpose") != "DEVICE_OPERATIONS":
                return 403, {"error": "Only operational metadata is exposed"}
            results = []
            for value in payload["deviceIds"]:
                device = identifier(value)
                association = db.execute("SELECT payload FROM associations WHERE device=? ORDER BY version DESC LIMIT 1", (device,)).fetchone()
                if association and json.loads(association[0])["accountId"] != account:
                    return 403, {"error": "Device is outside the authorized account"}
                row = db.execute("SELECT payload FROM operational_status WHERE account=? AND device=?", (account, device)).fetchone()
                if row:
                    results.append(json.loads(row[0]))
            return 200, results

        if self.path.startswith("/internal/v1/devices/") and self.path.endswith("/buffer-drain"):
            device = identifier(self.path.split("/")[4])
            version = int(payload["associationVersion"])
            row = db.execute("SELECT pending,evidence FROM buffers WHERE device=? AND version=?", (device, version)).fetchone()
            if row is None:
                return 200, {"drained": False, "pendingReadings": 0, "evidenceId": None, "pendingCountKnown": False}
            return 200, {"drained": row[0] == 0, "pendingReadings": row[0], "evidenceId": row[1], "pendingCountKnown": True}

        if self.path in ("/fixtures/metrics", "/fixtures/period-metrics"):
            account = identifier(payload["accountId"])
            period = self.path.endswith("period-metrics")
            if period:
                lower, upper = timestamp(payload["from"]), timestamp(payload["to"])
                if lower >= upper:
                    raise ValueError()
            for metric in payload["metrics"]:
                point = identifier(metric["pointId"])
                metric["pointId"] = point
                # All timestamps and measurements must be provided explicitly.
                required = ("volumeM3", "pressureKpa", "gasDetected", "valveState", "connectionState", "validityState", "measuredAt", "calculatedAt", "gap")
                if any(field not in metric for field in required):
                    raise ValueError()
                if not period and payload.get("refreshTimestamps") is True:
                    metric["_fixtureClock"] = True
                if period:
                    db.execute("INSERT OR REPLACE INTO period_metrics VALUES(?,?,?,?,?)", (account, point, lower.isoformat(), upper.isoformat(), json.dumps(metric)))
                else:
                    db.execute("INSERT OR REPLACE INTO metrics VALUES(?,?,?)", (account, point, json.dumps(metric)))
            return 200, {"mode": "DEVELOPMENT_SIMULATOR", "fixturesStored": len(payload["metrics"])}

        if self.path == "/fixtures/buffer":
            device = identifier(payload["deviceId"])
            version = int(payload["version"])
            pending = int(payload["pendingReadings"])
            if pending < 0 or version < 1:
                raise ValueError()
            evidence = "explicit-simulator-fixture-" + str(uuid.uuid4())
            db.execute("INSERT OR REPLACE INTO buffers VALUES(?,?,?,?)", (device, version, pending, evidence))
            return 200, {"mode": "DEVELOPMENT_SIMULATOR", "evidenceId": evidence}
        if self.path == "/fixtures/device-status":
            account = identifier(payload["accountId"])
            device = identifier(payload["deviceId"])
            state = payload["connectionState"]
            contact = payload["lastContact"]
            if not isinstance(state, str):
                raise ValueError()
            if contact:
                datetime.fromisoformat(contact.replace("Z", "+00:00"))
            status = {"deviceId": device, "connectionState": state, "lastContact": contact}
            db.execute("INSERT OR REPLACE INTO operational_status VALUES(?,?,?)", (account, device, json.dumps(status)))
            return 200, {"mode": "DEVELOPMENT_SIMULATOR", "fixtureStored": True}
        return 404, {"error": "Not found"}

    def batch(self, db, payload, exact_only=False):
        account = identifier(payload["accountId"])
        points = list(dict.fromkeys(identifier(point) for point in payload["pointIds"]))
        if not points or len(points) > 100:
            return 400, {"error": "Request 1..100 points per batch"}
        marks = ",".join("?" for _ in points)
        current = db.execute("SELECT payload FROM associations a WHERE json_extract(payload,'$.pointId') IN (" + marks +
                             ") AND version=(SELECT MAX(version) FROM associations WHERE device=a.device)", points).fetchall()
        if any(json.loads(row[0]).get("validTo") is None and json.loads(row[0])["accountId"] != account for row in current):
            return 403, {"error": "Point is outside account scope"}
        lower, upper = payload.get("from"), payload.get("to")
        if lower and upper:
            rows = db.execute("SELECT payload FROM period_metrics WHERE account=? AND point IN (" + marks +
                              ") AND lower=? AND upper=?", [account, *points, timestamp(lower).isoformat(), timestamp(upper).isoformat()]).fetchall()
            values = [json.loads(row[0]) for row in rows]
            metrics = {metric["pointId"]: metric for metric in values}
        else:
            metrics = {}
        if not exact_only:
            rows = db.execute("SELECT payload FROM metrics WHERE account=? AND point IN (" + marks + ")", [account, *points]).fetchall()
            for row in rows:
                metric = json.loads(row[0])
                measured = metric.get("measuredAt")
                if measured and lower and timestamp(measured) < timestamp(lower):
                    continue
                if measured and upper and timestamp(measured) >= timestamp(upper):
                    continue
                metrics.setdefault(metric["pointId"], metric)
        for metric in metrics.values():
            fixture_clock = metric.pop("_fixtureClock", False)
            if fixture_clock and not lower and not upper:
                metric["measuredAt"] = now()
                metric["calculatedAt"] = now()
        return 200, {"metrics": list(metrics.values()), "calculatedAt": now()}


class BoundedHTTPServer(ThreadingHTTPServer):
    request_queue_size = 128

    def __init__(self, *args, **kwargs):
        self.request_slots = threading.BoundedSemaphore(64)
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        self.request_slots.acquire()
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.request_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.request_slots.release()


def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


if __name__ == "__main__":
    initialize()
    server = BoundedHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8091"))), Handler)
    cert, key = os.environ.get("SIMULATOR_TLS_CERT"), os.environ.get("SIMULATOR_TLS_KEY")
    if cert and key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
    print("SmartGN development Telemetry simulator ready; no real ingestion or derived measurements", flush=True)
    server.serve_forever()
