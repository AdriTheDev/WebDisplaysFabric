package net.montoyo.wd.client;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.resources.Identifier;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of a screen block's visible faces, taken during the extract phase so that the
 * submit phase never touches the block entity.
 */
public class ScreenRenderState extends BlockEntityRenderState {
    public final List<Face> faces = new ArrayList<>();

    /** Cursor overlay, or null when the cursor is not on this block. */
    public boolean hasCursor;
    public BlockSide cursorSide;
    public float cursorX, cursorY, cursorZ;

    public static final class Face {
        public BlockSide side;
        public Rotation rotation;
        public Identifier texture;
        public float width, height;
        public int resolutionX, resolutionY;
    }
}
