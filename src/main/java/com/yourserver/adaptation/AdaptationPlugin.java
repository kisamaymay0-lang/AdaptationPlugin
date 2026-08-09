package com.yourserver.adaptation;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class AdaptationPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Map<String, Integer>> damageCounters = new HashMap<>();
    private final Map<UUID, BukkitTask> activeTimers = new HashMap<>();
    private final Map<UUID, String> activeAdaptations = new HashMap<>();
    private NamespacedKey adaptationKey;

    @Override
    public void onEnable() {
        this.adaptationKey = new NamespacedKey(this, "adaptation_level");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Плагин AdaptationPlugin [NBT] успешно запущен!");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : activeTimers.values()) {
            task.cancel();
        }
        damageCounters.clear();
        activeTimers.clear();
        activeAdaptations.clear();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();

        int totalEnchantLevel = 0;
        int pieceCount = 0;
        
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && armor.hasItemMeta()) {
                Integer level = armor.getItemMeta().getPersistentDataContainer()
                        .get(adaptationKey, PersistentDataType.INTEGER);
                if (level != null && level > 0) {
                    totalEnchantLevel += level;
                    pieceCount++;
                }
            }
        }

        if (pieceCount == 0) return;

        if (activeAdaptations.containsKey(uuid)) {
            String currentAdaptType = activeAdaptations.get(uuid);
            String incomingType = getDamageType(event.getCause());

            if (incomingType.equals(currentAdaptType)) {
                double reduction = 1.0 - (pieceCount * 0.08); 
                event.setDamage(event.getDamage() * reduction);
            } else {
                double penalty = 1.0 + (pieceCount * 0.10);
                event.setDamage(event.getDamage() * penalty);
            }
            return; 
        }

        String damageType = getDamageType(event.getCause());
        double avgLevel = (double) totalEnchantLevel / pieceCount;
        int requiredHits = Math.max(3, (int) (10 - (avgLevel * 2)));

        damageCounters.putIfAbsent(uuid, new HashMap<>());
        Map<String, Integer> pCounters = damageCounters.get(uuid);
        int currentHits = pCounters.getOrDefault(damageType, 0) + 1;
        pCounters.put(damageType, currentHits);

        if (currentHits >= requiredHits) {
            activateAdaptation(player, damageType, avgLevel);
        }
    }

    private void activateAdaptation(Player player, String type, double avgLevel) {
        UUID uuid = player.getUniqueId();
        activeAdaptations.put(uuid, type);
        damageCounters.remove(uuid); 

        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.0f);
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.8f, 0.5f);

        String message = "";
        if (type.equals("MELEE")) message = ChatColor.RED + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: БЛИЖ. УРОН!";
        if (type.equals("RANGED")) message = ChatColor.GREEN + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: СНАРЯДАМ!";
        if (type.equals("MAGIC")) message = ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: МАГИИ!";

        final String finalMessage = message;
        int durationSeconds = Math.max(4, (int) (20 - (avgLevel * 4)));

        BukkitTask timerTask = new BukkitRunnable() {
            int timeLeft = durationSeconds;

            @Override
            public void run() {
                if (!player.isOnline() || timeLeft <= 0) {
                    activeAdaptations.remove(uuid);
                    activeTimers.remove(uuid);
                    if (player.isOnline()) {
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("")); 
                    }
                    cancel(); 
                    return;
                }

                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(finalMessage));
                timeLeft--;
            }
        }.runTaskTimer(this, 0L, 20L);

        activeTimers.put(uuid, timerTask);
    }

    private String getDamageType(DamageCause cause) {
        switch (cause) {
            case PROJECTILE:
                return "RANGED"; 
            case MAGIC:
            case POISON:
            case WITHER:
            case DRAGON_BREATH:
                return "MAGIC";  
            default:
                return "MELEE";  
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        damageCounters.remove(uuid);
        activeAdaptations.remove(uuid);
        if (activeTimers.containsKey(uuid)) {
            activeTimers.get(uuid).cancel();
            activeTimers.remove(uuid);
        }
    }
}
