#!/usr/bin/env python3
"""Validate the human-maintained skill manifest and rebuild derived catalogs.

Production prompts and schemas are source assets. This script deliberately has
no code path that writes them.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "skills" / "manifest.json"
REQUIRED = {
    "skillId", "version", "phase", "runtime", "promptPath", "schemaPath",
    "rulePackDependencies", "source", "fingerprint",
}


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def inside_repo(relative: str) -> Path:
    path = (ROOT / relative).resolve()
    if ROOT.resolve() not in path.parents:
        raise ValueError(f"路径越出仓库：{relative}")
    return path


def validate_skill(skill: dict, seen: set[str], refresh: bool) -> dict:
    missing = REQUIRED - skill.keys()
    if missing:
        raise ValueError(f"Skill 缺少字段 {sorted(missing)}：{skill.get('skillId', '<unknown>')}")
    skill_id = str(skill["skillId"]).strip()
    if not skill_id or skill_id in seen:
        raise ValueError(f"Skill ID 为空或重复：{skill_id}")
    seen.add(skill_id)
    if not isinstance(skill["runtime"], bool) or not isinstance(skill["rulePackDependencies"], list):
        raise ValueError(f"Skill runtime/rulePackDependencies 类型无效：{skill_id}")
    prompt = inside_repo(skill["promptPath"])
    if not prompt.is_file():
        raise ValueError(f"Skill prompt 不存在：{skill['promptPath']}")
    schema_path = str(skill["schemaPath"])
    if schema_path and not inside_repo(schema_path).is_file():
        raise ValueError(f"Skill schema 不存在：{schema_path}")
    actual = hashlib.sha256(prompt.read_bytes()).hexdigest()
    if refresh:
        skill["fingerprint"] = actual
    elif skill["fingerprint"] != actual:
        raise ValueError(f"Skill prompt 已修改但 manifest fingerprint 未更新：{skill_id}")
    return skill


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh-fingerprints", action="store_true")
    args = parser.parse_args()
    manifest = read_json(MANIFEST)
    seen: set[str] = set()
    skills = [validate_skill(dict(skill), seen, args.refresh_fingerprints) for skill in manifest.get("skills", [])]
    if not skills:
        raise ValueError("Skill manifest 不能为空")
    normalized = {"version": manifest.get("version", "2.0.0"), "agents": manifest.get("agents", []), "skills": skills}
    if args.refresh_fingerprints:
        write_json(MANIFEST, normalized)
    write_json(ROOT / "skills" / "catalog.json", normalized)
    write_json(ROOT / "src" / "main" / "resources" / "production-skills" / "catalog.json", normalized)
    runtime = [skill["skillId"] for skill in skills if skill["runtime"]]
    print(f"validated {len(skills)} skills; runtime={len(runtime)}")


if __name__ == "__main__":
    main()
