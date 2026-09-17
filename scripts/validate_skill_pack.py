#!/usr/bin/env python3
"""Validate that every V2 production skill contains usable, parseable artifacts."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = json.loads((ROOT / "skills" / "catalog.json").read_text(encoding="utf-8"))
REQUIRED = ("SKILL.md", "prompt.md", "input-schema.json", "output-schema.json", "examples/input.json", "examples/output.json", "tests/cases.json")


def main() -> None:
    skills = CATALOG.get("skills", [])
    if len(skills) != 27 or set(CATALOG.get("agents", [])) != {"StoryAgent", "DirectorAgent", "ContinuityQcAgent", "EditingAgent"}:
        raise SystemExit("catalog must contain four agents and 27 skills")
    dynamic = {"06-shot-planning", "09-character-design", "10-location-design", "11-prop-design"}
    for item in skills:
        folder = ROOT / "skills" / item["id"]
        if item["id"] in dynamic:
            for name in ("SKILL.md", "prompt.md"):
                if not (folder / name).is_file():
                    raise SystemExit(f"{item['id']} missing {name}")
            sources = (("src/main/java/com/yourapp/drama/production/DirectorContract.java", "src/test/java/com/yourapp/drama/production/DirectorContractTest.java") if item["id"] == "06-shot-planning" else ("src/main/java/com/yourapp/drama/workflow/AssetViewService.java",))
            for source in sources:
                if not (ROOT / source).is_file():
                    raise SystemExit(f"{item['id']} dynamic contract missing {source}")
            continue
        missing = [name for name in REQUIRED if not (folder / name).is_file()]
        if missing:
            raise SystemExit(f"{item['id']} missing {missing}")
        for name in REQUIRED[2:]:
            json.loads((folder / name).read_text(encoding="utf-8"))
        input_schema = json.loads((folder / "input-schema.json").read_text(encoding="utf-8"))
        output_schema = json.loads((folder / "output-schema.json").read_text(encoding="utf-8"))
        if input_schema.get("additionalProperties") is not False or output_schema.get("additionalProperties") is not False:
            raise SystemExit(f"{item['id']} schemas must reject unknown top-level fields")
    print("skill-pack: 4 agents, 23 static skills valid; dynamic director and asset-view contracts present (run Java tests to validate behavior)")


if __name__ == "__main__":
    main()
