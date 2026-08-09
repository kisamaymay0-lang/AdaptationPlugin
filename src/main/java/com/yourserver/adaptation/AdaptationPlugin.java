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
    private final Map<UUID, BukkitTask> activeTimers = new HashMap<>();
    private final Map<UUID, String> activeAdaptations = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Плагин AdaptationPlugin [ANVIL & NEW MATH] успешно запущен!");
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

    // 🔨 МЕХАНИКА НАКОВАЛЬНИ ДЛЯ КНИГ С АДАПТАЦИЕЙ
    @EventHandler
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory anvil = event.getInventory();
        ItemStack leftItem = anvil.getItem(0); // Броня
        ItemStack rightItem = anvil.getItem(1); // Книга зачарования

        if (leftItem == null || rightItem == null) return;

        // Проверяем, что первый предмет — броня, а второй — книга зачарования
        if (!leftItem.getType().name().contains("HELMET") &&
            !leftItem.getType().name().contains("CHESTPLATE") &&
            !leftItem.getType().name().contains("LEGGINGS") &&
            !leftItem.getType().name().contains("BOOTS")) return;

        if (rightItem.getType() != Material.ENCHANTED_BOOK) return;

        ItemMeta bookMeta = rightItem.getItemMeta();
        if (bookMeta == null || !bookMeta.hasLore()) return;

        // Ищем уровень адаптации в книге
        String foundEnchant = null;
        for (String line : bookMeta.getLore()) {
            if (line.contains("Адаптация I") || line.contains("Адаптация II") || line.contains("Адаптация III")) {
                foundEnchant = line;
                break;
            }
        }

        if (foundEnchant == null) return;

        // Создаем результат скрещивания
        ItemStack result = leftItem.clone();
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) return;

        List<String> lore = resultMeta.hasLore() ? resultMeta.getLore() : new ArrayList<>();
        
        // Очищаем старые уровни адаптации на броне, если они были, чтобы заменить на новый
        lore.removeIf(line -> line.contains("Адаптация I") || line.contains("Адаптация II") || line.contains("Адаптация III"));
        lore.add(foundEnchant); // Добавляем новый уровень из книги
        
        resultMeta.setLore(lore);
        result.setItemMeta(resultMeta);

        // Устанавливаем результат в наковальню и цену в 5 уровней опыта
        event.setResult(result);
        anvil.setRepairCost(4); 
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

        // 🧮 НОВАЯ МАТЕМАТИКА УРОВНЕЙ С ТВОЕГО СКРИНШОТА
        int requiredHits = 6;
        int durationSeconds = 8;

        if (avgLevel > 1.0 && avgLevel <= 2.0) {
            requiredHits = 10;
            durationSeconds = 16;
        } else if (avgLevel > 2.0) {
            requiredHits = 8;
            durationSeconds = 16;
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

        player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, 0.9f); 

        new BukkitRunnable() {
            int bellCount = 1;

            @Override
            public void run() {
                if (!player.isOnline() || bellCount >= 3 || !activeAdaptations.containsKey(uuid)) {
                    cancel(); 
                    return;
                }
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, 0.9f);
                bellCount++;
            }
        }.runTaskTimer(this, 20L, 20L); 

        String message = "";
        if (type.equals("MELEE")) message = ChatColor.RED + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: БЛИЖ. УРОН!";
        if (type.equals("RANGED")) message = ChatColor.GREEN + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: СНАРЯДАМ!";
        if (type.equals("MAGIC")) message = ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: МАГИИ!";

        final String finalMessage = message;

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

