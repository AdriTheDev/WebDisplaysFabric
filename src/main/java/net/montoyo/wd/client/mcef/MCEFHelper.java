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

    /**
     * Sets the volume of every media element on the page, and keeps new ones in line.
     *
     * <p>CEF has no per-browser volume control, so this drives the HTML5 media elements
     * directly. Simply assigning {@code element.volume} loses: a site with its own volume
     * control (YouTube being the obvious one) reasserts its stored level within a second, so
     * the setting appeared to take and then snapped back to full.
     *
     * <p>Instead the setter on {@code HTMLMediaElement.prototype} is replaced, which every
     * assignment on the page necessarily goes through. The page's value is remembered and
     * handed back by the getter, so its own slider still reads and behaves normally, but what
     * reaches the element is that value scaled by the screen's setting. The screen volume is
     * then a ceiling on the page rather than a fight with it. A timer still sweeps existing
     * elements, since media created before the hook - or set through some path that avoids the
     * setter - would otherwise stay loud.
     */
    public static void setBrowserVolume(Object browser, float volume) {
        float clamped = Math.max(0.0f, Math.min(1.0f, volume));
        injectJavascript(browser, VOLUME_JS_PREFIX + clamped + ")");
    }

    /**
     * The volume script, less its trailing argument. Injected on page load as well as on every
     * change, because a fresh document starts with an unhooked prototype and full volume.
     */
    public static final String VOLUME_JS_PREFIX =
            "(function(v){window.__wdVolume=v;"
            // Hook the prototype once. Keep the original accessors: they are the only way to
            // reach the real element volume once the property has been shadowed.
            + "if(!window.__wdVolumeDesc){var d=Object.getOwnPropertyDescriptor("
            + "HTMLMediaElement.prototype,'volume');"
            + "if(d&&d.get&&d.set){window.__wdVolumeDesc=d;"
            + "Object.defineProperty(HTMLMediaElement.prototype,'volume',{configurable:true,"
            + "enumerable:d.enumerable,"
            + "get:function(){return this.__wdPageVolume===undefined?d.get.call(this):this.__wdPageVolume},"
            + "set:function(x){this.__wdPageVolume=x;d.set.call(this,x*window.__wdVolume)}})}}"
            + "var apply=function(){try{document.querySelectorAll('video,audio').forEach("
            + "function(e){var page=e.__wdPageVolume===undefined?1:e.__wdPageVolume;"
            + "var target=page*window.__wdVolume;var d=window.__wdVolumeDesc;"
            + "if(d){if(Math.abs(d.get.call(e)-target)>0.001)d.set.call(e,target)}"
            + "else if(Math.abs(e.volume-target)>0.001){e.volume=target}"
            // Only ever undo a mute this script applied; a page that muted itself deliberately
            // is left alone.
            + "if(window.__wdVolume<=0){e.__wdMuted=true;e.muted=true}"
            + "else if(e.__wdMuted){e.__wdMuted=false;e.muted=false}})}catch(err){}};"
            + "apply();if(!window.__wdVolumeTimer)window.__wdVolumeTimer=setInterval(apply,1000)})(";

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

    /** AWT event ids, spelled out so the AWT class does not have to be imported for two ints. */
    private static final int AWT_KEY_PRESSED = 400 + 1;
    private static final int AWT_KEY_RELEASED = 400 + 2;

    public static void sendKeyPress(Object browser, int glfwKeyCode, long scanCode, int modifiers) {
        if (sendAwtKey(browser, AWT_KEY_PRESSED, glfwKeyCode, (int) scanCode, modifiers)) return;
        invokeVoid(browser, "onKeyPressed", new Class[]{KeyEvent.class},
                new KeyEvent(glfwKeyCode, (int) scanCode, modifiers));
    }

    public static void sendKeyRelease(Object browser, int glfwKeyCode, long scanCode, int modifiers) {
        if (sendAwtKey(browser, AWT_KEY_RELEASED, glfwKeyCode, (int) scanCode, modifiers)) return;
        invokeVoid(browser, "onKeyReleased", new Class[]{KeyEvent.class},
                new KeyEvent(glfwKeyCode, (int) scanCode, modifiers));
    }

    /**
     * Hands a key straight to JCEF as an AWT event carrying its scan code.
     *
     * <p>MCEF's own onKeyPressed builds an AWT event with no scan code, which on Windows leaves
     * Chromium with no virtual key code to act on - see {@link ScancodeKeyEvent}. This does the
     * same job with the scan code filled in. The AWT key code and modifier translation is still
     * MCEF's, borrowed rather than reimplemented so the two paths cannot disagree.
     *
     * <p>Returns false, so the caller falls back to MCEF, if any part of that is missing: this
     * reaches past MCEF's public API into JCEF, and a version that has moved things around
     * should degrade to the old behaviour rather than swallow every keystroke.
     */
    private static boolean sendAwtKey(Object browser, int awtEventId, int glfwKeyCode,
                                      int scanCode, int modifiers) {
        if (browser == null) return false;
        try {
            Class<?> cls = browser.getClass();
            Method toAwtKeyCode = findCachedMethod(cls, "toAwtKeyCode", int.class);
            Method toAwtModifiers = findCachedMethod(cls, "toAwtInputModifiers", int.class);
            Method getComponent = findCachedMethod(cls, "getUIComponent");
            Method send = findCachedMethod(cls, "sendKeyEvent", java.awt.event.KeyEvent.class);
            if (toAwtKeyCode == null || toAwtModifiers == null || getComponent == null || send == null) {
                return false;
            }

            Object component = getComponent.invoke(browser);
            if (!(component instanceof java.awt.Component awtComponent)) return false;

            int keyCode = (int) toAwtKeyCode.invoke(null, glfwKeyCode);
            int awtModifiers = (int) toAwtModifiers.invoke(null, modifiers);
            send.invoke(browser, new ScancodeKeyEvent(awtComponent, awtEventId, awtModifiers,
                    keyCode, (char) keyCode, scanCode));
            return true;
        } catch (Throwable e) {
            Log.warning("Direct key delivery unavailable, falling back to MCEF: {}", e.toString());
            return false;
        }
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
