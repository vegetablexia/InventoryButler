#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Minecraft 的 client / server jar 预填进 Loom 缓存。

背景
----
Loom 从 `piston-data.mojang.com` 下载这两个 jar。如果这个域名被网络环境
（代理、加速器、hosts 劫持）拦掉，构建会卡在下载阶段不动。

这个脚本改从 BMCLAPI 镜像下载，校验官方 sha1 之后，按 Loom 期望的文件名
放进 `~/.gradle/caches/fabric-loom/<版本>/`。Loom 的 Download.requiresDownload()
在「文件存在且 sha1 与版本 JSON 一致」时会直接跳过下载，于是构建就能继续。

用法
----
    python tools/fetch_minecraft.py 26.2

需要联网；不需要 Java。
"""

import hashlib
import json
import os
import shutil
import sys
import urllib.request

VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
MIRROR_VERSION = "https://bmclapi2.bangbang93.com/version/{version}/{kind}"

KINDS = {"client": "minecraft-client.jar", "server": "minecraft-server.jar"}


def http_get(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as resp:
        return resp.read()


def http_download(url: str, dest: str) -> None:
    print(f"  下载 {url}")
    with urllib.request.urlopen(url, timeout=1800) as resp, open(dest, "wb") as out:
        shutil.copyfileobj(resp, out, length=1 << 20)


def find_version_json(version: str) -> dict:
    """从官方版本清单里找到该版本的元数据地址，再拉下来。"""
    manifest = json.loads(http_get(VERSION_MANIFEST))
    for entry in manifest["versions"]:
        if entry["id"] == version:
            return json.loads(http_get(entry["url"]))
    raise SystemExit(f"版本清单里找不到 {version}")


def sha1_of(path: str) -> str:
    digest = hashlib.sha1()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def loom_cache_dir(version: str) -> str:
    gradle_home = os.environ.get("GRADLE_USER_HOME")
    if not gradle_home:
        gradle_home = os.path.join(os.path.expanduser("~"), ".gradle")
    return os.path.join(gradle_home, "caches", "fabric-loom", version)


def main() -> None:
    version = sys.argv[1] if len(sys.argv) > 1 else "26.2"
    cache = loom_cache_dir(version)
    os.makedirs(cache, exist_ok=True)

    print(f"目标版本: {version}")
    print(f"Loom 缓存: {cache}")
    meta = find_version_json(version)

    workdir = os.path.join(cache, ".download")
    os.makedirs(workdir, exist_ok=True)

    for kind, filename in KINDS.items():
        info = meta["downloads"].get(kind)
        if not info:
            print(f"  跳过 {kind}（该版本没有这个文件）")
            continue

        target = os.path.join(cache, filename)
        if os.path.exists(target) and sha1_of(target) == info["sha1"]:
            print(f"  {filename} 已就位（sha1 匹配），跳过")
            continue

        tmp = os.path.join(workdir, kind + ".jar")
        http_download(MIRROR_VERSION.format(version=version, kind=kind), tmp)

        actual = sha1_of(tmp)
        if actual != info["sha1"]:
            raise SystemExit(
                f"  {kind} 校验失败！\n  期望 {info['sha1']}\n  实际 {actual}"
            )
        if os.path.getsize(tmp) != info["size"]:
            raise SystemExit(f"  {kind} 大小不符，期望 {info['size']}")

        # 顺手清掉 Loom 留下的半截文件和锁，否则它下次仍会尝试重新下载
        for stale in (target + ".part", target + ".lock"):
            if os.path.exists(stale):
                os.remove(stale)
        shutil.move(tmp, target)
        print(f"  {filename} 已就位（sha1 校验通过，{info['size']:,} 字节）")

    shutil.rmtree(workdir, ignore_errors=True)
    print("完成。现在可以正常运行 gradlew build 了。")


if __name__ == "__main__":
    main()
