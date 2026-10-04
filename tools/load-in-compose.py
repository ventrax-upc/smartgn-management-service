#!/usr/bin/env python3
"""Run the local load generator on Compose, independently of Windows port forwarding.

Reuse the same 500-account fixture and stream results to the host. The existing
Telemetry Python runtime is used; no packages are installed. Minimal local JWT
configuration travels over stdin, never command arguments or result files.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

spec = importlib.util.spec_from_file_location("load_meter", Path(__file__).with_name("load-multi-meter.py"))
load = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)

PROGRAM = """import json,os,runpy,sys
from pathlib import Path
configuration=json.loads(sys.stdin.read())
os.environ.update(configuration['environment'])
Path('/tmp/smartgn-load-fixture.json').write_text(json.dumps(configuration['fixture']),encoding='utf-8')
sys.argv=['load-multi-meter.py','--compose-network','--base','http://management:8081','--telemetry','http://telemetry:8090',
          '--fixture-file','/tmp/smartgn-load-fixture.json','--output','/tmp/smartgn-load-result.json',
          '--duration',str(configuration['duration']),'--rps',str(configuration['rps'])]
runpy.run_path('/tools/load-multi-meter.py',run_name='__main__')
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--duration", type=int, default=900)
    parser.add_argument("--rps", type=int, default=100)
    parser.add_argument("--output", default=".local/load-result.json")
    parser.add_argument("--fixture-file", default=".local/load-fixture.json")
    args = parser.parse_args()
    if args.duration < 1 or args.duration > 3600 or args.rps < 1:
        parser.error("Invalid workload dimensions")
    environment = load.smoke.load_environment(Path(".env"))
    fixture_path = Path(args.fixture_file)
    if fixture_path.exists():
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    else:
        setup = argparse.Namespace(fixture_file=args.fixture_file, users=500, meters=100, telemetry="http://127.0.0.1:8090")
        fixture = load.setup_fixture(environment, setup)
    configuration = {"environment": {key: environment[key] for key in ("JWT_SECRET", "JWT_ISSUER", "JWT_AUDIENCE") if key in environment},
                     "fixture": fixture, "duration": args.duration, "rps": args.rps}
    process = subprocess.Popen(["docker", "exec", "-i", "smartgn-telemetry-simulator", "python", "-u", "-c", PROGRAM],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8")
    process.stdin.write(json.dumps(configuration))
    process.stdin.close()
    result_lines = []
    recording = False
    for line in process.stdout:
        if line.strip() == "{":
            recording = True
        if recording:
            result_lines.append(line)
        else:
            print(line.rstrip(), flush=True)
    code = process.wait()
    if not result_lines:
        raise RuntimeError("Compose generator did not return a measurement")
    result = json.loads("".join(result_lines))
    result["loadGenerator"] = "LOCAL_COMPOSE_NETWORK"
    result["windowsPublishedPortMeasured"] = False
    destination = Path(args.output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2), flush=True)
    return code


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({"status": "FAIL", "phase": "COMPOSE_GENERATOR", "errorType": type(error).__name__}), flush=True)
        raise SystemExit(1)
