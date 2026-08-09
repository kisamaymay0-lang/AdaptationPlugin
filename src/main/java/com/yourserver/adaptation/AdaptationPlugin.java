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
        getLogger().info("Плагин AdaptationPlugin [50%-FIX] успешно запущен!");
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

    // ВАЖНО: Меняем событие на EntityDamageEvent, чтобы ловить ЛАВУ, ПАДЕНИЕ и ВСЕ типы урона!
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();

        int totalEnchantLevel = 0;
        int pieceCount = 0;
        
        // Проверка брони по тексту Lore
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

        // Если адаптация уже работает — режем урон ровно ВДВОЕ на фулл-сете
        if (activeAdaptations.containsKey(uuid)) {
            String currentAdaptType = activeAdaptations.get(uuid);
            String incomingType = getDamageType(event.getCause());

            if (incomingType.equals(currentAdaptType)) {
                // Защита: 12.5% за каждый предмет. 4 предмета = 50% (урон срезается вдвое) [stem-calculative-problem-solving]
                double reduction = 1.0 - (pieceCount * 0.125); 
                event.setDamage(event.getDamage() * reduction);
            } else {
                // Штраф: урон не совпал -> получаем на 10% больше за каждый предмет
                double penalty = 1.0 + (pieceCount * 0.10);
                event.setDamage(event.getDamage() * penalty);
            }
            return; 
        }

        // Логика накопления ударов
        String damageType = getDamageType(event.getCause());
        
        // Безопасность: игнорируем типы урона, которые не входят в категории (например, голодание или пустота)
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

    // Распределяем абсолютно ВСЕ ванильные источники урона Майнкрафта
    private String getDamageType(DamageCause cause) {
        switch (cause) {
            // 🏹 СНАРЯДЫ И ВЗРЫВЫ
            case PROJECTILE:
            case BLOCK_EXPLOSION:
            case ENTITY_EXPLOSION:
                return "RANGED"; 

            // 🧪 МАГИЯ И ЗЕЛЬЯ
            case MAGIC:
            case POISON:
            case WITHER:
            case DRAGON_BREATH:
            case SONIC_BOOM: // Крик Вардена
                return "MAGIC";  

            // ⚔️ БЛИЖНИЙ УРОН, ЛАВА, ОКРУЖЕНИЕ
            case ENTITY_ATTACK:
            case ENTITY_SWEEP_ATTACK:
            case LAVA:
            case FIRE:
            case FIRE_TICK:
            case CONTACT: // Кактусы / Ягоды
            case FALL: // Падение с высоты
            case FLY_INTO_WALL: // Элитры
            case SUFFOCATION: // Задохнулся в стене
            case DROWNING: // Утонул
            case HOT_FLOOR: // Магма-блок
            case FREEZE: // Рыхлый снег
            case THORNS: // Чары шипов
                return "MELEE";

            // Игнорируем то, от чего броня спасать не должна (команды, бездна, голод)
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
