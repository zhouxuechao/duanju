#!/usr/bin/env python3
"""Validate the human-maintained skill manifest without rewriting source assets."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "skills" / "manifest.json"
REQUIRED = {
    "skillId", "version", "phase", "runtime", "promptPath", "schemaPath",
    "rulePackDependencies", "source", "fingerprint",
}


def inside_repo(relative: str) -> Path:
    path = (ROOT / relative).resolve()
    if ROOT.resolve() not in path.parents:
        raise ValueError(f"path escapes repository: {relative}")
    return path


def main() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    skills = manifest.get("skills", [])
    if not skills:
        raise SystemExit("skill manifest must not be empty")
    agents = manifest.get("agents", [])
    if not agents or len(agents) != len(set(agents)):
        raise SystemExit("manifest agents must be non-empty and unique")

    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    seen_ids: set[str] = set()
    seen_fingerprints: set[str] = set()
    runtime_count = 0
    for skill in skills:
        missing = REQUIRED - skill.keys()
        if missing:
            raise SystemExit(f"skill missing fields {sorted(missing)}: {skill.get('skillId', '<unknown>')}")
        skill_id = str(skill["skillId"]).strip()
        if not skill_id or skill_id in seen_ids:
            raise SystemExit(f"duplicate skillId: {skill_id}")
        seen_ids.add(skill_id)
        fingerprint = str(skill["fingerprint"]).strip()
        if not fingerprint or fingerprint in seen_fingerprints:
            raise SystemExit(f"duplicate fingerprint: {fingerprint}")
        seen_fingerprints.add(fingerprint)
        if skill.get("agent") and skill["agent"] not in agents:
            raise SystemExit(f"{skill_id} references unknown agent: {skill['agent']}")
        if not isinstance(skill["runtime"], bool) or not isinstance(skill["rulePackDependencies"], list):
            raise SystemExit(f"{skill_id} runtime/rulePackDependencies have invalid types")

        prompt_path = str(skill["promptPath"])
        prompt = inside_repo(prompt_path)
        if not prompt.is_file():
            raise SystemExit(f"{skill_id} promptPath does not exist: {prompt_path}")
        actual_fingerprint = hashlib.sha256(prompt.read_bytes()).hexdigest()
        if actual_fingerprint != fingerprint:
            raise SystemExit(f"{skill_id} fingerprint does not match promptPath")

        schema_path = str(skill["schemaPath"])
        if schema_path:
            schema = inside_repo(schema_path)
            if not schema.is_file():
                raise SystemExit(f"{skill_id} schemaPath does not exist: {schema_path}")
            json.loads(schema.read_text(encoding="utf-8"))
        for dependency in skill["rulePackDependencies"]:
            if not inside_repo(str(dependency)).exists():
                raise SystemExit(f"{skill_id} rule pack does not exist: {dependency}")

        if skill["runtime"]:
            runtime_count += 1
            relative_prompt = prompt.relative_to(ROOT / "skills").as_posix()
            if f"<include>{relative_prompt}</include>" not in pom:
                raise SystemExit(f"{skill_id} runtime prompt is not packaged by pom.xml")

    print(f"validated {len(skills)} skills from manifest; runtime={runtime_count}")


if __name__ == "__main__":
    main()
