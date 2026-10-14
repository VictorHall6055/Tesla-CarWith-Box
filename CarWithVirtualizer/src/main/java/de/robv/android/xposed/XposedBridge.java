package de.robv.android.xposed;

import android.util.Log;
import java.util.Set;

public class XposedBridge {
    public static final String TAG = "CarWithVirtualizer";

    public static void log(String text) {
        Log.i(TAG, text);
    }

    public static void log(Throwable t) {
        Log.e(TAG, "Error in hook", t);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        return null;
    }
}

