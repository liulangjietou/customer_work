#!/usr/bin/env python3
"""从已完成的正式原始样本生成统计，不修改样本、不预设达标结论。"""
import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
args = parser.parse_args()
root = args.directory.resolve()
assert (root / "run.exit").read_text().strip() == "0", "Run did not complete successfully"
conditions = json.loads((root / "browser/conditions.json").read_text())
assert conditions["mode"] == "measure" and conditions["samples"] == 50 and conditions["warmups"] == 10
assert json.loads((root / "browser/errors.json").read_text()) == []


def distribution(values):
    """百分位用升序第 ceil(p*N) 条，保留样本数、最大值和均值。"""
    ordered = sorted(values)
    assert ordered and all(math.isfinite(value) and value >= 0 for value in ordered)
    return {"n": len(ordered), "p50": ordered[math.ceil(0.5 * len(ordered)) - 1],
            "p95": ordered[math.ceil(0.95 * len(ordered)) - 1], "max": ordered[-1],
            "mean": sum(ordered) / len(ordered)}


groups = defaultdict(list)
for row in json.loads((root / "browser/results.json").read_text()):
    assert row["iteration"] >= 0 and not row.get("error")
    groups[(row["scenario"], row["viewport"]["width"])].append(row)
expected = {(scenario, width) for scenario in ["queue-page", "queue-assignee-filter", "conversation-switch-200", "scroll-200"]
            for width in [1680, 390]}
assert set(groups) == expected, (set(groups), expected)
browser = []
long_tasks = {width: json.loads((root / f"browser/long-tasks-{width}.json").read_text()) for width in [1680, 390]}
for (scenario, width), rows in sorted(groups.items()):
    assert len(rows) == 50 and sorted(row["iteration"] for row in rows) == list(range(50))
    # 使用视口结束时的完整观察记录，包含延后送达及横跨操作开始时间的长任务。
    tasks = [task for task in long_tasks[width] if any(
        task["startTime"] < row["endTime"] and task["startTime"] + task["duration"] > row["startTime"]
        for row in rows)]
    entry = {"scenario": scenario, "viewportWidth": width, "elapsedMs": distribution([row["elapsedMs"] for row in rows]),
             "longTaskCount": len(tasks),
             "maxLongTaskMs": max([task["duration"] for task in tasks], default=0)}
    if scenario == "scroll-200":
        frames = [duration for row in rows for duration in row["frames"]]
        entry["frameMs"] = distribution(frames)
        entry["framesOver50Ms"] = sum(duration > 50 for duration in frames)
        entry["framesOver50Ratio"] = entry["framesOver50Ms"] / len(frames)
        assert all(row["messageCount"] == 200 and max(row["positions"]) > 0 for row in rows)
    else:
        entry["domMs"] = distribution([row["domMs"] for row in rows])
        entry["requestMs"] = distribution([request["duration"] for row in rows for request in row["requests"]])
    browser.append(entry)
query_groups = defaultdict(list)
with (root / "query/ticket-query-samples.csv").open() as stream:
    for row in csv.DictReader(stream):
        assert not row["error"], row
        query_groups[row["scenario"]].append(row)
assert set(query_groups) == {"first-page", "last-page", "status-filter", "assignee-filter", "empty-filter"}
queries = []
for scenario, rows in sorted(query_groups.items()):
    assert len(rows) == 50 and sorted(int(row["iteration"]) for row in rows) == list(range(50))
    queries.append({"scenario": scenario, "elapsedMs": distribution([float(row["elapsedMs"]) for row in rows])})
result = {"mode": "MEASURED_NOT_BUDGET_REVIEWED", "browser": browser, "queries": queries,
          "failedSamples": 0, "budgetPassed": None}
(root / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps(result, ensure_ascii=False, indent=2))
