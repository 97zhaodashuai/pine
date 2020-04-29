package top.canyie.pine;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;

import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.entry.Entry32;
import top.canyie.pine.entry.Entry64;
import top.canyie.pine.utils.Primitives;
import top.canyie.pine.utils.ReflectionHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author canyie
 */
@SuppressWarnings("WeakerAccess")
public final class Pine {
    private static final String TAG = "Pine";
    public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
    private static volatile boolean initialized;
    private static final Map<String, Method> sBridgeMethods = new HashMap<>(8, 2f);
    private static final Map<Long, HookInfo> sHookInfoMap = new ConcurrentHashMap<>();
    private static final Object sHookLock = new Object();
    private static boolean is64Bit;
    private static volatile int hookMode = HookMode.AUTO;
    private static HookListener sHookListener;

    private Pine() {
        throw new RuntimeException("Use static methods");
    }

    public static void ensureInitialized() {
        if (initialized) return;
        synchronized (Pine.class) {
            if (initialized) return;
            initialize();
            initialized = true;
        }
    }

    @SuppressLint("ObsoleteSdkInt") private static void initialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT ||
                Build.VERSION.SDK_INT > Build.VERSION_CODES.Q)
            throw new RuntimeException("Unsupported android sdk level " + Build.VERSION.SDK_INT);
        else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                Build.VERSION.PREVIEW_SDK_INT > 0)
            throw new RuntimeException("Not supported Android R now!");

        String vmVersion = System.getProperty("java.vm.version");
        if (vmVersion == null || !vmVersion.startsWith("2"))
            throw new RuntimeException("Only supports ART runtime");

        try {
            LibLoader libLoader = PineConfig.libLoader;
            if (libLoader != null) libLoader.loadLib();

            Method m1 = ReflectionHelper.getMethod(Ruler.class, "m1", (Class<?>[]) null);
            Method m2 = ReflectionHelper.getMethod(Ruler.class, "m2", (Class<?>[]) null);

            Method getAccessFlags = ReflectionHelper.findMethod(Method.class,
                    "getAccessFlags", (Class<?>[]) null);
            int accessFlags;
            if (getAccessFlags != null) {
                accessFlags = (int) getAccessFlags.invoke(m1);
            } else {
                Log.w(TAG, "Method Method.getAccessFlags() not found, use default access flags.");
                accessFlags = Modifier.PRIVATE | Modifier.STATIC | Modifier.NATIVE;
                // accessFlags = m1.getModifiers();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                accessFlags |= 0x10000000; // kAccPublicApi
            }

            init0(Build.VERSION.SDK_INT, m1, m2, accessFlags, PineConfig.debuggable);
            initBridgeMethods();

            if (PineConfig.useFastNative
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                enableFastNative();

        } catch (Exception e) {
            throw new RuntimeException("Pine init error", e);
        }
    }

    private static void initBridgeMethods() {
        Class<?> entryClass;
        Class<?>[] paramTypes;

        if (is64Bit) {
            entryClass = Entry64.class;
            paramTypes = new Class<?>[] {long.class, long.class, long.class,
                    long.class, long.class, long.class, long.class};
        } else {
            entryClass = Entry32.class;
            paramTypes = new Class<?>[] {int.class, int.class, int.class};
        }

        String[] bridgeMethodNames = {"voidBridge", "intBridge", "longBridge", "doubleBridge", "floatBridge",
                "booleanBridge", "byteBridge", "charBridge", "shortBridge", "objectBridge"};
        try {
            for (String bridgeMethodName : bridgeMethodNames) {
                Method bridge = entryClass.getDeclaredMethod(bridgeMethodName, paramTypes);
                bridge.setAccessible(true);

                // Resolve bridge method (bridge method is always static and with parameters)
                try {
                    bridge.invoke(null, (Object[]) EMPTY_OBJECT_ARRAY);
                } catch (Exception ignored) {
                }

                sBridgeMethods.put(bridgeMethodName, bridge);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to init bridge methods", e);
        }
    }

    public static void setHookMode(int newHookMode) {
        if (newHookMode < HookMode.AUTO || newHookMode > HookMode.REPLACEMENT)
            throw new IllegalArgumentException("Illegal hookMode " + newHookMode);
        hookMode = newHookMode;
    }

    public static void setHookListener(HookListener l) {
        sHookListener = l;
    }

    public static HookListener getHookListener() {
        return sHookListener;
    }

    public static MethodHook.Unhook hook(Method method, MethodHook callback) {
        if (method == null) throw new NullPointerException("method == null");
        if (callback == null) throw new NullPointerException("callback == null");
        method.setAccessible(true);
        Class<?> declaring = method.getDeclaringClass();
        if (declaring.isInterface())
            throw new IllegalArgumentException("Cannot hook interfaces: " + method);

        int modifiers = method.getModifiers();
        if (Modifier.isAbstract(modifiers))
            throw new IllegalArgumentException("Cannot hook abstract methods: " + method);

        return hookImpl(declaring, modifiers, method, callback);
    }

    public static MethodHook.Unhook hook(Constructor<?> constructor, MethodHook callback) {
        if (constructor == null) throw new NullPointerException("constructor == null");
        if (callback == null) throw new NullPointerException("callback == null");
        constructor.setAccessible(true);
        Class<?> declaring = constructor.getDeclaringClass();
        if (declaring.isInterface())
            throw new IllegalArgumentException("Cannot hook interfaces: " + constructor);

        int modifiers = constructor.getModifiers();
        if (Modifier.isStatic(modifiers))
            throw new IllegalArgumentException("Cannot hook <clinit> (invoke when class-init)");

        return hookImpl(declaring, modifiers, constructor, callback);
    }

    private static MethodHook.Unhook hookImpl(Class<?> declaring, int modifiers, Member method, MethodHook callback) {
        if (PineConfig.debug)
            Log.d(TAG, "Hooking " + method + " callback " + callback);
        ensureInitialized();

        HookListener hookListener = sHookListener;

        if (hookListener != null)
            hookListener.beforeHook(method, callback);

        long artMethod = getArtMethod(method);
        HookInfo hookInfo;
        boolean newMethod = false;

        synchronized (sHookLock) {
            hookInfo = sHookInfoMap.get(artMethod);
            if (hookInfo == null) {
                hookInfo = new HookInfo(method);
                newMethod = true;
                sHookInfoMap.put(artMethod, hookInfo);
            }
        }

        if (newMethod)
            hookNewMethod(hookInfo, declaring, modifiers, method);

        hookInfo.addCallback(callback);
        MethodHook.Unhook unhook = callback.new Unhook(hookInfo);

        if (hookListener != null)
            hookListener.afterHook(method, unhook);

        return unhook;
    }

    private static void hookNewMethod(HookInfo hookInfo, Class<?> declaring, int modifiers,
                                      Member method) {
        boolean isInlineHook;
        if (hookMode == HookMode.AUTO) {
            // On Android N or lower, entry_point_from_compiled_code_ may be hard-coded in the machine code
            // (sharpening optimization), entry replacement will most likely not take effect, so we prefer
            // to use inline hook; And on Android O+, this optimization is not performed,
            // but in the entry replacement mode, we need to force the backup method to use interpreter
            // to avoid use compiled code (may compiled by JIT, an unknown error occurs when the backup
            // method is called), so we still prefer inline hook mode.

            isInlineHook = true;
        } else {
            isInlineHook = hookMode == HookMode.INLINE;
        }

        boolean isStatic = Modifier.isStatic(modifiers);
        if (isStatic) resolve((Method) method);
        hookInfo.isNonStatic = !isStatic;

        long thread = Primitives.currentArtThread();

        boolean isNativeOrProxy = Modifier.isNative(modifiers) || Proxy.isProxyClass(declaring);

        // Only try compile target method when trying inline hook.
        if (isInlineHook) {
            // Cannot compile native or proxy methods.
            if (!isNativeOrProxy) {
                boolean compiled = compile0(thread, method);
                if (!compiled) {
                    Log.e(TAG, "Failed to compile target method, force use replacement mode.");
                    isInlineHook = false;
                }
            } else {
                isInlineHook = false;
            }
        }

        String bridgeMethodName;
        if (method instanceof Method) {
            hookInfo.paramTypes = ((Method) method).getParameterTypes();
            Class<?> returnType = ((Method) method).getReturnType();
            bridgeMethodName = returnType.isPrimitive() ? returnType.getName() + "Bridge" : "objectBridge";
        } else {
            hookInfo.paramTypes = ((Constructor) method).getParameterTypes();
            // Constructor is actually a method named <init> and the return type is void.
            bridgeMethodName = "voidBridge";
        }

        hookInfo.paramNumber = hookInfo.paramTypes.length;

        Method bridge = sBridgeMethods.get(bridgeMethodName);
        if (bridge == null)
            throw new AssertionError("Cannot find bridge method for " + method);

        Method backup = hook0(thread, declaring, method, bridge, isInlineHook, isNativeOrProxy);

        if (backup == null)
            throw new RuntimeException("Failed to hook method " + method);

        backup.setAccessible(true);
        hookInfo.backup = backup;
    }

    private static void resolve(Method method) {
        Object[] badArgs;
        if (method.getParameterTypes().length > 0) {
            badArgs = null;
        } else {
            badArgs = new Object[1];
        }
        try {
            method.invoke(null, badArgs);
        } catch (IllegalArgumentException e) {
            // Only should happen. We used the unmatched parameter array.
            return;
        } catch (Exception e) {
            throw new RuntimeException("Unknown exception thrown when resolve static method.", e);
        }
        throw new RuntimeException("No IllegalArgumentException thrown when resolve static method.");
    }

    public static HookInfo getHookInfo(long artMethod) {
        HookInfo result = sHookInfoMap.get(artMethod);
        if (result == null) {
            throw new AssertionError("Cannot find HookInfo for ArtMethod pointer 0x" + Long.toHexString(artMethod));
        }
        return result;
    }

    public static Object getObject(long thread, long address) {
        if (address == 0) return null;
        return getObject0(thread, address);
    }

    public static long getAddress(long thread, Object o) {
        if (o == null) return 0;
        return getAddress0(thread, o);
    }

    static Object callBackupMethod(Member origin, Method backup, Object thisObject, Object[] args) throws InvocationTargetException, IllegalAccessException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // On Android 7.0+, java.lang.Class object is movable and may cause crash when
            // invoke backup method, so we update declaring_class when invoke backup method.
            Class<?> declaring = origin.getDeclaringClass();
            updateDeclaringClass(origin, backup);
            Object result = backup.invoke(thisObject, args);

            // Explicit use declaring_class object to ensure it has reference on stack
            // and avoid being moved by gc.
            declaring.getClass();
            return result;
        } else {
            return backup.invoke(thisObject, args);
        }
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object... args) throws IllegalAccessException, InvocationTargetException {
        if (method == null) throw new NullPointerException("method == null");
        boolean isMethod;
        if (method instanceof Method) {
            isMethod = true;
            ((Method) method).setAccessible(true);
        } else if (method instanceof Constructor) {
            isMethod = false;
            ((Constructor) method).setAccessible(true);
        } else {
            throw new IllegalArgumentException("method must be of type Method or Constructor");
        }

        HookInfo hookInfo = sHookInfoMap.get(getArtMethod(method));
        if (hookInfo == null) {
            // Not hooked
            if (isMethod) {
                return ((Method) method).invoke(thisObject, args);
            } else {
                if (thisObject != null)
                    throw new IllegalArgumentException(
                            "Cannot invoke a not hooked Constructor with a non-null receiver");
                try {
                    ((Constructor) method).newInstance(args);
                    return null;
                } catch (InstantiationException e) {
                    throw new IllegalArgumentException("invalid Constructor", e);
                }
            }
        }

        return callBackupMethod(hookInfo.target, hookInfo.backup, thisObject, args);
    }

    public static boolean compile(Member method) {
        int modifiers = method.getModifiers();
        Class<?> declaring = method.getDeclaringClass();

        if (!(method instanceof Method || method instanceof Constructor))
            throw new IllegalArgumentException("Only methods and constructors can be compiled: " + method);

        if (declaring.isInterface())
            throw new IllegalArgumentException("Cannot compile interfaces: " + method);

        if (Modifier.isAbstract(modifiers))
            throw new IllegalArgumentException("Cannot compile abstract methods: " + method);

        if (Modifier.isNative(modifiers) || Proxy.isProxyClass(declaring)) {
            // Cannot compile native methods and proxy methods
            return false;
        }

        ensureInitialized();
        return compile0(Primitives.currentArtThread(), method);
    }

    public static boolean decompile(Member method, boolean disableJit) {
        int modifiers = method.getModifiers();
        Class<?> declaring = method.getDeclaringClass();

        if (!(method instanceof Method || method instanceof Constructor))
            throw new IllegalArgumentException("Only methods and constructors can be decompiled: " + method);

        if (declaring.isInterface())
            throw new IllegalArgumentException("Cannot decompile interfaces: " + method);

        if (Modifier.isAbstract(modifiers))
            throw new IllegalArgumentException("Cannot decompile abstract methods: " + method);

        if (Proxy.isProxyClass(declaring)) {
            // Proxy methods entry is fixed at art_quick_proxy_invoke_handler.
            return false;
        }
        ensureInitialized();
        return decompile0(method, disableJit);
    }

    public static boolean disableJitInline() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // No JIT.
            return false;
        }
        ensureInitialized();
        return disableJitInline0();
    }

    public static Object handleHookedMethod(HookInfo hookInfo, Object thisObject, Object[] args)
            throws Throwable {
        if (PineConfig.debug)
            Log.d(TAG, "handleHookedMethod: target=" + hookInfo.target + " thisObject=" +
                    thisObject + " args=" + Arrays.toString(args));

        if (PineConfig.disableHooks || hookInfo.emptyCallbacks()) {
            try {
                return callBackupMethod(hookInfo.target, hookInfo.backup, thisObject, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        CallFrame callFrame = new CallFrame(hookInfo, thisObject, args);
        MethodHook[] callbacks = hookInfo.getCallbacks();

        // call before callbacks
        int beforeIdx = 0;
        do {
            MethodHook callback = callbacks[beforeIdx];
            try {
                callback.beforeHookedMethod(callFrame);
            } catch (Throwable e) {
                Log.e(TAG, "Unexpected exception occurred when calling " + callback.getClass().getName() + ".beforeHookedMethod()", e);
                // reset result (ignoring what the unexpectedly exiting callback did)
                callFrame.resetResult();
                continue;
            }
            if (callFrame.returnEarly) {
                // skip remaining "before" callbacks and corresponding "after" callbacks
                beforeIdx++;
                break;
            }
        } while (++beforeIdx < callbacks.length);

        // call original method if not requested otherwise
        if (!callFrame.returnEarly) {
            try {
                callFrame.setResult(callFrame.invokeOriginalMethod());
            } catch (InvocationTargetException e) {
                callFrame.setThrowable(e.getTargetException());
            }
        }

        // call after callbacks
        int afterIdx = beforeIdx - 1;
        do {
            MethodHook callback = callbacks[afterIdx];
            Object lastResult = callFrame.getResult();
            Throwable lastThrowable = callFrame.getThrowable();
            try {
                callback.afterHookedMethod(callFrame);
            } catch (Throwable e) {
                Log.e(TAG, "Unexpected exception occurred when calling " + callback.getClass().getName() + ".afterHookedMethod()", e);

                // reset to last result (ignoring what the unexpectedly exiting callback did)
                if (lastThrowable == null)
                    callFrame.setResult(lastResult);
                else
                    callFrame.setThrowable(lastThrowable);
            }
        } while (--afterIdx >= 0);

        // return
        if (callFrame.hasThrowable())
            throw callFrame.getThrowable();
        else
            return callFrame.getResult();
    }

    public static void log(String message) {
        if (PineConfig.debug) {
            Log.i(TAG, message);
        }
    }

    public static void log(String fmt, Object... args) {
        if (PineConfig.debug) {
            Log.i(TAG, String.format(fmt, args));
        }
    }

    private static native void init0(int androidVersion, Method m1, Method m2, int accessFlags,
                                     boolean debuggable);

    private static native void enableFastNative();

    private static native long getArtMethod(Member method);

    private static native Method hook0(long thread, Class<?> declaring, Member target, Method bridge,
                                       boolean isInlineHook, boolean isNativeOrProxy);

    private static native boolean compile0(long thread, Member method);

    private static native boolean decompile0(Member method, boolean disableJit);

    private static native boolean disableJitInline0();

    private static native Object getObject0(long thread, long address);

    private static native long getAddress0(long thread, Object o);

    public static native void getArgs32(int extras, int[] out, int sp, boolean skipR1);

    public static native void getArgs64(long extras, long[] out, long sp);

    private static native void updateDeclaringClass(Member origin, Method backup);


    public static final class HookInfo {
        public final Member target;
        public Method backup;
        public boolean isNonStatic;
        public int paramNumber;
        public Class<?>[] paramTypes;
        private Set<MethodHook> callbacks = Collections.synchronizedSet(new HashSet<MethodHook>());

        HookInfo(Member target) {
            this.target = target;
        }

        public void addCallback(MethodHook callback) {
            callbacks.add(callback);
        }

        public void removeCallback(MethodHook callback) {
            callbacks.remove(callback);
        }

        public boolean emptyCallbacks() {
            return callbacks.isEmpty();
        }

        public MethodHook[] getCallbacks() {
            return callbacks.toArray(new MethodHook[callbacks.size()]);
        }
    }

    public static class CallFrame {
        public final Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        /* package */ boolean returnEarly;
        private HookInfo hookInfo;

        public CallFrame(HookInfo hookInfo, Object thisObject, Object[] args) {
            this.hookInfo = hookInfo;
            this.method = hookInfo.target;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null)
                throw throwable;
            return result;
        }

        public void resetResult() {
            this.result = null;
            this.throwable = null;
            this.returnEarly = false;
        }

        public Object invokeOriginalMethod() throws InvocationTargetException, IllegalAccessException {
            return callBackupMethod(hookInfo.target, hookInfo.backup, thisObject, args);
        }

        public Object invokeOriginalMethod(Object thisObject, Object... args) throws InvocationTargetException, IllegalAccessException {
            return callBackupMethod(hookInfo.target, hookInfo.backup, thisObject, args);
        }
    }

    public interface HookListener {
        void beforeHook(Member method, MethodHook callback);

        void afterHook(Member method, MethodHook.Unhook unhook);
    }

    public interface LibLoader {
        void loadLib();
    }

    public interface HookMode {
        int AUTO = 0;
        int INLINE = 1;
        int REPLACEMENT = 2;
    }
}
