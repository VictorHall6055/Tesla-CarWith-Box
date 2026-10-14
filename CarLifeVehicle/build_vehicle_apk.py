import os
import sys
import glob
import shutil
import zipfile
import subprocess

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
SRC_DIR = os.path.join(PROJECT_DIR, "src")
RES_DIR = os.path.join(PROJECT_DIR, "res")
ASSETS_DIR = os.path.join(PROJECT_DIR, "assets")
MANIFEST = os.path.join(PROJECT_DIR, "AndroidManifest.xml")
LIBS_DIR = os.path.join(PROJECT_DIR, "libs")
BUILD_DIR = os.path.join(PROJECT_DIR, "build")
GEN_DIR = os.path.join(BUILD_DIR, "gen")
CLASSES_DIR = os.path.join(BUILD_DIR, "classes")
OUTPUT_APK = os.path.join(PROJECT_DIR, "CarLifeVehicle.apk")
ALIGNED_APK = os.path.join(BUILD_DIR, "aligned.apk")
KEYSTORE = os.path.join(PROJECT_DIR, "debug.keystore")

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
    print(f"[RUN] {' '.join(str(c) for c in cmd[:5])}...")
    try:
        res = subprocess.run(cmd, check=True, text=True, capture_output=True, **kwargs)
        if res.stdout.strip():
            print(res.stdout.strip())
        return res
    except subprocess.CalledProcessError as e:
        print(f"Error running command: {' '.join(str(c) for c in cmd)}")
        print("STDOUT:", e.stdout)
        print("STDERR:", e.stderr)
        raise

def main():
    os.makedirs(BUILD_DIR, exist_ok=True)
    os.makedirs(GEN_DIR, exist_ok=True)
    os.makedirs(CLASSES_DIR, exist_ok=True)

    # 1. Compile resources with AAPT2
    print("[1/7] Compiling resources with aapt2...")
    compiled_res = os.path.join(BUILD_DIR, "resources.zip")
    run([AAPT2, "compile", "--dir", RES_DIR, "-o", compiled_res])

    # 2. Link resources with AAPT2
    print("[2/7] Linking resources with aapt2...")
    res_apk = os.path.join(BUILD_DIR, "res.apk")
    aapt_link_cmd = [
        AAPT2, "link",
        "-I", ANDROID_JAR,
        "--manifest", MANIFEST,
        "--java", GEN_DIR,
        "-o", res_apk,
        compiled_res,
        "--auto-add-overlay"
    ]
    if os.path.isdir(ASSETS_DIR):
        aapt_link_cmd.extend(["-A", ASSETS_DIR])
    run(aapt_link_cmd)

    AIDL = os.path.join(BUILD_TOOLS, "aidl.exe")
    FRAMEWORK_AIDL = os.path.join(os.path.dirname(ANDROID_JAR), "framework.aidl")

    # 3. Compile AIDL files
    print("[3/8] Compiling AIDL files...")
    aidl_files = []
    for root, _, files in os.walk(SRC_DIR):
        for f in files:
            if f.endswith(".aidl"):
                aidl_files.append(os.path.join(root, f))
    for aidl in aidl_files:
        run([AIDL, f"-p{FRAMEWORK_AIDL}", f"-I{SRC_DIR}", f"-o{GEN_DIR}", aidl])

    # 4. Collect Java source files
    print("[4/8] Finding Java sources...")
    src_files = []
    for root, _, files in os.walk(SRC_DIR):
        for f in files:
            if f.endswith(".java"):
                src_files.append(os.path.join(root, f))
    for root, _, files in os.walk(GEN_DIR):
        for f in files:
            if f.endswith(".java"):
                src_files.append(os.path.join(root, f))
    print(f"Found {len(src_files)} Java files.")

    # Collect jars in libs/
    lib_jars = [os.path.join(LIBS_DIR, f) for f in os.listdir(LIBS_DIR) if f.endswith(".jar")]
    classpath = os.pathsep.join([ANDROID_JAR] + lib_jars)

    # Write sources list file
    sources_txt = os.path.join(BUILD_DIR, "sources.txt")
    with open(sources_txt, "w", encoding="utf-8") as f:
        for s in src_files:
            f.write(s.replace("\\", "/") + "\n")

    # 4. Compile with javac
    print("[4/7] Compiling Java code with javac...")
    javac_cmd = [
        JAVAC,
        "-encoding", "UTF-8",
        "-source", "8",
        "-target", "8",
        "-cp", classpath,
        "-d", CLASSES_DIR,
        f"@{sources_txt}"
    ]
    run(javac_cmd)

    # 5. Package classes and libs into DEX using D8
    print("[5/7] Converting class files to DEX with d8...")
    # Zip classes to avoid long command line
    classes_jar = os.path.join(BUILD_DIR, "temp_classes.jar")
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(CLASSES_DIR):
            for file in files:
                full_path = os.path.join(root, file)
                rel_path = os.path.relpath(full_path, CLASSES_DIR)
                zf.write(full_path, rel_path)

    d8_cmd = [
        D8_BAT,
        "--output", BUILD_DIR,
        "--lib", ANDROID_JAR,
        classes_jar
    ] + lib_jars
    run(d8_cmd)

    # 6. Merge DEX into APK
    print("[6/7] Merging DEX into base APK...")
    unaligned_apk = os.path.join(BUILD_DIR, "unaligned.apk")
    shutil.copyfile(res_apk, unaligned_apk)

    with zipfile.ZipFile(unaligned_apk, "a") as zf:
        for dex_file in glob.glob(os.path.join(BUILD_DIR, "classes*.dex")):
            zf.write(dex_file, os.path.basename(dex_file))

    # 7. ZipAlign and Sign
    print("[7/7] Zipalign and signing APK...")
    if os.path.exists(ALIGNED_APK):
        os.remove(ALIGNED_APK)
    run([ZIPALIGN, "-f", "-p", "4", unaligned_apk, ALIGNED_APK])

    # Ensure keystore exists
    if not os.path.exists(KEYSTORE):
        run([
            KEYTOOL, "-genkeypair",
            "-keystore", KEYSTORE,
            "-storepass", "android",
            "-alias", "androiddebugkey",
            "-keypass", "android",
            "-dname", "CN=Android Debug,O=Android,C=US",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000"
        ])

    run([
        APKSIGNER, "sign",
        "--ks", KEYSTORE,
        "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--out", OUTPUT_APK,
        ALIGNED_APK
    ])

    print(f"\n[SUCCESS] Generated CarLifeVehicle APK: {OUTPUT_APK}")
    print(f"File size: {os.path.getsize(OUTPUT_APK)} bytes")

if __name__ == "__main__":
    main()
