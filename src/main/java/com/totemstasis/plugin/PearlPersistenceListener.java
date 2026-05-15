package com.totemstasis.plugin;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class PearlPersistenceListener implements Listener {
    private final TotemStasisPlugin plugin;
    private final File pearlsFile;
    private final Map<UUID, PearlRecord> pearls = new HashMap<>();
    private BukkitTask saveTask;

    PearlPersistenceListener(TotemStasisPlugin plugin) {
        this.plugin = plugin;
        this.pearlsFile = new File(plugin.getDataFolder(), "pearls.yml");
        loadPearls();
    }

    void start() {
        if (!plugin.getConfig().getBoolean("pearl-persistence.enabled", true)) {
            return;
        }

        long interval = plugin.getConfig().getLong("pearl-persistence.save-interval-ticks", 20L);
        saveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            refreshLivePearls();
            savePearls();
        }, interval, interval);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                restorePearl(player);
            }
        });
    }

    void cleanUp() {
        if (saveTask != null) {
            saveTask.cancel();
        }
        refreshLivePearls();
        savePearls();
    }

    @EventHandler(ignoreCancelled = true)
    void onPearlThrown(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player player)) {
            return;
        }
        pearls.put(player.getUniqueId(), PearlRecord.from(pearl));
        savePearls();
    }

    @EventHandler(ignoreCancelled = true)
    void onPearlHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) {
            return;
        }

        ProjectileSource shooter = pearl.getShooter();
        if (shooter instanceof Player player) {
            pearls.remove(player.getUniqueId());
            savePearls();
        } else {
            removeByPearlId(pearl.getUniqueId());
        }
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PearlRecord record = pearls.get(player.getUniqueId());
        if (record == null) {
            return;
        }

        EnderPearl pearl = findPearl(record.pearlId());
        if (pearl != null && !pearl.isDead()) {
            pearls.put(player.getUniqueId(), PearlRecord.from(pearl));
            pearl.remove();
            savePearls();
        }
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> restorePearl(event.getPlayer()), 5L);
    }

    private void refreshLivePearls() {
        Iterator<Map.Entry<UUID, PearlRecord>> iterator = pearls.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PearlRecord> entry = iterator.next();
            EnderPearl pearl = findPearl(entry.getValue().pearlId());
            if (pearl == null || pearl.isDead()) {
                if (plugin.getServer().getPlayer(entry.getKey()) != null) {
                    iterator.remove();
                }
                continue;
            }
            entry.setValue(PearlRecord.from(pearl));
        }
    }

    private void restorePearl(Player player) {
        PearlRecord record = pearls.get(player.getUniqueId());
        if (record == null) {
            return;
        }

        EnderPearl existing = findPearl(record.pearlId());
        if (existing != null && !existing.isDead()) {
            existing.setShooter(player);
            return;
        }

        World world = plugin.getServer().getWorld(record.worldId());
        if (world == null) {
            return;
        }

        Location location = new Location(world, record.x(), record.y(), record.z(), record.yaw(), record.pitch());
        if (!location.getChunk().isLoaded()) {
            location.getChunk().load();
        }

        EnderPearl pearl = world.spawn(location, EnderPearl.class, spawned -> {
            spawned.setShooter(player);
            spawned.setVelocity(new Vector(record.velocityX(), record.velocityY(), record.velocityZ()));
            spawned.setGravity(record.gravity());
            spawned.setPersistent(true);
        });
        pearls.put(player.getUniqueId(), PearlRecord.from(pearl));
        savePearls();
    }

    private EnderPearl findPearl(UUID pearlId) {
        for (World world : plugin.getServer().getWorlds()) {
            Entity entity = world.getEntity(pearlId);
            if (entity instanceof EnderPearl pearl) {
                return pearl;
            }
        }
        return null;
    }

    private void removeByPearlId(UUID pearlId) {
        UUID ownerToRemove = null;
        for (Map.Entry<UUID, PearlRecord> entry : pearls.entrySet()) {
            if (entry.getValue().pearlId().equals(pearlId)) {
                ownerToRemove = entry.getKey();
                break;
            }
        }
        if (ownerToRemove != null) {
            pearls.remove(ownerToRemove);
            savePearls();
        }
    }

    private void loadPearls() {
        if (!pearlsFile.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(pearlsFile);
        if (!yaml.isConfigurationSection("pearls")) {
            return;
        }

        for (String ownerId : yaml.getConfigurationSection("pearls").getKeys(false)) {
            String path = "pearls." + ownerId + ".";
            try {
                UUID owner = UUID.fromString(ownerId);
                UUID pearlId = UUID.fromString(yaml.getString(path + "pearl"));
                UUID worldId = UUID.fromString(yaml.getString(path + "world"));
                pearls.put(owner, new PearlRecord(
                    pearlId,
                    worldId,
                    yaml.getDouble(path + "x"),
                    yaml.getDouble(path + "y"),
                    yaml.getDouble(path + "z"),
                    (float) yaml.getDouble(path + "yaw"),
                    (float) yaml.getDouble(path + "pitch"),
                    yaml.getDouble(path + "velocity-x"),
                    yaml.getDouble(path + "velocity-y"),
                    yaml.getDouble(path + "velocity-z"),
                    yaml.getBoolean(path + "gravity", true)
                ));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping broken saved pearl record for " + ownerId + ".");
            }
        }
    }

    private void savePearls() {
        plugin.getDataFolder().mkdirs();

        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PearlRecord> entry : pearls.entrySet()) {
            String path = "pearls." + entry.getKey() + ".";
            PearlRecord record = entry.getValue();
            yaml.set(path + "pearl", record.pearlId().toString());
            yaml.set(path + "world", record.worldId().toString());
            yaml.set(path + "x", record.x());
            yaml.set(path + "y", record.y());
            yaml.set(path + "z", record.z());
            yaml.set(path + "yaw", record.yaw());
            yaml.set(path + "pitch", record.pitch());
            yaml.set(path + "velocity-x", record.velocityX());
            yaml.set(path + "velocity-y", record.velocityY());
            yaml.set(path + "velocity-z", record.velocityZ());
            yaml.set(path + "gravity", record.gravity());
        }

        try {
            yaml.save(pearlsFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save persistent pearls: " + exception.getMessage());
        }
    }

    private record PearlRecord(
        UUID pearlId,
        UUID worldId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        double velocityX,
        double velocityY,
        double velocityZ,
        boolean gravity
    ) {
        private static PearlRecord from(EnderPearl pearl) {
            Location location = pearl.getLocation();
            Vector velocity = pearl.getVelocity();
            return new PearlRecord(
                pearl.getUniqueId(),
                location.getWorld().getUID(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                velocity.getX(),
                velocity.getY(),
                velocity.getZ(),
                pearl.hasGravity()
            );
        }
    }
}
