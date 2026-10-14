import os
import subprocess
import zipfile
import glob

PROJECT_DIR = r"C:\Users\MiBook\.gemini\antigravity\scratch\tesla_java_bridge"
SRC_JAVA = os.path.join(PROJECT_DIR, "src", "com", "tesla", "carwith", "Main.java")
CLASSES_DIR = os.path.join(PROJECT_DIR, "build", "classes")
BUILD_DIR = os.path.join(PROJECT_DIR, "build")
OUTPUT_JAR = os.path.join(PROJECT_DIR, "tesla_bridge.jar")

JAVAC = r"D:\Software\Android\Android Studio\jbr\bin\javac.exe"
D8_BAT = r"C:\Users\MiBook\AppData\Local\Android\Sdk\build-tools\37.0.0\d8.bat"
ANDROID_JAR = r"C:\Users\MiBook\AppData\Local\Android\Sdk\platforms\android-34\android.jar"

os.makedirs(CLASSES_DIR, exist_ok=True)

print("1. Compiling Main.java with javac...")
subprocess.run([
    JAVAC,
    "-source", "17",
    "-target", "17",
    "-cp", ANDROID_JAR,
    "-d", CLASSES_DIR,
    SRC_JAVA
], check=True)

print("2. Translating to DEX with D8...")
class_files = glob.glob(os.path.join(CLASSES_DIR, "com", "tesla", "carwith", "*.class"))
print(f"Found {len(class_files)} class files: {[os.path.basename(f) for f in class_files]}")

env = os.environ.copy()
env["JAVA_HOME"] = r"D:\Software\Android\Android Studio\jbr"
subprocess.run([
    D8_BAT,
    "--min-api", "26",
    "--output", BUILD_DIR,
    "--lib", ANDROID_JAR,
    *class_files
], check=True, env=env)

print("3. Packaging into tesla_bridge.jar...")
dex_file = os.path.join(BUILD_DIR, "classes.dex")
with zipfile.ZipFile(OUTPUT_JAR, "w") as z:
    z.write(dex_file, "classes.dex")

print(f"Build complete! Saved to {OUTPUT_JAR}")
