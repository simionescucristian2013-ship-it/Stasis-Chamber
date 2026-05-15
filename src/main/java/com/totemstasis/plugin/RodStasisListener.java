package com.totemstasis.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class RodStasisListener implements Listener {
    private static final BlockFace[] NEARBY = {
        BlockFace.DOWN,
        BlockFace.NORTH,
        BlockFace.EAST,
        BlockFace.SOUTH,
        BlockFace.WEST
    };

    private final TotemStasisPlugin plugin;
    private final NamespacedKey rodSessionKey;
    private final NamespacedKey standSessionKey;
    private final Map<UUID, UUID> pendingHooks = new HashMap<>();
    private final Map<String, RodSession> sessions = new HashMap<>();
    private final File sessionsFile;
    private BukkitTask standKeeper;

    RodStasisListener(TotemStasisPlugin plugin) {
        this.plugin = plugin;
        this.rodSessionKey = new NamespacedKey(plugin, "rod_stasis_session");
        this.standSessionKey = new NamespacedKey(plugin, "rod_stasis_stand");
        this.sessionsFile = new File(plugin.getDataFolder(), "rod-sessions.yml");
        loadSessions();
    }

    void startPlateKeeper() {
        long interval = plugin.getConfig().getLong("rod-stasis.keep-plate-lit-ticks", 20L);
        standKeeper = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Iterator<Map.Entry<String, RodSession>> iterator = sessions.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, RodSession> entry = iterator.next();
                RodSession session = entry.getValue();
                Block plate = blockFromKey(session.plateKey());
                if (plate == null || !isPressurePlate(plate.getType())) {
                    removeStand(session);
                    iterator.remove();
                    continue;
                }

                ArmorStand stand = findStand(session);
                if (stand == null || stand.isDead()) {
                    stand = spawnStand(entry.getKey(), plate);
                    session.setStandId(stand.getUniqueId());
                    saveSessions();
                } else {
                    Location center = centerOf(plate);
                    if (!stand.getWorld().equals(center.getWorld()) || stand.getLocation().distanceSquared(center) > 0.05D) {
                        stand.teleport(center);
                    }
                }

                setNearbyTrapdoors(plate, true);
            }
            tickPendingHooks();
        }, interval, interval);
    }

    void cleanUp() {
        if (standKeeper != null) {
            standKeeper.cancel();
        }
        saveSessions();
    }

    @EventHandler(ignoreCancelled = true)
    void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("totemstasis.use")) {
            return;
        }

        if (event.getState() == PlayerFishEvent.State.FISHING) {
            HeldRod heldRod = heldRod(player);
            if (heldRod == null || sessionId(heldRod.item()) != null) {
                return;
            }
            pendingHooks.put(player.getUniqueId(), event.getHook().getUniqueId());
            return;
        }

        if (event.getState() == PlayerFishEvent.State.REEL_IN || event.getState() == PlayerFishEvent.State.IN_GROUND) {
            HeldRod linkedRod = linkedHeldRod(player);
            if (linkedRod != null) {
                recallRod(player, sessionId(linkedRod.item()), linkedRod.hand());
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    void onHookHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof FishHook hook) || !(hook.getShooter() instanceof Player player)) {
            return;
        }

        Block hitBlock = event.getHitBlock();
        if (hitBlock == null || !isPressurePlate(hitBlock.getType())) {
            return;
        }

        if (!player.hasPermission("totemstasis.use")) {
            return;
        }

        createRodSession(player, hook, hitBlock);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    void onUseLinkedRod(PlayerInteractEvent event) {
        if (event.getHand() == null) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FISHING_ROD) {
            return;
        }

        String sessionId = sessionId(item);
        if (sessionId == null) {
            return;
        }

        event.setCancelled(true);
        recallRod(player, sessionId, event.getHand());
    }

    private void tickPendingHooks() {
        double maxSpeed = plugin.getConfig().getDouble("rod-stasis.hook-settle-speed", 0.005D);
        Iterator<Map.Entry<UUID, UUID>> iterator = pendingHooks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, UUID> entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            FishHook hook = findHook(entry.getValue());
            if (player == null || hook == null || hook.isDead()) {
                iterator.remove();
                continue;
            }
            if (hook.getVelocity().lengthSquared() > maxSpeed) {
                continue;
            }

            Block plate = pressurePlateNear(hook.getLocation());
            if (plate == null) {
                continue;
            }

            createRodSession(player, hook, plate);
            iterator.remove();
        }
    }

    private void createRodSession(Player player, FishHook hook, Block plate) {
        HeldRod heldRod = heldRod(player);
        if (heldRod == null || sessionId(heldRod.item()) != null) {
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        tagRod(heldRod.item(), sessionId);

        ArmorStand stand = spawnStand(sessionId, plate);
        sessions.put(sessionId, new RodSession(StasisLocations.key(plate), stand.getUniqueId()));
        saveSessions();

        pendingHooks.remove(player.getUniqueId());
        hook.remove();
        setNearbyTrapdoors(plate, true);
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 1F, 1.4F);
    }

    private void recallRod(Player player, String sessionId, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.OFF_HAND
            ? player.getInventory().getItemInOffHand()
            : player.getInventory().getItemInMainHand();
        if (item.getType() != Material.FISHING_ROD || !sessionId.equals(sessionId(item))) {
            return;
        }

        RodSession session = sessions.remove(sessionId);
        clearRodTag(item);

        if (session != null) {
            Block plate = blockFromKey(session.plateKey());
            removeStand(session);
            if (plate != null) {
                setNearbyTrapdoors(plate, false);
            }
            saveSessions();
        }

        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 1F, 0.7F);
    }

    private ArmorStand spawnStand(String sessionId, Block plate) {
        Location location = centerOf(plate);
        ArmorStand stand = plate.getWorld().spawn(location, ArmorStand.class, armorStand -> {
            armorStand.setVisible(false);
            armorStand.setGravity(false);
            armorStand.setMarker(false);
            armorStand.setPersistent(true);
            armorStand.setInvulnerable(true);
            armorStand.setCollidable(false);
            armorStand.setSilent(true);
            armorStand.setSmall(false);
            armorStand.addScoreboardTag("rod_stasis_holder");
        });
        stand.setInvulnerable(true);
        stand.setRemoveWhenFarAway(false);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setCustomName("Rod Stasis Anchor");
        stand.getPersistentDataContainer().set(standSessionKey, PersistentDataType.STRING, sessionId);
        return stand;
    }

    private FishHook findHook(UUID hookId) {
        for (World world : plugin.getServer().getWorlds()) {
            Entity entity = world.getEntity(hookId);
            if (entity instanceof FishHook hook) {
                return hook;
            }
        }
        return null;
    }

    private ArmorStand findStand(RodSession session) {
        for (World world : plugin.getServer().getWorlds()) {
            Entity entity = world.getEntity(session.standId());
            if (entity instanceof ArmorStand stand) {
                return stand;
            }
        }
        return null;
    }

    private void removeStand(RodSession session) {
        ArmorStand stand = findStand(session);
        if (stand != null) {
            stand.remove();
        }
    }

    private Location centerOf(Block plate) {
        return plate.getLocation().add(0.5D, 0.1D, 0.5D);
    }

    private Block pressurePlateNear(Location location) {
        Block base = location.getBlock();
        if (isPressurePlate(base.getType())) {
            return base;
        }
        Block below = base.getRelative(BlockFace.DOWN);
        if (isPressurePlate(below.getType())) {
            return below;
        }
        return null;
    }

    private HeldRod heldRod(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType() == Material.FISHING_ROD) {
            return new HeldRod(EquipmentSlot.HAND, mainHand);
        }

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand.getType() == Material.FISHING_ROD) {
            return new HeldRod(EquipmentSlot.OFF_HAND, offHand);
        }
        return null;
    }

    private void tagRod(ItemStack rod, String sessionId) {
        ItemMeta meta = rod.getItemMeta();
        meta.getPersistentDataContainer().set(rodSessionKey, PersistentDataType.STRING, sessionId);
        rod.setItemMeta(meta);
    }

    private String sessionId(ItemStack rod) {
        if (!rod.hasItemMeta()) {
            return null;
        }
        return rod.getItemMeta().getPersistentDataContainer().get(rodSessionKey, PersistentDataType.STRING);
    }

    private String linkedSessionInHands(Player player) {
        HeldRod heldRod = linkedHeldRod(player);
        return heldRod == null ? null : sessionId(heldRod.item());
    }

    private HeldRod linkedHeldRod(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType() == Material.FISHING_ROD && sessionId(mainHand) != null) {
            return new HeldRod(EquipmentSlot.HAND, mainHand);
        }

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand.getType() == Material.FISHING_ROD && sessionId(offHand) != null) {
            return new HeldRod(EquipmentSlot.OFF_HAND, offHand);
        }
        return null;
    }

    private void clearRodTag(ItemStack rod) {
        if (!rod.hasItemMeta()) {
            return;
        }
        ItemMeta meta = rod.getItemMeta();
        meta.getPersistentDataContainer().remove(rodSessionKey);
        rod.setItemMeta(meta);
    }

    private Block blockFromKey(String key) {
        Location location = StasisLocations.fromKey(key);
        return location == null ? null : location.getBlock();
    }

    private boolean isPressurePlate(Material material) {
        return switch (material) {
            case ACACIA_PRESSURE_PLATE,
                BAMBOO_PRESSURE_PLATE,
                BIRCH_PRESSURE_PLATE,
                CHERRY_PRESSURE_PLATE,
                CRIMSON_PRESSURE_PLATE,
                DARK_OAK_PRESSURE_PLATE,
                HEAVY_WEIGHTED_PRESSURE_PLATE,
                JUNGLE_PRESSURE_PLATE,
                LIGHT_WEIGHTED_PRESSURE_PLATE,
                MANGROVE_PRESSURE_PLATE,
                OAK_PRESSURE_PLATE,
                POLISHED_BLACKSTONE_PRESSURE_PLATE,
                SPRUCE_PRESSURE_PLATE,
                STONE_PRESSURE_PLATE,
                WARPED_PRESSURE_PLATE -> true;
            default -> false;
        };
    }

    private void setNearbyTrapdoors(Block plate, boolean open) {
        if (!plugin.getConfig().getBoolean("rod-stasis.activate-trapdoor", true)) {
            return;
        }

        for (BlockFace face : NEARBY) {
            Block block = plate.getRelative(face);
            BlockData data = block.getBlockData();
            if (data instanceof Openable openable && block.getType().name().endsWith("_TRAPDOOR")) {
                openable.setOpen(open);
                block.setBlockData(openable, true);
            }
        }
    }

    private void loadSessions() {
        if (!sessionsFile.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionsFile);
        if (!yaml.isConfigurationSection("sessions")) {
            return;
        }

        for (String sessionId : yaml.getConfigurationSection("sessions").getKeys(false)) {
            String path = "sessions." + sessionId + ".";
            String plateKey = yaml.getString(path + "plate");
            String standId = yaml.getString(path + "stand");
            if (plateKey == null || standId == null) {
                continue;
            }
            sessions.put(sessionId, new RodSession(plateKey, UUID.fromString(standId)));
        }
    }

    private void saveSessions() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, RodSession> entry : sessions.entrySet()) {
            String path = "sessions." + entry.getKey() + ".";
            yaml.set(path + "plate", entry.getValue().plateKey());
            yaml.set(path + "stand", entry.getValue().standId().toString());
        }

        try {
            yaml.save(sessionsFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save rod stasis sessions: " + exception.getMessage());
        }
    }

    private record HeldRod(EquipmentSlot hand, ItemStack item) {
    }

    private static final class RodSession {
        private final String plateKey;
        private UUID standId;

        private RodSession(String plateKey, UUID standId) {
            this.plateKey = plateKey;
            this.standId = standId;
        }

        private String plateKey() {
            return plateKey;
        }

        private UUID standId() {
            return standId;
        }

        private void setStandId(UUID standId) {
            this.standId = standId;
        }
    }
}
