#!/usr/bin/env python3
"""递归校验 Actions 元数据与 uses 依赖；只读 gh API，不执行远端代码。

依赖 PyYAML==6.0.3。tag/branch/SHA 均解析到 commit 后读取元数据，避免读取漂移。
404 才尝试另一个元数据文件名；403/网络失败必须响，不能当作「没有子依赖」。
只证明引用可解析，不证明远端 shell 的下载地址/运行行为安全或可用。
"""
from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote

import yaml


class RefError(RuntimeError):
    pass


class NotFound(RefError):
    pass


def gh_api(endpoint: str):
    try:
        proc = subprocess.run(["gh", "api", endpoint], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise RefError(f"GitHub API 不可用：{type(e).__name__}") from e
    if proc.returncode:
        if "HTTP 404" in proc.stderr:
            raise NotFound(endpoint)
        raise RefError(f"GitHub API 失败：{endpoint}（非 404，检查授权/限流/连接）")
    try:
        return json.loads(proc.stdout)
    except ValueError as e:
        raise RefError(f"GitHub API 非 JSON：{endpoint}") from e


def uses(document: dict) -> list[str]:
    """只读 jobs 与 runs.steps，绝不把 run shell/注释中的 uses 示例当引用。"""
    refs = []
    for job in document.get("jobs", {}).values():
        if "uses" in job:
            refs.append(job["uses"])
        refs.extend(step["uses"] for step in job.get("steps", []) if "uses" in step)
    refs.extend(step["uses"] for step in document.get("runs", {}).get("steps", []) if "uses" in step)
    if any(not isinstance(ref, str) or "${{" in ref for ref in refs):
        raise RefError("uses 必须是可静态解析的字符串")
    return refs


class Checker:
    def __init__(self, root: Path, api=gh_api):
        self.root, self.api = root.resolve(), api
        self.done = set()
        self.active = set()
        self.commits = {}
        self.checked = []

    def load(self, text: str, origin: str) -> dict:
        try:
            doc = yaml.safe_load(text)
        except yaml.YAMLError as e:
            raise RefError(f"非法 YAML：{origin}") from e
        if not isinstance(doc, dict):
            raise RefError(f"元数据不是映射：{origin}")
        return doc

    def metadata(self, repo: str, path: str, commit: str, workflow=False) -> dict:
        names = [path] if workflow else [f"{path}/action.yml".lstrip("/"), f"{path}/action.yaml".lstrip("/")]
        for name in names:
            try:
                data = self.api(f"repos/{repo}/contents/{name}?ref={commit}")
            except NotFound:
                continue
            if not isinstance(data, dict) or data.get("encoding") != "base64":
                raise RefError(f"无法读取 action/workflow 元数据：{repo}/{name}@{commit}")
            try:
                text = base64.b64decode(data["content"]).decode("utf-8")
            except (KeyError, ValueError, UnicodeError) as e:
                raise RefError(f"无法解码元数据：{repo}/{name}") from e
            return self.load(text, f"{repo}/{name}")
        raise RefError(f"元数据缺失：{repo}/{path}@{commit}")

    def visit(self, ref: str, chain=()):
        chain = (*chain, ref)
        if len(chain) > 20 or len(self.done) + len(self.active) >= 256:
            raise RefError("依赖图超过深度/节点上限")
        if ref.startswith("docker://"):
            raise RefError(f"Docker uses 不在 Git ref 检查范围，需另设镜像校验：{ref}")
        if ref.startswith("./"):
            # runner 对 composite 中的 ./ 仍按调用仓工作目录解析。
            path = (self.root / ref).resolve()
            if not path.is_relative_to(self.root):
                raise RefError(f"本地 action 越界：{ref}")
            key = ("local", str(path))
            files = [path] if path.is_file() and path.suffix in (".yml", ".yaml") else [path / "action.yml", path / "action.yaml"]
            file = next((f for f in files if f.is_file()), None)
            if file is None:
                raise RefError(f"本地 action 元数据缺失：{ref}")
            doc = self.load(file.read_text(encoding="utf-8"), ref)
        else:
            match = re.fullmatch(r"([\w.-]+/[\w.-]+)(/[^@]+)?@([^\s@]+)", ref)
            if not match:
                raise RefError(f"非法远程 uses：{ref}")
            repo, path, version = match.groups()
            path = (path or "").lstrip("/")
            cache_key = (repo.lower(), version)
            if cache_key not in self.commits:
                commit = self.api(f"repos/{repo}/commits/{quote(version, safe='')}").get("sha", "")
                if not re.fullmatch(r"[0-9a-f]{40}", commit):
                    raise RefError(f"ref 未解析为 commit：{ref}")
                self.commits[cache_key] = commit
            commit = self.commits[cache_key]
            key = (repo.lower(), path, commit)
            if key in self.done:
                return
            doc = self.metadata(repo, path, commit, workflow=path.startswith(".github/workflows/"))
        if key in self.active:
            raise RefError("循环 action 依赖：" + " -> ".join(chain))
        if key in self.done:
            return
        self.active.add(key)
        try:
            if "runs" not in doc and "jobs" not in doc:
                raise RefError(f"缺 runs/jobs 元数据：{ref}")
            for child in uses(doc):
                try:
                    self.visit(child, chain)
                except RefError as e:
                    raise RefError(f"{ref} -> {e}") from e
            self.done.add(key)
            self.checked.append(ref)
        finally:
            self.active.remove(key)

    def check(self):
        files = sorted((self.root / ".github/workflows").glob("*.y*ml"))
        refs = []
        for file in files:
            refs.extend(uses(self.load(file.read_text(encoding="utf-8"), str(file))))
        if not refs:
            raise RefError("未找到任何 uses，拒绝空检查假绿")
        errors = []
        for ref in sorted(set(refs)):
            try:
                self.visit(ref)
            except RefError as e:
                errors.append(f"{ref}: {e}")
        return errors


def escape(text):
    return str(text).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = ap.parse_args()
    checker = Checker(args.root)
    try:
        errors = checker.check()
    except (RefError, OSError, TypeError, AttributeError) as e:
        errors = [str(e)]
    for ref in checker.checked:
        print(f"ok   {ref}")
    for error in errors:
        print(f"::error::依赖不可解析：{escape(error)}")
    print(f"::{ 'error' if errors else 'notice' }::递归 uses 校验：{len(checker.done)} 个 action/workflow，错误 {len(errors)}")
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
