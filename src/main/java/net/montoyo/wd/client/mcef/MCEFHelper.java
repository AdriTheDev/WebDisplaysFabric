package net.montoyo.wd.client.mcef;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.montoyo.wd.utilities.Log;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reflection-based bridge to MCEF Modern (net.dimaskama.mcef).
 *
 * <p>MCEF stays an optional dependency: nothing here is referenced at compile time except
 * vanilla Blaze3D types, so the mod loads and runs (with blank screens) when MCEF is absent.
 *
 * <p>Browser input is driven through the underlying JCEF {@code CefBrowser} using AWT events
 * rather than MCEF Modern's {@code MCEFBrowser} event methods, because WebDisplays synthesises
 * its own events from world-space raycasts instead of forwarding real GUI input.
 */
public class MCEFHelper {
    private static final String API_CLASS = "net.dimaskama.mcef.api.MCEFApi";

    private static boolean available = false;
    private static boolean checked = false;
    private static volatile Object api = null;
    private static volatile boolean initFailed = false;

    private static final ConcurrentHashMap<String, Method> methodCache = new ConcurrentHashMap<>();

    public static boolean isMCEFAvailable() {
        if (!checked) {
            checked = true;
            try {
                Class.forName(API_CLASS);
                available = true;
                Log.info("MCEF Modern classes found");
            } catch (Throwable e) {
                available = false;
                Log.info("MCEF Modern not available: {}", e.getMessage());
            }
        }
        return available;
    }

    public static boolean isMCEFInitialized() {
        return api != null;
    }

    /**
     * Kicks off MCEF's asynchronous initialisation and reports the outcome to {@code callback}.
     * The callback runs on whichever thread completes the future.
     */
    @SuppressWarnings("unchecked")
    public static void scheduleInit(Consumer<Boolean> callback) {
        if (!isMCEFAvailable()) {
            callback.accept(false);
            return;
        }
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            Method getFuture = apiClass.getMethod("getInstanceFuture");
            CompletableFuture<Object> future = (CompletableFuture<Object>) getFuture.invoke(null);
            future.whenComplete((instance, error) -> {
                if (error != null || instance == null) {
                    initFailed = true;
                    Log.warning("MCEF initialization failed: {}", error != null ? error.getMessage() : "no instance");
                    callback.accept(false);
                } else {
                    api = instance;
                    callback.accept(true);
                }
            });
        } catch (Throwable e) {
            initFailed = true;
            Log.warning("Failed to schedule MCEF init: {}", e.getMessage());
            callback.accept(false);
        }
    }

    public static boolean isInitFailed() {
        return initFailed;
    }

    public static Object createBrowser(String url, boolean transparent, int width, int height) {
        Object instance = api;
        if (instance == null) return null;
        try {
            Method create = findCachedMethod(instance.getClass(), "createBrowser", String.class, boolean.class);
            if (create == null) return null;
            Object browser = create.invoke(instance, url, transparent);
            if (browser != null) {
                // MCEF Modern requires resize() to be called right after creation.
                resizeBrowser(browser, width, height);
            }
            return browser;
        } catch (Throwable e) {
            Log.warning("Failed to create browser: {}", e.getMessage());
            return null;
        }
    }

    public static void resizeBrowser(Object browser, int width, int height) {
        invokeVoid(browser, "resize", new Class[]{int.class, int.class}, width, height);
    }

    public static void closeBrowser(Object browser) {
        if (browser == null) return;
        // Stop any playing media before tearing the browser down.
        injectJavascript(browser, "(function(){try{document.querySelectorAll('video,audio')"
                + ".forEach(function(e){e.pause();e.muted=true;e.src='';e.load()})}catch(e){}})()");
        invokeVoid(browser, "close", new Class[]{});
    }

    public static void setFocus(Object browser, boolean focused) {
        invokeVoid(browser, "setFocus", new Class[]{boolean.class}, focused);
    }

    /** Returns the GPU texture view holding the browser's current frame, or null if not ready. */
    public static GpuTextureView getBrowserTextureView(Object browser) {
        if (browser == null) return null;
        try {
            Method m = findCachedMethod(browser.getClass(), "getTextureView");
            if (m == null) return null;
            Object view = m.invoke(browser);
            return (view instanceof GpuTextureView tv) ? tv : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // === JCEF passthrough ===

    private static Object cefBrowser(Object browser) {
        if (browser == null) return null;
        try {
            Method m = findCachedMethod(browser.getClass(), "getCefBrowser");
            return m != null ? m.invoke(browser) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    public static void loadBrowserUrl(Object browser, String url) {
        Object cef = cefBrowser(browser);
        invokeVoid(cef, "loadURL", new Class[]{String.class}, url);
    }

    public static String getBrowserUrl(Object browser) {
        Object cef = cefBrowser(browser);
        if (cef == null) return "";
        try {
            Method m = findCachedMethod(cef.getClass(), "getURL");
            if (m == null) return "";
            Object url = m.invoke(cef);
            return url != null ? url.toString() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    public static void injectJavascript(Object browser, String code) {
        Object cef = cefBrowser(browser);
        invokeVoid(cef, "executeJavaScript", new Class[]{String.class, String.class, int.class}, code, "", 0);
    }

    // === Synthesised input ===
    //
    // These go through MCEF Modern's own MCEFBrowser methods rather than JCEF's raw AWT
    // events. MCEF translates GLFW codes and modifiers to AWT internally and tracks drag
    // and enter/exit state as it does so; feeding CefBrowser AWT events directly bypasses
    // all of that and the browser ignores them.

    public static void sendMouseClick(Object browser, int x, int y, int button, boolean release, int clickCount) {
        MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
        if (release) {
            invokeVoid(browser, "onMouseReleased", new Class[]{MouseButtonEvent.class}, event);
        } else {
            invokeVoid(browser, "onMouseClicked", new Class[]{MouseButtonEvent.class, boolean.class},
                    event, clickCount > 1);
        }
    }

    public static void sendMouseMove(Object browser, int x, int y, boolean leave) {
        invokeVoid(browser, "onMouseMoved", new Class[]{int.class, int.class}, x, y);
    }

    public static void sendMouseWheel(Object browser, int x, int y, double amount, int modifiers) {
        invokeVoid(browser, "onMouseScrolled", new Class[]{int.class, int.class, double.class}, x, y, amount);
    }

    /** Sends a typed character, which is what text fields consume. */
    public static void sendKeyEvent(Object browser, char c) {
        invokeVoid(browser, "onCharTyped", new Class[]{CharacterEvent.class}, new CharacterEvent(c));
    }

    public static void sendKeyPress(Object browser, int glfwKeyCode, long scanCode, int modifiers) {
        invokeVoid(browser, "onKeyPressed", new Class[]{KeyEvent.class},
                new KeyEvent(glfwKeyCode, (int) scanCode, modifiers));
    }

    public static void sendKeyRelease(Object browser, int glfwKeyCode, long scanCode, int modifiers) {
        invokeVoid(browser, "onKeyReleased", new Class[]{KeyEvent.class},
                new KeyEvent(glfwKeyCode, (int) scanCode, modifiers));
    }

    // === Reflection plumbing ===

    private static void invokeVoid(Object target, String name, Class<?>[] paramTypes, Object... args) {
        if (target == null) return;
        try {
            Method m = findCachedMethod(target.getClass(), name, paramTypes);
            if (m != null) m.invoke(target, args);
        } catch (Throwable e) {
            Log.warning("MCEF call {} failed: {}", name, e.getMessage());
        }
    }

    private static Method findCachedMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        String key = clazz.getName() + "#" + name + "(" + java.util.Arrays.toString(paramTypes) + ")";
        Method cached = methodCache.get(key);
        if (cached != null) return cached;
        cached = findMethod(clazz, name, paramTypes);
        if (cached != null) {
            cached.setAccessible(true);
            methodCache.put(key, cached);
        }
        return cached;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                for (Class<?> iface : current.getInterfaces()) {
                    try {
                        return iface.getMethod(name, paramTypes);
                    } catch (NoSuchMethodException ignored) {
                        // keep looking
                    }
                }
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
