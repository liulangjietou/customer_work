#!/usr/bin/env python3
"""编译并运行独立性能环境；smoke 仅检查测量器，不产出正式达标结论。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import time
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classpath-file", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--mode", choices=["smoke", "measure"], default="smoke")
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
classpath = args.classpath_file.read_text().strip()
assert classpath and all(Path(entry).exists() for entry in classpath.split(os.pathsep))
java_home = os.environ.get("JAVA_HOME")
if not java_home:
    java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()
java_version = subprocess.check_output([java_home + "/bin/java", "-version"], stderr=subprocess.STDOUT, text=True)
version_match = re.search(r'version "(?:1\.)?(\d+)', java_version)
if not version_match or int(version_match.group(1)) < 17:
    raise RuntimeError("JDK 17 or newer required; set JAVA_HOME before running the performance harness")
environment = os.environ.copy()
environment["JAVA_HOME"] = java_home
environment["TMPDIR"] = str(output)
environment["JAVA_TOOL_OPTIONS"] = "-Djava.io.tmpdir=" + str(output)


def run_logged(name, command):
    """保留命令退出码和完整日志，不把中断转换成通过。"""
    with (output / (name + ".log")).open("w") as log:
        result = subprocess.run(command, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
    (output / (name + ".exit")).write_text(str(result.returncode))
    if result.returncode:
        raise RuntimeError(f"{name} failed with exit={result.returncode}; inspect retained log")


def verify_cleanup(directory):
    """清理标记只由 DROP 自有库成功后的代码生成，必须与本次创建的库完全相同。"""
    owned = (directory / "owned-database.txt").read_text().strip()
    cleaned = (directory / "owned-database-cleaned.txt").read_text().strip()
    assert owned == cleaned and owned.startswith("cw_perf_")


def fingerprint(directory):
    """按相对路径绑定文件内容，既识别修改，也识别新增和删除。"""
    return {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(directory.rglob("*")) if path.is_file()}


backend_artifacts = {}
for module in ["customer-work-starter", "customer-work-app-server", "customer-admin-server"]:
    candidates = {Path(entry).resolve() for entry in classpath.split(os.pathsep)
                  if entry.endswith(f"/{module}/target/classes")}
    assert len(candidates) == 1, f"Classpath must contain one current compiled module: {module}"
    compiled = candidates.pop()
    compiled_source = compiled.parents[1] / "src/main"
    sources = fingerprint(ROOT / module / "src/main")
    assert sources and sources == fingerprint(compiled_source), f"Compiled checkout source differs: {module}"
    artifacts = fingerprint(compiled)
    assert artifacts, f"Compiled module is empty: {module}"
    backend_artifacts[module] = {
        "compiledDirectory": str(compiled),
        "sourceFingerprint": hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest(),
        "compiledFingerprint": hashlib.sha256(json.dumps(artifacts, sort_keys=True).encode()).hexdigest(),
    }


source_files = list((ROOT / "scripts/performance").glob("*"))
source_files += [ROOT / "customer-admin-web/src/views/ticket/UserTicketManage.vue"]
source_hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                 for path in source_files if path.is_file()}
conditions = {
    "mode": args.mode,
    "startedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    "baseCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
    "branch": subprocess.check_output(["git", "branch", "--show-current"], cwd=ROOT, text=True).strip(),
    "sourceHashes": source_hashes,
    "platform": platform.platform(),
    "javaVersion": java_version,
    "nodeVersion": subprocess.check_output(["node", "--version"], text=True).strip(),
    "classpathSha256": hashlib.sha256(classpath.encode()).hexdigest(),
    "backendArtifacts": backend_artifacts,
    "loadAverageAtStart": os.getloadavg(),
    "productionDistHashes": {str(path.relative_to(ROOT / "customer-admin-web/dist")): hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in (ROOT / "customer-admin-web/dist").rglob("*") if path.is_file()},
}
assert conditions["productionDistHashes"], "Build the production frontend before measuring"
if platform.system() == "Darwin":
    conditions["hardware"] = subprocess.check_output(["sysctl", "hw.model", "hw.memsize", "hw.physicalcpu", "hw.logicalcpu"], text=True)
    conditions["os"] = subprocess.check_output(["sw_vers"], text=True)
(output / "run-conditions.json").write_text(json.dumps(conditions, ensure_ascii=False, indent=2))
classes = output / "classes"
classes.mkdir()
run_logged("compile", [java_home + "/bin/javac", "-parameters", "-cp", classpath, "-d", str(classes),
                       *map(str, sorted((ROOT / "scripts/performance").glob("*.java")))])
runtime_classpath = str(classes) + os.pathsep + classpath

server_directory = output / "server"
log = (output / "server.log").open("w")
process = subprocess.Popen([java_home + "/bin/java", "-cp", runtime_classpath,
                            "CustomerServicePerformanceServer", str(server_directory)],
                           cwd=ROOT, env=environment, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
try:
    deadline = time.monotonic() + 120
    settings_file = server_directory / "server.properties"
    while not (server_directory / "server.ready").exists():
        if process.poll() is not None:
            raise RuntimeError("Performance server stopped before readiness; inspect server.log")
        if time.monotonic() >= deadline:
            raise TimeoutError("Performance server readiness timed out")
        time.sleep(0.2)
    settings = dict(line.split("=", 1) for line in settings_file.read_text().splitlines()
                    if line and not line.startswith("#"))
    base = settings["adminBaseUrl"].replace("\\:", ":")
    header = {settings["requestHeader"]: settings["requestToken"], "Accept": "application/json"}
    checks = []
    for path, method, headers, expected in [
        ("/api/ticket/page?pageNum=1&pageSize=20", "GET", {}, 401),
        ("/api/ticket/performance-owned-9998/claim", "POST", header, 405),
        ("/api/ticket/performance-other-1", "GET", header, 404),
        ("/api/ticket/page?pageNum=1&pageSize=20", "GET", header, 200),
    ]:
        request = urllib.request.Request(base + path, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read()
        checks.append({"path": path, "method": method, "status": status, "expected": expected})
        assert status == expected, (checks[-1], body.decode(errors="replace"))
        if status == 200:
            payload = json.loads(body)
            assert payload["code"] == 0 and payload["data"]["total"] == 10000
            assert len(payload["data"]["items"]) == 20
    (output / "server-checks.json").write_text(json.dumps(checks, indent=2))
    run_logged("browser", ["node", str(ROOT / "scripts/performance/browser-baseline.mjs"),
                           str(server_directory), str(output / "browser"), args.mode])
finally:
    if process.poll() is None:
        try:
            process.stdin.write(b"\n")
            process.stdin.flush()
            process.wait(timeout=40)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            process.terminate()
            try:
                process.wait(timeout=35)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    log.close()
    (output / "server.exit").write_text(str(process.returncode))
    # 无论浏览器是否成功，清理失败都会明确报错并保留所属库名。
    if (server_directory / "owned-database.txt").exists():
        verify_cleanup(server_directory)
assert process.returncode == 0
if args.mode == "measure":
    query_directory = output / "query"
    run_logged("query", [java_home + "/bin/java", "-cp", runtime_classpath,
                         "CustomerTicketQueryBaseline", str(query_directory)])
    verify_cleanup(query_directory)
assert all(hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == sha for path, sha in source_hashes.items())
(output / "run.exit").write_text("0")
print(f"PERFORMANCE_{args.mode.upper()}_COMPLETE {output}")
