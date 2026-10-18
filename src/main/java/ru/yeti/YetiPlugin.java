package ru.yeti;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class YetiPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey itemKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "yeti_orb");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("yeti");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("YetiOrb включен.");
    }

    // ------------------------------------------------------------------
    //  Предмет
    // ------------------------------------------------------------------

    private Component color(String s) {
        return legacy.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private String msg(String path) {
        return getConfig().getString("messages." + path, "");
    }

    private void send(org.bukkit.command.CommandSender to, String path, String... replace) {
        String m = msg(path);
        for (int i = 0; i + 1 < replace.length; i += 2) m = m.replace(replace[i], replace[i + 1]);
        to.sendMessage(legacy.deserialize(m));
    }

    private Attribute resolveAttribute(String id) {
        id = id.toLowerCase(Locale.ROOT).replace("generic.", "");
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(id));
        if (a == null) a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic." + id));
        return a;
    }

    public ItemStack createItem() {
        ConfigurationSection c = getConfig().getConfigurationSection("item");
        Material mat = Material.matchMaterial(c.getString("material", "PRISMARINE_CRYSTALS"));
        if (mat == null || !mat.isItem()) mat = Material.PRISMARINE_CRYSTALS;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(color(c.getString("name", "&bШар YETI")));

        List<Component> lore = new ArrayList<>();
        for (String line : c.getStringList("lore")) lore.add(color(line));
        meta.lore(lore);

        int cmd = c.getInt("custom_model_data", 0);
        if (cmd > 0) meta.setCustomModelData(cmd);

        EquipmentSlotGroup slot = EquipmentSlotGroup.getByName(c.getString("attribute_slot", "OFFHAND").toLowerCase(Locale.ROOT));
        if (slot == null) slot = EquipmentSlotGroup.OFFHAND;

        ConfigurationSection attrs = c.getConfigurationSection("attributes");
        if (attrs != null) {
            for (String key : attrs.getKeys(false)) {
                Attribute attr = resolveAttribute(key);
                if (attr == null) {
                    getLogger().warning("Неизвестный атрибут в конфиге: " + key);
                    continue;
                }
                double amount = attrs.getDouble(key + ".amount", 0);
                AttributeModifier.Operation op;
                try {
                    op = AttributeModifier.Operation.valueOf(attrs.getString(key + ".operation", "ADD_NUMBER").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    op = AttributeModifier.Operation.ADD_NUMBER;
                }
                meta.addAttributeModifier(attr, new AttributeModifier(
                        new NamespacedKey(this, "yeti_" + key.toLowerCase(Locale.ROOT)), amount, op, slot));
            }
        }

        if (c.getBoolean("hide_attributes", true)) meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isYeti(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------
    //  Способность
    // ------------------------------------------------------------------

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return; // только момент нажатия
        Player p = e.getPlayer();
        if (!isYeti(p.getInventory().getItemInOffHand())) return;
        if (!p.hasPermission("yeti.use")) return;

        long now = System.currentTimeMillis();
        long until = cooldowns.getOrDefault(p.getUniqueId(), 0L);
        if (now < until) {
            send(p, "cooldown", "%seconds%", String.valueOf((until - now + 999) / 1000));
            return;
        }

        double radius = getConfig().getDouble("ability.radius", 15.0);
        boolean hitCreative = getConfig().getBoolean("ability.hit_creative", false);

        List<Player> targets = new ArrayList<>();
        for (Player t : p.getWorld().getPlayers()) {
            if (t.equals(p) || t.isDead()) continue;
            if (!hitCreative && (t.getGameMode() == GameMode.CREATIVE || t.getGameMode() == GameMode.SPECTATOR)) continue;
            if (t.getLocation().distance(p.getLocation()) <= radius) targets.add(t);
        }

        if (targets.isEmpty()) {
            send(p, "no_targets", "%radius%", String.valueOf((int) radius));
            return;
        }

        cooldowns.put(p.getUniqueId(), now + getConfig().getLong("ability.cooldown_seconds", 30) * 1000L);
        send(p, "cast", "%ability%", getConfig().getString("ability.name", "Снежная глыба"),
                "%count%", String.valueOf(targets.size()));

        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.5f, 0.5f);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 1.5f, 0.6f);
        p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 1, 0), 80, 1, 1, 1, 0.1);

        for (Player t : targets) launchIcicle(p, t);
    }

    private void launchIcicle(Player caster, Player target) {
        ConfigurationSection a = getConfig().getConfigurationSection("ability");
        double speed = a.getDouble("projectile_speed", 1.6);
        double damage = a.getDouble("damage", 14.0);
        float length = (float) a.getDouble("icicle_length", 3.5);
        float width = (float) a.getDouble("icicle_width", 0.7);
        int slowSec = a.getInt("slowness_seconds", 4);
        int slowLvl = Math.max(1, a.getInt("slowness_level", 2));
        boolean freeze = a.getBoolean("freeze", true);

        World world = caster.getWorld();
        Location cur = caster.getEyeLocation().add(0, 1.2, 0);

        ItemDisplay display = world.spawn(cur, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.BLUE_ICE));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(false);
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
        });

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!display.isValid() || !target.isOnline() || target.isDead()
                        || !target.getWorld().equals(display.getWorld()) || ticks++ > 100) {
                    display.remove();
                    cancel();
                    return;
                }

                Location aim = target.getLocation().add(0, 1.0, 0);
                Vector dir = aim.toVector().subtract(cur.toVector());
                double dist = dir.length();

                if (dist <= speed + 0.6) {
                    hit();
                    display.remove();
                    cancel();
                    return;
                }

                dir.normalize();
                cur.add(dir.clone().multiply(speed));

                // Поворот сосульки острием по направлению полёта
                Quaternionf rot = new Quaternionf().rotationTo(
                        new Vector3f(0, 1, 0), new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()));
                display.teleport(cur);
                display.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0), rot, new Vector3f(width, length, width), new Quaternionf()));

                world.spawnParticle(Particle.SNOWFLAKE, cur, 6, 0.25, 0.25, 0.25, 0.02);
                world.spawnParticle(Particle.BLOCK, cur, 6, 0.3, 0.3, 0.3, Material.PACKED_ICE.createBlockData());
            }

            private void hit() {
                Location loc = target.getLocation().add(0, 1, 0);
                target.damage(damage, caster);
                if (freeze) target.setFreezeTicks(target.getMaxFreezeTicks() + 40);
                if (slowSec > 0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowSec * 20, slowLvl - 1));
                }
                world.spawnParticle(Particle.BLOCK, loc, 80, 0.5, 0.7, 0.5, 0.1, Material.ICE.createBlockData());
                world.spawnParticle(Particle.SNOWFLAKE, loc, 60, 0.6, 0.8, 0.6, 0.15);
                world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.7f);
                world.playSound(loc, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.5f, 1.0f);
                send(target, "hit", "%player%", caster.getName());
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // ------------------------------------------------------------------
    //  Команды
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] args) {
        if (!s.hasPermission("yeti.admin")) {
            send(s, "no_permission");
            return true;
        }
        if (args.length == 0) {
            s.sendMessage("§e/yeti give [игрок] §7| §e/yeti reload");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                send(s, "reloaded");
            }
            case "give" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (s instanceof Player pl ? pl : null);
                if (target == null) {
                    send(s, "player_not_found");
                    return true;
                }
                target.getInventory().addItem(createItem()).values()
                        .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
                send(target, "given");
                if (!target.equals(s)) send(s, "given_other", "%player%", target.getName());
            }
            default -> s.sendMessage("§e/yeti give [игрок] §7| §e/yeti reload");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) return List.of("give", "reload");
        return null;
    }
}
