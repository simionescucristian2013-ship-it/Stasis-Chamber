package com.totemstasis.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Objects;

final class StasisLocations {
    private StasisLocations() {
    }

    static String key(Block block) {
        return key(block.getLocation());
    }

    static String key(Location location) {
        return Objects.requireNonNull(location.getWorld()).getUID() + ":"
            + location.getBlockX() + ":"
            + location.getBlockY() + ":"
            + location.getBlockZ();
    }

    static Location fromKey(String key) {
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return null;
        }

        World world = Bukkit.getWorld(java.util.UUID.fromString(parts[0]));
        if (world == null) {
            return null;
        }

        return new Location(
            world,
            Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2]),
            Integer.parseInt(parts[3])
        );
    }
}
