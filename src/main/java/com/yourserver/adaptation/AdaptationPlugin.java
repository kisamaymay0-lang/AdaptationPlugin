package com.yourserver.adaptation;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class AdaptationPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Map<String, Integer>> damageCounters = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> superDamageCounters = new HashMap<>(); 
    private final Map<UUID, BukkitTask> activeTimers = new HashMap<>();
    private final Map<UUID, String> activeAdaptations = new HashMap<>();
    private final Map<UUID, Boolean> superAdaptations = new HashMap<>(); 

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Плагин AdaptationPlugin [SUPER-ADAPTATION] успешно запущен!");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : activeTimers.values()) {
            task.cancel();
        }
        damageCounters.clear();
        superDamageCounters.clear();
        activeTimers.clear();
        activeAdaptations.clear();
        superAdaptations.clear();
    }

    // 🔨 МЕХАНИКА НАКОВАЛЬНИ
    @EventHandler
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory anvil = event.getInventory();
        ItemStack leftItem = anvil.getItem(0); 
        ItemStack rightItem = anvil.getItem(1); 

        if (leftItem == null || rightItem == null) return;

        if (!leftItem.getType().name().contains("HELMET") &&
            !leftItem.getType().name().contains("CHESTPLATE") &&
            !leftItem.getType().name().contains("LEGGINGS") &&
            !leftItem.getType().name().contains("BOOTS")) return;

        if (rightItem.getType() != Material.ENCHANTED_BOOK) return;

        ItemMeta bookMeta = rightItem.getItemMeta();
        if (bookMeta == null || !bookMeta.hasLore()) return;

        String foundEnchant = null;
        for (String line : bookMeta.getLore()) {
            if (line.contains("Адаптация I") || line.contains("Адаптация II") || line.contains("Адаптация III")) {
                foundEnchant = line;
                break;
            }
        }

        if (foundEnchant == null) return;

        ItemStack result = leftItem.clone();
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) return;

        List<String> lore = resultMeta.hasLore() ? resultMeta.getLore() : new ArrayList<>();
        lore.removeIf(line -> line.contains("Адаптация I") || line.contains("Адаптация II") || line.contains("Адаптация III"));
        lore.add(foundEnchant); 
        
        resultMeta.setLore(lore);
        result.setItemMeta(resultMeta);

        event.setResult(result);
        anvil.setRepairCost(5); 
    }
    // 🛡️ РАСЧЕТ УРОНА И НАКОПЛЕНИЯ
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

        String damageType = getDamageType(event.getCause());
        if (damageType.equals("UNKNOWN")) return;

        // Если любая адаптация уже работает
        if (activeAdaptations.containsKey(uuid)) {
            String currentAdaptType = activeAdaptations.get(uuid);

            if (damageType.equals(currentAdaptType)) {
                if (superAdaptations.getOrDefault(uuid, false)) {
                    // ПОВЫШЕННАЯ ФАЗА: Намертво режем урон на 80%
                    event.setDamage(event.getDamage() * 0.20);
                } else {
                    // ОБЫЧНАЯ ФАЗА: Режем на 50%
                    double reduction = 1.0 - (pieceCount * 0.125); 
                    event.setDamage(event.getDamage() * reduction);

                    // Копим 8 ударов для ПОВЫШЕННОЙ фазы
                    superDamageCounters.putIfAbsent(uuid, new HashMap<>());
                    Map<String, Integer> sCounters = superDamageCounters.get(uuid);
                    int sHits = sCounters.getOrDefault(damageType, 0) + 1;
                    sCounters.put(damageType, sHits);

                    if (sHits >= 8) {
                        activateSuperAdaptation(player, damageType);
                    }
                }
            } else {
                // Штраф: урон не совпал (+10% за вещь)
                double penalty = 1.0 + (pieceCount * 0.10);
                event.setDamage(event.getDamage() * penalty);
            }
            return; 
        }

        // Обычное накопление (10-8-6)
        double avgLevel = (double) totalEnchantLevel / pieceCount;
        int requiredHits = 10;
        int durationSeconds = 10;

        if (avgLevel > 1.0 && avgLevel <= 2.0) {
            requiredHits = 8;
        } else if (avgLevel > 2.0) {
            requiredHits = 6;
        }

        damageCounters.putIfAbsent(uuid, new HashMap<>());
        Map<String, Integer> pCounters = damageCounters.get(uuid);
        int currentHits = pCounters.getOrDefault(damageType, 0) + 1;
        pCounters.put(damageType, currentHits);

        if (currentHits >= requiredHits) {
            activateAdaptation(player, damageType, durationSeconds);
        }
    }

    private void activateAdaptation(Player player, String type, int durationSeconds) {
        UUID uuid = player.getUniqueId();
        activeAdaptations.put(uuid, type);
        damageCounters.remove(uuid); 
        superDamageCounters.remove(uuid); 

        playBellSound(player, 0.9f, 20L); // Обычный темп

        String message = "";
        if (type.equals("MELEE")) message = ChatColor.RED + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: БЛИЖ. УРОН!";
        if (type.equals("RANGED")) message = ChatColor.GREEN + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: СНАРЯДАМ!";
        if (type.equals("MAGIC")) message = ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: МАГИИ!";

        startActionBarTimer(player, message, durationSeconds);
    }

    private void activateSuperAdaptation(Player player, String type) {
        UUID uuid = player.getUniqueId();
        
        if (activeTimers.containsKey(uuid)) {
            activeTimers.get(uuid).cancel();
        }

        superAdaptations.put(uuid, true); 
        superDamageCounters.remove(uuid);

        playBellSound(player, 1.4f, 15L); // Ускоренный набат тревоги

        String message = "";
        if (type.equals("MELEE")) message = ChatColor.DARK_RED + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD + "ПОВЫШЕННАЯ АДАПТАЦИЯ К: БЛИЖ. УРОН!";
        if (type.equals("RANGED")) message = ChatColor.DARK_GREEN + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD + "ПОВЫШЕННАЯ АДАПТАЦИЯ К: СНАРЯДАМ!";
        if (type.equals("MAGIC")) message = ChatColor.DARK_PURPLE + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD + "ПОВЫШЕННАЯ АДАПТАЦИЯ К: МАГИИ!";

        startActionBarTimer(player, message, 8); // Повышенная всегда идет 8 секунд
    }

    private void startActionBarTimer(Player player, String msg, int seconds) {
        UUID uuid = player.getUniqueId();
        
        BukkitTask timerTask = new BukkitRunnable() {
            int timeLeft = seconds;

            @Override
            public void run() {
                if (!player.isOnline() || timeLeft <= 0) {
                    activeAdaptations.remove(uuid);
                    superAdaptations.remove(uuid);
                    superDamageCounters.remove(uuid);
                    activeTimers.remove(uuid);
                    if (player.isOnline()) {
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("")); 
                    }
                    cancel(); 
                    return;
                }

                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
                timeLeft--;
            }
        }.runTaskTimer(this, 0L, 20L);

        activeTimers.put(uuid, timerTask);
    }

    private void playBellSound(Player player, float pitch, long period) {
        player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, pitch); 
        new BukkitRunnable() {
            int bellCount = 1;
            @Override
            public void run() {
                if (!player.isOnline() || bellCount >= 3) {
                    cancel(); 
                    return;
                }
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, pitch);
                bellCount++;
            }
        }.runTaskTimer(this, period, period);
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
        superDamageCounters.remove(uuid);
        activeAdaptations.remove(uuid);
        superAdaptations.remove(uuid);
        if (activeTimers.containsKey(uuid)) {
            activeTimers.get(uuid).cancel();
            activeTimers.remove(uuid);
        }
    }
}
