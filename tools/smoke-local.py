#!/usr/bin/env python3
"""Repeatable live local HTTP/MQTT acceptance check, using only Python stdlib.

Mints local test JWTs from the local .env; never use this helper against production.
Creates uniquely labelled records and preserves existing data. Does not print secrets.
The Telemetry dependency is a declared simulator; EMQX MQTT and Management HTTP are real.
"""
import argparse
import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
from pathlib import Path
import socket
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone


class SmokeFailure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise SmokeFailure(message)


def load_environment(path):
    values = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip("\"").strip("'")
    values.update(os.environ)
    return values


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def jwt(environment, account, role, plan="FREE"):
    timestamp = int(time.time())
    claims = {"sub": account, "accountId": account, "role": role, "plan": plan,
              "iss": environment.get("JWT_ISSUER", "smartgn-iam-service"), "iat": timestamp, "exp": timestamp + 3600}
    if environment.get("JWT_AUDIENCE"):
        claims["aud"] = environment["JWT_AUDIENCE"]
    unsigned = b64(b'{"alg":"HS256","typ":"JWT"}') + "." + b64(json.dumps(claims).encode())
    return unsigned + "." + b64(hmac.new(environment["JWT_SECRET"].encode(), unsigned.encode(), hashlib.sha256).digest())


def call(base, path, token, body=None, method=None, expected=None):
    method = method or ("POST" if body is not None else "GET")
    request = urllib.request.Request(base.rstrip("/") + path,
                                    data=None if body is None else json.dumps(body).encode(), method=method,
                                    headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            status, content = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, content = error.code, error.read()
    except (OSError, TimeoutError):
        raise SmokeFailure("HTTP dependency unavailable for " + method + " " + path) from None
    if expected is not None:
        require(status == expected, "Unexpected HTTP status for " + method + " " + path + ": " + str(status))
    else:
        require(200 <= status < 300, "HTTP operation failed for " + method + " " + path + ": " + str(status))
    return json.loads(content) if content else None


def iso_now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def mqtt_variable(value):
    result = bytearray()
    while True:
        byte = value % 128
        value //= 128
        if value:
            byte |= 128
        result.append(byte)
        if not value:
            return bytes(result)


def mqtt_string(value):
    encoded = value.encode()
    return struct.pack("!H", len(encoded)) + encoded


class Mqtt5:
    def __init__(self, host, port, username, password):
        self.socket = socket.create_connection((host, port), timeout=3)
        self.socket.settimeout(3)
        self.packet_id = 0
        client = "smartgn-smoke-" + str(uuid.uuid4())
        connect = mqtt_string("MQTT") + b"\x05\xc2\x00\x3c\x00" + mqtt_string(client) + mqtt_string(username) + mqtt_string(password)
        self.send(0x10, connect)
        header, body = self.read()
        require(header == 0x20 and len(body) >= 3, "Broker did not return an MQTT 5 CONNACK")
        self.reason = body[1]

    def send(self, header, body):
        self.socket.sendall(bytes([header]) + mqtt_variable(len(body)) + body)

    def exact(self, size):
        result = bytearray()
        while len(result) < size:
            value = self.socket.recv(size - len(result))
            if not value:
                raise EOFError()
            result.extend(value)
        return bytes(result)

    def read(self):
        header = self.exact(1)[0]
        size, multiplier = 0, 1
        for index in range(4):
            byte = self.exact(1)[0]
            size += (byte & 127) * multiplier
            if not byte & 128:
                return header, self.exact(size)
            multiplier *= 128
        raise SmokeFailure("Malformed MQTT remaining length")

    def publish(self, topic, payload):
        self.packet_id += 1
        body = mqtt_string(topic) + struct.pack("!H", self.packet_id) + b"\x00" + json.dumps(payload).encode()
        self.send(0x32, body)
        header, ack = self.read()
        if header == 0xE0:
            return ack[0] if ack else 0x80
        require(header == 0x40 and len(ack) >= 2, "Broker did not acknowledge QoS 1 publication")
        require(struct.unpack("!H", ack[:2])[0] == self.packet_id, "MQTT PUBACK identity mismatch")
        return ack[2] if len(ack) > 2 else 0

    def close(self):
        try:
            self.send(0xE0, b"\x00\x00")
        except OSError:
            pass
        self.socket.close()


def wait_device(base, owner_token, point, device, predicate, limit=45):
    deadline = time.monotonic() + limit
    while time.monotonic() < deadline:
        rows = call(base, "/api/v1/devices?pointId=" + point, owner_token)
        for row in rows:
            value = row["device"]
            if value["id"] == device and predicate(value):
                return value
        time.sleep(0.3)
    raise SmokeFailure("Device did not reach the requested externally confirmed state")


def wait_admin_device(base, admin_token, device, predicate, limit=30):
    deadline = time.monotonic() + limit
    while time.monotonic() < deadline:
        for row in collection(base, "/api/v1/devices", admin_token):
            value = row["device"]
            if value["id"] == device and predicate(value):
                return value
        time.sleep(0.25)
    raise SmokeFailure("Device administrative confirmation did not arrive")


def collection(base, path, token):
    """Read bounded API pages for local acceptance utilities, preserving server ordering."""
    offset = 0
    separator = "&" if "?" in path else "?"
    while True:
        rows = call(base, path + separator + "offset=" + str(offset) + "&limit=100", token)
        yield from rows
        if len(rows) < 100:
            return
        offset += 100


def install_request(base, owner, admin, installer, label):
    property_value = call(base, "/api/v1/properties", owner,
                          {"name": "Smoke " + label, "address": "Local acceptance fixture " + label, "propertyType": "HOUSE"}, expected=201)
    point = call(base, "/api/v1/properties/" + property_value["id"] + "/supply-points", owner,
                 {"serialNumber": "point-" + label, "locationName": "Local acceptance point"}, expected=201)
    request = call(base, "/api/v1/installations", admin, {"propertyId": property_value["id"], "pointId": point["id"]}, expected=201)
    call(base, "/api/v1/installations/" + request["id"] + "/assign", admin, {"installerId": installer}, method="PATCH")
    call(base, "/api/v1/installations/" + request["id"] + "/status", admin, {"status": "IN_PROGRESS"}, method="PATCH")
    return property_value, point, request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=Path(__file__).resolve().parents[1] / ".env")
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--simulator-url", default="http://127.0.0.1:8090")
    parser.add_argument("--mqtt-host", default="127.0.0.1")
    parser.add_argument("--mqtt-port", type=int, default=1883)
    args = parser.parse_args()
    env = load_environment(args.env_file)
    require("JWT_SECRET" in env and "SIMULATOR_ADMIN_TOKEN" in env, "Required local JWT and simulator environment configuration is missing")
    account, super_account = str(uuid.uuid4()), str(uuid.uuid4())
    owner = jwt(env, account, "PROPIETARIO")
    admin = jwt(env, super_account, "SUPERADMIN")
    stranger = jwt(env, str(uuid.uuid4()), "PROPIETARIO")
    label = str(uuid.uuid4())
    base = args.base_url
    installer = call(base, "/api/v1/installers", admin, {"firstName": "Local", "lastName": "Smoke installer", "identification": "smoke-" + label,
                                                        "phone": "+51999000000", "email": "smoke@example.test"}, expected=201)
    if not installer["enabled"]:
        installer = call(base, "/api/v1/installers/" + installer["id"] + "/status", admin,
                         {"enabled": True, "version": installer["version"]}, method="PATCH")
    property_value, point, request = install_request(base, owner, admin, installer["id"], label)
    call(base, "/api/v1/properties/" + property_value["id"], stranger, expected=403)
    call(base, "/api/v1/installations/" + request["id"] + "/status", admin, {"status": "COMPLETED"}, method="PATCH", expected=409)
    receipt = call(base, "/api/v1/devices", admin, {"installationId": request["id"], "serialNumber": "meter-" + label,
                                                   "location": "Local fixture", "installedAt": iso_now()}, expected=202)
    device_id = receipt["device"]["id"]
    initial_secret = receipt["oneTimeCredential"]
    device = wait_device(base, owner, point["id"], device_id, lambda d: d["status"] == "ACTIVE" and d["brokerConfirmed"] and d["telemetryConfirmed"])
    own_topic = "smartgn/devices/" + device_id + "/telemetry"
    publisher = Mqtt5(args.mqtt_host, args.mqtt_port, device_id, initial_secret)
    require(publisher.reason == 0, "Provisioned device credential was rejected")
    fixture = {"eventId": str(uuid.uuid4()), "deviceId": device_id, "associationVersion": device["associationVersion"],
               "measuredAt": iso_now(), "fixture": "management-local-smoke"}
    require(publisher.publish(own_topic, fixture) < 0x80, "Own-topic publication was rejected")
    try:
        denied = publisher.publish("smartgn/devices/" + str(uuid.uuid4()) + "/telemetry", fixture)
        require(denied >= 0x80, "Cross-device publication unexpectedly succeeded")
    except (EOFError, ConnectionResetError):
        denied = 0x87
    publisher.close()
    path = "/api/v1/installations/" + request["id"] + "/status"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        closures = list(executor.map(lambda _: call(base, path, admin, {"status": "COMPLETED"}, method="PATCH"), range(2)))
    require(closures[0]["completedAt"] == closures[1]["completedAt"], "Concurrent close changed the installation completion timestamp")
    repeated = call(base, path, admin, {"status": "COMPLETED"}, method="PATCH")
    require(repeated["completedAt"] == closures[0]["completedAt"], "Repeated close changed the completion timestamp")
    rotation = call(base, "/api/v1/devices/" + device_id + "/credentials/rotate", admin, {}, expected=202)
    new_secret = rotation["oneTimeCredential"]
    wait_device(base, owner, point["id"], device_id, lambda d: d["status"] == "ACTIVE" and d["brokerConfirmed"])
    old = Mqtt5(args.mqtt_host, args.mqtt_port, device_id, initial_secret)
    require(old.reason != 0, "Old credential still authenticates after confirmed rotation")
    old.close()
    live = Mqtt5(args.mqtt_host, args.mqtt_port, device_id, new_secret)
    require(live.reason == 0, "Rotated credential was rejected")
    require(live.publish(own_topic, fixture) < 0x80, "Rotated credential cannot publish its topic")
    began = time.monotonic()
    call(base, "/api/v1/devices/" + device_id + "/credentials/revoke", admin, {}, expected=202)
    live.socket.settimeout(0.25)
    disconnected = False
    while time.monotonic() - began < 30:
        try:
            header, body = live.read()
            if header == 0xE0:
                disconnected = True
                break
        except (EOFError, ConnectionResetError):
            disconnected = True
            break
        except socket.timeout:
            continue
    elapsed = time.monotonic() - began
    require(disconnected and elapsed <= 30, "Existing MQTT session was not disconnected within 30 seconds")
    live.close()
    wait_admin_device(base, admin, device_id, lambda d: d["status"] == "REVOKED" and d["brokerConfirmed"])
    _, second_point, second_request = install_request(base, owner, admin, installer["id"], str(uuid.uuid4()))
    call(base, "/api/v1/devices/" + device_id + "/associations", admin,
         {"installationId": second_request["id"], "resolveGap": False}, expected=409)
    call(args.simulator_url, "/fixtures/buffer", env["SIMULATOR_ADMIN_TOKEN"],
         {"deviceId": device_id, "version": 1, "pendingReadings": 0})
    reassociation = call(base, "/api/v1/devices/" + device_id + "/associations", admin,
                         {"installationId": second_request["id"], "resolveGap": False}, expected=202)
    moved = wait_device(base, owner, second_point["id"], device_id, lambda d: d["status"] == "ACTIVE" and d["associationVersion"] == 2)
    history = call(base, "/api/v1/devices/" + device_id + "/associations", admin)
    require(len(history) == 2 and any(v["version"] == 1 and v["validTo"] is not None for v in history), "Historical association was not preserved")
    configured = Mqtt5(args.mqtt_host, args.mqtt_port, device_id, reassociation["oneTimeCredential"])
    require(configured.reason == 0, "New association credential was rejected")
    fixture["associationVersion"] = moved["associationVersion"]
    require(configured.publish(own_topic, fixture) < 0x80, "Reassociated device cannot publish")
    configured.close()
    call(base, "/api/v1/installations/" + second_request["id"] + "/status", admin, {"status": "COMPLETED"}, method="PATCH")
    print(json.dumps({"status": "PASS", "mode": "LOCAL_MANAGEMENT_AND_REAL_EMQX_WITH_TELEMETRY_SIMULATOR",
                      "accountId": account, "deviceId": device_id, "installationIds": [request["id"], second_request["id"]],
                      "checks": ["resource isolation", "no premature completion", "durable activation", "MQTT own-topic QoS1", "MQTT cross-topic denied",
                                 "concurrent and repeated close", "credential rotation", "old credential denied", "existing session revocation",
                                 "explicit buffer confirmation", "versioned reassociation", "historical association retained"],
                      "revocationSeconds": round(elapsed, 3), "telemetryIngestionVerified": False}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except SmokeFailure as error:
        print(json.dumps({"status": "FAIL", "reason": str(error)}))
        sys.exit(1)
    except Exception as error:
        # Never print a raw exception or HTTP request: it could contain credentials.
        print(json.dumps({"status": "FAIL", "reason": "Unexpected local acceptance failure", "errorType": type(error).__name__}))
        sys.exit(1)
