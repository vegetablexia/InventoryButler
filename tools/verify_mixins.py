#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Mixin 静态自检 —— 在启动游戏之前，把「编译能过、一启动就崩」的那类问题揪出来。

背景（真实踩过的坑）：
    @Shadow 一个**父类**的字段，编译期一切正常，一启动就崩：

        InvalidMixinException: @Shadow field minecraft was not located in the
        target class ...AbstractContainerScreen. No refMap loaded.

根因是 Mixin 0.8.x 的不对称行为 —— 方法会沿继承链找，字段不会：

    TargetClassContext.findAliasedField(deque, desc, boolean)
        只遍历 this.classNode.fields（目标类**自己声明**的字段）
        + this.mixinFields（别的 mixin 加进来的字段）
        —— 不向上遍历父类。

而 Mixin 的注解处理器（编译期）是沿继承链找的，所以编译期发现不了。

用法：
    python tools/verify_mixins.py                 # 自动找 .tools/mc/client-26.2.jar
    python tools/verify_mixins.py --jar <mc.jar>
    python tools/verify_mixins.py --strict        # 把 WARN 也当失败

退出码：0 = 通过（或只有 WARN），1 = 有 ERROR。
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys

SRC_ROOTS = [
    "src/main/java",
    "src/client/java",
]
MIXIN_JSON_GLOBS = [
    "src/main/resources/*.mixins.json",
    "src/client/resources/*.mixins.json",
]
DEFAULT_JARS = [
    ".tools/mc/client-26.2.jar",
    ".tools/mc/server-26.2.jar",
]


def find_default_jars():
    """.tools/mc 可能在本目录，也可能在工程上一级（工作区根目录），逐级往上找。"""
    out, seen = [], set()
    for pat in DEFAULT_JARS:
        base = os.path.abspath(os.path.dirname(__file__))
        for _ in range(5):
            cand = os.path.abspath(os.path.join(base, pat))
            if os.path.isfile(cand) and cand not in seen:
                seen.add(cand)
                out.append(cand)
                break
            nb = os.path.dirname(base)
            if nb == base:
                break
            base = nb
    return out

# javap 打出来的类声明行，用来拿父类：
#   public abstract class net.minecraft...AbstractContainerScreen<T extends ...> extends net.minecraft...Screen
# 注意：类名后面可能跟泛型参数，所以匹配前要先把 <...> 去掉。
CLASS_DECL_RE = re.compile(
    r"^\s*(?:public|abstract|final|\s)*\s*(?:class|interface|enum)\s+([\w.$]+)"
    r"(?:\s+extends\s+([\w.$]+))?"
)


def strip_generics(line):
    """去掉 <...>（含一层嵌套），否则 `Foo<T extends Bar>` 会把类名切错。"""
    prev = None
    while prev != line:
        prev = line
        line = re.sub(r"<[^<>]*>", "", line)
    return line

# @Shadow / @Inject 出现在行里
SHADOW_RE = re.compile(r"@Shadow\b")
INJECT_RE = re.compile(r'@Inject\(\s*method\s*=\s*"([^"]+)"')
MIXIN_RE = re.compile(r"@Mixin\(\s*([\w.$]+)\.class")

FIELD_DECL_RE = re.compile(
    r"^\s*(?:(?:public|protected|private|static|final|volatile|transient|abstract)\s+)*"
    r"([\w.$<>\[\],\s]+?)\s+(\w+)\s*;\s*$"
)
METHOD_DECL_RE = re.compile(
    r"^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\s+)*"
    r"([\w.$<>\[\],\s]+?)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws [\w.,\s]+)?[;{]\s*$"
)


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")


class JavaTypes:
    """用 javap 建索引：每个类自己声明了哪些成员、父类是谁。"""

    def __init__(self, javap, classpath):
        self.javap = javap
        self.classpath = classpath
        self._cache = {}

    def info(self, fqcn):
        """返回 (fields, methods, superclass)；类不存在返回 None。

        fields  : {(name, desc)}   只含**本类自己声明**的字段
        methods : {(name, desc)}   只含**本类自己声明**的方法
        """
        if fqcn in self._cache:
            return self._cache[fqcn]
        cp = self.classpath
        r = run([self.javap, "-p", "-s", "-classpath", cp, fqcn])
        text = r.stdout or ""
        if not text.strip() or "Error" in text:
            self._cache[fqcn] = None
            return None

        simple = fqcn.split(".")[-1]
        superclass = None
        fields, methods = set(), set()
        pending = None  # 上一行的声明，等下一行的 descriptor
        seen_self_decl = False
        for line in text.splitlines():
            st = line.strip()
            if st.startswith("descriptor:"):
                desc = st[len("descriptor:"):].strip()
                if pending:
                    pending(desc)
                    pending = None
                continue

            cleaned = strip_generics(line)
            cm = CLASS_DECL_RE.match(cleaned)
            if cm:
                name = cm.group(1)
                if name.split(".")[-1] == simple:
                    superclass = cm.group(2)
                    seen_self_decl = True
                    continue
                # javap 会把内部类也打出来；碰到就停，免得把内部类的成员算进来
                if seen_self_decl:
                    break
                continue

            # 成员声明行：以修饰符/类型开头且以 ; 或 { 结尾
            if ("(" in st and (st.endswith(";") or st.endswith("{"))) or (
                st.endswith(";") and "(" not in st
            ):
                if "(" in st:
                    head = st.split("(")[0].strip().split()
                    if head:
                        name = head[-1]
                        pending = lambda d, n=name: methods.add((n, d))
                else:
                    parts = st.rstrip(";").strip().split()
                    if len(parts) >= 2:
                        name = parts[-1]
                        pending = lambda d, n=name: fields.add((n, d))
                continue
            # 不是声明行就清掉待定状态，避免错配
            if pending and st and not st.startswith("//"):
                pending = None

        self._cache[fqcn] = (fields, methods, superclass)
        return self._cache[fqcn]

    def declared_field(self, fqcn, name):
        info = self.info(fqcn)
        if not info:
            return None
        for (n, d) in info[0]:
            if n == name:
                return d
        return None

    def declared_method(self, fqcn, name):
        info = self.info(fqcn)
        if not info:
            return None
        for (n, d) in info[1]:
            if n == name:
                return d
        return None

    def find_field_in_hierarchy(self, fqcn, name):
        """返回声明该字段的类名（含父类），找不到返回 None。"""
        cur = fqcn
        seen = 0
        while cur and seen < 50:
            seen += 1
            if self.declared_field(cur, name) is not None:
                return cur
            info = self.info(cur)
            cur = info[2] if info else None
        return None

    def find_method_in_hierarchy(self, fqcn, name, desc):
        cur = fqcn
        seen = 0
        while cur and seen < 50:
            seen += 1
            info = self.info(cur)
            if info and (name, desc) in info[1]:
                return cur
            cur = info[2] if info else None
        return None


def strip_descriptor(desc, name):
    """'mouseReleased(Lnet/...;)Z' -> '(Lnet/...;)Z'"""
    if "(" not in desc:
        return desc
    return desc[desc.index("("):]


def collect_mixin_classes():
    """读 mixins.json，返回 [(源文件路径, 目标类全名)]。"""
    found = []
    for pattern in MIXIN_JSON_GLOBS:
        for path in glob.glob(pattern):
            try:
                cfg = json.load(open(path, encoding="utf-8"))
            except Exception as e:
                print(f"  ! 解析 {path} 失败: {e}")
                continue
            pkg = cfg.get("package", "")
            names = []
            for key in ("mixins", "client", "server"):
                names += cfg.get(key, []) or []
            for n in names:
                for root in SRC_ROOTS:
                    cand = os.path.join(root, *pkg.split("."), n + ".java")
                    if os.path.isfile(cand):
                        found.append((cand, None))
                        break
                else:
                    print(f"  ! 找不到 mixin 源文件 {pkg}.{n}")
    return found


def import_map(source):
    """简单的 import 收集：simple name -> fq name。"""
    out = {}
    for line in source.splitlines():
        m = re.match(r"\s*import\s+(?:static\s+)?([\w.$]+)\s*;", line)
        if m:
            fq = m.group(1)
            out[fq.split(".")[-1]] = fq
    return out


def resolve(simple, imports, package):
    if simple in imports:
        return imports[simple]
    if "." in simple:
        return simple
    return f"{package}.{simple}" if package else simple


def scan_source(path, types, problems):
    """检查一个 mixin 源文件。problems 是 [(level, msg)]。"""
    src = open(path, encoding="utf-8").read()
    lines = src.splitlines()
    m = MIXIN_RE.search(src)
    if not m:
        problems.append(("ERROR", f"{path}: 找不到 @Mixin(...) 目标"))
        return
    pkg_m = re.search(r"^\s*package\s+([\w.]+)\s*;", src, re.M)
    package = pkg_m.group(1) if pkg_m else ""
    imports = import_map(src)
    target = resolve(m.group(1), imports, package)

    info = types.info(target)
    if info is None:
        problems.append(("ERROR", f"{path}: 目标类 {target} 在 jar 里找不到"))
        return

    print(f"\n  {os.path.basename(path)}  ->  {target}")

    # ---- @Shadow ----
    i = 0
    shadow_fields = 0
    while i < len(lines):
        if SHADOW_RE.search(lines[i]):
            j = i + 1
            decl = None
            while j < len(lines) and j < i + 8:
                st = lines[j].strip()
                if not st or st.startswith("//") or st.startswith("*") or st.startswith("/*") \
                        or st.startswith("@") or st.startswith("*/"):
                    j += 1
                    continue
                decl = st
                break
            if decl is None:
                i += 1
                continue
            fm = FIELD_DECL_RE.match(decl)
            mm = METHOD_DECL_RE.match(decl)
            if mm and mm.group(2) != target.split(".")[-1]:
                mname = mm.group(2)
                # 方法：Mixin 会沿继承链找（findMethodInHierarchy），所以不强制同类声明
                if types.declared_method(target, mname) is None:
                    owner = _find_method_anywhere(types, target, mname)
                    if owner is None:
                        problems.append(
                            ("ERROR", f"@Shadow 方法 '{mname}' 在 {target} 及其父类中都找不到"))
                    else:
                        problems.append(
                            ("WARN", f"@Shadow 方法 '{mname}' 不是 {target} 自己声明的"
                                     f"（来自 {owner}）—— 方法可以这样用，确认是有意的"))
            elif fm:
                fname = fm.group(2)
                ftype = fm.group(1).strip()
                shadow_fields += 1
                if types.declared_field(target, fname) is None:
                    owner = types.find_field_in_hierarchy(target, fname)
                    if owner:
                        problems.append((
                            "ERROR",
                            f"@Shadow 字段 '{fname}' 来自父类 {owner}，**不是** {target} 自己声明的。\n"
                            f"         Mixin 的 @Shadow 字段不向上遍历父类 —— 编译能过，一启动就崩：\n"
                            f"         InvalidMixinException: @Shadow field {fname} was not located\n"
                            f"         修法：改用静态访问器（如 Minecraft.getInstance()）或自己算，"
                            f"别 shadow 父类字段。"))
                    else:
                        problems.append(
                            ("ERROR", f"@Shadow 字段 '{fname}' 在 {target} 及其父类中都不存在"
                                      f"（类型声明为 {ftype}）"))
            i = j + 1
            continue
        i += 1

    # ---- @Inject ----
    injects = 0
    for mr in INJECT_RE.finditer(src):
        raw = mr.group(1)
        injects += 1
        if "(" not in raw:
            problems.append(
                ("ERROR", f'@Inject method "{raw}" 没写描述符；重载方法会歧义，也可能匹配错'))
            continue
        mname = raw.split("(")[0]
        mdesc = strip_descriptor(raw, mname)
        owner = types.find_method_in_hierarchy(target, mname, mdesc)
        if owner is None:
            problems.append(
                ("ERROR", f"@Inject 目标 {mname}{mdesc} 在 {target} 及其父类中都找不到\n"
                          f"         描述符要完全一致（用 javap -s 核对）"))
        elif owner != target:
            problems.append(
                ("WARN", f"@Inject 目标 {mname}{mdesc} 声明在父类 {owner}，不是 {target} —— 确认注入点符合预期"))

    print(f"      @Shadow 字段 {shadow_fields} 个，@Inject {injects} 处")


def _find_method_anywhere(types, fqcn, name):
    cur = fqcn
    seen = 0
    while cur and seen < 50:
        seen += 1
        info = types.info(cur)
        if info and any(n == name for (n, _) in info[1]):
            return cur
        cur = info[2] if info else None
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", action="append", default=[])
    ap.add_argument("--javap", default=None)
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    # 一律以工程根目录为准，脚本从哪儿调用都行
    os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

    javap = args.javap
    if not javap:
        java_home = os.environ.get("JAVA_HOME", "")
        cand = os.path.join(java_home, "bin", "javap.exe")
        if java_home and os.path.isfile(cand):
            javap = cand
        else:
            cand = os.path.join(java_home, "bin", "javap")
            javap = cand if java_home and os.path.isfile(cand) else "javap"

    jars = args.jar or find_default_jars()
    if not jars:
        print("找不到 MC jar，请用 --jar 指定（默认从本目录向上找 .tools/mc/client-26.2.jar）")
        return 1
    classpath = ";".join(os.path.abspath(j) for j in jars)

    print(f"javap : {javap}")
    print(f"jar   : {', '.join(jars)}")

    types = JavaTypes(javap, classpath)
    problems = []
    mixins = collect_mixin_classes()
    if not mixins:
        print("没找到任何 mixin（检查 MIXIN_JSON_GLOBS）")
        return 1
    for path, _ in mixins:
        scan_source(path, types, problems)

    errors = [p for p in problems if p[0] == "ERROR"]
    warns = [p for p in problems if p[0] == "WARN"]

    print("\n" + "=" * 68)
    if errors:
        print(f"❌ {len(errors)} 个 ERROR（这些会导致启动崩溃）：")
        for lvl, msg in errors:
            print(f"  - {msg}")
    if warns:
        print(f"⚠️  {len(warns)} 个 WARN：")
        for lvl, msg in warns:
            print(f"  - {msg}")
    if not errors and not warns:
        print("✅ 全部通过：没有父类 @Shadow 字段，@Inject 目标也都在。")
    elif not errors:
        print("✅ 没有 ERROR。")

    return 1 if (errors or (args.strict and warns)) else 0


if __name__ == "__main__":
    sys.exit(main())
