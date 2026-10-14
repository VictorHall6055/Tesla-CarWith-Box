import os
import zipfile
import subprocess

MODULE_DIR = r"C:\Users\MiBook\.gemini\antigravity\scratch\tesla_carwith_box"
OUTPUT_ZIP = r"C:\Users\MiBook\.gemini\antigravity\scratch\tesla_carwith_box.zip"
ADB = r"C:\Users\MiBook\AppData\Local\Android\Sdk\platform-tools\adb.exe"

print("1. Packaging tesla_carwith_box.zip...")
if os.path.exists(OUTPUT_ZIP):
    try:
        os.remove(OUTPUT_ZIP)
    except Exception as e:
        print("Note on remove:", e)

with zipfile.ZipFile(OUTPUT_ZIP, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(MODULE_DIR):
        for f in files:
            full_path = os.path.join(root, f)
            rel_path = os.path.relpath(full_path, MODULE_DIR)
            z.write(full_path, rel_path)

size_kb = os.path.getsize(OUTPUT_ZIP) / 1024
print(f"Zip created successfully: {OUTPUT_ZIP} ({size_kb:.1f} KB)")

print("2. Detecting connected ADB devices...")
res_dev = subprocess.run([ADB, "devices"], capture_output=True, text=True)
print(res_dev.stdout.strip())

devices = [line.split()[0] for line in res_dev.stdout.splitlines()[1:] if '\tdevice' in line]
if devices:
    target_dev = devices[0]
    print(f"Targeting device: {target_dev}")
    print("3. Pushing to phone /sdcard/Download/tesla_carwith_box.zip...")
    res = subprocess.run([ADB, "-s", target_dev, "push", OUTPUT_ZIP, "/sdcard/Download/tesla_carwith_box.zip"], capture_output=True, text=True)
    print("ADB Push zip:", res.stdout.strip())
    
    veh_apk = r"C:\Users\MiBook\.gemini\antigravity\scratch\CarLifeVehicle\CarLifeVehicle.apk"
    if os.path.exists(veh_apk):
        res2 = subprocess.run([ADB, "-s", target_dev, "push", veh_apk, "/sdcard/Download/CarLifeVehicle.apk"], capture_output=True, text=True)
        print("ADB Push CarLifeVehicle.apk:", res2.stdout.strip())

    virt_apk = r"C:\Users\MiBook\.gemini\antigravity\scratch\CarWithVirtualizer\CarWithVirtualizer.apk"
    if os.path.exists(virt_apk):
        res3 = subprocess.run([ADB, "-s", target_dev, "push", virt_apk, "/sdcard/Download/CarWithVirtualizer.apk"], capture_output=True, text=True)
        print("ADB Push CarWithVirtualizer.apk:", res3.stdout.strip())
else:
    print("No ADB device online. Zip ready locally.")

