package dev.math.bclasses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Classes por vida. A classe fica presa ao mundo hc em que foi escolhida:
 * quando o jogador entra num mundo hc diferente (party morreu e avancou), ela zera.
 */
public final class BabylonClasses extends JavaPlugin implements Listener {

    public enum PClass {
        EXPLORADOR("Explorador", Material.COMPASS, NamedTextColor.AQUA, false,
                "Exploracao, encantamento e combate."),
        ENGENHEIRO("Engenheiro", Material.DISPENSER, NamedTextColor.GOLD, true,
                "Torres automaticas, geradores e defesa da base."),
        COLETOR("Coletor", Material.GOLDEN_HOE, NamedTextColor.GREEN, false,
                "Farm, replantio, mochila e coleta em massa.");

        final String label; final Material icon; final NamedTextColor color; final boolean ready; final String desc;
        PClass(String label, Material icon, NamedTextColor color, boolean ready, String desc) {
            this.label = label; this.icon = icon; this.color = color; this.ready = ready; this.desc = desc;
        }
    }

    public static final class PData {
        PClass cls; String world; int xp;
    }

    static final int[] LEVEL_XP = {0, 25, 75, 175, 350};
    static final int CHANGE_COST_LEVELS = 30;

    private final Map<UUID, PData> players = new HashMap<>();
    private File playersFile;
    Engineer engineer;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        playersFile = new File(getDataFolder(), "players.yml");
        loadPlayers();
        engineer = new Engineer(this);
        engineer.enable();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::savePlayers, 1200L, 1200L);
        for (Player p : Bukkit.getOnlinePlayers()) checkLife(p, false);
    }

    @Override
    public void onDisable() {
        if (engineer != null) { engineer.save(); engineer.clearTags(); }
        savePlayers();
    }

    // ---------- dados ----------

    static String baseOf(String worldName) {
        if (worldName.endsWith("_nether")) return worldName.substring(0, worldName.length() - 7);
        if (worldName.endsWith("_the_end")) return worldName.substring(0, worldName.length() - 8);
        return worldName;
    }

    static boolean isRunWorld(World w) {
        return w != null && baseOf(w.getName()).startsWith("hc_");
    }

    PData data(UUID id) { return players.computeIfAbsent(id, k -> new PData()); }

    PClass classOf(Player p) {
        PData d = players.get(p.getUniqueId());
        if (d == null || d.cls == null) return null;
        if (isRunWorld(p.getWorld()) && !baseOf(p.getWorld().getName()).equals(d.world)) return null;
        return d.cls;
    }

    boolean is(Player p, PClass c) { return classOf(p) == c; }

    int level(UUID id) {
        PData d = players.get(id);
        int xp = d == null ? 0 : d.xp, lvl = 1;
        for (int i = 0; i < LEVEL_XP.length; i++) if (xp >= LEVEL_XP[i]) lvl = i + 1;
        return lvl;
    }

    void addXp(UUID id, int amount) {
        PData d = players.get(id);
        if (d == null || d.cls == null) return;
        int before = level(id);
        d.xp += amount;
        int after = level(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null && after > before) {
            p.sendMessage(Component.text("✦ " + d.cls.label + " subiu para o nivel " + after + "!", d.cls.color, TextDecoration.BOLD));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            if (d.cls == PClass.ENGENHEIRO) p.sendMessage(Component.text(engineer.levelSummary(after), NamedTextColor.GRAY));
        }
    }

    private void loadPlayers() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(playersFile);
        for (String k : y.getKeys(false)) {
            try {
                PData d = new PData();
                String c = y.getString(k + ".class");
                d.cls = c == null ? null : PClass.valueOf(c);
                d.world = y.getString(k + ".world");
                d.xp = y.getInt(k + ".xp");
                players.put(UUID.fromString(k), d);
            } catch (Exception ignored) { }
        }
    }

    void savePlayers() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PData> e : players.entrySet()) {
            PData d = e.getValue();
            if (d.cls == null) continue;
            String k = e.getKey().toString();
            y.set(k + ".class", d.cls.name());
            y.set(k + ".world", d.world);
            y.set(k + ".xp", d.xp);
        }
        try { y.save(playersFile); } catch (IOException ex) { getLogger().warning("players.yml: " + ex.getMessage()); }
    }

    // ---------- ciclo de vida ----------

    /** Zera a classe se o jogador esta num mundo hc diferente do da escolha; convida a escolher. */
    void checkLife(Player p, boolean delayedPrompt) {
        if (!isRunWorld(p.getWorld())) return;
        String base = baseOf(p.getWorld().getName());
        PData d = data(p.getUniqueId());
        if (d.cls != null && !base.equals(d.world)) {
            p.sendMessage(Component.text("☠ Nova vida: sua classe " + d.cls.label + " foi zerada.", NamedTextColor.RED));
            d.cls = null; d.xp = 0; d.world = null;
        }
        if (d.cls == null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && classOf(p) == null && isRunWorld(p.getWorld())) prompt(p);
            }, delayedPrompt ? 100L : 1L);
        }
    }

    void prompt(Player p) {
        p.sendMessage(Component.text("⚒ Voce ainda nao tem classe nesta vida. ", NamedTextColor.YELLOW)
                .append(Component.text("[Escolher classe]", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/classe"))));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { checkLife(e.getPlayer(), true); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { checkLife(e.getPlayer(), true); }

    // ---------- menu ----------

    static final class ClassMenu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    void openMenu(Player p) {
        ClassMenu h = new ClassMenu();
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Escolha sua classe"));
        h.inv = inv;
        PClass cur = classOf(p);
        int[] slots = {11, 13, 15};
        PClass[] all = PClass.values();
        for (int i = 0; i < all.length; i++) {
            PClass c = all[i];
            ItemStack it = new ItemStack(c.icon);
            ItemMeta m = it.getItemMeta();
            m.displayName(Component.text(c.label, c.color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(c.desc, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            if (!c.ready) lore.add(Component.text("Em construcao", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            else if (c == cur) lore.add(Component.text("Sua classe atual (nivel " + level(p.getUniqueId()) + ")", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            else if (cur != null) lore.add(Component.text("Trocar custa " + CHANGE_COST_LEVELS + " niveis de XP", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            else lore.add(Component.text("Clique para escolher", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Vale ate voce morrer.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            m.lore(lore);
            it.setItemMeta(m);
            inv.setItem(slots[i], it);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ClassMenu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        PClass pick = switch (e.getRawSlot()) { case 11 -> PClass.EXPLORADOR; case 13 -> PClass.ENGENHEIRO; case 15 -> PClass.COLETOR; default -> null; };
        if (pick == null) return;
        p.closeInventory();
        choose(p, pick);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ClassMenu) e.setCancelled(true);
    }

    void choose(Player p, PClass pick) {
        if (!pick.ready) { p.sendMessage(Component.text(pick.label + " ainda esta em construcao. Por enquanto so o Engenheiro.", NamedTextColor.RED)); return; }
        if (!isRunWorld(p.getWorld())) { p.sendMessage(Component.text("Escolha a classe dentro do mundo da run.", NamedTextColor.RED)); return; }
        PClass cur = classOf(p);
        if (cur == pick) { p.sendMessage(Component.text("Voce ja e " + pick.label + ".", NamedTextColor.GRAY)); return; }
        if (cur != null) {
            if (p.getLevel() < CHANGE_COST_LEVELS) {
                p.sendMessage(Component.text("Trocar de classe custa " + CHANGE_COST_LEVELS + " niveis de XP.", NamedTextColor.RED));
                return;
            }
            p.setLevel(p.getLevel() - CHANGE_COST_LEVELS);
        }
        PData d = data(p.getUniqueId());
        d.cls = pick; d.xp = 0; d.world = baseOf(p.getWorld().getName());
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
        Bukkit.broadcast(Component.text("⚒ " + p.getName() + " agora e ", NamedTextColor.GRAY)
                .append(Component.text(pick.label, pick.color, TextDecoration.BOLD)));
        if (pick == PClass.ENGENHEIRO) engineer.onBecome(p);
        savePlayers();
    }

    // ---------- comando ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("admin") && s.hasPermission("bclasses.admin")) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { s.sendMessage("Jogador offline."); return true; }
            PData d = data(t.getUniqueId());
            if (d.cls == null) { s.sendMessage("Jogador sem classe."); return true; }
            int lvl = Math.max(1, Math.min(5, Integer.parseInt(a[2])));
            d.xp = LEVEL_XP[lvl - 1];
            s.sendMessage(t.getName() + " agora e " + d.cls.label + " nivel " + lvl);
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Somente jogadores."); return true; }
        if (a.length >= 2 && a[0].equalsIgnoreCase("dar") && p.hasPermission("bclasses.admin")) {
            ItemStack it = engineer.item(a[1].toLowerCase(Locale.ROOT));
            if (it == null) { p.sendMessage("Itens: " + String.join(", ", Engineer.TYPES)); return true; }
            p.getInventory().addItem(it);
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("info")) {
            PClass c = classOf(p);
            if (c == null) { prompt(p); return true; }
            int lvl = level(p.getUniqueId());
            int xp = data(p.getUniqueId()).xp;
            String next = lvl >= 5 ? "maximo" : xp + "/" + LEVEL_XP[lvl];
            p.sendMessage(Component.text(c.label + " nivel " + lvl + " (XP " + next + ")", c.color, TextDecoration.BOLD));
            if (c == PClass.ENGENHEIRO) p.sendMessage(Component.text(engineer.status(p), NamedTextColor.GRAY));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("trocar")) { openMenu(p); return true; }
        if (classOf(p) == PClass.ENGENHEIRO) { engineer.openPanel(p); return true; }
        openMenu(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) {
            List<String> o = new ArrayList<>(List.of("info", "trocar"));
            if (s.hasPermission("bclasses.admin")) { o.add("admin"); o.add("dar"); }
            return o;
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("dar")) return Arrays.asList(Engineer.TYPES);
        return null;
    }
}
