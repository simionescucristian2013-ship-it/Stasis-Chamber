package com.totemstasis.plugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class TotemStasisPlugin extends JavaPlugin {
    private RodStasisListener rodStasis;
    private PearlPersistenceListener pearlPersistence;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.rodStasis = new RodStasisListener(this);
        this.pearlPersistence = new PearlPersistenceListener(this);

        getServer().getPluginManager().registerEvents(rodStasis, this);
        getServer().getPluginManager().registerEvents(pearlPersistence, this);

        rodStasis.startPlateKeeper();
        pearlPersistence.start();
    }

    @Override
    public void onDisable() {
        if (rodStasis != null) {
            rodStasis.cleanUp();
        }
        if (pearlPersistence != null) {
            pearlPersistence.cleanUp();
        }
    }
}
