package net.montoyo.wd.client.mcef;

import java.awt.Component;
import java.awt.event.KeyEvent;

/**
 * An AWT key event that carries a hardware scan code where JCEF can find it.
 *
 * <p>On Windows, JCEF derives the virtual key code it hands to Chromium entirely from a field
 * literally named {@code scancode} on the event object:
 *
 * <pre>
 *   GetJNIFieldLong(env, cls, key_event, "scancode", &amp;scanCode);
 *   BYTE VkCode = LOBYTE(MapVirtualKey(scanCode, MAPVK_VSC_TO_VK));
 *   ...
 *   cef_event.windows_key_code = VkCode;   // for KEY_PRESSED
 * </pre>
 *
 * <p>AWT fills that field in only for events it produced itself from real Windows messages. An
 * event constructed in Java leaves it at zero, so {@code MapVirtualKey(0, ...)} yields zero and
 * every synthesised key-down reaches Chromium with no virtual key code at all. Typing still
 * appeared to work, because typed text travels on the separate KEY_TYPED path which uses the
 * character rather than the key code - which is exactly why backspace, enter and tab, the keys
 * with no character of their own, did nothing.
 *
 * <p>The field is package-private in {@code java.awt.event}, and Java's module encapsulation
 * rules out reflecting into it. Declaring our own is enough: JNI's field lookup starts at the
 * object's runtime class, so this one is what JCEF reads.
 */
public class ScancodeKeyEvent extends KeyEvent {

    /**
     * Read by JCEF through JNI. Not referenced from Java, hence the deliberate absence of a
     * getter: the name and the type are the entire contract.
     */
    @SuppressWarnings("unused")
    public long scancode;

    public ScancodeKeyEvent(Component source, int id, int modifiers, int keyCode, char keyChar,
                            int scancode) {
        super(source, id, System.currentTimeMillis(), modifiers, keyCode, keyChar);
        // Windows scan codes are a single byte; GLFW reports extended keys with an extra bit
        // set, which MapVirtualKey does not understand in that form and would reject outright.
        // Dropping it maps an extended key to its unextended twin - numpad enter behaves as
        // return - which is far better than the key doing nothing.
        this.scancode = scancode & 0xFF;
    }
}
