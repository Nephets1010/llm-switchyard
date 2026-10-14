"""
复现 Docker 的 .dockerignore 匹配语义，用于在【没有 Docker 的机器上】验证构建上下文。

★ 为什么要自己实现：
  1. 本机没装 Docker，无法 `docker build` 实测；而 CI 报的
     「每个 pom.xml 都 not found」恰恰是构建上下文被 .dockerignore 吃掉了。
  2. 用「像 Docker 的近似实现」去验证是循环论证 —— 近似实现与真实语义的差异
     正是最容易漏 bug 的地方（我前两版就分别把 '**' 与目录语义搞错，
     都是靠下面这组自检用例抓出来的）。
  3. 语义逐条对照 moby/patternmatcher/patternmatcher.go（Docker 官方 Go 实现）。

三条关键语义（都与本项目的 bug 直接相关）：

  a) 黑名单语义：没有任何规则匹配的文件【默认进入】构建上下文。
     → matches() 返回 None 必须当作「不排除」。

  b) '**' 匹配零个或多个目录：'**/pom.xml' 同时匹配 'pom.xml' 与 'a/b/pom.xml'。
     ★ 本项目先写 '!pom.xml'（只恢复根级），又写 '**/pom.xml'（排除所有层级），
       后者覆盖前者 —— 于是根级与各模块的 pom.xml 全部消失。

  c) 目录规则作用于整棵子树：'docs/' 命中后其下所有文件都不可见。
     判断某文件时必须逐级检查父目录，而不只是文件自身。

用法：
    python3 tools/check_dockerignore.py --selftest   # 只跑自检
    python3 tools/check_dockerignore.py              # 校验 Dockerfile 的 COPY 源
"""

from __future__ import annotations

import os
import re
import sys
from typing import List, Optional, Tuple

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _compile(pattern: str) -> str:
    """把 .dockerignore 规则翻译成正则源码（对照 moby 的 compile()）。"""
    pattern = pattern.strip("/")
    out: List[str] = []
    i, n = 0, len(pattern)
    while i < n:
        ch = pattern[i]
        i += 1
        if ch == "*":
            if i < n and pattern[i] == "*":                 # '**'
                i += 1
                if i < n and pattern[i] == "/":             # '**/' → 跨任意层级（含零层）
                    i += 1
                    out.append("(?:.*/)?")
                else:                                       # 结尾的 '**'
                    out.append(".*")
            else:                                           # 单 '*' 不跨分隔符
                out.append("[^/]*")
        elif ch == "?":
            out.append("[^/]")
        else:
            out.append(re.escape(ch))
    return "".join(out)


class DockerIgnore:
    """Docker 的 .dockerignore 求值器。规则按顺序应用，后匹配者胜。"""

    def __init__(self, lines: List[str]):
        self.rules: List[Tuple[re.Pattern, bool]] = []      # (regex, negate)
        for raw in lines:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            negate = line.startswith("!")
            if negate:
                line = line[1:].strip()
            try:
                self.rules.append(
                    (re.compile("^" + _compile(line) + "$"), negate)
                )
            except re.error as ex:
                raise ValueError(f"无法编译规则 {raw!r}: {ex}") from ex

    def _decide(self, path: str) -> Optional[bool]:
        """对该路径逐条应用规则。True=排除 / False=恢复 / None=无规则匹配。"""
        matched: Optional[bool] = None
        for regex, negate in self.rules:
            if regex.match(path):
                matched = not negate
        return matched

    def excluded(self, path: str) -> bool:
        """该文件是否【不】在构建上下文中。

        逐级检查父目录：目录一旦被排除，其下内容一律不可见，
        且这种排除无法被子目录里的 '!' 恢复（Docker 的行为如此）。
        """
        parts = path.strip("/").split("/")
        for depth in range(1, len(parts)):
            if self._decide("/".join(parts[:depth])) is True:
                return True
        return self._decide(path) is True


def read_dockerignore(root: str) -> DockerIgnore:
    path = os.path.join(root, ".dockerignore")
    if not os.path.exists(path):
        return DockerIgnore([])
    with open(path, encoding="utf-8") as f:
        return DockerIgnore(f.read().splitlines())


# ── 解析 Dockerfile 的 COPY 指令 ──────────────────────────────────────────
COPY_DIRECTIVE = re.compile(r"^\s*COPY\s+(?:--from=(\S+)\s+)?(.+)$", re.IGNORECASE)


def parse_dockerfile_copy(path: str) -> List[str]:
    """返回所有从【构建上下文】取文件的 COPY 源（跳过 --from=<stage>）。"""
    needed: List[str] = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.strip().startswith("#"):
                continue
            m = COPY_DIRECTIVE.match(line)
            if not m or m.group(1):
                continue
            parts = m.group(2).split()
            for src in parts[:-1]:                # 末项是目标路径
                if src.startswith("--"):
                    continue
                needed.append(src)
    return needed


# ── 自检用例：固化 moby 官方测试里与本项目用法直接相关的那些 ────────────────
SELFTESTS: List[Tuple[List[str], str, bool]] = [
    # (规则, 路径, 期望是否被排除)
    # a) 黑名单语义：没规则 ≠ 排除
    ([], "pom.xml", False),
    (["# 只有注释"], "pom.xml", False),
    # b) '*' 不跨分隔符
    (["*.xml"], "pom.xml", True),
    (["*.xml"], "sub/dir.xml", False),
    # c) 目录规则作用于整棵子树
    (["target/"], "target/x", True),
    (["target/"], "switchyard-core/target/x", False),   # 只匹配根级 target/
    (["docs/"], "docs/a.md", True),
    (["docs/"], "docs/nested/deep/a.md", True),
    (["docs/"], "docs", True),
    # d) '**' 匹配零层与多层
    (["**/pom.xml"], "pom.xml", True),
    (["**/pom.xml"], "a/b/pom.xml", True),
    # ★ 本项目的真实 bug：先 '!pom.xml' 恢复根级，再 '**/pom.xml' 全部排除
    (["*.xml", "!pom.xml"], "pom.xml", False),
    (["*.xml", "!pom.xml", "**/pom.xml"], "pom.xml", True),
    (["*.xml", "!pom.xml", "**/pom.xml"], "switchyard-core/pom.xml", True),
    # e) 后匹配者胜
    (["*.md", "!README.md"], "README.md", False),
    (["*.md", "!README.md", "**/README.md"], "README.md", True),
    # f) 目录被排除后，子目录里的 '!' 无法恢复
    (["docs/", "!docs/keep.md"], "docs/keep.md", True),
]


def selftest() -> int:
    failed = 0
    for rules, path, expected in SELFTESTS:
        got = DockerIgnore(rules).excluded(path)
        ok = got == expected
        if not ok:
            failed += 1
        print(f"  {'PASS' if ok else 'FAIL'}  rules={str(rules):<44} path={path:<30} "
              f"excluded={got} (期望 {expected})")
    print(f"\n自检：{len(SELFTESTS) - failed}/{len(SELFTESTS)} 通过")
    return failed


def main() -> int:
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0

    di = read_dockerignore(REPO_ROOT)
    needed = parse_dockerfile_copy(os.path.join(REPO_ROOT, "Dockerfile"))

    actual: set = set()
    for root, dirs, files in os.walk(REPO_ROOT):
        dirs[:] = [x for x in dirs if x != ".git"]
        rel = os.path.relpath(root, REPO_ROOT).replace(os.sep, "/")
        for name in files:
            actual.add(f"{rel}/{name}" if rel != "." else name)

    print("Dockerfile 的 COPY 源 → 构建上下文可用性：\n")
    bad: List[str] = []
    for src in needed:
        src = src.rstrip("/")
        if src in actual:
            if di.excluded(src):
                print(f"  ❌ {src}  存在，但被 .dockerignore 排除")
                bad.append(src)
            else:
                print(f"  ✅ {src}  进入上下文")
        elif any(p.startswith(src + "/") for p in actual):
            inside = [p for p in actual if p.startswith(src + "/")]
            killed = [p for p in inside if di.excluded(p)]
            if killed:
                print(f"  ❌ {src}/  下 {len(killed)}/{len(inside)} 个文件被排除，"
                      f"例如 {killed[:2]}")
                bad.append(src)
            else:
                print(f"  ✅ {src}/  目录及全部内容进入上下文（{len(inside)} 个文件）")
        else:
            print(f"  ❌ {src}  仓库里就不存在（与 .dockerignore 无关）")
            bad.append(src)

    if bad:
        print(f"\n发现 {len(bad)} 个 COPY 源不可用：{bad}")
        print("\nDocker 在计算 cache checksum 阶段就会报 'not found'，此时一条 RUN 都还没执行，")
        print("因此这类报错与 BuildKit 缓存跨 commit 污染无关 —— 就是构建上下文里没有该文件。")
        return 1
    print("\n全部 COPY 源均可用，构建上下文完整。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
