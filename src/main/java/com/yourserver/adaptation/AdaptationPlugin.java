package com.yourserver.adaptation;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
    private final Map<UUID, Long> lastHitTime = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Плагин AdaptationPlugin [OPTIMIZED] успешно запущен!");
    }

    @Override
    public void onDisable() {
        activeTimers.values().forEach(BukkitTask::cancel);
        damageCounters.clear(); superDamageCounters.clear(); activeTimers.clear(); activeAdaptations.clear(); superAdaptations.clear(); lastHitTime.clear();
    }

    // 🔨 ПОЛНАЯ ВАНИЛЬНАЯ НАКОВАЛЬНЯ (СКРЕЩИВАНИЕ ЧАРОВ)
    @EventHandler
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory anvil = event.getInventory();
        ItemStack left = anvil.getItem(0), right = anvil.getItem(1);
        if (left == null || right == null || right.getType() != Material.ENCHANTED_BOOK) return;

        String type = left.getType().name();
        if (!type.contains("HELMET") && !type.contains("CHESTPLATE") && !type.contains("LEGGINGS") && !type.contains("BOOTS") && left.getType() != Material.ENCHANTED_BOOK) return;

        int lvlLeft = getLvl(left), lvlRight = getLvl(right);
        if (lvlRight == 0) return;

        int finalLvl = (lvlLeft == lvlRight && lvlLeft < 3) ? lvlLeft + 1 : Math.max(lvlLeft, lvlRight);
        
        ItemStack result = left.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return;

        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.removeIf(l -> l.contains("Адаптация I") || l.contains("Адаптация II") || l.contains("Адаптация III"));
        
        String strLvl = finalLvl == 1 ? "I" : finalLvl == 2 ? "II" : "III";
        lore.add(ChatColor.PURPLE + "Адаптация " + strLvl);
        
        meta.setLore(lore); result.setItemMeta(meta);
        event.setResult(result); anvil.setRepairCost(5); 
    }

    private int getLvl(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return 0;
        for (String line : item.getItemMeta().getLore()) {
            if (line.contains("Адаптация III")) return 3;
            if (line.contains("Адаптация II")) return 2;
            if (line.contains("Адаптация I")) return 1;
        }
        return 0;
    }

    // 🛡️ ОБРАБОТКА УРОНА И БАЛАНСА
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity(); UUID uuid = player.getUniqueId();

        int totalLvl = 0, pieceCount = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            int lvl = getLvl(armor);
            if (lvl > 0) { totalLvl += lvl; pieceCount++; }
        }
        if (pieceCount == 0) return;

        String type = getDamageType(event.getCause());
        if (type.equals("UNKNOWN")) return;

        long now = System.currentTimeMillis();
        boolean isSpam = (now - lastHitTime.getOrDefault(uuid, 0L) < 450);
        if (!isSpam) lastHitTime.put(uuid, now);

        if (activeAdaptations.containsKey(uuid)) {
            if (type.equals(activeAdaptations.get(uuid))) {
                player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2, 0.1);
                if (superAdaptations.getOrDefault(uuid, false)) {
                    event.setDamage(event.getDamage() * 0.50); // 50% защита
                } else {
                    event.setDamage(event.getDamage() * (1.0 - (pieceCount * 0.075))); // 30% защита
                    if (!isSpam) {
                        superDamageCounters.putIfAbsent(uuid, new HashMap<>());
                        int sHits = superDamageCounters.get(uuid).getOrDefault(type, 0) + 1;
                        superDamageCounters.get(uuid).put(type, sHits);
                        if (sHits >= 8) activateSuper(player, type);
                    }
                }
            } else {
                event.setDamage(event.getDamage() * (1.0 + (pieceCount * 0.10))); // Штраф уязвимости
            }
            return;
        }

        if (isSpam) return;

        double avg = (double) totalLvl / pieceCount;
        int req = avg > 2.0 ? 6 : avg > 1.0 ? 8 : 10;

        damageCounters.putIfAbsent(uuid, new HashMap<>());
        int hits = damageCounters.get(uuid).getOrDefault(type, 0) + 1;
        damageCounters.get(uuid).put(type, hits);

        if (hits >= req) activateNormal(player, type);
    }

    private void activateNormal(Player player, String type) {
        UUID uuid = player.getUniqueId(); activeAdaptations.put(uuid, type);
        damageCounters.remove(uuid); superDamageCounters.remove(uuid);
        playBell(player, 0.9f, 20L);

        String suff = type.equals("MELEE") ? ChatColor.RED + "БЛИЖ. УРОН!" : type.equals("RANGED") ? ChatColor.GREEN + "СНАРЯДАМ!" : ChatColor.LIGHT_PURPLE + "МАГИИ!";
        startTimer(player, ChatColor.WHITE + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: " + suff, 10);
    }

    private void activateSuper(Player player, String type) {
        UUID uuid = player.getUniqueId();
        if (activeTimers.containsKey(uuid)) activeTimers.get(uuid).cancel();

        superAdaptations.put(uuid, true); superDamageCounters.remove(uuid);
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 160, 1, false, false, true)); // 4 золотых сердца
        playBell(player, 1.4f, 15L);

        String suff = type.equals("MELEE") ? ChatColor.DARK_RED + "БЛИЖ. УРОН!" : type.equals("RANGED") ? ChatColor.DARK_GREEN + "СНАРЯДАМ!" : ChatColor.DARK_PURPLE + "МАГИИ!";
        String line = ChatColor.WHITE + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD + "ПОВЫШ. АДАПТАЦИЯ К: " + ChatColor.RESET + suff;
        startTimer(player, line, 8);
    }

    private void startTimer(Player player, String msg, int sec) {
        UUID uuid = player.getUniqueId();
        activeTimers.put(uuid, new BukkitRunnable() {
            int time = sec;
            @Override
            public void run() {
                if (!player.isOnline() || time <= 0) {
                    activeAdaptations.remove(uuid); superAdaptations.remove(uuid); superDamageCounters.remove(uuid); activeTimers.remove(uuid);
                    if (player.isOnline()) player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
                    cancel(); return;
                }
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg)); time--;
            }
        }.runTaskTimer(this, 0L, 20L));
    }

    private void playBell(Player player, float pitch, long per) {
        player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, pitch);
        new BukkitRunnable() {
            int count = 1;
            @Override
            public void run() {
                if (!player.isOnline() || count >= 3) { cancel(); return; }
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, pitch); count++;
            }
        }.runTaskTimer(this, per, per);
    }

    private String getDamageType(DamageCause c) {
        if (c == DamageCause.PROJECTILE || c == DamageCause.BLOCK_EXPLOSION || c == DamageCause.ENTITY_EXPLOSION) return "RANGED";
        if (c == DamageCause.MAGIC || c == DamageCause.POISON || c == DamageCause.WITHER || c == DamageCause.DRAGON_BREATH || c == DamageCause.SONIC_BOOM) return "MAGIC";
        if (c == DamageCause.UNKNOWN || c == DamageCause.VOID || c == DamageCause.STARVATION || c == DamageCause.CUSTOM) return "UNKNOWN";
        return "MELEE";
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        damageCounters.remove(id); superDamageCounters.remove(id); activeAdaptations.remove(id); superAdaptations.remove(id); lastHitTime.remove(id);
        if (activeTimers.containsKey(id)) { activeTimers.get(id).cancel(); activeTimers.remove(id); }
    }
}
