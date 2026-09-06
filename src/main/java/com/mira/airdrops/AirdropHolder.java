package com.mira.airdrops;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class AirdropHolder implements InventoryHolder {
    public enum Type { MAIN, LOOT }
    private final Type type;
    private Inventory inventory;

    public AirdropHolder(Type type) { this.type = type; }
    public Type type() { return type; }
    public void bind(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Inventory not bound");
        return inventory;
    }
}
