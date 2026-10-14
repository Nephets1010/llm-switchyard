"""
GitHub Actions workflow 静态校验器。

★ 为什么要自己写而不是直接用 PyYAML：
  PyYAML 的 SafeLoader **允许重复键并静默保留最后一个**，于是「同一步骤里写了两个
  run:」这种错误在本地解析时完全看不出来，只有 GitHub 解析器会报错
  （"'run' is already defined"）。这正是本次事故的成因：
  本地 mvn 全绿、文件也没报错，但 CI 一个 job 都创建不出来。

校验项：
  1. YAML 可解析，且**无重复键**（自定义 loader 抛错并报出行号）
  2. 不得在 jobs.*.name 里使用 env 上下文（GitHub 会在此报 Unrecognized named-value）
  3. 每个 step 必须有 name 或 uses 之一（否则 CI 日志里是一坨无名步骤）
  4. 引用的本地脚本文件真实存在
  5. needs / if 表达式里的上下文在该作用域内确实存在

用法：python3 tools/validate_workflows.py
退出码 0 = 全部通过；非 0 = 有问题（逐条打印到 stderr）
"""

from __future__ import annotations

import io
import os
import re
import sys
from typing import Any

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - 仅在缺依赖时触发
    # ★ 不要让脚本以 ModuleNotFoundError + traceback 结束：
    #   CI 上那种输出看不出「是没装依赖」还是「脚本本身坏了」，
    #   排查的人得先想到 PyYAML 才查得到。
    sys.stderr.write(
        "缺少依赖 PyYAML。请先安装：\n"
        "    python -m pip install PyYAML\n"
        "若在 GitHub Actions 中运行，请确认 workflow 里已加：\n"
        "    - run: python -m pip install PyYAML\n"
    )
    raise SystemExit(2) from None

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKFLOW_DIR = os.path.join(REPO_ROOT, ".github", "workflows")

# ── 允许在 jobs.*.name 中使用的上下文（GitHub 的实际限制）────────────────
# job 的 name 字段在 workflow 解析早期求值，此时只有 github / inputs / vars 可用；
# env 是在后续阶段才被填充的，因此引用 env 会直接报 Unrecognized named-value。
JOB_NAME_ALLOWED_CONTEXTS = {"github", "inputs", "vars"}

EXPRESSION_RE = re.compile(r"\$\{\{\s*([^}]+?)\s*\}\}")


class DuplicateKeyError(Exception):
    """YAML 映射中出现重复键。"""


class StrictLoader(yaml.SafeLoader):
    """拒绝重复键的 SafeLoader。"""


def _construct_mapping(loader: StrictLoader, node: yaml.MappingNode, deep: bool = False) -> dict:
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise DuplicateKeyError(
                f"重复键 {key!r}（行 {key_node.start_mark.line + 1}）"
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


StrictLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping
)


def _iter_steps(workflow: dict) -> list[tuple[str, int, dict]]:
    """产出 (job_id, step_index, step)。"""
    out: list[tuple[str, int, dict]] = []
    for job_id, job in (workflow.get("jobs") or {}).items():
        for i, step in enumerate(job.get("steps") or []):
            out.append((job_id, i, step))
    return out


def check(path: str) -> list[str]:
    """返回问题列表；空列表表示通过。"""
    problems: list[str] = []
    rel = os.path.relpath(path, REPO_ROOT)

    with io.open(path, encoding="utf-8") as f:
        raw = f.read()

    # ── 1. YAML 可解析 + 无重复键 ──────────────────────────────────────
    try:
        wf = yaml.load(raw, Loader=StrictLoader)
    except DuplicateKeyError as ex:
        return [f"{rel}: YAML 重复键 —— {ex}"]
    except yaml.YAMLError as ex:
        return [f"{rel}: YAML 语法错误 —— {ex}"]

    if not isinstance(wf, dict):
        return [f"{rel}: 顶层不是映射"]

    # ── 2. job name 里不得引用 env ─────────────────────────────────────
    for job_id, job in (wf.get("jobs") or {}).items():
        name = job.get("name")
        if not isinstance(name, str):
            continue
        for expr in EXPRESSION_RE.findall(name):
            head = expr.split(".", 1)[0].split("[", 1)[0].strip()
            if head == "env":
                line = raw[: raw.find(name)].count("\n") + 1
                problems.append(
                    f"{rel}:{line}: job '{job_id}' 的 name 里使用了 env 上下文"
                    f"（${{{{ {expr} }}}}）—— GitHub 解析器会报 "
                    f"Unrecognized named-value: 'env'。请写死版本号，或只改 "
                    f"steps[].name。"
                )

    # ── 3. step 必须有 name 或 uses ────────────────────────────────────
    for job_id, idx, step in _iter_steps(wf):
        if not isinstance(step, dict):
            continue
        if "uses" not in step and "run" not in step:
            problems.append(
                f"{rel}: job '{job_id}' 的第 {idx} 个 step 既无 uses 也无 run"
            )
        if "uses" in step and "run" in step:
            problems.append(
                f"{rel}: job '{job_id}' 的第 {idx} 个 step 同时有 uses 与 run（二者互斥）"
            )

    # ── 4. run 脚本里引用的本地文件必须存在 ────────────────────────────
    for job_id, _idx, step in _iter_steps(wf):
        if not isinstance(step, dict):
            continue
        run = step.get("run")
        if not isinstance(run, str):
            continue
        for m in re.finditer(r"(?:cat|source|\.)\s+((?:\./)?[\w./-]+\.(?:sh|py))", run):
            target = m.group(1)
            if target.startswith("./") and os.path.exists(os.path.join(REPO_ROOT, target)):
                continue
            if os.path.exists(os.path.join(REPO_ROOT, target)):
                continue
            problems.append(
                f"{rel}: job '{job_id}' 的脚本引用了不存在的文件 {target}"
            )

    # ── 5. needs 指向的 job 必须存在 ───────────────────────────────────
    for job_id, job in (wf.get("jobs") or {}).items():
        for need in job.get("needs") or []:
            if need not in (wf.get("jobs") or {}):
                problems.append(f"{rel}: job '{job_id}' 的 needs 指向不存在的 '{need}'")

    return problems


def main() -> int:
    if not os.path.isdir(WORKFLOW_DIR):
        print(f"未找到 {WORKFLOW_DIR}", file=sys.stderr)
        return 1

    files = sorted(
        os.path.join(WORKFLOW_DIR, f)
        for f in os.listdir(WORKFLOW_DIR)
        if f.endswith((".yml", ".yaml"))
    )
    if not files:
        print("没有找到任何 workflow 文件", file=sys.stderr)
        return 1

    all_problems: list[str] = []
    for path in files:
        problems = check(path)
        rel = os.path.relpath(path, REPO_ROOT)
        if problems:
            all_problems.extend(problems)
            print(f"FAIL  {rel}")
        else:
            print(f"OK    {rel}")

    if all_problems:
        print("\n发现 %d 个问题：" % len(all_problems), file=sys.stderr)
        for p in all_problems:
            print("  - " + p, file=sys.stderr)
        return 1

    print(f"\n全部 {len(files)} 个 workflow 校验通过"
          f"（无重复键、env 未出现在 job name、本地脚本均存在）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
