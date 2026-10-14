#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os
import sys
import glob
import shutil
import zipfile
import subprocess

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
SRC_DIR = os.path.join(PROJECT_DIR, "src", "main", "java")
RES_DIR = os.path.join(PROJECT_DIR, "src", "main", "res")
ASSETS_DIR = os.path.join(PROJECT_DIR, "src", "main", "assets")
MANIFEST = os.path.join(PROJECT_DIR, "src", "main", "AndroidManifest.xml")
BUILD_DIR = os.path.join(PROJECT_DIR, "build")
CLASSES_DIR = os.path.join(BUILD_DIR, "classes")
OUTPUT_APK = os.path.join(PROJECT_DIR, "CarWithVirtualizer.apk")
ALIGNED_APK = os.path.join(BUILD_DIR, "aligned.apk")
KEYSTORE = os.path.join(BUILD_DIR, "debug.keystore")

# Tool paths
JAVAC = r"D:\Software\Android\Android Studio\jbr\bin\javac.exe"
JAVA = r"D:\Software\Android\Android Studio\jbr\bin\java.exe"
KEYTOOL = r"D:\Software\Android\Android Studio\jbr\bin\keytool.exe"
ANDROID_JAR = r"C:\Users\MiBook\AppData\Local\Android\Sdk\platforms\android-34\android.jar"
BUILD_TOOLS = r"C:\Users\MiBook\AppData\Local\Android\Sdk\build-tools\37.0.0"
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
D8_BAT = os.path.join(BUILD_TOOLS, "d8.bat")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")
ADB = r"C:\Users\MiBook\AppData\Local\Android\Sdk\platform-tools\adb.exe"

def run(cmd, **kwargs):
    print(f"[RUN] {' '.join(str(c) for c in cmd)}")
    try:
        res = subprocess.run(cmd, check=True, text=True, capture_output=True, **kwargs)
        if res.stdout.strip():
            print(res.stdout.strip())
        return res
    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Command failed with exit code {e.returncode}")
        print("STDOUT:\n", e.stdout)
        print("STDERR:\n", e.stderr)
        raise

def build():
    os.makedirs(CLASSES_DIR, exist_ok=True)

    # 1. Compile Java
    print("\n--- [1] Compiling Java classes ---")
    java_files = []
    for root, _, files in os.walk(SRC_DIR):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))
    
    cmd_javac = [
        JAVAC,
        "-source", "17",
        "-target", "17",
        "-cp", ANDROID_JAR,
        "-d", CLASSES_DIR
    ] + java_files
    run(cmd_javac)
    print("Java compilation succeeded!")

    # 2. D8 to DEX
    print("\n--- [2] Converting classes to DEX (D8) ---")
    temp_jar = os.path.join(BUILD_DIR, "temp_classes.jar")
    with zipfile.ZipFile(temp_jar, "w") as z:
        for root, _, files in os.walk(CLASSES_DIR):
            for f in files:
                if f.endswith(".class"):
                    # Exclude stubs from runtime DEX! Stubs are provided by Xposed/LSPosed at runtime!
                    rel = os.path.relpath(os.path.join(root, f), CLASSES_DIR)
                    if not rel.startswith("de" + os.sep + "robv" + os.sep + "android" + os.sep + "xposed"):
                        z.write(os.path.join(root, f), rel)

    env = os.environ.copy()
    env["JAVA_HOME"] = r"D:\Software\Android\Android Studio\jbr"
    cmd_d8 = [
        D8_BAT,
        "--min-api", "26",
        "--output", BUILD_DIR,
        "--lib", ANDROID_JAR,
        temp_jar
    ]
    run(cmd_d8, env=env)
    print("DEX generation succeeded: classes.dex")

    # 3. AAPT2 compile and link
    print("\n--- [3] Compiling resources and linking APK (AAPT2) ---")
    compiled_res = os.path.join(BUILD_DIR, "res.zip")
    run([AAPT2, "compile", "--dir", RES_DIR, "-o", compiled_res])

    unaligned_apk = os.path.join(BUILD_DIR, "unaligned.apk")
    run([
        AAPT2, "link",
        "-I", ANDROID_JAR,
        "--manifest", MANIFEST,
        "-o", unaligned_apk,
        compiled_res
    ])
    print("AAPT2 link succeeded!")

    # 4. Add classes.dex and assets into APK
    print("\n--- [4] Packaging classes.dex and assets into APK ---")
    dex_file = os.path.join(BUILD_DIR, "classes.dex")
    with zipfile.ZipFile(unaligned_apk, "a") as z:
        z.write(dex_file, "classes.dex")
        for root, _, files in os.walk(ASSETS_DIR):
            for f in files:
                full_p = os.path.join(root, f)
                rel_p = os.path.relpath(full_p, ASSETS_DIR)
                z.write(full_p, os.path.join("assets", rel_p))

    # 5. Zipalign
    print("\n--- [5] Aligning APK (Zipalign) ---")
    if os.path.exists(ALIGNED_APK):
        os.remove(ALIGNED_APK)
    run([ZIPALIGN, "-f", "-p", "4", unaligned_apk, ALIGNED_APK])

    # 6. Generate keystore if not exists and Sign APK
    print("\n--- [6] Signing APK (Apksigner) ---")
    if not os.path.exists(KEYSTORE):
        run([
            KEYTOOL, "-genkeypair", "-v",
            "-keystore", KEYSTORE,
            "-alias", "debug",
            "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000",
            "-storepass", "android", "-keypass", "android",
            "-dname", "CN=CarWithVirtualizer,O=Tesla,C=CN"
        ])
    
    if os.path.exists(OUTPUT_APK):
        os.remove(OUTPUT_APK)
    shutil.copy2(ALIGNED_APK, OUTPUT_APK)

    run([
        APKSIGNER, "sign",
        "--ks", KEYSTORE,
        "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--ks-key-alias", "debug",
        OUTPUT_APK
    ], env=env)
    print(f"APK signed successfully: {OUTPUT_APK}")

    # 7. Install to phone
    print("\n--- [7] Installing to device via ADB ---")
    res_dev = subprocess.run([ADB, "devices"], capture_output=True, text=True)
    devices = [line.split()[0] for line in res_dev.stdout.splitlines()[1:] if '\tdevice' in line]
    if devices:
        target_dev = devices[0]
        print(f"Targeting device: {target_dev}")
        run([ADB, "-s", target_dev, "install", "-r", OUTPUT_APK])
        print("Installed to device successfully!")
    else:
        print("No online ADB device found. APK compiled locally.")

if __name__ == "__main__":
    build()
