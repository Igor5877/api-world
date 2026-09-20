package com.nguyendevs.freesia.backend;

import com.nguyendevs.freesia.backend.misc.VirtualPlayerManager;
import com.nguyendevs.freesia.backend.tracker.TrackerProcessor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import static net.kyori.adventure.text.format.TextColor.color;

public final class FreesiaBackend extends JavaPlugin implements Listener {
    public static FreesiaBackend INSTANCE;

    private volatile Player payloadPlayer;

    private final TrackerProcessor trackerProcessor = new TrackerProcessor();
    private final VirtualPlayerManager virtualPlayerManager = new VirtualPlayerManager();

    @Override
    public void onEnable() {
        INSTANCE = this;

        Bukkit.getMessenger().registerIncomingPluginChannel(this, TrackerProcessor.CHANNEL_NAME, this.trackerProcessor);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, TrackerProcessor.CHANNEL_NAME);

        Bukkit.getMessenger().registerIncomingPluginChannel(this, VirtualPlayerManager.CHANNEL_NAME, this.virtualPlayerManager);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, VirtualPlayerManager.CHANNEL_NAME);

        Bukkit.getPluginManager().registerEvents(this.trackerProcessor, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&5[&dFreesia-Backend&5] &aFreesia Backend plugin enabled successfully!"));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (this.payloadPlayer == null) {
            this.payloadPlayer = event.getPlayer();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer().equals(this.payloadPlayer)) {
            this.payloadPlayer = Bukkit.getOnlinePlayers().stream().findAny().orElse(null);
        }
    }

    public Player getPayloadPlayer() {
        return this.payloadPlayer;
    }

    public TrackerProcessor getTrackerProcessor() {
        return this.trackerProcessor;
    }

    public VirtualPlayerManager getVirtualPlayerManager() {
        return this.virtualPlayerManager;
    }

    @Override
    public void onDisable() {
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&5[&dFreesia-Backend&5] &cFreesia Backend plugin disabled!"));
    }
}
