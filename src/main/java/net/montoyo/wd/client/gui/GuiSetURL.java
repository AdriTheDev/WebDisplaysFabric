package net.montoyo.wd.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.utilities.data.BlockSide;

public class GuiSetURL extends Screen {
    private final BlockPos blockPos;
    private final BlockSide side;
    private EditBox urlField;

    public GuiSetURL(BlockPos pos, BlockSide side) {
        super(Component.translatable("webdisplays.gui.seturl.url"));
        this.blockPos = pos;
        this.side = side;
    }

    @Override
    protected void init() {
        urlField = new EditBox(this.font, this.width / 2 - 100, this.height / 2 - 30, 200, 20,
                Component.translatable("webdisplays.gui.seturl.url"));
        urlField.setMaxLength(2048);
        urlField.setValue("https://");
        this.addRenderableWidget(urlField);
        this.setInitialFocus(urlField);

        this.addRenderableWidget(Button.builder(
                Component.translatable("webdisplays.gui.seturl.ok"),
                button -> applyUrl()
        ).bounds(this.width / 2 - 100, this.height / 2 + 10, 95, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("webdisplays.gui.seturl.cancel"),
                button -> this.onClose()
        ).bounds(this.width / 2 + 5, this.height / 2 + 10, 95, 20).build());
    }

    private void applyUrl() {
        String url = urlField.getValue();
        if (!url.isEmpty()) {
            try {
                String finalUrl = ScreenBlockEntity.url(url);
                if (ClientPlayNetworking.canSend(ScreenActionPayload.TYPE)) {
                    ClientPlayNetworking.send(ScreenActionPayload.setUrl(blockPos, side.id, finalUrl));
                }
                if (Minecraft.getInstance().level != null
                        && Minecraft.getInstance().level.getBlockEntity(blockPos) instanceof ScreenBlockEntity screen) {
                    screen.setScreenURL(side, finalUrl);
                }
            } catch (Exception ignored) {
                // Malformed URL: leave the screen unchanged.
            }
        }
        this.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 50, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
