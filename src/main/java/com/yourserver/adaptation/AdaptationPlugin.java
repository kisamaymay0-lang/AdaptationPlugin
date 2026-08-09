package com.yourserver.adaptation;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class AdaptationPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Map<String, Integer>> damageCounters = new HashMap<>();
    private final Map<UUID, BukkitTask> activeTimers = new HashMap<>();
    private final Map<UUID, String> activeAdaptations = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Плагин AdaptationPlugin [BELL-SOUND-FIX] успешно запущен!");
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
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();

        int totalEnchantLevel = 0;
        int pieceCount = 0;
        
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && armor.hasItemMeta()) {
                ItemMeta meta = armor.getItemMeta();
                if (meta.hasLore()) {
                    for (String line : meta.getLore()) {
                        if (line.contains("Адаптация III")) {
                            totalEnchantLevel += 3;
                            pieceCount++;
                            break;
                        } else if (line.contains("Адаптация II")) {
                            totalEnchantLevel += 2;
                            pieceCount++;
                            break;
                        } else if (line.contains("Адаптация I")) {
                            totalEnchantLevel += 1;
                            pieceCount++;
                            break;
                        }
                    }
                }
            }
        }

        if (pieceCount == 0) return;

        if (activeAdaptations.containsKey(uuid)) {
            String currentAdaptType = activeAdaptations.get(uuid);
            String incomingType = getDamageType(event.getCause());

            if (incomingType.equals(currentAdaptType)) {
                double reduction = 1.0 - (pieceCount * 0.125); 
                event.setDamage(event.getDamage() * reduction);
            } else {
                double penalty = 1.0 + (pieceCount * 0.10);
                event.setDamage(event.getDamage() * penalty);
            }
            return; 
        }

        String damageType = getDamageType(event.getCause());
        if (damageType.equals("UNKNOWN")) return;

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

        // 🔔 ТРОЙНОЙ ЗВОН КОЛОКОЛА С ИНТЕРВАЛОМ В 1 СЕКУНДУ (Громкость 3.0)
        // 1-й удар: звучит мгновенно в момент включения
        player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, 0.9f); 

        new BukkitRunnable() {
            int bellCount = 1;

            @Override
            public void run() {
                if (!player.isOnline() || bellCount >= 3 || !activeAdaptations.containsKey(uuid)) {
                    cancel(); // Отключаем таймер колокола, если игрок ливнул или эффект досрочно спал
                    return;
                }
                
                // 2-й и 3-й удары: проигрываются каждый тик этого шедулера
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, 0.9f);
                bellCount++;
            }
        }.runTaskTimer(this, 20L, 20L); // 20 тиков задержка перед стартом (1 сек), и повтор каждые 20 тиков (1 сек)

        // Настройка сообщения в экшн-баре
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
            case BLOCK_EXPLOSION:
            case ENTITY_EXPLOSION:
                return "RANGED"; 

            case MAGIC:
            case POISON:
            case WITHER:
            case DRAGON_BREATH:
            case SONIC_BOOM:
                return "MAGIC";  

            case ENTITY_ATTACK:
            case ENTITY_SWEEP_ATTACK:
            case LAVA:
            case FIRE:
            case FIRE_TICK:
            case CONTACT: 
            case FALL: 
            case FLY_INTO_WALL: 
            case SUFFOCATION: 
            case DROWNING: 
            case HOT_FLOOR: 
            case FREEZE: 
            case THORNS: 
                return "MELEE";

            default:
                return "UNKNOWN";  
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
