#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
命令行构建翻页器 APK（不需要 Gradle / Android Studio，只用 SDK 自带工具）

    python build_apk.py
"""

from __future__ import annotations

import os
import shutil
import tempfile
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE / "src"
MANIFEST = HERE / "AndroidManifest.xml"
BUILD = HERE / "build"
OUT_APK = HERE / "PageFlip.apk"
THIRD_PARTY_NOTICES = {
    HERE / "libs" / "LICENSE-ZXing.txt": "assets/licenses/ZXing-LICENSE.txt",
    HERE / "libs" / "NOTICE-ZXing.txt": "assets/licenses/ZXing-NOTICE.txt",
}

# SDK 路径查找顺序：环境变量 -> 常见安装位置 -> 从 build-tools 目录反推
def _find_sdk() -> Path:
    cands = []
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        v = os.environ.get(env)
        if v:
            cands.append(Path(v))
    home = Path.home()
    cands += [
        Path(r"D:\Android\AndroidSDK"),
        home / "Android" / "Sdk",
        home / "AppData" / "Local" / "Android" / "Sdk",
        home / "Library" / "Android" / "sdk",
    ]
    for c in cands:
        if c.is_dir() and (c / "build-tools").is_dir():
            return c
    # 都没命中：返回第一个候选，让后续报错信息指出来
    return cands[0]


SDK = _find_sdk()


def _pick_build_tools() -> Path:
    """选一个可用的 build-tools（优先版本号最大的）。"""
    bt = SDK / "build-tools"
    if not bt.is_dir():
        return bt / "35.0.0"
    versions = []
    for d in bt.iterdir():
        if d.is_dir():
            try:
                versions.append((tuple(int(x) for x in d.name.split(".")), d))
            except ValueError:
                continue
    versions.sort()
    return versions[-1][1] if versions else bt / "35.0.0"


def _pick_platform() -> Path:
    """选一个可用的 android-XX 平台（优先版本号最大的）。"""
    pl = SDK / "platforms"
    if not pl.is_dir():
        return pl / "android-35" / "android.jar"
    versions = []
    for d in pl.iterdir():
        if d.is_dir() and d.name.startswith("android-"):
            try:
                versions.append((tuple(int(x) for x in d.name.split("-")[1].split(".")), d))
            except ValueError:
                continue
    versions.sort()
    return (versions[-1][1] / "android.jar") if versions else pl / "android-35" / "android.jar"


BUILD_TOOLS = _pick_build_tools()
ANDROID_JAR = _pick_platform()

AAPT2 = BUILD_TOOLS / "aapt2.exe"
ZIPALIGN = BUILD_TOOLS / "zipalign.exe"
APKSIGNER = BUILD_TOOLS / "apksigner.bat"
D8 = BUILD_TOOLS / "d8.bat"
KEYSTORE = HERE / "debug.keystore"
KEY_ALIAS = "pageflip"
# 发布签名可通过环境变量指定，密钥文件本身不入库：
#   PF_KEYSTORE=/path/to/release.jks  PF_KEYSTORE_PASS=xxx  PF_KEY_ALIAS=xxx  PF_KEY_PASS=xxx
# 不设置时回退到仓库内的 debug.keystore（脚本会自动生成）。
ENV_KEYSTORE = os.environ.get("PF_KEYSTORE")
ENV_STORE_PASS = os.environ.get("PF_KEYSTORE_PASS")
ENV_ALIAS = os.environ.get("PF_KEY_ALIAS")
ENV_KEY_PASS = os.environ.get("PF_KEY_PASS")

MIN_SDK = "23"
TARGET_SDK = "34"


def run(cmd, cwd=None, env=None):
    print("  $", " ".join(str(c) for c in cmd))
    r = subprocess.run([str(c) for c in cmd], cwd=cwd, env=env,
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(r.stdout[-4000:])
        print(r.stderr[-4000:], file=sys.stderr)
        raise SystemExit(f"[失败] 命令退出码 {r.returncode}")
    if r.stdout.strip():
        print("   ", r.stdout.strip()[:600])
    if r.stderr.strip():
        print("   ", r.stderr.strip()[:600])


def sign_apk(keystore, alias, store_pass, key_pass, aligned):
    # 口令通过子进程环境传递，不能出现在打印的命令或进程命令行里。
    signing_env = os.environ.copy()
    signing_env["PF_APKSIGNER_STORE_PASS"] = store_pass
    signing_env["PF_APKSIGNER_KEY_PASS"] = key_pass
    run([APKSIGNER, "sign", "--ks", keystore,
         "--ks-pass", "env:PF_APKSIGNER_STORE_PASS",
         "--key-pass", "env:PF_APKSIGNER_KEY_PASS",
         "--ks-key-alias", alias, "--out", OUT_APK, aligned], env=signing_env)


def configure_stdio():
    # Windows 的 GBK 控制台不一定能表示工具诊断中的所有字符。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace", line_buffering=True)


def main() -> int:
    configure_stdio()
    for p in (AAPT2, ANDROID_JAR, D8, ZIPALIGN, APKSIGNER, *THIRD_PARTY_NOTICES):
        if not Path(p).exists():
            print(f"[错误] 缺少 {p}", file=sys.stderr)
            return 1

    if BUILD.exists():
        shutil.rmtree(BUILD)
    (BUILD / "classes").mkdir(parents=True)
    (BUILD / "dex").mkdir(parents=True)

    print("[1/6] 编译资源 + 生成未签名 APK")
    res_dir = HERE / "res"
    res_args = []
    if res_dir.is_dir() and any(res_dir.rglob("*.png")):
        # aapt2 读不了中文路径下的目录，先把 res 拷到纯英文临时目录再编译
        tmp_root = Path(tempfile.mkdtemp(prefix="pageflip_"))
        try:
            shutil.copytree(res_dir, tmp_root / "res")
            res_zip = tmp_root / "res.zip"
            run([AAPT2, "compile", "--dir", tmp_root / "res", "-o", res_zip])
            shutil.copy2(res_zip, BUILD / "res.zip")
            res_args = [BUILD / "res.zip"]
        finally:
            shutil.rmtree(tmp_root, ignore_errors=True)

    base_apk = BUILD / "base.apk"
    run([AAPT2, "link", "-o", base_apk, "-I", ANDROID_JAR,
         "--manifest", MANIFEST,
         "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
         "--java", BUILD / "gen"] + res_args)

    print("[2/6] 编译 Java 源码")
    sources = [str(p) for p in SRC.rglob("*.java")]
    gen = list((BUILD / "gen").rglob("*.java")) if (BUILD / "gen").exists() else []
    lib_jars = [str(p) for p in (HERE / "libs").glob("*.jar")] if (HERE / "libs").is_dir() else []
    cp = os.pathsep.join([str(ANDROID_JAR)] + lib_jars)
    run(["javac", "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8",
         "--release", "11", "-encoding", "UTF-8",
         "-classpath", cp, "-d", BUILD / "classes"] + sources + gen)

    print("[3/6] 转 Dex")
    classes = [str(p) for p in (BUILD / "classes").rglob("*.class")]
    run([D8, "--lib", ANDROID_JAR, "--min-api", MIN_SDK,
         "--output", BUILD / "dex"] + classes + lib_jars)

    print("[4/6] 打包 classes.dex")
    with zipfile.ZipFile(base_apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(BUILD / "dex" / "classes.dex", "classes.dex")
        for source, destination in THIRD_PARTY_NOTICES.items():
            z.write(source, destination)

    print("[5/6] 对齐")
    aligned = BUILD / "aligned.apk"
    run([ZIPALIGN, "-f", "4", base_apk, aligned])

    print("[6/6] 签名")
    if ENV_KEYSTORE:
        # 外部发布密钥
        ks = Path(ENV_KEYSTORE)
        if not ks.exists():
            print(f"[错误] PF_KEYSTORE 指向的文件不存在: {ks}", file=sys.stderr)
            return 1
        store_pass = ENV_STORE_PASS or ""
        alias = ENV_ALIAS or KEY_ALIAS
        key_pass = ENV_KEY_PASS or store_pass
        if not store_pass:
            print("[错误] 设置了 PF_KEYSTORE 但缺少 PF_KEYSTORE_PASS", file=sys.stderr)
            return 1
    else:
        # 本地 debug 签名（自动生成）
        ks = KEYSTORE
        store_pass, alias, key_pass = "android", KEY_ALIAS, "android"
        if not ks.exists():
            run(["keytool", "-genkeypair", "-keystore", ks,
                 "-alias", alias, "-storepass", store_pass, "-keypass", key_pass,
                 "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                 "-dname", "CN=PageFlip, O=PageFlip, C=CN"])
    if OUT_APK.exists():
        OUT_APK.unlink()
    sign_apk(ks, alias, store_pass, key_pass, aligned)

    size = OUT_APK.stat().st_size / 1024
    print(f"\n[完成] {OUT_APK}  ({size:.1f} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
