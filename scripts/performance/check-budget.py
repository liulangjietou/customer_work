#!/usr/bin/env python3
"""按已冻结的本机验收预算检查正式样本；任何超限都写出失败并返回非零。"""
import argparse
import hashlib
import json
import math
from pathlib import Path


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
args = parser.parse_args()
root = args.directory.resolve()
budget_file = Path(__file__).with_name("budgets.json")
budget = json.loads(budget_file.read_text())
assert (root / "run.exit").read_text().strip() == "0"
assert json.loads((root / "browser/errors.json").read_text()) == []
conditions = json.loads((root / "browser/conditions.json").read_text())
assert conditions["mode"] == "measure"
for key, value in {**budget["dataset"], "samples": budget["samples"], "warmups": budget["warmups"]}.items():
    assert conditions[key] == value, f"Different measurement condition: {key}"
summary_file = root / "summary.json"
summary = json.loads(summary_file.read_text())
assert summary["failedSamples"] == 0
expected = {(scenario, width) for scenario in ["queue-page", "queue-assignee-filter", "conversation-switch-200", "scroll-200"]
            for width in budget["viewportWidths"]}
assert {(row["scenario"], row["viewportWidth"]) for row in summary["browser"]} == expected
assert len(summary["browser"]) == len(expected)
assert {row["scenario"] for row in summary["queries"]} == {
    "first-page", "last-page", "status-filter", "assignee-filter", "empty-filter"}
assert len(summary["queries"]) == 5
checks = []


def check(name, actual, limit):
    """记录实际值和冻结限值；不删去超限样本，也不修改预算。"""
    checks.append({"name": name, "actual": actual, "limit": limit,
                   "passed": math.isfinite(actual) and 0 <= actual <= limit})


for row in summary["browser"]:
    assert row["elapsedMs"]["n"] == budget["samples"]
    name = f'{row["scenario"]}:{row["viewportWidth"]}'
    check(name + ":max-long-task-ms", row["maxLongTaskMs"], budget["maxLongTaskMs"])
    if row["scenario"] == "scroll-200":
        # 一秒滚动时长是采样窗口，帧间隔和长帧比例才是滚动预算。
        check(name + ":frame-p95-ms", row["frameMs"]["p95"], budget["scrollFrameP95Ms"])
        check(name + ":frames-over-50-ratio", row["framesOver50Ratio"], budget["scrollFramesOver50Ratio"])
    else:
        check(name + ":p95-ms", row["elapsedMs"]["p95"], budget["p95Ms"][row["scenario"]])
for row in summary["queries"]:
    assert row["elapsedMs"]["n"] == budget["samples"]
    check("sql:" + row["scenario"] + ":p95-ms", row["elapsedMs"]["p95"], budget["p95Ms"]["sql-query"])
result = {"passed": all(item["passed"] for item in checks),
          "budgetSha256": hashlib.sha256(budget_file.read_bytes()).hexdigest(),
          "summarySha256": hashlib.sha256(summary_file.read_bytes()).hexdigest(),
          "referenceRun": budget["referenceRun"], "checks": checks}
(root / "budget-verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps(result, ensure_ascii=False, indent=2))
raise SystemExit(0 if result["passed"] else 1)
