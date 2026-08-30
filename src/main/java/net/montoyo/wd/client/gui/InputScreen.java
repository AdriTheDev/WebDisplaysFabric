package net.montoyo.wd.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.montoyo.wd.client.ScreenCursorTracker;
import net.montoyo.wd.client.ClientInputHandler;
import net.montoyo.wd.network.ScreenInputPayload;
import net.montoyo.wd.utilities.data.BlockSide;

public class InputScreen extends Screen {

    private final BlockPos screenPos;
    private final BlockSide screenSide;

    public InputScreen(BlockPos screenPos, BlockSide screenSide) {
        super(Component.literal(""));
        this.screenPos = screenPos;
        this.screenSide = screenSide;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        ClientInputHandler.send(ScreenInputPayload.keyDown(screenPos, screenSide.id,
                event.key(), event.modifiers()));
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        ClientInputHandler.send(ScreenInputPayload.keyUp(screenPos, screenSide.id,
                event.key(), event.modifiers()));
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        int codePoint = event.codepoint();
        ClientInputHandler.send(ScreenInputPayload.charTyped(screenPos, screenSide.id, codePoint));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
        if (cursor != null && cursor.screenData != null) {
            int clickCount = doubled ? 2 : 1;
            cursor.screenData.lastClickTime = System.currentTimeMillis();
            ClientInputHandler.send(ScreenInputPayload.mouseDown(cursor.pos, cursor.side.id,
                    cursor.pixelX, cursor.pixelY, event.button(), clickCount));
            ClientInputHandler.send(ScreenInputPayload.mouseUp(cursor.pos, cursor.side.id,
                    cursor.pixelX, cursor.pixelY, event.button()));
        }
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing. The default blurs and dims the world, which hides the very screen being
        // typed on; this overlay exists to be looked through.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Do not call extractBackground here: Screen#extractRenderStateWithTooltipAndSubtitles
        // already does, and blurring twice in one frame throws. Leaving the background alone
        // also keeps the screen you are typing on visible, which is the point of this overlay.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // No echo of what has been typed: the overlay cannot see the page's focused field, so
        // a local buffer drifts from it the moment anything edits the text. It does report
        // whether a browser was found, which is the difference between "typing does nothing"
        // and "this keyboard points at a face with no screen on it".
        if (ClientInputHandler.browserAt(screenPos, screenSide.id) != null) {
            graphics.text(this.font, "Typing on screen", 8, 8, 0xFFFFFFFF, true);
        } else {
            graphics.text(this.font, "No screen on the linked face", 8, 8, 0xFFFF5555, true);
        }
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
}
