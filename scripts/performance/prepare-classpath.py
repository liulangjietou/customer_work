#!/usr/bin/env python3
"""从实际后端测试报告提取运行依赖，避免手工拼接或误用本地旧 SNAPSHOT。"""
import argparse
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--backend-tree", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
backend = args.backend_tree.resolve()
modules = ["customer-work-starter", "customer-work-app-server", "customer-admin-server"]
entries = [backend / module / "target/classes" for module in modules]
reports = []
for module in ["customer-work-app-server", "customer-admin-server"]:
    candidates = sorted((backend / module / "target/surefire-reports").glob("TEST-*.xml"))
    assert candidates, f"Run the backend test gate first: {module}"
    source = candidates[0]
    suite = ET.parse(source).getroot()
    properties = {item.get("name"): item.get("value") for item in suite.findall("properties/property")}
    classpath = properties.get("java.class.path")
    assert classpath, f"No actual runtime classpath in {source}"
    reports.append(str(source))
    for entry in classpath.split(os.pathsep):
        path = Path(entry).resolve()
        if path.name == "test-classes" and path.parent.name == "target":
            continue
        if path.name == "classes" and path.parent.name == "target":
            assert path in entries, f"Unexpected compiled checkout in report: {path}"
            continue
        if path.suffix == ".jar" and any(path.name.startswith(name + "-") for name in modules):
            continue
        if path not in entries:
            entries.append(path)
assert all(entry.exists() for entry in entries), "Runtime dependency missing; rebuild before measuring"
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open("x") as output:
    output.write(os.pathsep.join(map(str, entries)))
print(json.dumps({"classpath": str(args.output.resolve()), "entries": len(entries), "reports": reports}, indent=2))
