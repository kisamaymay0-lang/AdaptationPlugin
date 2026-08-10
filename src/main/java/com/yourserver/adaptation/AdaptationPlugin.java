package com.yourserver.adaptation;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class AdaptationPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, Map<String, Integer>> damageCounters = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> superDamageCounters = new HashMap<>(); 
    private final Map<UUID, BukkitTask> activeTimers = new HashMap<>();
    private final Map<UUID, String> activeAdaptations = new HashMap<>();
    private final Map<UUID, Boolean> superAdaptations = new HashMap<>(); 
    private final Map<UUID, Long> lastHitTime = new HashMap<>();
    private final Map<UUID, BossBar> activeBossBars = new HashMap<>();
    
    private NamespacedKey enchantKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        enchantKey = new NamespacedKey(this, "adaptation_lvl");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("adaptation") != null) {
            getCommand("adaptation").setExecutor(this);
        }
        getLogger().info("Плагин AdaptationPlugin [PDC-FINAL] успешно запущен!");
    }

    @Override
    public void onDisable() {
        activeTimers.values().forEach(BukkitTask::cancel);
        activeBossBars.values().forEach(BossBar::removeAll);
        damageCounters.clear(); superDamageCounters.clear(); activeTimers.clear(); activeAdaptations.clear(); superAdaptations.clear(); lastHitTime.clear(); activeBossBars.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("adaptation.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length == 1 && args.equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "Конфигурация AdaptationPlugin успешно перезагружена!");
            return true;
        }

        if (args.length < 3 || !args.equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.RED + "Использование: /adaptation give <игрок> <1/2/3> ИЛИ /adaptation reload");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "Игрок не найден или оффлайн!");
            return true;
        }

        int lvl;
        try {
            lvl = Integer.parseInt(args[2]);
            if (lvl < 1 || lvl > 3) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Уровень должен быть от 1 до 3!");
            return true;
        }

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(enchantKey, PersistentDataType.INTEGER, lvl);
            
            String strLvl = lvl == 1 ? "I" : lvl == 2 ? "II" : "III";
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Адаптация " + strLvl);
            meta.setLore(lore);
            
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            book.setItemMeta(meta);
        }

        target.getInventory().addItem(book);
        sender.sendMessage(ChatColor.GREEN + "Книга Адаптация " + args[2] + " выдана игроку " + target.getName());
        return true;
    }

    @EventHandler
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory anvil = event.getInventory();
        ItemStack left = anvil.getItem(0), right = anvil.getItem(1);
        if (left == null || right == null || right.getType() != Material.ENCHANTED_BOOK) return;

        String type = left.getType().name();
        if (!type.contains("HELMET") && !type.contains("CHESTPLATE") && !type.contains("LEGGINGS") && !type.contains("BOOTS")) return;

        int lvlLeft = getStoredLvl(left);
        int lvlRight = getStoredLvl(right);
        if (lvlRight == 0) return;

        int finalLvl = (lvlLeft == lvlRight && lvlLeft < 3) ? lvlLeft + 1 : Math.max(lvlLeft, lvlRight);
        
        ItemStack result = left.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return;

        meta.getPersistentDataContainer().set(enchantKey, PersistentDataType.INTEGER, finalLvl);

        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.removeIf(l -> l.contains("Адаптация"));
        
        String strLvl = finalLvl == 1 ? "I" : finalLvl == 2 ? "II" : "III";
        lore.add(ChatColor.GRAY + "Адаптация " + strLvl);
        meta.setLore(lore);

        if (!meta.hasEnchants()) {
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        result.setItemMeta(meta);
        event.setResult(result); 
        anvil.setRepairCost(5); 
    }

    private int getStoredLvl(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer lvl = item.getItemMeta().getPersistentDataContainer().get(enchantKey, PersistentDataType.INTEGER);
        return lvl != null ? lvl : 0;
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity(); UUID uuid = player.getUniqueId();

        int totalLvl = 0, pieceCount = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            int lvl = getStoredLvl(armor);
            if (lvl > 0) {
                totalLvl += lvl;
                pieceCount++;
            }
        }
        if (pieceCount == 0) return;

        String type = getDamageType(event.getCause());
        if (type.equals("IGNORE")) return;

        long now = System.currentTimeMillis();
        boolean isSpam = (now - lastHitTime.getOrDefault(uuid, 0L) < 450);
        if (!isSpam) lastHitTime.put(uuid, now);

        if (activeAdaptations.containsKey(uuid)) {
            if (type.equals(activeAdaptations.get(uuid))) {
                
                spawnAdaptationParticles(player, type);

                if (superAdaptations.getOrDefault(uuid, false)) {
                    event.setDamage(event.getDamage() * 0.50);
                } else {
                    event.setDamage(event.getDamage() * (1.0 - (pieceCount * 0.075)));
                    if (!isSpam) {
                        superDamageCounters.putIfAbsent(uuid, new HashMap<>());
                        int sHits = superDamageCounters.get(uuid).getOrDefault(type, 0) + 1;
                        superDamageCounters.get(uuid).put(type, sHits);
                        
                        int requiredSuperHits = getConfig().getInt("settings.required-super-hits", 8);
                        if (sHits >= requiredSuperHits) activateSuper(player, type);
                    }
                }
            } else {
                event.setDamage(event.getDamage() * (1.0 + (pieceCount * 0.10)));
            }
            return;
        }

        if (isSpam) return;

        double avg = (double) totalLvl / pieceCount;
        
        int req;
        if (avg > 2.0) {
            req = getConfig().getInt("settings.required-hits.lvl3", 6);
        } else if (avg > 1.0) {
            req = getConfig().getInt("settings.required-hits.lvl2", 8);
        } else {
            req = getConfig().getInt("settings.required-hits.lvl1", 10);
        }

        damageCounters.putIfAbsent(uuid, new HashMap<>());
        int hits = damageCounters.get(uuid).getOrDefault(type, 0) + 1;
        damageCounters.get(uuid).put(type, hits);

        if (hits >= req) activateNormal(player, type);
    }

    private void spawnAdaptationParticles(Player player, String type) {
        Color color;
        switch (type) {
            case "MELEE": color = Color.fromRGB(255, 0, 0); break;
            case "RANGED": color = Color.fromRGB(0, 255, 0); break;
            case "MAGIC": color = Color.fromRGB(200, 0, 255); break;
            default: color = Color.WHITE; break;
        }
        Particle.DustOptions dustOptions = new Particle.DustOptions(color, 1.2f);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0.0, dustOptions);
    }

    private void activateNormal(Player player, String type) {
        UUID uuid = player.getUniqueId(); 
        if (activeTimers.containsKey(uuid)) activeTimers.get(uuid).cancel();
        if (activeBossBars.containsKey(uuid)) { activeBossBars.get(uuid).removeAll(); activeBossBars.remove(uuid); }

        activeAdaptations.put(uuid, type);
        damageCounters.remove(uuid); superDamageCounters.remove(uuid);
        playBell(player, 0.9f, 20L);

        String suff = type.equals("MELEE") ? ChatColor.RED + "" + ChatColor.BOLD + "БЛИЖ. УРОН!" : 
                       type.equals("RANGED") ? ChatColor.GREEN + "" + ChatColor.BOLD + "СНАРЯДАМ!" : 
                       ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "МАГИИ!";

        String line = ChatColor.WHITE + "" + ChatColor.BOLD + "АДАПТАЦИЯ К: " + suff;
        BarColor barColor = type.equals("MELEE") ? BarColor.RED : type.equals("RANGED") ? BarColor.GREEN : BarColor.PURPLE;
        
        int duration = getConfig().getInt("settings.duration-normal", 10);
        createBossBarTimer(player, line, barColor, duration);
    }

    private void activateSuper(Player player, String type) {
        UUID uuid = player.getUniqueId();
        if (activeTimers.containsKey(uuid)) activeTimers.get(uuid).cancel();
        if (activeBossBars.containsKey(uuid)) { activeBossBars.get(uuid).removeAll(); activeBossBars.remove(uuid); }

        superAdaptations.put(uuid, true); superDamageCounters.remove(uuid);
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 160, 1, false, false, true));
        playBell(player, 1.4f, 15L);

        String prefixStyle = ChatColor.WHITE + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD;
        String suff = type.equals("MELEE") ? ChatColor.DARK_RED + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD + "БЛИЖ. УРОН!" : 
                       type.equals("RANGED") ? ChatColor.DARK_GREEN + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD + "СНАРЯДАМ!" : 
                       ChatColor.DARK_PURPLE + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD + "МАГИИ!";

        String line = prefixStyle + "ПОВЫШ. АДАПТАЦИЯ К: " + suff;
        BarColor barColor = type.equals("MELEE") ? BarColor.RED : type.equals("RANGED") ? BarColor.GREEN : BarColor.PURPLE;
        
        int duration = getConfig().getInt("settings.duration-super", 8);
        createBossBarTimer(player, line, barColor, duration);
    }

    private void createBossBarTimer(Player player, String msg, BarColor color, int sec) {
        UUID uuid = player.getUniqueId();
        BossBar bossBar = Bukkit.createBossBar(msg, color, BarStyle.SOLID);
        bossBar.addPlayer(player);
        activeBossBars.put(uuid, bossBar);

        activeTimers.put(uuid, new BukkitRunnable() {
            final double maxTime = sec;
            double timeLeft = sec;

            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);
                
                if (p == null || !p.isOnline() || timeLeft <= 0) {
                    if (p != null && p.isOnline() && timeLeft <= 0) {
                        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
                        p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0.05);
                    }
                    
                    activeAdaptations.remove(uuid); 
                    superAdaptations.remove(uuid); 
                    superDamageCounters.remove(uuid); 
                    activeTimers.remove(uuid);
                    
                    if (activeBossBars.containsKey(uuid)) {
                        activeBossBars.get(uuid).removeAll();
                        activeBossBars.remove(uuid);
                    }
                    cancel(); 
                    return;
                }

                bossBar.setProgress(timeLeft / maxTime);
                timeLeft -= 0.05; 
            }
        }.runTaskTimer(this, 0L, 1L));
    }

    private void playBell(Player player, float pitch, long per) {
        if (player == null) return;
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, pitch);
        UUID uuid = player.getUniqueId();
        
        new BukkitRunnable() {
            int count = 1;
            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline() || count >= 3) { cancel(); return; }
                p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 3.0f, pitch); count++;
            }
        }.runTaskTimer(this, per, per);
    }

    private String getDamageType(DamageCause c) {
        if (c == DamageCause.PROJECTILE || c == DamageCause.BLOCK_EXPLOSION || c == DamageCause.ENTITY_EXPLOSION) return "RANGED";
        if (c == DamageCause.MAGIC || c == DamageCause.POISON || c == DamageCause.WITHER || c == DamageCause.DRAGON_BREATH) return "MAGIC";
        if (c.name().equals("SONIC_BOOM")) return "MAGIC";
        if (c == DamageCause.VOID || c == DamageCause.STARVATION || c.name().equals("CUSTOM")) return "IGNORE";
        return "MELEE";
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        damageCounters.remove(id); superDamageCounters.remove(id); activeAdaptations.remove(id); superAdaptations.remove(id); lastHitTime.remove(id);
        if (activeTimers.containsKey(id)) { activeTimers.get(id).cancel(); activeTimers.remove(id); }
        if (activeBossBars.containsKey(id)) { activeBossBars.get(id).removeAll(); activeBossBars.remove(id); }
    }
}
