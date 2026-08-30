package net.montoyo.wd.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenInputPayload;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Applies screen input relayed by the server to this client's browser.
 *
 * <p>Every client, including whoever performed the action, drives its browser from here rather than
 * from its own input handlers. Going through the server costs a round trip but means all copies of
 * a page receive identical events in identical order, which is what makes a screen look like one
 * shared session instead of several private ones.
 */
public final class ClientInputHandler {

    /**
     * Browsers already told they have focus. CEF discards key events aimed at an unfocused
     * browser, and focus set once at creation does not always survive the page finishing its
     * load. Focus is therefore (re)asserted the first time a key is actually delivered, which
     * is late enough to stick and, unlike doing it per click, never lands immediately before a
     * mouse press where it would swallow the press.
     */
    private static final Set<Object> FOCUSED = Collections.newSetFromMap(new WeakHashMap<>());

    private ClientInputHandler() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ScreenInputPayload.TYPE,
                (payload, context) -> context.client().execute(() -> apply(payload)));
    }

    private static void apply(ScreenInputPayload payload) {
        Object browser = browserAt(payload.pos(), payload.side());
        if (browser == null) return;

        switch (payload.kind()) {
            case ScreenInputPayload.KIND_MOUSE_DOWN -> {
                // Hovering is local, so other clients are not pointing here. Move first so the
                // press lands where the sender actually clicked.
                MCEFHelper.sendMouseMove(browser, payload.a(), payload.b(), false);
                MCEFHelper.sendMouseClick(browser, payload.a(), payload.b(),
                        payload.button(), false, payload.clickCount());
            }
            case ScreenInputPayload.KIND_MOUSE_UP -> MCEFHelper.sendMouseClick(browser,
                    payload.a(), payload.b(), payload.button(), true, 1);
            case ScreenInputPayload.KIND_SCROLL -> {
                MCEFHelper.sendMouseMove(browser, payload.a(), payload.b(), false);
                MCEFHelper.sendMouseWheel(browser, payload.a(), payload.b(), payload.scrollAmount(), 0);
            }
            case ScreenInputPayload.KIND_KEY_DOWN -> {
                ensureFocused(browser);
                MCEFHelper.sendKeyPress(browser, payload.a(), 0, payload.b());

                // Backspace, enter and tab do nothing on a key-down alone. Windows follows
                // WM_KEYDOWN with a WM_CHAR carrying the control character for these, and CEF's
                // off-screen input path expects that pair; Minecraft never reports a typed
                // character for them, so the character has to be synthesised here or the key
                // is silently ignored. Printable keys already arrive with their own char event
                // and must not be doubled up.
                char control = controlCharFor(payload.a());
                if (control != 0) {
                    MCEFHelper.sendKeyEvent(browser, control);
                }
            }
            case ScreenInputPayload.KIND_KEY_UP ->
                    MCEFHelper.sendKeyRelease(browser, payload.a(), 0, payload.b());
            case ScreenInputPayload.KIND_CHAR -> {
                ensureFocused(browser);
                MCEFHelper.sendKeyEvent(browser, (char) payload.a());
            }
            default -> { }
        }
    }

    /** The character CEF expects alongside an editing key, or 0 for keys that need none. */
    private static char controlCharFor(int glfwKey) {
        return switch (glfwKey) {
            case InputConstants.KEY_BACKSPACE -> '\b';
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> '\r';
            case InputConstants.KEY_TAB -> '\t';
            default -> 0;
        };
    }

    private static void ensureFocused(Object browser) {
        if (FOCUSED.add(browser)) {
            MCEFHelper.setFocus(browser, true);
        }
    }

    /**
     * Resolves the browser a payload targets, falling back to the block's only screen when the
     * named face has none. A keyboard is linked by right-clicking whichever face of the screen
     * block the player happened to hit, which is easily a different face from the one carrying
     * the screen; without the fallback that mistake silently swallows every keystroke.
     */
    public static Object browserAt(BlockPos pos, int sideId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (!(mc.level.getBlockEntity(pos) instanceof ScreenBlockEntity screen)) return null;

        ScreenData data = screen.getScreen(BlockSide.fromInt(sideId));
        if (data == null && screen.screenCount() == 1) {
            data = screen.getScreen(0);
        }
        return data != null ? data.browser : null;
    }

    /** Sends one interaction to the server; it comes back through {@link #apply} to be applied. */
    public static void send(ScreenInputPayload payload) {
        if (ClientPlayNetworking.canSend(ScreenInputPayload.TYPE)) {
            ClientPlayNetworking.send(payload);
        }
    }
}
