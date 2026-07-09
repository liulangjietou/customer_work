"""
sync_claude_runtime.py - 将 claude-runtime 同步到项目兼容层和插件目录。
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent

SYNC_DIRS = ("agents", "skills", "styles", "workflows", "scripts")
PLUGIN_EXTRA_DIRS = ("templates",)


def _copy_tree(src: Path, dst: Path) -> None:
    if not src.exists():
        return

    for source_path in src.rglob("*"):
        if "__pycache__" in source_path.parts or source_path.suffix == ".pyc":
            continue
        relative = source_path.relative_to(src)
        target_path = dst / relative
        if source_path.is_dir():
            target_path.mkdir(parents=True, exist_ok=True)
            continue
        target_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_path, target_path)


def _load_package_version(project_root: Path) -> str:
    package_json = project_root / "package.json"
    if not package_json.exists():
        return "0.8.0"
    return json.loads(package_json.read_text(encoding="utf-8")).get("version", "0.8.0")


def _write_project_settings(project_root: Path, runtime_root: Path) -> None:
    hooks_source = runtime_root / "hooks" / "hooks.json"
    if not hooks_source.exists():
        return

    settings_path = project_root / ".claude" / "settings.json"
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    settings_path.write_text(hooks_source.read_text(encoding="utf-8"), encoding="utf-8")


def _write_plugin_hooks(plugin_root: Path) -> None:
    hooks = {
        "hooks": {
            "SessionStart": [
                {
                    "hooks": [
                        {
                            "type": "command",
                            "command": "python \"${CLAUDE_PLUGIN_ROOT}/scripts/bootstrap_workspace.py\"",
                        }
                    ]
                }
            ],
            "SubagentStop": [
                {
                    "matcher": "humanizer|article-illustrator",
                    "hooks": [
                        {
                            "type": "command",
                            "command": "python \"${CLAUDE_PLUGIN_ROOT}/scripts/auto_clean_hook.py\"",
                        }
                    ],
                }
            ],
        }
    }
    target = plugin_root / "hooks" / "hooks.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(hooks, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _write_plugin_manifest(project_root: Path, plugin_root: Path) -> None:
    manifest = {
        "name": "writing-agent",
        "version": _load_package_version(project_root),
        "description": "可中断、可复盘的多阶段中文文章写作工作流",
        "author": {
            "name": "dongbeixiaohuo",
            "url": "https://github.com/dongbeixiaohuo",
        },
        "homepage": "https://github.com/dongbeixiaohuo/writing-agent",
        "repository": "https://github.com/dongbeixiaohuo/writing-agent",
        "license": "MIT",
        "keywords": ["claude-code", "writing", "workflow", "chinese"],
        "skills": "./skills/",
        "agents": "./agents/",
        "hooks": "./hooks/hooks.json",
        "interface": {
            "displayName": "Writing Agent",
            "shortDescription": "把中文文章写作拆成可调度、可复盘的多阶段流程",
            "longDescription": "适用于中文长文、公众号文章、观点文的多阶段写作工作流，支持 clone 与 plugin 两种交付路径。",
            "developerName": "dongbeixiaohuo",
            "category": "Productivity",
            "capabilities": ["Interactive", "Write"],
            "websiteURL": "https://github.com/dongbeixiaohuo/writing-agent",
            "defaultPrompt": [
                "帮我按写稿 Agent 工作流写一篇文章",
                "先从选题开始，给我做协作模式写作",
                "解释这个工作区里每个写作阶段文件的作用",
            ],
            "brandColor": "#1f6feb",
        },
    }
    plugin_manifest = plugin_root / ".claude-plugin" / "plugin.json"
    plugin_manifest.parent.mkdir(parents=True, exist_ok=True)
    plugin_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _write_marketplace(project_root: Path) -> None:
    marketplace = {
        "name": "writing-agent-marketplace",
        "interface": {
            "displayName": "Writing Agent Marketplace",
        },
        "plugins": [
            {
                "name": "writing-agent",
                "source": {
                    "source": "local",
                    "path": "./plugins/writing-agent",
                },
                "policy": {
                    "installation": "AVAILABLE",
                    "authentication": "ON_INSTALL",
                },
                "category": "Productivity",
            }
        ],
    }
    marketplace_path = project_root / ".claude-plugin" / "marketplace.json"
    marketplace_path.parent.mkdir(parents=True, exist_ok=True)
    marketplace_path.write_text(json.dumps(marketplace, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sync_runtime_assets(project_root: Path = PROJECT_ROOT) -> list[str]:
    runtime_root = project_root / "claude-runtime"
    if not runtime_root.exists():
        raise FileNotFoundError(f"缺少运行时事实源目录: {runtime_root}")

    synced_targets: list[str] = []

    for directory in SYNC_DIRS:
        source_dir = runtime_root / directory
        if not source_dir.exists():
            continue

        if directory != "scripts":
            project_target = project_root / ".claude" / directory
            _copy_tree(source_dir, project_target)
            synced_targets.append(str(project_target))
        else:
            project_target = project_root / "scripts"
            _copy_tree(source_dir, project_target)
            synced_targets.append(str(project_target))

        plugin_target = project_root / "plugins" / "writing-agent" / directory
        _copy_tree(source_dir, plugin_target)
        synced_targets.append(str(plugin_target))

    for directory in PLUGIN_EXTRA_DIRS:
        source_dir = runtime_root / directory
        if not source_dir.exists():
            continue
        plugin_target = project_root / "plugins" / "writing-agent" / directory
        _copy_tree(source_dir, plugin_target)
        synced_targets.append(str(plugin_target))

    _write_project_settings(project_root, runtime_root)
    _write_plugin_hooks(project_root / "plugins" / "writing-agent")
    _write_plugin_manifest(project_root, project_root / "plugins" / "writing-agent")
    _write_marketplace(project_root)

    return synced_targets


def main() -> int:
    synced_targets = sync_runtime_assets(PROJECT_ROOT)
    for target in synced_targets:
        print(f"SYNCED {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
