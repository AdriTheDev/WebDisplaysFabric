package net.montoyo.wd.client.mcef;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.montoyo.wd.utilities.Log;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
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
    /** Dummy AWT source component for synthesised events; JCEF's off-screen browser ignores it. */
    private static final Component EVENT_SOURCE = new Canvas();

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

    private static void sendMouseEvent(Object browser, MouseEvent event) {
        Object cef = cefBrowser(browser);
        invokeVoid(cef, "sendMouseEvent", new Class[]{MouseEvent.class}, event);
    }

    public static void sendMouseClick(Object browser, int x, int y, int button, boolean release, int clickCount) {
        int awtButton = switch (button) {
            case 1 -> MouseEvent.BUTTON2; // middle
            case 2 -> MouseEvent.BUTTON3; // right
            default -> MouseEvent.BUTTON1;
        };
        sendMouseEvent(browser, new MouseEvent(EVENT_SOURCE,
                release ? MouseEvent.MOUSE_RELEASED : MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, y, Math.max(1, clickCount), false, awtButton));
    }

    public static void sendMouseMove(Object browser, int x, int y, boolean leave) {
        sendMouseEvent(browser, new MouseEvent(EVENT_SOURCE,
                leave ? MouseEvent.MOUSE_EXITED : MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, x, y, 0, false, MouseEvent.NOBUTTON));
    }

    public static void sendMouseWheel(Object browser, int x, int y, double amount, int modifiers) {
        Object cef = cefBrowser(browser);
        MouseWheelEvent event = new MouseWheelEvent(EVENT_SOURCE, MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), modifiers, x, y, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 100, (int) Math.signum(amount));
        invokeVoid(cef, "sendMouseWheelEvent", new Class[]{MouseWheelEvent.class}, event);
    }

    private static void sendKeyEvent(Object browser, KeyEvent event) {
        Object cef = cefBrowser(browser);
        invokeVoid(cef, "sendKeyEvent", new Class[]{KeyEvent.class}, event);
    }

    /** Sends a typed character (KEY_TYPED), which is what text fields consume. */
    public static void sendKeyEvent(Object browser, char c) {
        sendKeyEvent(browser, new KeyEvent(EVENT_SOURCE, KeyEvent.KEY_TYPED,
                System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, c));
    }

    public static void sendKeyPress(Object browser, int glfwKeyCode, long scanCode, int glfwModifiers) {
        int vk = glfwToAwtKeyCode(glfwKeyCode);
        sendKeyEvent(browser, new KeyEvent(EVENT_SOURCE, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), glfwToAwtModifiers(glfwModifiers), vk, (char) vk));
    }

    public static void sendKeyRelease(Object browser, int glfwKeyCode, long scanCode, int glfwModifiers) {
        int vk = glfwToAwtKeyCode(glfwKeyCode);
        sendKeyEvent(browser, new KeyEvent(EVENT_SOURCE, KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(), glfwToAwtModifiers(glfwModifiers), vk, (char) vk));
    }

    private static int glfwToAwtModifiers(int glfwModifiers) {
        int awt = 0;
        if ((glfwModifiers & 0x0001) != 0) awt |= InputEvent.SHIFT_DOWN_MASK;
        if ((glfwModifiers & 0x0002) != 0) awt |= InputEvent.CTRL_DOWN_MASK;
        if ((glfwModifiers & 0x0004) != 0) awt |= InputEvent.ALT_DOWN_MASK;
        if ((glfwModifiers & 0x0008) != 0) awt |= InputEvent.META_DOWN_MASK;
        return awt;
    }

    private static int glfwToAwtKeyCode(int keyCode) {
        if (keyCode >= 65 && keyCode <= 90) return keyCode;   // A-Z
        if (keyCode >= 48 && keyCode <= 57) return keyCode;   // 0-9
        if (keyCode >= 290 && keyCode <= 301) return keyCode - 290 + KeyEvent.VK_F1;
        return switch (keyCode) {
            case 256 -> KeyEvent.VK_ESCAPE;
            case 257 -> KeyEvent.VK_ENTER;
            case 258 -> KeyEvent.VK_TAB;
            case 259 -> KeyEvent.VK_BACK_SPACE;
            case 260 -> KeyEvent.VK_INSERT;
            case 261 -> KeyEvent.VK_DELETE;
            case 262 -> KeyEvent.VK_RIGHT;
            case 263 -> KeyEvent.VK_LEFT;
            case 264 -> KeyEvent.VK_DOWN;
            case 265 -> KeyEvent.VK_UP;
            case 266 -> KeyEvent.VK_PAGE_UP;
            case 267 -> KeyEvent.VK_PAGE_DOWN;
            case 268 -> KeyEvent.VK_HOME;
            case 269 -> KeyEvent.VK_END;
            case 340 -> KeyEvent.VK_SHIFT;
            case 341 -> KeyEvent.VK_CONTROL;
            case 342 -> KeyEvent.VK_ALT;
            case 344 -> KeyEvent.VK_CAPS_LOCK;
            case 32 -> KeyEvent.VK_SPACE;
            case 39 -> KeyEvent.VK_QUOTE;
            case 44 -> KeyEvent.VK_COMMA;
            case 45 -> KeyEvent.VK_MINUS;
            case 46 -> KeyEvent.VK_PERIOD;
            case 47 -> KeyEvent.VK_SLASH;
            case 59 -> KeyEvent.VK_SEMICOLON;
            case 61 -> KeyEvent.VK_EQUALS;
            case 91 -> KeyEvent.VK_OPEN_BRACKET;
            case 92 -> KeyEvent.VK_BACK_SLASH;
            case 93 -> KeyEvent.VK_CLOSE_BRACKET;
            case 96 -> KeyEvent.VK_BACK_QUOTE;
            default -> keyCode;
        };
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
