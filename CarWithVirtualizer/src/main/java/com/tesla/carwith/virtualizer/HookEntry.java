package com.tesla.carwith.virtualizer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.ParcelUuid;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "CarWithVirtualizer";
    public static final String VIRTUAL_MAC = "00:11:22:33:44:55";
    public static final String DEFAULT_VEHICLE_NAME = "Xiaomi SU7-Tesla";

    public static void log(String msg) {
        android.util.Log.i(TAG, msg);
        try {
            XposedBridge.log(msg);
        } catch (Throwable ignored) {}
    }

    public static void log(Throwable t) {
        android.util.Log.e(TAG, "Hook error", t);
        try {
            XposedBridge.log(t);
        } catch (Throwable ignored) {}
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.miui.carlink".equals(lpparam.packageName) &&
            !"com.baidu.carlife.xiaomi".equals(lpparam.packageName) &&
            !"com.baidu.carlifevehicle".equals(lpparam.packageName) &&
            !"com.android.systemui".equals(lpparam.packageName) &&
            !"miui.systemui.plugin".equals(lpparam.packageName) &&
            !"com.milink.service".equals(lpparam.packageName)) {
            return;
        }

        log("[+] CarWithVirtualizer successfully injected into: " + lpparam.packageName +
                         " (" + lpparam.processName + ")");

        if ("com.android.systemui".equals(lpparam.packageName) ||
            "miui.systemui.plugin".equals(lpparam.packageName) ||
            "com.milink.service".equals(lpparam.packageName)) {
            hookSystemUiSoundFlow(lpparam);
            return;
        }

        if ("com.baidu.carlifevehicle".equals(lpparam.packageName)) {
            hookCarlifeVehicleHeadless(lpparam);
            return;
        }

        if ("com.baidu.carlife.xiaomi".equals(lpparam.packageName)) {
            hookCarlifeHeadlessMode(lpparam);
            hookPermissionsAndPreferences(lpparam);
            hookCarlifeXiaomiAudio(lpparam);
            return;
        }

        if ("com.miui.carlink".equals(lpparam.packageName)) {
            hookVirtualDisplayCapture(lpparam);
            hookBluetoothDiscovery(lpparam);
            hookBluetoothDevice(lpparam);
            hookBluetoothSocket(lpparam);
            hookProfileConnectedDevices(lpparam);
            hookVehicleInfoUtils(lpparam);
            hookWifiHotspotBypass(lpparam);
            hookCarlifeActivities(lpparam);
            hookPermissionsAndPreferences(lpparam);
            hookP2pAndStateMachine(lpparam);
            hookDataBus(lpparam);
            hookSocketRedirection(lpparam);
            hookDialogSuppression(lpparam);
            hookAutoLaunchCarLifeVehicle(lpparam);
        }
    }

    public static String getVehicleName() {
        try {
            File f = new File("/data/local/tmp/tesla_vehicle_name");
            if (f.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(f));
                String line = reader.readLine();
                reader.close();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Throwable ignored) {}
        return DEFAULT_VEHICLE_NAME;
    }

    private static void saveDisplayId(int id) {
        try {
            File f = new File("/data/local/tmp/tesla_logical_display_id");
            FileWriter fw = new FileWriter(f, false);
            fw.write(String.valueOf(id) + "\n");
            fw.flush();
            fw.close();
            f.setReadable(true, false);
            f.setWritable(true, false);
            log("[DisplayManager] Successfully wrote tesla_logical_display_id: " + id);

            File oldF = new File("/data/local/tmp/tesla_display_id");
            if (oldF.exists()) {
                oldF.delete();
            }
        } catch (Throwable t) {
            log("[-] Failed to write tesla_logical_display_id: " + t.getMessage());
        }
    }

    // =========================================================================
    // 0. 虚拟车载显示屏捕捉 (VirtualDisplay capture)
    // =========================================================================
    private void hookVirtualDisplayCapture(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedBridge.hookAllMethods(
                DisplayManager.class,
                "createVirtualDisplay",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (result instanceof VirtualDisplay) {
                            VirtualDisplay vd = (VirtualDisplay) result;
                            if (vd.getDisplay() != null) {
                                int id = vd.getDisplay().getDisplayId();
                                String name = vd.getDisplay().getName();
                                log("[DisplayManager] VirtualDisplay created! ID=" + id + ", name=" + name);
                                saveDisplayId(id);
                            }
                        }
                    }
                }
            );
            log("[+] DisplayManager.createVirtualDisplay hook installed.");
        } catch (Throwable t) {
            log("[-] Error hooking DisplayManager.createVirtualDisplay: " + t.getMessage());
        }
    }

    // =========================================================================
    // 1. 模拟车载 BLE 广播与蓝牙发现信号
    // =========================================================================
    private void hookBluetoothDiscovery(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook BluetoothLeScanner.startScan(List, ScanSettings, ScanCallback)
            XposedHelpers.findAndHookMethod(
                BluetoothLeScanner.class,
                "startScan",
                List.class,
                ScanSettings.class,
                ScanCallback.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final ScanCallback callback = (ScanCallback) param.args[2];
                        if (callback != null) {
                            log("[BLE] CarWith started filtered BLE scan, injecting synthetic vehicle beacon...");
                            injectVirtualBeacon(callback);
                        }
                    }
                }
            );

            // Hook BluetoothLeScanner.startScan(ScanCallback)
            XposedHelpers.findAndHookMethod(
                BluetoothLeScanner.class,
                "startScan",
                ScanCallback.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final ScanCallback callback = (ScanCallback) param.args[0];
                        if (callback != null) {
                            log("[BLE] CarWith started generic BLE scan, injecting synthetic vehicle beacon...");
                            injectVirtualBeacon(callback);
                        }
                    }
                }
            );

            // Hook BluetoothAdapter.getBondedDevices
            XposedHelpers.findAndHookMethod(
                BluetoothAdapter.class,
                "getBondedDevices",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        @SuppressWarnings("unchecked")
                        Set<BluetoothDevice> original = (Set<BluetoothDevice>) param.getResult();
                        Set<BluetoothDevice> modified = new HashSet<>(original != null ? original : new HashSet<>());
                        try {
                            BluetoothAdapter adapter = (BluetoothAdapter) param.thisObject;
                            BluetoothDevice mockDevice = adapter.getRemoteDevice(VIRTUAL_MAC);
                            modified.add(mockDevice);
                            param.setResult(modified);
                        } catch (Exception e) {
                            log(e);
                        }
                    }
                }
            );

            // 保留系统原生 BluetoothAdapter 连接状态，使澎湃OS4系统控制中心播控卡片真实感知真实蓝牙状态，实现自由切换
        } catch (Throwable t) {
            log("[-] Error hooking Bluetooth discovery: " + t.getMessage());
        }
    }

    private void hookBluetoothDevice(final XC_LoadPackage.LoadPackageParam lpparam) {
        final boolean isSystemUi = "com.android.systemui".equals(lpparam.packageName) ||
                                   "miui.systemui.plugin".equals(lpparam.packageName) ||
                                   "com.milink.service".equals(lpparam.packageName);
        final String displayName = isSystemUi ? "虚拟车机" : getVehicleName();
        try {
            // Hook BluetoothDevice.getName()
            XposedHelpers.findAndHookMethod(
                BluetoothDevice.class,
                "getName",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothDevice dev = (BluetoothDevice) param.thisObject;
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            param.setResult(displayName);
                        }
                    }
                }
            );

            // Hook BluetoothDevice.getAlias()
            XposedHelpers.findAndHookMethod(
                BluetoothDevice.class,
                "getAlias",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothDevice dev = (BluetoothDevice) param.thisObject;
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            param.setResult(displayName);
                        }
                    }
                }
            );

            // Hook BluetoothDevice.getType()
            XposedHelpers.findAndHookMethod(
                BluetoothDevice.class,
                "getType",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothDevice dev = (BluetoothDevice) param.thisObject;
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            param.setResult(BluetoothDevice.DEVICE_TYPE_DUAL);
                        }
                    }
                }
            );

            // Hook BluetoothDevice.isConnected()
            XposedHelpers.findAndHookMethod(
                BluetoothDevice.class,
                "isConnected",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothDevice dev = (BluetoothDevice) param.thisObject;
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            param.setResult(true);
                        }
                    }
                }
            );

            // Hook BluetoothDevice.getBondState()
            XposedHelpers.findAndHookMethod(
                BluetoothDevice.class,
                "getBondState",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothDevice dev = (BluetoothDevice) param.thisObject;
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            param.setResult(12); // BluetoothDevice.BOND_BONDED
                        }
                    }
                }
            );

            // Hook BluetoothDevice.getBluetoothClass()
            XposedHelpers.findAndHookMethod(
                BluetoothDevice.class,
                "getBluetoothClass",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothDevice dev = (BluetoothDevice) param.thisObject;
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            try {
                                Constructor<?> ctor = android.bluetooth.BluetoothClass.class.getDeclaredConstructor(int.class);
                                ctor.setAccessible(true);
                                Object carClass = ctor.newInstance(0x0404); // CAR_AUDIO
                                param.setResult(carClass);
                            } catch (Throwable t) {
                                log(t);
                            }
                        }
                    }
                }
            );

            // Hook BluetoothDevice.getUuids()
            try {
                XposedHelpers.findAndHookMethod(
                    BluetoothDevice.class,
                    "getUuids",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            BluetoothDevice dev = (BluetoothDevice) param.thisObject;
                            if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                                param.setResult(new ParcelUuid[] {
                                    ParcelUuid.fromString("0000110b-0000-1000-8000-00805f9b34fb"), // Audio Sink
                                    ParcelUuid.fromString("0000110d-0000-1000-8000-00805f9b34fb"), // Advanced Audio Distribution
                                    ParcelUuid.fromString("0000111e-0000-1000-8000-00805f9b34fb"), // Handsfree
                                    ParcelUuid.fromString("0000110e-0000-1000-8000-00805f9b34fb")  // AVRCP
                                });
                            }
                        }
                    }
                );
            } catch (Throwable ignored) {}

            // Hook BluetoothClass.getDeviceClass() & getMajorDeviceClass()
            try {
                XposedHelpers.findAndHookMethod(
                    android.bluetooth.BluetoothClass.class,
                    "getDeviceClass",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            int val = (Integer) param.getResult();
                            if (val == 0x0404 || val == 0) {
                                param.setResult(0x0404); // CAR_AUDIO
                            }
                        }
                    }
                );

                XposedHelpers.findAndHookMethod(
                    android.bluetooth.BluetoothClass.class,
                    "getMajorDeviceClass",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            int val = (Integer) param.getResult();
                            if (val == 0x0400 || val == 0) {
                                param.setResult(0x0400); // Major VEHICLE (1024)
                            }
                        }
                    }
                );
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            log("[-] Error hooking BluetoothDevice: " + t.getMessage());
        }
    }

    private void hookProfileConnectedDevices(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> a2dpClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothA2dp", lpparam.classLoader);
            if (a2dpClass != null) {
                XposedHelpers.findAndHookMethod(
                    a2dpClass,
                    "getConnectedDevices",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            @SuppressWarnings("unchecked")
                            List<BluetoothDevice> original = (List<BluetoothDevice>) param.getResult();
                            List<BluetoothDevice> modified = new ArrayList<>(original != null ? original : new ArrayList<>());
                            try {
                                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                                if (adapter != null) {
                                    BluetoothDevice mockDev = adapter.getRemoteDevice(VIRTUAL_MAC);
                                    boolean exists = false;
                                    for (BluetoothDevice d : modified) {
                                        if (d != null && VIRTUAL_MAC.equalsIgnoreCase(d.getAddress())) {
                                            exists = true;
                                            break;
                                        }
                                    }
                                    if (!exists) {
                                        modified.add(mockDev);
                                        param.setResult(modified);
                                    }
                                }
                            } catch (Throwable t) {
                                log(t);
                            }
                        }
                    }
                );

                XposedHelpers.findAndHookMethod(
                    a2dpClass,
                    "getConnectionState",
                    BluetoothDevice.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            BluetoothDevice dev = (BluetoothDevice) param.args[0];
                            if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                                param.setResult(android.bluetooth.BluetoothProfile.STATE_CONNECTED);
                            }
                        }
                    }
                );
            }

            Class<?> headsetClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothHeadset", lpparam.classLoader);
            if (headsetClass != null) {
                XposedHelpers.findAndHookMethod(
                    headsetClass,
                    "getConnectedDevices",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            @SuppressWarnings("unchecked")
                            List<BluetoothDevice> original = (List<BluetoothDevice>) param.getResult();
                            List<BluetoothDevice> modified = new ArrayList<>(original != null ? original : new ArrayList<>());
                            try {
                                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                                if (adapter != null) {
                                    BluetoothDevice mockDev = adapter.getRemoteDevice(VIRTUAL_MAC);
                                    boolean exists = false;
                                    for (BluetoothDevice d : modified) {
                                        if (d != null && VIRTUAL_MAC.equalsIgnoreCase(d.getAddress())) {
                                            exists = true;
                                            break;
                                        }
                                    }
                                    if (!exists) {
                                        modified.add(mockDev);
                                        param.setResult(modified);
                                    }
                                }
                            } catch (Throwable t) {
                                log(t);
                            }
                        }
                    }
                );

                XposedHelpers.findAndHookMethod(
                    headsetClass,
                    "getConnectionState",
                    BluetoothDevice.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            BluetoothDevice dev = (BluetoothDevice) param.args[0];
                            if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                                param.setResult(android.bluetooth.BluetoothProfile.STATE_CONNECTED);
                            }
                        }
                    }
                );
            }
        } catch (Throwable t) {
            log("[-] Error hooking profile connected devices: " + t.getMessage());
        }
    }

    // =========================================================================
    // 2. 模拟蓝牙 RFCOMM 通讯短路 (防止物理车机未开启 RFCOMM 导致的连接中断)
    // =========================================================================
    private void hookBluetoothSocket(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                BluetoothSocket.class,
                "connect",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothSocket sock = (BluetoothSocket) param.thisObject;
                        BluetoothDevice dev = sock.getRemoteDevice();
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            log("[BluetoothSocket] Intercepted RFCOMM connect() to " + dev.getAddress() + ", returning success.");
                            param.setResult(null);
                        }
                    }
                }
            );

            XposedHelpers.findAndHookMethod(
                BluetoothSocket.class,
                "isConnected",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothSocket sock = (BluetoothSocket) param.thisObject;
                        BluetoothDevice dev = sock.getRemoteDevice();
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            param.setResult(true);
                        }
                    }
                }
            );

            XposedHelpers.findAndHookMethod(
                BluetoothSocket.class,
                "getInputStream",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothSocket sock = (BluetoothSocket) param.thisObject;
                        BluetoothDevice dev = sock.getRemoteDevice();
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            param.setResult(new ByteArrayInputStream(new byte[0]));
                        }
                    }
                }
            );

            XposedHelpers.findAndHookMethod(
                BluetoothSocket.class,
                "getOutputStream",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        BluetoothSocket sock = (BluetoothSocket) param.thisObject;
                        BluetoothDevice dev = sock.getRemoteDevice();
                        if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                            param.setResult(new ByteArrayOutputStream());
                        }
                    }
                }
            );

            log("[+] BluetoothSocket hooks installed for CarWith.");
        } catch (Throwable t) {
            log("[-] Error hooking BluetoothSocket: " + t.getMessage());
        }
    }

    // =========================================================================
    // 3. 车机设备工具类 (com.carwith.common.utils.q)
    // =========================================================================
    private void hookVehicleInfoUtils(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> qClass = XposedHelpers.findClassIfExists("com.carwith.common.utils.q", lpparam.classLoader);
            if (qClass != null) {
                // Hook q.g() -> 返回 BluetoothDevice
                try {
                    XposedHelpers.findAndHookMethod(
                        qClass,
                        "g",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.getResult() == null) {
                                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                                    if (adapter != null) {
                                        param.setResult(adapter.getRemoteDevice(VIRTUAL_MAC));
                                    }
                                }
                            }
                        }
                    );
                } catch (Throwable ignored) {}

                // Hook q.h() -> 返回车辆名称
                try {
                    XposedHelpers.findAndHookMethod(
                        qClass,
                        "h",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object res = param.getResult();
                                if (res == null || (res instanceof String && ((String) res).isEmpty())) {
                                    param.setResult(getVehicleName());
                                }
                            }
                        }
                    );
                } catch (Throwable ignored) {}

                // Hook q.i() -> 返回车机蓝牙名称 (CarlifeApConnectActivity.h1 核心校验，为空直接弹toast中断!)
                try {
                    XposedHelpers.findAndHookMethod(
                        qClass,
                        "i",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object res = param.getResult();
                                if (res == null || (res instanceof String && ((String) res).isEmpty())) {
                                    param.setResult(getVehicleName());
                                }
                            }
                        }
                    );
                } catch (Throwable ignored) {}

                // Hook q.j() -> 返回车机名称或MAC
                try {
                    XposedHelpers.findAndHookMethod(
                        qClass,
                        "j",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object res = param.getResult();
                                if (res == null || (res instanceof String && ((String) res).isEmpty())) {
                                    param.setResult(getVehicleName());
                                }
                            }
                        }
                    );
                } catch (Throwable ignored) {}

                // Hook q.k() -> 返回已连接设备列表 List<BluetoothDevice> (BaseCarlifeActivity.O0 核心校验!)
                try {
                    XposedBridge.hookAllMethods(
                        qClass,
                        "k",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object res = param.getResult();
                                if (res instanceof List) {
                                    @SuppressWarnings("unchecked")
                                    List<BluetoothDevice> list = (List<BluetoothDevice>) res;
                                    List<BluetoothDevice> resList = new ArrayList<>(list != null ? list : new ArrayList<>());
                                    if (resList.isEmpty()) {
                                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                                        if (adapter != null) {
                                            resList.add(adapter.getRemoteDevice(VIRTUAL_MAC));
                                            param.setResult(resList);
                                        }
                                    }
                                }
                            }
                        }
                    );
                } catch (Throwable ignored) {}

                // Hook q.l() -> 返回设备列表
                try {
                    XposedHelpers.findAndHookMethod(
                        qClass,
                        "l",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                @SuppressWarnings("unchecked")
                                List<BluetoothDevice> list = (List<BluetoothDevice>) param.getResult();
                                List<BluetoothDevice> resList = new ArrayList<>(list != null ? list : new ArrayList<>());
                                if (resList.isEmpty()) {
                                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                                    if (adapter != null) {
                                        resList.add(adapter.getRemoteDevice(VIRTUAL_MAC));
                                        param.setResult(resList);
                                    }
                                }
                            }
                        }
                    );
                } catch (Throwable ignored) {}

                // Hook q.o(BluetoothDevice) -> 验证经典蓝牙已连接
                try {
                    XposedHelpers.findAndHookMethod(
                        qClass,
                        "o",
                        BluetoothDevice.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        }
                    );
                } catch (Throwable ignored) {}

                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) {
                        XposedHelpers.setStaticObjectField(qClass, "b", adapter.getRemoteDevice(VIRTUAL_MAC));
                    }
                } catch (Throwable ignored) {}

                log("[+] com.carwith.common.utils.q comprehensive hooks installed.");
            }
        } catch (Throwable t) {
            log("[-] Error hooking com.carwith.common.utils.q: " + t.getMessage());
        }
    }

    // =========================================================================
    // 4. 识别与放行 Wi-Fi 热点连接状态 (确保热点连接向导立即识别热点开启)
    // =========================================================================
    private void hookWifiHotspotBypass(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook WifiManager.isWifiApEnabled() -> 返回 true
            XposedHelpers.findAndHookMethod(
                WifiManager.class,
                "isWifiApEnabled",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(true);
                    }
                }
            );

            // Hook WifiManager.getWifiApState() -> 13 = WIFI_AP_STATE_ENABLED
            XposedHelpers.findAndHookMethod(
                WifiManager.class,
                "getWifiApState",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(13);
                    }
                }
            );

            // Hook WifiManager.isWifiEnabled() -> true
            XposedHelpers.findAndHookMethod(
                WifiManager.class,
                "isWifiEnabled",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(true);
                    }
                }
            );

            // Hook CarWith 内部专属热点检测工具类 (com.carwith.common.utils.b3: e, f, g, d)
            try {
                Class<?> b3Class = XposedHelpers.findClassIfExists("com.carwith.common.utils.b3", lpparam.classLoader);
                if (b3Class != null) {
                    XposedBridge.hookAllMethods(
                        b3Class,
                        "e",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        }
                    );
                    XposedBridge.hookAllMethods(
                        b3Class,
                        "f",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        }
                    );
                    XposedBridge.hookAllMethods(
                        b3Class,
                        "g",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        }
                    );
                    XposedBridge.hookAllMethods(
                        b3Class,
                        "d",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        }
                    );
                    log("[+] Hooked com.carwith.common.utils.b3 (e, f, g, d) -> true (Hotspot recognized)");
                }
            } catch (Throwable ignored) {}

            // Hook EasyConn 热点检测工具类 (net.easyconn.carman.utils.WifiUtls.isWifiApOpen)
            try {
                Class<?> ecWifiClass = XposedHelpers.findClassIfExists("net.easyconn.carman.utils.WifiUtls", lpparam.classLoader);
                if (ecWifiClass != null) {
                    XposedHelpers.findAndHookMethod(
                        ecWifiClass,
                        "isWifiApOpen",
                        Context.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        }
                    );
                }
            } catch (Throwable ignored) {}

            log("[+] Wi-Fi Hotspot status hooks installed for CarWith.");
        } catch (Throwable t) {
            log("[-] Error hooking WifiManager: " + t.getMessage());
        }
    }

    // =========================================================================
    // 4.1 CarLife 页面阻断绕过与连接流程接管
    // =========================================================================
    private void hookCarlifeActivities(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook BaseCarlifeActivity.O0(Activity) -> false (防止判定为蓝牙断开 "Bluetooth connection disconnected .")
            Class<?> baseCarlife = XposedHelpers.findClassIfExists(
                "com.carwith.launcher.settings.phone.carlife.BaseCarlifeActivity",
                lpparam.classLoader
            );
            if (baseCarlife != null) {
                XposedBridge.hookAllMethods(
                    baseCarlife,
                    "O0",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(false);
                            log("[BaseCarlifeActivity] O0() intercepted -> returning false (connected)");
                        }
                    }
                );

                // Hook L0() -> 车辆名称
                try {
                    XposedBridge.hookAllMethods(
                        baseCarlife,
                        "L0",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(getVehicleName());
                            }
                        }
                    );
                    log("[+] BaseCarlifeActivity hooks installed.");
                } catch (Throwable ignored) {}
            }

            // Hook CarLifeWlanConnectActivity: 追踪用户点击并强制绕过定位权限检查
            try {
                Class<?> wlanActivity = XposedHelpers.findClassIfExists(
                    "com.carwith.launcher.settings.phone.carlife.CarLifeWlanConnectActivity",
                    lpparam.classLoader
                );
                if (wlanActivity != null) {
                    XposedBridge.hookAllMethods(
                        wlanActivity,
                        "h1",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                log("[CarLifeWlanConnectActivity] h1() '开始连接' tapped! Forcing hasPermission = true");
                                if (param.args.length > 0) {
                                    param.args[0] = true;
                                }
                            }
                        }
                    );

                    XposedBridge.hookAllMethods(
                        wlanActivity,
                        "onActivityResult",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args.length >= 2) {
                                    log("[CarLifeWlanConnectActivity] onActivityResult intercepted! Forcing RESULT_OK");
                                    param.args[1] = -1; // Activity.RESULT_OK
                                }
                            }
                        }
                    );
                    log("[+] CarLifeWlanConnectActivity hooks installed.");
                }
            } catch (Throwable ignored) {}

            // Hook CarlifeApConnectActivity: 追踪用户点击与连接状态
            try {
                Class<?> apActivity = XposedHelpers.findClassIfExists(
                    "com.carwith.launcher.settings.phone.carlife.CarlifeApConnectActivity",
                    lpparam.classLoader
                );
                if (apActivity != null) {
                    XposedBridge.hookAllMethods(
                        apActivity,
                        "h1",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                log("[CarlifeApConnectActivity] h1() '开始连接' tapped! Forcing hasPermission = true");
                                if (param.args.length > 0) {
                                    param.args[0] = true;
                                }
                            }
                        }
                    );

                    XposedBridge.hookAllMethods(
                        apActivity,
                        "onActivityResult",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args.length >= 2) {
                                    log("[CarlifeApConnectActivity] onActivityResult intercepted! Forcing RESULT_OK");
                                    param.args[1] = -1; // Activity.RESULT_OK
                                }
                            }
                        }
                    );

                    XposedBridge.hookAllMethods(
                        apActivity,
                        "b1",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                log("[CarlifeApConnectActivity] b1() realStartCarLifeConnect dispatched: carBtName=" + param.args[0]);
                            }
                        }
                    );
                    log("[+] CarlifeApConnectActivity hooks installed.");
                }
            } catch (Throwable ignored) {}

            // Hook k9.d (CarlifeConnectManager.Y) -> 确保 carBtName 永不为空
            try {
                Class<?> k9dClass = XposedHelpers.findClassIfExists("k9.d", lpparam.classLoader);
                if (k9dClass != null) {
                    XposedBridge.hookAllMethods(
                        k9dClass,
                        "Y",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args.length >= 2) {
                                    Object carBt = param.args[1];
                                    if (carBt == null || carBt.toString().trim().isEmpty()) {
                                        param.args[1] = getVehicleName();
                                    }
                                    log("[k9.d.Y] startCarLifeConnect intercepted! mode=" + param.args[0] + ", carBtName=" + param.args[1]);
                                }
                            }
                        }
                    );
                    log("[+] k9.d.Y hook installed.");
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            log("[-] Error hooking Carlife Activities: " + t.getMessage());
        }
    }

    // =========================================================================
    // 4.2 权限与协议许可自动放行 (防止协议授权或后台定位弹窗阻塞)
    // =========================================================================
    private void hookPermissionsAndPreferences(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook SharedPreferences.getBoolean("is_agree_carlife", ...) -> true
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.SharedPreferencesImpl",
                    lpparam.classLoader,
                    "getBoolean",
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String key = (String) param.args[0];
                            if ("is_agree_carlife".equals(key)) {
                                param.setResult(true);
                            }
                        }
                    }
                );
            } catch (Throwable ignored) {}

            // Hook ContextWrapper.checkSelfPermission for ACCESS_BACKGROUND_LOCATION -> 0 (PERMISSION_GRANTED)
            try {
                XposedBridge.hookAllMethods(
                    android.content.ContextWrapper.class,
                    "checkSelfPermission",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String perm = (String) param.args[0];
                            if (perm != null && (perm.contains("LOCATION") || perm.contains("BLUETOOTH"))) {
                                param.setResult(0); // PackageManager.PERMISSION_GRANTED
                            }
                        }
                    }
                );
            } catch (Throwable ignored) {}

            // Hook com.carwith.common.utils.c2 (PermissionUtils) -> 全权限放行
            try {
                Class<?> c2Class = XposedHelpers.findClassIfExists("com.carwith.common.utils.c2", lpparam.classLoader);
                if (c2Class != null) {
                    XposedBridge.hookAllMethods(
                        c2Class,
                        "a",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        }
                    );
                    XposedBridge.hookAllMethods(
                        c2Class,
                        "b",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        }
                    );
                    XposedBridge.hookAllMethods(
                        c2Class,
                        "c",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(true);
                            }
                        }
                    );
                    XposedBridge.hookAllMethods(
                        c2Class,
                        "d",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(new ArrayList<>());
                            }
                        }
                    );
                    log("[+] Hooked com.carwith.common.utils.c2 (PermissionUtils) -> all permissions granted");
                }
            } catch (Throwable ignored) {}
            log("[+] Permissions & Preferences bypass installed.");
        } catch (Throwable t) {
            log("[-] Error hooking Permissions: " + t.getMessage());
        }
    }

    // =========================================================================
    // 5. 虚拟 P2P 协商短路与状态机就绪注入
    // =========================================================================
    private void hookP2pAndStateMachine(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook u9.d.a(NetworkInterface) -> "127.0.0.1" (防止 local address 为 null 导致 give up)
            try {
                Class<?> u9dClass = XposedHelpers.findClassIfExists("u9.d", lpparam.classLoader);
                if (u9dClass != null) {
                    XposedBridge.hookAllMethods(
                        u9dClass,
                        "a",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult("127.0.0.1");
                            }
                        }
                    );
                    log("[+] Hooked u9.d.a -> 127.0.0.1");
                }
            } catch (Throwable ignored) {}

            // Hook r9.j.a(String prefix, int ipVersion) -> "127.0.0.1" (防止 Failed to get the ip address of p2p interface)
            try {
                Class<?> r9jClass = XposedHelpers.findClassIfExists("r9.j", lpparam.classLoader);
                if (r9jClass != null) {
                    XposedBridge.hookAllMethods(
                        r9jClass,
                        "a",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult("127.0.0.1");
                            }
                        }
                    );
                    log("[+] Hooked r9.j.a -> 127.0.0.1");
                }
            } catch (Throwable ignored) {}

            // Hook P2pController.connect(String) and connect(u9.h)
            Class<?> p2pCtrlClass = XposedHelpers.findClassIfExists("com.miui.carlink.castfwk.wireless.p2p.P2pController", lpparam.classLoader);
            if (p2pCtrlClass != null) {
                XposedBridge.hookAllMethods(
                    p2pCtrlClass,
                    "connect",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                            log("[P2pController] connect() intercepted, bypassing physical Wi-Fi P2P negotiation...");
                            XposedHelpers.setBooleanField(param.thisObject, "mConnectToGroupSuccess", true);
                            param.setResult(null);

                            final Object controller = param.thisObject;
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        InetAddress loopback = InetAddress.getByName("127.0.0.1");
                                        XposedHelpers.callMethod(controller, "notifyOnConnectedWhenReachable",
                                            loopback, "lo", false, "12345678", "DIRECT-Tesla");
                                        log("[P2pController] notifyOnConnectedWhenReachable dispatched successfully!");
                                    } catch (Throwable t) {
                                        log(t);
                                    }
                                }
                            }, 150);
                        }
                    }
                );
                log("[+] P2pController.connect short-circuit installed.");
            }

        } catch (Throwable t) {
            log("[-] Error hooking P2P and StateMachine: " + t.getMessage());
        }
    }

    // =========================================================================
    // 6. DataBus 就绪状态注入 (防止 "Databus is not ready" 重试超时)
    // =========================================================================
    private void hookDataBus(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> dataBusClass = XposedHelpers.findClassIfExists("com.miui.carlink.castfwk.DataBusClient", lpparam.classLoader);
            if (dataBusClass != null) {
                XposedHelpers.findAndHookMethod(
                    dataBusClass,
                    "f",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    }
                );
                log("[+] Hooked DataBusClient.f() -> true (DataBus ready)");
            }
        } catch (Throwable t) {
            log("[-] Error hooking DataBusClient: " + t.getMessage());
        }
    }

    // =========================================================================
    // 7. 将车机投屏网络连接自动重定向至本地回环 Loopback (127.0.0.1)
    // =========================================================================
    private void hookSocketRedirection(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook java.net.Socket.connect(SocketAddress, int)
            XposedHelpers.findAndHookMethod(
                Socket.class,
                "connect",
                SocketAddress.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SocketAddress endpoint = (SocketAddress) param.args[0];
                        if (endpoint instanceof InetSocketAddress) {
                            InetSocketAddress isa = (InetSocketAddress) endpoint;
                            int targetPort = isa.getPort();
                            // 匹配 CarLife/ICCOA/EasyConn 车载协议相关端口 (7240~7246, 7999, 8240, 9240~9242, 9340, 9440, 9000~9005, 10710~10715, 10920~10925, 20150~20155)
                            boolean isCarPort = (targetPort >= 7240 && targetPort <= 7246) || 
                                                targetPort == 7999 || 
                                                targetPort == 8240 || 
                                                (targetPort >= 9240 && targetPort <= 9242) ||
                                                targetPort == 9340 || 
                                                targetPort == 9440 ||
                                                (targetPort >= 9000 && targetPort <= 9005) ||
                                                (targetPort >= 10710 && targetPort <= 10715) ||
                                                (targetPort >= 10920 && targetPort <= 10925) ||
                                                (targetPort >= 20150 && targetPort <= 20155);
                            if (isCarPort) {
                                InetAddress loopback = InetAddress.getByName("127.0.0.1");
                                InetSocketAddress redirected = new InetSocketAddress(loopback, targetPort);
                                param.args[0] = redirected;
                                log("[Socket Redirect] Re-routed " + isa.getHostString() + ":" + targetPort +
                                                 " -> 127.0.0.1:" + targetPort);
                            }
                        }
                    }
                }
            );
            log("[+] Socket loopback redirection installed for CarWith.");
        } catch (Throwable t) {
            log("[-] Error hooking Socket.connect: " + t.getMessage());
        }
    }

    // =========================================================================
    // 8. 彻底屏蔽弹窗与前台抢夺 (CarlifeDialogActivity / YiLianDialogActivity)
    // =========================================================================
    private void hookDialogSuppression(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> carlifeDialog = XposedHelpers.findClassIfExists(
                "com.carwith.launcher.settings.phone.carlife.CarlifeDialogActivity",
                lpparam.classLoader
            );
            if (carlifeDialog != null) {
                XposedHelpers.findAndHookMethod(
                    carlifeDialog,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Activity act = (Activity) param.thisObject;
                            act.finish();
                            param.setResult(null);
                            log("[Dialog Suppressed] CarlifeDialogActivity auto-dismissed.");
                        }
                    }
                );
            }

            Class<?> yilianDialog = XposedHelpers.findClassIfExists(
                "com.carwith.launcher.settings.phone.yilian.YiLianDialogActivity",
                lpparam.classLoader
            );
            if (yilianDialog != null) {
                XposedHelpers.findAndHookMethod(
                    yilianDialog,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Activity act = (Activity) param.thisObject;
                            act.finish();
                            param.setResult(null);
                            log("[Dialog Suppressed] YiLianDialogActivity auto-dismissed.");
                        }
                    }
                );
            }
        } catch (Throwable t) {
            log("[-] Error hooking dialog suppression: " + t.getMessage());
        }
    }

    // =========================================================================
    // 8.1 彻底抑制 Carlife 手机端 Activity 在主用户 (User 0) 前台夺焦弹窗
    // =========================================================================
    private void hookCarlifeHeadlessMode(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 针对 XiaomiPermissionActivity: 绝不抛异常，在 afterHookedMethod 设置 RESULT_OK 并结束，满足 CarWith 的鉴权
            Class<?> permActClass = XposedHelpers.findClassIfExists(
                "com.baidu.carlife.mix.xiaomi.XiaomiPermissionActivity",
                lpparam.classLoader
            );
            if (permActClass != null) {
                XposedBridge.hookAllMethods(
                    permActClass,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Activity act = (Activity) param.thisObject;
                            log("[Carlife] XiaomiPermissionActivity intercepted -> auto-grant RESULT_OK!");
                            act.setResult(Activity.RESULT_OK);
                            act.finish();
                        }
                    }
                );
            }

            // 针对其他 Carlife 手机端前台展示 Activity: 在 afterHookedMethod 中安全后置，绝不在 beforeHookedMethod 中取消 onCreate！
            XposedBridge.hookAllMethods(
                Activity.class,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Activity act = (Activity) param.thisObject;
                        String name = act.getClass().getName();
                        if (!name.contains("XiaomiPermissionActivity")) {
                            log("[Carlife Headless] Auto-moving background: " + name);
                            act.moveTaskToBack(true);
                        }
                    }
                }
            );
            log("[+] Carlife headless mode suppression installed (safe after-onCreate).");
        } catch (Throwable t) {
            log("[-] Error hooking Carlife headless mode: " + t.getMessage());
        }
    }

    // =========================================================================
    // 9. 合成并派发 ICCOA / 小米智联车载 BLE 广播包
    // =========================================================================
    private void injectVirtualBeacon(final ScanCallback callback) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null) return;
                    BluetoothDevice mockDevice = adapter.getRemoteDevice(VIRTUAL_MAC);

                    String currentVehicleName = getVehicleName();
                    byte[] nameBytes = currentVehicleName.getBytes(StandardCharsets.UTF_8);
                    byte[] advBytes = new byte[3 + 4 + (2 + nameBytes.length) + 6];
                    int idx = 0;

                    // Flags
                    advBytes[idx++] = 0x02;
                    advBytes[idx++] = 0x01;
                    advBytes[idx++] = 0x06;

                    // 16-bit Service UUID: 0xFE2C (ICCOA Carlink 联盟标配)
                    advBytes[idx++] = 0x03;
                    advBytes[idx++] = 0x03;
                    advBytes[idx++] = 0x2C;
                    advBytes[idx++] = (byte) 0xFE;

                    // Complete Local Name
                    advBytes[idx++] = (byte) (1 + nameBytes.length);
                    advBytes[idx++] = 0x09;
                    System.arraycopy(nameBytes, 0, advBytes, idx, nameBytes.length);
                    idx += nameBytes.length;

                    // Manufacturer Data: Xiaomi Inc. (0x038F)
                    advBytes[idx++] = 0x05;
                    advBytes[idx++] = (byte) 0xFF;
                    advBytes[idx++] = (byte) 0x8F;
                    advBytes[idx++] = 0x03;
                    advBytes[idx++] = 0x01; // ICCOA Car mode
                    advBytes[idx++] = 0x00;

                    Method parseMethod = ScanRecord.class.getDeclaredMethod("parseFromBytes", byte[].class);
                    parseMethod.setAccessible(true);
                    ScanRecord record = (ScanRecord) parseMethod.invoke(null, (Object) advBytes);
                    ScanResult result = new ScanResult(mockDevice, record, -42, SystemClock.elapsedRealtimeNanos());

                    callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
                    log("[BLE ✓] Dispatched simulated ICCOA CarLink advertisement: " +
                                     currentVehicleName + " [" + VIRTUAL_MAC + "]");

                } catch (Throwable t) {
                    log("[-] Error dispatching synthetic ScanResult: " + t.getMessage());
                }
            }
        }, 200);
    }

    // =========================================================================
    // 10. 自动静默拉起 CarLife 车机端 (免手动点击 CarLife 图标)
    // =========================================================================
    private void hookAutoLaunchCarLifeVehicle(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> actClass = XposedHelpers.findClassIfExists(
                "com.carwith.launcher.settings.phone.UCarConnectInfoActivity",
                lpparam.classLoader
            );
            if (actClass != null) {
                XposedBridge.hookAllMethods(actClass, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Activity act = (Activity) param.thisObject;
                        if (act == null) return;
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Intent it = new Intent(Intent.ACTION_MAIN);
                                    it.setComponent(new ComponentName("com.baidu.carlifevehicle", "com.baidu.carlifevehicle.CarlifeActivity"));
                                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    act.startActivity(it);
                                    log("[AutoLaunch] Successfully dispatched background start for CarLifeVehicle!");
                                } catch (Throwable t) {
                                    log("[-] AutoLaunch CarLifeVehicle error: " + t.getMessage());
                                }
                            }
                        }, 500);
                    }
                });
                log("[+] Auto-launch CarLifeVehicle hook installed.");
            }
        } catch (Throwable t) {
            log("[-] Error installing auto-launch CarLifeVehicle hook: " + t.getMessage());
        }
    }

    // =========================================================================
    // 11. 百度 CarLife 车机端 (com.baidu.carlifevehicle) 虚拟车机后台无感静默模式
    // 彻底解决：切回后台不暂停视频推流、不关闭触控、界面完全透明并自动退居后台栈底
    // =========================================================================
    private void hookCarlifeVehicleHeadless(XC_LoadPackage.LoadPackageParam lpparam) {
        log("[+] Activating Headless Virtual Vehicle Mode for com.baidu.carlifevehicle");
        hookCarlifeVehicleAudio(lpparam);

        // 1. 彻底拦截发送给手机端的“视频暂停”指令 (MSG_CMD_VIDEO_ENCODER_PAUSE = 0x0001800a)
        // 确保手机切回桌面或使用其他 App 时，CarWith 虚拟屏幕推流永不暂停！
        try {
            Class<?> utilClass = XposedHelpers.findClassIfExists(
                "com.baidu.carlifevehicle.util.CarlifeUtil",
                lpparam.classLoader
            );
            if (utilClass != null) {
                XposedBridge.hookAllMethods(utilClass, "sendVideoPauseMsg", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        log("[CarlifeVehicle ✓] Intercepted CarlifeUtil.sendVideoPauseMsg -> permanently suppressed!");
                        param.setResult(null);
                    }
                });
            }
        } catch (Throwable t) {
            log("[-] Error hooking sendVideoPauseMsg: " + t.getMessage());
        }

        // 2. 拦截 CarlifeActivity 生命周期与触控开关
        try {
            Class<?> actClass = XposedHelpers.findClassIfExists(
                "com.baidu.carlifevehicle.CarlifeActivity",
                lpparam.classLoader
            );
            if (actClass != null) {
                // onPause: 强制保持 mIsForeground 为 true，禁止关闭触控
                XposedBridge.hookAllMethods(actClass, "onPause", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, "mIsForeground", true);
                        } catch (Throwable ignored) {}
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, "mIsForeground", true);
                        } catch (Throwable ignored) {}
                    }
                });

                // 彻底拦截并静默处理 showTouch / closeTouch，禁止百度 CarLife 擅自篡改系统 show_touches 产生屏幕小白点
                XposedBridge.hookAllMethods(actClass, "showTouch", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                        try {
                            Activity act = (Activity) param.thisObject;
                            android.provider.Settings.System.putInt(act.getContentResolver(), "show_touches", 0);
                        } catch (Throwable ignored) {}
                    }
                });

                XposedBridge.hookAllMethods(actClass, "closeTouch", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                        try {
                            Activity act = (Activity) param.thisObject;
                            android.provider.Settings.System.putInt(act.getContentResolver(), "show_touches", 0);
                        } catch (Throwable ignored) {}
                    }
                });

                // onCreate: 窗口完全透明 1x1 像素化，自动退至后台栈底，绝不遮挡手机屏幕
                XposedBridge.hookAllMethods(actClass, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Activity act = (Activity) param.thisObject;
                        if (act == null) return;

                        // 设置窗口为 1x1 完全透明、无焦点、不接收触摸
                        try {
                            android.view.Window w = act.getWindow();
                            if (w != null) {
                                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                                lp.width = 1;
                                lp.height = 1;
                                lp.x = -2000;
                                lp.y = -2000;
                                lp.alpha = 0.0f;
                                lp.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                         | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                         | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                                w.setAttributes(lp);
                            }
                        } catch (Throwable t) {
                            log("[-] Error configuring transparent window: " + t.getMessage());
                        }

                        // 自动移至后台任务栈底，把手机屏幕完全交还给用户
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    log("[CarlifeVehicle] Auto-moving vehicle activity to background...");
                                    act.moveTaskToBack(true);
                                } catch (Throwable ignored) {}
                            }
                        }, 250);
                    }
                });

                // onResume: 若因任何原因被唤醒，依然保持后台，不抢前台
                XposedBridge.hookAllMethods(actClass, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Activity act = (Activity) param.thisObject;
                        if (act == null) return;
                        try {
                            android.view.Window w = act.getWindow();
                            if (w != null) {
                                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                                lp.width = 1;
                                lp.height = 1;
                                lp.alpha = 0.0f;
                                w.setAttributes(lp);
                            }
                        } catch (Throwable ignored) {}
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try { act.moveTaskToBack(true); } catch (Throwable ignored) {}
                            }
                        }, 200);
                    }
                });

                // onBackPressed: 返回键直接退回后台，不退出进程
                XposedBridge.hookAllMethods(actClass, "onBackPressed", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity act = (Activity) param.thisObject;
                        if (act != null) {
                            act.moveTaskToBack(true);
                        }
                        param.setResult(null);
                    }
                });

                log("[+] CarlifeActivity headless hooks applied successfully.");
            }
        } catch (Throwable t) {
            log("[-] Error hooking CarlifeActivity: " + t.getMessage());
        }
    }

    // =========================================================================
    // 12. 百度 CarLife 车机端音频与焦点穿透管理 (彻底解决车机与手机同机焦点冲突/静音/丢包)
    // 1) 蓝牙原生模式：手机音乐直通车机蓝牙，不抢占焦点，屏蔽 informMusicPause 暂停信令
    // 2) 网页串流模式：音频PCM数据实时截获并重定向转发至本地 UDP 8089 供特斯拉浏览器播放
    // =========================================================================
    private static DatagramSocket sPcmSocket = null;
    private static InetSocketAddress sBridgeAudioAddr = null;

    private static void forwardPcmToBridge(byte[] data, int offset, int size) {
        if (data == null || size <= 0) return;
        try {
            if (sPcmSocket == null || sPcmSocket.isClosed()) {
                sPcmSocket = new DatagramSocket();
                sBridgeAudioAddr = new InetSocketAddress("127.0.0.1", 8089);
            }
            int maxChunk = 1400;
            int curOffset = offset;
            int remaining = size;
            while (remaining > 0) {
                int sendLen = Math.min(remaining, maxChunk);
                DatagramPacket packet = new DatagramPacket(data, curOffset, sendLen, sBridgeAudioAddr);
                sPcmSocket.send(packet);
                curOffset += sendLen;
                remaining -= sendLen;
            }
        } catch (Throwable ignored) {}
    }

    private static volatile long sLastCheckTime = 0;
    private static volatile String sAudioModeCache = "system";

    public static String getAudioModeConfig() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastCheckTime > 500) {
            sLastCheckTime = now;
            try {
                File f = new File("/data/adb/tesla_box/audio_mode");
                if (!f.exists()) {
                    f = new File("/data/local/tmp/carwith_audio_mode");
                }
                if (f.exists()) {
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String line = br.readLine();
                    br.close();
                    if (line != null && !line.trim().isEmpty()) {
                        sAudioModeCache = line.trim().toLowerCase();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return sAudioModeCache != null ? sAudioModeCache : "system";
    }

    private static volatile long sLastActiveCheckTime = 0;
    private static volatile String sActiveAudioTargetCache = "system";

    public static String getActiveAudioTarget() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastActiveCheckTime > 500) {
            sLastActiveCheckTime = now;
            try {
                File f = new File("/data/local/tmp/tesla_active_audio");
                if (f.exists()) {
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String line = br.readLine();
                    br.close();
                    if (line != null && !line.trim().isEmpty()) {
                        sActiveAudioTargetCache = line.trim().toLowerCase();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return sActiveAudioTargetCache != null ? sActiveAudioTargetCache : "system";
    }

    public static void setActiveAudioTarget(final String target) {
        sActiveAudioTargetCache = target;
        sLastActiveCheckTime = SystemClock.uptimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File("/data/local/tmp/tesla_active_audio");
                    FileWriter fw = new FileWriter(f, false);
                    fw.write(target + "\n");
                    fw.close();
                    f.setReadable(true, false);
                    f.setWritable(true, false);
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    public static void setAudioModeConfig(final String mode) {
        sAudioModeCache = mode;
        sLastCheckTime = SystemClock.uptimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File("/data/adb/tesla_box");
                    if (!dir.exists()) dir.mkdirs();
                    File f1 = new File("/data/adb/tesla_box/audio_mode");
                    FileWriter fw1 = new FileWriter(f1, false);
                    fw1.write(mode + "\n");
                    fw1.close();
                    f1.setReadable(true, false);
                    f1.setWritable(true, false);

                    File f2 = new File("/data/local/tmp/carwith_audio_mode");
                    FileWriter fw2 = new FileWriter(f2, false);
                    fw2.write(mode + "\n");
                    fw2.close();
                    f2.setReadable(true, false);
                    f2.setWritable(true, false);

                    int val = ("virtual".equalsIgnoreCase(mode) || "system".equalsIgnoreCase(mode) || "auto".equalsIgnoreCase(mode)) ? 0 : 1;
                    File bdcf = new File("/data/local/tmp/bdcf");
                    if (bdcf.exists()) {
                        List<String> lines = new ArrayList<>();
                        BufferedReader br = new BufferedReader(new FileReader(bdcf));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.startsWith("AUDIO_TRANSMISSION_MODE=")) {
                                lines.add("AUDIO_TRANSMISSION_MODE=" + val);
                            } else {
                                lines.add(line);
                            }
                        }
                        br.close();
                        FileWriter bfw = new FileWriter(bdcf, false);
                        for (String l : lines) {
                            bfw.write(l + "\n");
                        }
                        bfw.close();
                        bdcf.setReadable(true, false);
                        bdcf.setWritable(true, false);
                    }

                    if (!"virtual".equals(mode)) {
                        try {
                            Runtime.getRuntime().exec(new String[]{"sh", "-c", "service call audio 7 i32 3 i32 0 s16 default 2>/dev/null; cmd media_session volume --stream 3 --set 15 2>/dev/null"});
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    log(t);
                }
            }
        }).start();
    }

    private static int getEffectiveAudioTransmissionMode() {
        String mode = getAudioModeConfig();
        if ("virtual".equalsIgnoreCase(mode)) {
            return 0; // 虚拟车机通道
        }
        if ("native".equalsIgnoreCase(mode) || "bt".equalsIgnoreCase(mode) || "bluetooth".equalsIgnoreCase(mode) || "speaker".equalsIgnoreCase(mode) || "1".equals(mode)) {
            return 1; // 纯蓝牙直连 / 本机扬声器
        }
        // 默认 "system" 模式: 协议协商保持 0 (车机音频通道激活)，由控制中心播控卡片实时分流
        return 0;
    }

    private static boolean shouldForwardPcmAudio() {
        String modeConfig = getAudioModeConfig();
        if ("virtual".equalsIgnoreCase(modeConfig)) {
            return true;
        }
        if ("native".equalsIgnoreCase(modeConfig) || "bluetooth".equalsIgnoreCase(modeConfig) || "speaker".equalsIgnoreCase(modeConfig)) {
            return false;
        }
        // 默认 "system" 模式: 查看用户当前在控制中心选中的是哪个设备
        String active = getActiveAudioTarget();
        if ("bluetooth".equalsIgnoreCase(active) || "speaker".equalsIgnoreCase(active)) {
            return false;
        }
        return true;
    }

    private void hookCarlifeVehicleAudio(XC_LoadPackage.LoadPackageParam lpparam) {
        log("[+] Initializing CarLifeVehicle Audio & AudioFocus Virtualization hooks...");

        // 1. 动态覆盖 CarlifeConfUtil 配置参数
        try {
            Class<?> confUtilClass = XposedHelpers.findClassIfExists(
                "com.baidu.carlifevehicle.util.CarlifeConfUtil",
                lpparam.classLoader
            );
            if (confUtilClass != null) {
                XposedBridge.hookAllMethods(confUtilClass, "getIntProperty", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args != null && param.args.length > 0) {
                            String key = String.valueOf(param.args[0]);
                            if ("AUDIO_TRANSMISSION_MODE".equals(key)) {
                                int mode = getEffectiveAudioTransmissionMode();
                                param.setResult(mode);
                            } else if ("VOICE_MIC".equals(key)) {
                                param.setResult(1); // 1: 手机麦克风
                            }
                        }
                    }
                });

                XposedBridge.hookAllMethods(confUtilClass, "getBooleanProperty", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args != null && param.args.length > 0) {
                            String key = String.valueOf(param.args[0]);
                            if ("AUDIO_TTS_REQUEST_FOCUS".equals(key)) {
                                param.setResult(false);
                            }
                        }
                    }
                });
                log("[CarlifeVehicle ✓] CarlifeConfUtil audio properties hooked successfully.");
            }
        } catch (Throwable t) {
            log("[-] Error hooking CarlifeConfUtil: " + t.getMessage());
        }

        // 2. 彻底拦截 AudioManager.requestAudioFocus，对车机端返回 GRANTED 但绝不向系统抢焦点
        try {
            XposedBridge.hookAllMethods(AudioManager.class, "requestAudioFocus", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                }
            });

            XposedBridge.hookAllMethods(AudioManager.class, "abandonAudioFocus", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                }
            });
            log("[CarlifeVehicle ✓] AudioManager.requestAudioFocus virtualization applied.");
        } catch (Throwable t) {
            log("[-] Error hooking AudioManager audio focus: " + t.getMessage());
        }

        // 2.5 Hook ArbitrationModuleDual.getMediaPlayStatus 消除 mMusicAudioTrackLock 死锁
        try {
            Class<?> arbClass = XposedHelpers.findClassIfExists(
                "com.baidu.carlifevehicle.audioplayer.arbitrationmodule.ArbitrationModuleDual",
                lpparam.classLoader
            );
            if (arbClass != null) {
                XposedBridge.hookAllMethods(arbClass, "getMediaPlayStatus", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(true);
                    }
                });
                log("[CarlifeVehicle ✓] ArbitrationModuleDual.getMediaPlayStatus hooked -> always true");
            }
        } catch (Throwable t) {
            log("[-] Error hooking ArbitrationModuleDual: " + t.getMessage());
        }

        // 3. Hook 百度所有 AudioTrack 管理器类，解除焦点防丢锁并支持 PCM 转发
        String[] managers = new String[] {
            "com.baidu.carlifevehicle.audioplayer.audiotrackmanager.AudioTrackManagerDualNormal",
            "com.baidu.carlifevehicle.audioplayer.audiotrackmanager.AudioTrackManagerDualStreamType",
            "com.baidu.carlifevehicle.audioplayer.audiotrackmanager.AudioTrackManagerSingle"
        };

        for (String mgrName : managers) {
            try {
                Class<?> mgrClass = XposedHelpers.findClassIfExists(mgrName, lpparam.classLoader);
                if (mgrClass == null) continue;

                // getMusicAudioTrackFocus -> 虚拟成功 (0)，并置标志位为 true
                XposedBridge.hookAllMethods(mgrClass, "getMusicAudioTrackFocus", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "setMediaAudioFocusStatus", true);
                        } catch (Throwable ignored) {}
                        param.setResult(0);
                    }
                });

                // getTTSAudioTrackFocus -> 虚拟成功 (0)，并置标志位为 true
                XposedBridge.hookAllMethods(mgrClass, "getTTSAudioTrackFocus", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "setTTSAudioFocusStatus", true);
                        } catch (Throwable ignored) {}
                        param.setResult(0);
                    }
                });

                // informMusicPause -> 彻底拦截！绝不允许车机因单机焦点变动而反向下发暂停指令打断手机音乐
                XposedBridge.hookAllMethods(mgrClass, "informMusicPause", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                    }
                });

                // getMediaAudioFocusStatus / getTTSAudioFocusStatus -> 始终返回 true，防止 write 时丢包丢帧
                XposedBridge.hookAllMethods(mgrClass, "getMediaAudioFocusStatus", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(true);
                    }
                });
                XposedBridge.hookAllMethods(mgrClass, "getTTSAudioFocusStatus", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(true);
                    }
                });

                // writeMusicAudioTrack & writeTTSAudioTrack -> 捕获 PCM 音频块转发至 UDP 8089，同时取消本地 AudioTrack 写入
                XposedBridge.hookAllMethods(mgrClass, "writeMusicAudioTrack", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (shouldForwardPcmAudio() && param.args != null && param.args.length >= 3) {
                                byte[] data = (byte[]) param.args[0];
                                int offset = (Integer) param.args[1];
                                int size = (Integer) param.args[2];
                                forwardPcmToBridge(data, offset, size);
                            }
                        } catch (Throwable ignored) {}
                        param.setResult(null);
                    }
                });

                XposedBridge.hookAllMethods(mgrClass, "writeTTSAudioTrack", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (shouldForwardPcmAudio() && param.args != null && param.args.length >= 3) {
                                byte[] data = (byte[]) param.args[0];
                                int offset = (Integer) param.args[1];
                                int size = (Integer) param.args[2];
                                forwardPcmToBridge(data, offset, size);
                            }
                        } catch (Throwable ignored) {}
                        param.setResult(null);
                    }
                });

                log("[CarlifeVehicle ✓] AudioTrackManager hooks applied to: " + mgrName);
            } catch (Throwable t) {
                log("[-] Error hooking " + mgrName + ": " + t.getMessage());
            }
        }
    }

    // =========================================================================
    // 13. 小米澎湃OS4 控制中心播控卡片“声音流转”虚拟车机设备注入与三向自由切换
    // 支持：【本机扬声器】、【车载蓝牙】、【虚拟车机】任意无损流转切换
    // =========================================================================
    private void hookSystemUiSoundFlow(XC_LoadPackage.LoadPackageParam lpparam) {
        log("[+] Installing SystemUI Sound Flow hooks for: " + lpparam.packageName);
        try {
            hookBluetoothDevice(lpparam);

            // Hook BluetoothAdapter.getBondedDevices in SystemUI / MiLink
            XposedHelpers.findAndHookMethod(
                BluetoothAdapter.class,
                "getBondedDevices",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        @SuppressWarnings("unchecked")
                        Set<BluetoothDevice> original = (Set<BluetoothDevice>) param.getResult();
                        Set<BluetoothDevice> modified = new HashSet<>(original != null ? original : new HashSet<>());
                        try {
                            BluetoothAdapter adapter = (BluetoothAdapter) param.thisObject;
                            BluetoothDevice mockDevice = adapter.getRemoteDevice(VIRTUAL_MAC);
                            modified.add(mockDevice);
                            param.setResult(modified);
                        } catch (Throwable t) {
                            log(t);
                        }
                    }
                }
            );

            // Hook BluetoothA2dp profile methods
            Class<?> a2dpClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothA2dp", lpparam.classLoader);
            if (a2dpClass != null) {
                // getConnectedDevices: Inject 虚拟车机 into connected list
                XposedHelpers.findAndHookMethod(
                    a2dpClass,
                    "getConnectedDevices",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            @SuppressWarnings("unchecked")
                            List<BluetoothDevice> original = (List<BluetoothDevice>) param.getResult();
                            List<BluetoothDevice> modified = new ArrayList<>(original != null ? original : new ArrayList<>());
                            try {
                                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                                if (adapter != null) {
                                    BluetoothDevice mockDevice = adapter.getRemoteDevice(VIRTUAL_MAC);
                                    boolean exists = false;
                                    for (BluetoothDevice d : modified) {
                                        if (d != null && VIRTUAL_MAC.equalsIgnoreCase(d.getAddress())) {
                                            exists = true;
                                            break;
                                        }
                                    }
                                    if (!exists) {
                                        modified.add(mockDevice);
                                    }
                                    param.setResult(modified);
                                }
                            } catch (Throwable t) {
                                log(t);
                            }
                        }
                    }
                );

                // getConnectionState: Return STATE_CONNECTED for 虚拟车机
                XposedHelpers.findAndHookMethod(
                    a2dpClass,
                    "getConnectionState",
                    BluetoothDevice.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            BluetoothDevice dev = (BluetoothDevice) param.args[0];
                            if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                                param.setResult(android.bluetooth.BluetoothProfile.STATE_CONNECTED);
                            }
                        }
                    }
                );

                // getActiveDevice: Dynamic routing based on user selection in Control Center & module mode
                XposedHelpers.findAndHookMethod(
                    a2dpClass,
                    "getActiveDevice",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String modeConfig = getAudioModeConfig();
                            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                            if (adapter == null) return;
                            BluetoothDevice mockDevice = adapter.getRemoteDevice(VIRTUAL_MAC);

                            if ("virtual".equalsIgnoreCase(modeConfig)) {
                                param.setResult(mockDevice);
                                return;
                            }
                            if ("native".equalsIgnoreCase(modeConfig) || "bluetooth".equalsIgnoreCase(modeConfig)) {
                                return; // return original real Bluetooth device
                            }
                            if ("speaker".equalsIgnoreCase(modeConfig)) {
                                param.setResult(null);
                                return;
                            }

                            // Default "system" mode: follow user's choice in HyperOS Control Center
                            String activeTarget = getActiveAudioTarget();
                            BluetoothDevice orig = (BluetoothDevice) param.getResult();

                            if ("virtual".equalsIgnoreCase(activeTarget)) {
                                param.setResult(mockDevice);
                            } else if ("speaker".equalsIgnoreCase(activeTarget)) {
                                param.setResult(null);
                            } else if ("bluetooth".equalsIgnoreCase(activeTarget)) {
                                if (orig != null && !VIRTUAL_MAC.equalsIgnoreCase(orig.getAddress())) {
                                    param.setResult(orig);
                                }
                            } else {
                                // Default / auto: keep original if connected, else mockDevice
                                if (orig != null && !VIRTUAL_MAC.equalsIgnoreCase(orig.getAddress())) {
                                    param.setResult(orig);
                                }
                            }
                        }
                    }
                );

                // setActiveDevice: User switches sound flow in HyperOS 4.0 Control Center
                XposedHelpers.findAndHookMethod(
                    a2dpClass,
                    "setActiveDevice",
                    BluetoothDevice.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            BluetoothDevice dev = (BluetoothDevice) param.args[0];
                            if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                                log("[SoundFlow] User switched to 【虚拟车机】");
                                setActiveAudioTarget("virtual");
                                param.setResult(true);
                            } else if (dev != null) {
                                log("[SoundFlow] User switched to 【" + dev.getName() + "】 (Bluetooth)");
                                setActiveAudioTarget("bluetooth");
                                // Allow real Bluetooth device activation
                            } else {
                                log("[SoundFlow] User switched to 【本机扬声器】 (Speaker)");
                                setActiveAudioTarget("speaker");
                                // Allow speaker activation
                            }
                        }
                    }
                );

                // isA2dpPlaying: If 虚拟车机, check if virtual output is active
                try {
                    XposedHelpers.findAndHookMethod(
                        a2dpClass,
                        "isA2dpPlaying",
                        BluetoothDevice.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                BluetoothDevice dev = (BluetoothDevice) param.args[0];
                                if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                                    String modeConfig = getAudioModeConfig();
                                    if ("virtual".equalsIgnoreCase(modeConfig)) {
                                        param.setResult(true);
                                    } else if ("native".equalsIgnoreCase(modeConfig) || "speaker".equalsIgnoreCase(modeConfig)) {
                                        param.setResult(false);
                                    } else {
                                        // system mode
                                        String active = getActiveAudioTarget();
                                        param.setResult("virtual".equalsIgnoreCase(active));
                                    }
                                }
                            }
                        }
                    );
                } catch (Throwable ignored) {}

                try {
                    XposedHelpers.findAndHookMethod(
                        a2dpClass,
                        "getConnectionPolicy",
                        BluetoothDevice.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                BluetoothDevice dev = (BluetoothDevice) param.args[0];
                                if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                                    param.setResult(100);
                                }
                            }
                        }
                    );
                } catch (Throwable ignored) {}

                try {
                    XposedHelpers.findAndHookMethod(
                        a2dpClass,
                        "getPriority",
                        BluetoothDevice.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                BluetoothDevice dev = (BluetoothDevice) param.args[0];
                                if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                                    param.setResult(100);
                                }
                            }
                        }
                    );
                } catch (Throwable ignored) {}
            }

            // Hook BluetoothHeadset
            Class<?> headsetClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothHeadset", lpparam.classLoader);
            if (headsetClass != null) {
                XposedHelpers.findAndHookMethod(
                    headsetClass,
                    "getConnectedDevices",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            @SuppressWarnings("unchecked")
                            List<BluetoothDevice> original = (List<BluetoothDevice>) param.getResult();
                            List<BluetoothDevice> modified = new ArrayList<>(original != null ? original : new ArrayList<>());
                            try {
                                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                                if (adapter != null) {
                                    BluetoothDevice mockDevice = adapter.getRemoteDevice(VIRTUAL_MAC);
                                    boolean exists = false;
                                    for (BluetoothDevice d : modified) {
                                        if (d != null && VIRTUAL_MAC.equalsIgnoreCase(d.getAddress())) {
                                            exists = true;
                                            break;
                                        }
                                    }
                                    if (!exists) {
                                        modified.add(mockDevice);
                                    }
                                    param.setResult(modified);
                                }
                            } catch (Throwable t) {
                                log(t);
                            }
                        }
                    }
                );

                XposedHelpers.findAndHookMethod(
                    headsetClass,
                    "getConnectionState",
                    BluetoothDevice.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            BluetoothDevice dev = (BluetoothDevice) param.args[0];
                            if (dev != null && VIRTUAL_MAC.equalsIgnoreCase(dev.getAddress())) {
                                param.setResult(android.bluetooth.BluetoothProfile.STATE_CONNECTED);
                            }
                        }
                    }
                );
            }
        } catch (Throwable t) {
            log("[-] Error in hookSystemUiSoundFlow: " + t.getMessage());
        }
    }

    // =========================================================================
    // 14. 百度 CarLife 手机端 (com.baidu.carlife.xiaomi) 音频通道解劫持与直通
    // 彻底解决：手机与车载蓝牙被全局静音 (AudioManager.setStreamMute 拦截)、音频路由感知
    // =========================================================================
    private void hookCarlifeXiaomiAudio(XC_LoadPackage.LoadPackageParam lpparam) {
        log("[+] Initializing Carlife Xiaomi Audio unhijack hooks...");

        // 1. 彻底阻止百度 Carlife 在非虚拟车机模式下对 STREAM_MUSIC 静音
        try {
            XposedBridge.hookAllMethods(AudioManager.class, "setStreamMute", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length >= 2) {
                        int streamType = (Integer) param.args[0];
                        boolean state = (Boolean) param.args[1];
                        if (streamType == AudioManager.STREAM_MUSIC && state) {
                            int mode = getEffectiveAudioTransmissionMode();
                            log("[CarlifeXiaomi ✓] Blocked AudioManager.setStreamMute(STREAM_MUSIC, true)");
                            param.setResult(null);
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(AudioManager.class, "adjustStreamVolume", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length >= 2) {
                        int streamType = (Integer) param.args[0];
                        int direction = (Integer) param.args[1];
                        if (streamType == AudioManager.STREAM_MUSIC && (direction == AudioManager.ADJUST_MUTE || direction == -100)) {
                            log("[CarlifeXiaomi ✓] Blocked AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE)");
                            param.setResult(null);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            log("[-] Error hooking AudioManager mute in CarlifeXiaomi: " + t.getMessage());
        }

        // 2. 动态拦截 AudioUtil 确定音频路由
        try {
            Class<?> audioUtilClass = XposedHelpers.findClassIfExists("com.baidu.carlife.core.audio.AudioUtil", lpparam.classLoader);
            if (audioUtilClass != null) {
                XposedBridge.hookAllMethods(audioUtilClass, "isUSingBTForAudio", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String modeConfig = getAudioModeConfig();
                        if ("virtual".equalsIgnoreCase(modeConfig)) {
                            param.setResult(false);
                            return;
                        }
                        if ("native".equalsIgnoreCase(modeConfig) || "speaker".equalsIgnoreCase(modeConfig)) {
                            param.setResult(true);
                            return;
                        }
                        // system mode: check active audio target in Control Center
                        String active = getActiveAudioTarget();
                        if ("virtual".equalsIgnoreCase(active)) {
                            param.setResult(false);
                        } else {
                            param.setResult(true);
                        }
                    }
                });

                XposedBridge.hookAllMethods(audioUtilClass, "getAudioTransmissionMode", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String modeConfig = getAudioModeConfig();
                        if ("virtual".equalsIgnoreCase(modeConfig)) {
                            param.setResult(0);
                            return;
                        }
                        if ("native".equalsIgnoreCase(modeConfig) || "speaker".equalsIgnoreCase(modeConfig)) {
                            param.setResult(1);
                            return;
                        }
                        // system mode: check active audio target in Control Center
                        String active = getActiveAudioTarget();
                        if ("virtual".equalsIgnoreCase(active)) {
                            param.setResult(0);
                        } else {
                            param.setResult(1);
                        }
                    }
                });
                log("[CarlifeXiaomi ✓] AudioUtil hooks applied with dynamic routing.");
            }
        } catch (Throwable t) {
            log("[-] Error hooking AudioUtil: " + t.getMessage());
        }
    }
}

