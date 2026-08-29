package net.montoyo.wd.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.data.BlockSide;
import org.jetbrains.annotations.Nullable;

public class KeyboardBlockEntity extends BlockEntity {
    private BlockPos linkedPos = null;
    private BlockSide linkedSide = null;

    public KeyboardBlockEntity(BlockPos pos, BlockState state) {
        super(WDRegistries.KEYBOARD_BLOCK_ENTITY, pos, state);
    }

    public void setLinked(BlockPos pos, BlockSide side) {
        this.linkedPos = pos;
        this.linkedSide = side;
        setChanged();
    }

    public void clearLinked() {
        this.linkedPos = null;
        this.linkedSide = null;
        setChanged();
    }

    public @Nullable BlockPos getLinkedPos() { return linkedPos; }
    public @Nullable BlockSide getLinkedSide() { return linkedSide; }

    @Override
    public void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (linkedPos != null && linkedSide != null) {
            output.putInt("lx", linkedPos.getX());
            output.putInt("ly", linkedPos.getY());
            output.putInt("lz", linkedPos.getZ());
            output.putInt("ls", linkedSide.id);
        }
    }

    @Override
    public void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        if (input.getInt("lx").isPresent()) {
            linkedPos = new BlockPos(
                    input.getIntOr("lx", 0),
                    input.getIntOr("ly", 0),
                    input.getIntOr("lz", 0));
            linkedSide = BlockSide.fromInt(input.getIntOr("ls", 0));
        } else {
            linkedPos = null;
            linkedSide = null;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
