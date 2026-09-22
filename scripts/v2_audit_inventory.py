#!/usr/bin/env python3
"""Build a conservative, reviewable V2 file index; never infer deletion from grep alone."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "v2-code-audit-inventory.md"
tracked = subprocess.check_output(["git", "-c", "core.quotePath=false", "ls-files"], cwd=ROOT, text=True, encoding="utf-8").splitlines()
untracked = subprocess.check_output(["git", "-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True, encoding="utf-8").splitlines()
files = sorted({path for path in tracked + untracked if not path.startswith(("target/", "data/", "frontend/node_modules/"))})
main_java = [path for path in files if path.startswith("src/main/java/") and path.endswith(".java")]
test_java = [path for path in files if path.startswith("src/test/java/") and path.endswith(".java")]
texts = {path: (ROOT / path).read_text(encoding="utf-8", errors="replace") for path in files if (ROOT / path).suffix in {".java", ".js", ".vue", ".py", ".ps1", ".sql", ".md", ".json", ".yml", ".yaml"}}
java_names = {path: Path(path).stem for path in main_java}


def flat(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def links(path: str, name: str) -> tuple[str, str]:
    symbol = re.compile(r"\b" + re.escape(name) + r"\b")
    callers = [Path(other).stem for other in main_java if other != path and symbol.search(texts[other])]
    test_callers = [Path(other).stem for other in test_java if symbol.search(texts[other])]
    callees = [dep for other, dep in java_names.items() if other != path and re.search(r"\b" + re.escape(dep) + r"\b", texts[path])]
    caller_label = ", ".join(callers[:4]) + (f" +{len(callers)-4}" if len(callers) > 4 else "")
    if not caller_label:
        caller_label = "未见静态类名引用；查动态入口"
    if test_callers:
        caller_label += "; 测试: " + ", ".join(test_callers[:2]) + (f" +{len(test_callers)-2}" if len(test_callers) > 2 else "")
    callee_label = ", ".join(sorted(callees)[:5]) + (f" +{len(callees)-5}" if len(callees) > 5 else "")
    return caller_label, callee_label or "无显式项目类名引用"


def classify(path: str) -> tuple[str, str, str, str, str, str]:
    value = texts.get(path, "")
    stem = Path(path).stem
    if path in main_java:
        caller, callee = links(path, stem)
        if "@RestController" in value or "@Controller" in value:
            caller = "Spring HTTP 映射; " + caller
        elif any(token in value for token in ("@Service", "@Component", "@Configuration", "@Scheduled", "@EventListener")):
            caller = "Spring DI/生命周期; " + caller
        role = (re.search(r"/\*\*\s*([^*\n]+)", value) or [None, ""])[1].strip()
        if not role:
            role = {"api": "HTTP/API 边界", "domain": "领域类型", "job": "任务执行或恢复", "model": "服务商接口", "persistence": "持久化", "production": "制作规则/编译/校验", "provider": "服务商适配", "storage": "媒体存储", "workflow": "业务编排"}.get(path.split("/")[6], "Java 生产模块")
        duplicate = "候选 A-03" if stem in {"DirectorContract", "ContinuityEngine", "ContextResolver", "CrossShotQc"} else "候选 A-04" if stem in {"PromptCompiler", "DirectorGenerationService", "AssetViewService"} else "未确认"
        deletion = "否；先迁移" if duplicate != "未确认" else "待证明；禁止直接删除"
        return flat(role[:90]), caller, callee, "是/或 Spring 动态入口", "相关测试按类名静态索引", duplicate + "; " + deletion
    if path in test_java:
        deps = [name for source, name in java_names.items() if re.search(r"\b" + re.escape(name) + r"\b", value)]
        return "JUnit 回归/合同测试", "Maven Surefire/JUnit 反射", ", ".join(deps[:5]) or "fixture/项目测试工具", "否", "是", "不删；除非等价测试替换"
    if path.endswith(".sql") and "/db/migration/" in path:
        tables = re.findall(r'(?:CREATE|ALTER) TABLE\s+"?([\w_]+)', value, flags=re.I)
        return "历史 Flyway 迁移", "Flyway 版本发现", ", ".join(tables[:5]) or "历史 schema", "启动时执行/校验", "是", "禁止删除或修改；高风险"
    if path.startswith("frontend/src/"):
        names = re.findall(r"(?:from\s+['\"]|import\s*['\"])([^'\"]+)", value)
        role = "Vue 页面/组件" if path.endswith(".vue") else "前端入口/策略/测试"
        return role, "Vue/Vite import 或测试运行器", ", ".join(names[:5]) or "HTTP API/视图状态", "是" if not path.endswith(".test.js") else "否", "是" if path.endswith(".test.js") else "间接", "路由和 import 需核实；中风险"
    if path.startswith("skills/"):
        role = "运行时 prompt/规则" if path.endswith(("prompt.md", ".json")) else "Skill 说明/Schema"
        return role, "manifest/catalog/classpath 动态加载", "故事/导演/图像生成合同", "视 runtime 标志", "是/间接", "指纹与路径受合同约束；高风险"
    if path.startswith("src/main/resources/production-skills/"):
        return "派生 Skill catalog", "RuntimeRulePackLoader/ProductionService", "skills/manifest.json", "是", "是", "由生成脚本维护；高风险"
    if path.startswith("src/main/resources/provider-rules/"):
        return "Provider RulePack", "RuntimeRulePackLoader 动态加载", "Seedance 请求编译", "是", "是", "高风险；勿按静态引用删除"
    if path.startswith("src/main/resources/static/demo/"):
        return "mock 演示媒体", "mock Provider/classpath URL", "前端/e2e 预览", "仅 mock", "是", "删除候选；先核实所有引用"
    if path.startswith("src/main/resources/application") or path == ".env.example":
        return "环境配置样例/分 profile 配置", "Spring 配置绑定", "Provider/数据库/存储/任务", "配置样例" if path == ".env.example" else "是", "是/间接", "高风险"
    if path.startswith("scripts/"):
        return "运行/测试/生成/诊断脚本", "命令行/CI/人工操作", "项目 API/构建/fixture", "维护工具", "间接", "逐脚本核实；中风险"
    if path.startswith("test-fixtures/"):
        return "回归/合同 fixture", "测试或脚本按路径读取", "mock Provider/期望结果", "否", "是", "中风险；路径可能动态构造"
    if path.startswith("docs/"):
        return "设计/API/验收文档", "人工阅读", "实现与测试约束", "否", "间接", "低风险；需先迁移有效说明"
    return "项目元数据/静态资源", "构建或人工入口", "项目运行/文档", "待核实", "待核实", "未确认；先查构建和路径引用"


rows = [
    "# V2 逐文件审计索引",
    "",
    "由 `scripts/v2_audit_inventory.py` 从 Git 跟踪及新增文件生成。调用方/被调用方列对 Java 只做**类名级静态索引**，不等于完整调用图；生产/测试使用和删除风险采用保守判断。删除仍须按主审计文档核对 Spring DI、反射、Jackson、前端路由、HTTP、Flyway、脚本与现存数据。",
    "",
    "| 文件 | 职责 | 调用方/入口 | 被调用方/依赖 | 生产使用 | 测试使用 | 是否重复 | 是否废弃 | 是否可删除 | 删除风险 |",
    "|---|---|---|---|---|---|---|---|---|---|",
]
for path in files:
    role, caller, callee, production, test, risk = classify(path)
    duplicate = "疑似" if "候选 A-" in risk else "未证实"
    deprecated = "候选" if path.startswith(("src/main/resources/static/demo/", "scripts/__pycache__/")) else "未证实"
    removable = "禁止" if "/db/migration/" in path else "待证"
    rows.append("| " + " | ".join(flat(x) for x in (path, role, caller, callee, production, test, duplicate, deprecated, removable, risk)) + " |")
OUT.write_text("\n".join(rows) + "\n", encoding="utf-8")
print(f"indexed {len(files)} files -> {OUT.relative_to(ROOT)}")
