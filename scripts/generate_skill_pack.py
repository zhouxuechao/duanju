#!/usr/bin/env python3
"""Generate the versioned production skill pack described by the V2 checklist."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKILLS = [
    ("01-story-planning", "StoryAgent", "把创意整理为可版本化的故事圣经", ["idea"], ["storyBible"], ["人物目标、冲突和结局方向必须明确"]),
    ("02-character-planning", "StoryAgent", "建立角色身份、关系和长期状态", ["storyBible"], ["characters"], ["角色身份锚点跨集保持稳定"]),
    ("03-episode-planning", "StoryAgent", "把故事拆为有钩子和承接的集纲", ["storyBible", "characters"], ["episodes"], ["每集结尾必须给下一集可执行的承接状态"]),
    ("04-script-writing", "StoryAgent", "生成场景化剧本和三轨对白底稿", ["episodePlan"], ["script", "dialogues"], ["对白保留 displayText、dialectText、speechText 三轨"]),
    ("05-scene-extraction", "DirectorAgent", "从剧本提取场景和初始连续性状态", ["script"], ["scenes"], ["同一场景只绑定一个主地点状态"]),
    ("06-shot-planning", "DirectorAgent", "把场景拆成原子镜头", ["scene", "sceneState"], ["shots"], ["镜头 2 至 5 秒且只有一个主要动作和视觉重点"]),
    ("07-shot-difficulty", "DirectorAgent", "评估镜头生成难度并给出拆分建议", ["shot"], ["difficulty", "reasons"], ["难度只使用 A、B、C、D"]),
    ("08-continuity-planning", "ContinuityQcAgent", "继承并验证镜头起止状态", ["shot", "sceneState"], ["startState", "endState", "risks"], ["只有已质检并锁定的 Take 才能推进 Scene State"]),
    ("09-character-design", "StoryAgent", "设计人物身份锚点和基础定妆", ["character"], ["characterBible", "baseLook"], ["身份源必须是已授权服务商人物素材"]),
    ("10-location-design", "StoryAgent", "设计地点圣经和可复用视觉锚点", ["location"], ["locationBible"], ["同一场景只引用当前地点或地点定妆"]),
    ("11-prop-design", "StoryAgent", "设计关键道具及其状态变化", ["prop"], ["propBible", "propState"], ["道具归属、位置和损坏状态必须可追踪"]),
    ("12-storyboard-generation", "DirectorAgent", "生成低成本分镜候选", ["shot", "continuity"], ["storyboard"], ["分镜不能代替正式关键帧身份源"]),
    ("13-image-prompt-compile", "DirectorAgent", "把结构化镜头编译为图片模型提示词", ["shot", "anchors"], ["prompt", "references"], ["只带当前可见人物及最多一个定妆或地点参考"]),
    ("14-keyframe-generation", "DirectorAgent", "提交并记录正式关键帧", ["prompt", "references"], ["keyframe"], ["同时保存 providerUrl 与 archiveUrl，二者用途不可互换"]),
    ("15-keyframe-qc", "ContinuityQcAgent", "检查关键帧身份、服装、场景、道具和构图", ["keyframe", "expectedState"], ["passed", "score", "risks", "repairPlan"], ["质检通过后才能锁定"]),
    ("16-video-strategy", "DirectorAgent", "选择首帧、参考视频或多模态生成策略", ["shot", "keyframe"], ["strategy", "reasons"], ["正式关键帧只能作为视频首帧，不作为普通参考图重复传入"]),
    ("17-video-prompt-compile", "DirectorAgent", "把原子动作编译为视频模型提示词", ["shot", "strategy", "continuity"], ["prompt", "references"], ["禁止把小说段落直接送入视频模型"]),
    ("18-video-generation", "DirectorAgent", "提交 Seedance 任务并跟踪多 Take", ["keyframe", "prompt"], ["videoTake"], ["Seedream 原始 providerUrl 必须原样直传 Seedance"]),
    ("19-video-qc", "ContinuityQcAgent", "检查动作、身份和起止状态", ["videoTake", "expectedState"], ["passed", "score", "observedState", "risks"], ["通过并锁定后才可进入时间线"]),
    ("20-video-repair", "ContinuityQcAgent", "把失败诊断转为局部重拍方案", ["videoTake", "risks"], ["repairPlan", "revisedShot"], ["只重做失败镜头并保留旧版本"]),
    ("21-dialect-render", "EditingAgent", "基于知识库生成人工可修正的方言文本", ["displayText", "knowledgeBase"], ["dialectText", "speechText", "confidence"], ["知识库无可靠匹配时必须请求人工修正，不得臆造方言"]),
    ("22-voice-generation", "EditingAgent", "用 speechText 和声音档案生成对白音频", ["speechText", "voiceProfile"], ["audioClip"], ["字幕始终使用 displayText"]),
    ("23-lipsync", "EditingAgent", "使用已采用视频和音频生成口型同步 Take", ["videoTake", "audioClip"], ["videoTake"], ["不得改变人物身份、服装、场景、动作或台词内容"]),
    ("24-subtitle-generation", "EditingAgent", "按音频时长生成普通话字幕", ["dialogues"], ["srt", "cues"], ["字幕文本只读取 displayText"]),
    ("25-sound-design", "EditingAgent", "规划环境声、音效和背景音乐", ["timeline", "scenes"], ["soundDesign"], ["对白清晰度优先且音效不得替代叙事信息"]),
    ("26-timeline-planning", "EditingAgent", "组装连续视频、对白、音效和字幕轨", ["episode", "lockedAssets"], ["timeline", "items"], ["只允许已质检、已锁定、已归档素材进入时间线"]),
    ("27-video-render", "EditingAgent", "生成可审阅预览和最终成片", ["timeline"], ["render", "subtitle"], ["FFmpeg 只读取安全的本地归档路径"]),
]


def schema(title: str, required_payload: list[str], output: bool = False) -> dict:
    payload_properties = {name: {} for name in required_payload}
    base = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": f"https://local.shiguang.invalid/schemas/{title}.json",
        "title": title,
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "projectId": {"type": "string", "minLength": 1},
            "projectVersion": {"type": "integer", "minimum": 1},
            "payload": {
                "type": "object",
                "properties": payload_properties,
                "required": required_payload,
                "additionalProperties": True,
            },
            "trace": {"type": "object", "additionalProperties": True},
        },
        "required": ["projectId", "projectVersion", "payload"],
    }
    if output:
        base["properties"].update({
            "status": {"enum": ["READY", "HUMAN_REVIEW_REQUIRED", "FAILED"]},
            "warnings": {"type": "array", "items": {"type": "string"}},
        })
        base["required"].extend(["status", "warnings"])
    return base


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    catalog = []
    for slug, agent, goal, inputs, outputs, hard_rules in SKILLS:
        if slug in {"06-shot-planning", "09-character-design", "10-location-design", "11-prop-design"}:
            if slug == "06-shot-planning":
                inputs = ["scene", "episodeScript", "continuitySnapshot", "assets"]
                version, contract = "2.0.0", "DirectorContract"
            else:
                inputs = ["asset", "sourceSnapshot", "referenceViewIds", "view"]
                version, contract = "2.0.0", "AssetViewService"
            catalog.append({"id": slug, "agent": agent, "version": version, "goal": goal, "inputs": inputs, "outputs": outputs, "hardRules": hard_rules, "contract": contract})
            continue
        folder = ROOT / "skills" / slug
        folder.mkdir(parents=True, exist_ok=True)
        skill_doc = [
            f"# {slug}", "", f"- 负责 Agent：`{agent}`", "- 版本：`1.0.0`", f"- 目标：{goal}", "",
            "## 输入", "", *[f"- `{name}`" for name in inputs], "", "## 输出", "", *[f"- `{name}`" for name in outputs], "",
            "## 硬规则", "", *[f"- {rule}" for rule in hard_rules],
            "- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。", "",
            "## 失败处理", "", "返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。", "",
        ]
        (folder / "SKILL.md").write_text("\n".join(skill_doc), encoding="utf-8")
        prompt = (
            f"你是 {agent} 的 {slug} 生产技能。\n"
            f"目标：{goal}。\n"
            "只根据经过版本标记的结构化输入工作。严格遵守以下规则：\n"
            + "\n".join(f"- {rule}" for rule in hard_rules)
            + "\n- 不补造缺失的身份、连续性、方言或授权信息。\n"
              "- 只返回符合 output-schema.json 的 JSON，不输出 Markdown。\n"
        )
        (folder / "prompt.md").write_text(prompt, encoding="utf-8")
        write_json(folder / "input-schema.json", schema(f"{slug}-input", inputs))
        write_json(folder / "output-schema.json", schema(f"{slug}-output", outputs, True))
        example_input = {"projectId": "project-example", "projectVersion": 1, "payload": {name: {"example": True} for name in inputs}, "trace": {"requestKey": f"example-{slug}"}}
        example_output = {"projectId": "project-example", "projectVersion": 1, "payload": {name: {"version": 1} for name in outputs}, "status": "READY", "warnings": [], "trace": {"skill": slug, "skillVersion": "1.0.0"}}
        write_json(folder / "examples" / "input.json", example_input)
        write_json(folder / "examples" / "output.json", example_output)
        write_json(folder / "tests" / "cases.json", {
            "valid": [example_input],
            "invalid": [{"name": "missing-project-version", "value": {"projectId": "project-example", "payload": example_input["payload"]}}],
        })
        catalog.append({"id": slug, "agent": agent, "version": "1.0.0", "goal": goal, "inputs": inputs, "outputs": outputs, "hardRules": hard_rules})
    write_json(ROOT / "skills" / "catalog.json", {"version": "1.0.0", "agents": ["StoryAgent", "DirectorAgent", "ContinuityQcAgent", "EditingAgent"], "skills": catalog})
    write_json(ROOT / "src" / "main" / "resources" / "production-skills" / "catalog.json", {"version": "1.0.0", "agents": ["StoryAgent", "DirectorAgent", "ContinuityQcAgent", "EditingAgent"], "skills": catalog})


if __name__ == "__main__":
    main()
