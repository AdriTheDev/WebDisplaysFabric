package net.montoyo.wd.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.montoyo.wd.client.ScreenCursorTracker;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.data.BlockSide;

public class InputScreen extends Screen {

    private final BlockPos screenPos;
    private final BlockSide screenSide;
    private final StringBuilder inputBuffer = new StringBuilder();

    public InputScreen(BlockPos screenPos, BlockSide screenSide) {
        super(Component.literal(""));
        this.screenPos = screenPos;
        this.screenSide = screenSide;
    }

    @Override
    protected void init() {
        // CEF discards key events aimed at an unfocused browser, so typing does nothing until
        // the browser is told it has focus.
        Object browser = getBrowser();
        if (browser != null) {
            MCEFHelper.setFocus(browser, true);
        }
    }

    @Override
    public void removed() {
        Object browser = getBrowser();
        if (browser != null) {
            MCEFHelper.setFocus(browser, false);
        }
        super.removed();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        Object browser = getBrowser();
        if (browser != null) {
            MCEFHelper.sendKeyPress(browser, event.key(), 0, event.modifiers());
            if (event.key() == InputConstants.KEY_BACKSPACE && inputBuffer.length() > 0) {
                inputBuffer.setLength(inputBuffer.length() - 1);
            }
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        Object browser = getBrowser();
        if (browser != null) {
            MCEFHelper.sendKeyRelease(browser, event.key(), 0, event.modifiers());
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        Object browser = getBrowser();
        int codePoint = event.codepoint();
        if (browser != null) {
            MCEFHelper.sendKeyEvent(browser, (char) codePoint);
        }
        if (codePoint >= 32 && codePoint != 127) {
            inputBuffer.appendCodePoint(codePoint);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
        if (cursor != null && cursor.screenData != null && cursor.screenData.browser != null) {
            int clickCount = doubled ? 2 : 1;
            cursor.screenData.lastClickTime = System.currentTimeMillis();
            MCEFHelper.sendMouseClick(cursor.screenData.browser, cursor.pixelX, cursor.pixelY, event.button(), false, clickCount);
            MCEFHelper.sendMouseClick(cursor.screenData.browser, cursor.pixelX, cursor.pixelY, event.button(), true, clickCount);
        }
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Do not call extractBackground here: Screen#extractRenderStateWithTooltipAndSubtitles
        // already does, and blurring twice in one frame throws. Leaving the background alone
        // also keeps the screen you are typing on visible, which is the point of this overlay.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        String display = inputBuffer.toString();
        if (display.length() > 40) display = "..." + display.substring(display.length() - 37);
        if (display.isEmpty()) display = " ";
        graphics.text(this.font, "> " + display + " _", 8, 8, 0xFFFFFFFF, true);
        graphics.text(this.font, "ESC to exit", 8, 20, 0xFF888888, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public boolean isFor(BlockPos pos, BlockSide side) {
        return screenPos.equals(pos) && screenSide == side;
    }

    public BlockPos getScreenPos() { return screenPos; }
    public BlockSide getScreenSide() { return screenSide; }

    private Object getBrowser() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (mc.level.getBlockEntity(screenPos) instanceof ScreenBlockEntity screen) {
            ScreenData data = screen.getScreen(screenSide);
            return data != null ? data.browser : null;
        }
        return null;
    }
}
