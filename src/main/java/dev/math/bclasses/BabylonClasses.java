package dev.math.bclasses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Sem classes fixas: qualquer jogador faz qualquer coisa, o que limita e o nivel.
 * Cada habilidade (por enquanto so Engenharia) sobe com XP proprio e zera quando a vida acaba:
 * o XP fica preso ao mundo hc em que foi ganho.
 */
public final class BabylonClasses extends JavaPlugin implements Listener {

    public static final class PData {
        String world; int xp, exp;
    }

    static final int[] LEVEL_XP = {0, 25, 75, 175, 350};
    static final int[] EXP_XP = {0, 150, 500, 1200, 2500};

    private final Map<UUID, PData> players = new HashMap<>();
    private final Set<UUID> fxOff = new HashSet<>();
    private File playersFile;
    Engineer engineer;
    Explorer explorer;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        playersFile = new File(getDataFolder(), "players.yml");
        loadPlayers();
        engineer = new Engineer(this);
        engineer.enable();
        explorer = new Explorer(this);
        explorer.enable();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::savePlayers, 1200L, 1200L);
        for (Player p : Bukkit.getOnlinePlayers()) checkLife(p);
    }

    @Override
    public void onDisable() {
        if (engineer != null) { engineer.save(); engineer.clearTags(); }
        if (explorer != null) explorer.save();
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

    boolean fxEnabled(Player p) { return !fxOff.contains(p.getUniqueId()); }

    void toggleFx(Player p) {
        boolean wasOff = fxOff.remove(p.getUniqueId());
        if (!wasOff) fxOff.add(p.getUniqueId());
        p.sendMessage(Component.text("⚡ Particulas de energia " + (wasOff ? "ligadas" : "desligadas") + ".", wasOff ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        savePlayers();
    }

    PData data(UUID id) { return players.computeIfAbsent(id, k -> new PData()); }

    /** Nivel de Engenharia (1 a 5). */
    int level(UUID id) {
        PData d = players.get(id);
        int xp = d == null ? 0 : d.xp, lvl = 1;
        for (int i = 0; i < LEVEL_XP.length; i++) if (xp >= LEVEL_XP[i]) lvl = i + 1;
        return lvl;
    }

    static int levelFor(int xp, int[] table) {
        int lvl = 1;
        for (int i = 0; i < table.length; i++) if (xp >= table[i]) lvl = i + 1;
        return lvl;
    }

    /** Nivel de Exploracao (1 a 5). */
    int explorerLevel(UUID id) {
        PData d = players.get(id);
        return levelFor(d == null ? 0 : d.exp, EXP_XP);
    }

    void addExp(UUID id, int amount) {
        PData d = data(id);
        Player p = Bukkit.getPlayer(id);
        if (d.world == null && p != null && isRunWorld(p.getWorld())) d.world = baseOf(p.getWorld().getName());
        int before = explorerLevel(id);
        d.exp += amount;
        int after = explorerLevel(id);
        if (p != null && after > before) {
            p.sendMessage(Component.text("✦ Exploracao subiu para o nivel " + after + "!", NamedTextColor.AQUA, TextDecoration.BOLD));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            p.sendMessage(Component.text(explorer.levelSummary(after), NamedTextColor.GRAY));
        }
    }

    void addXp(UUID id, int amount) {
        PData d = data(id);
        Player p = Bukkit.getPlayer(id);
        if (d.world == null && p != null && isRunWorld(p.getWorld())) d.world = baseOf(p.getWorld().getName());
        int before = level(id);
        d.xp += amount;
        int after = level(id);
        if (p != null) {
            p.sendActionBar(Component.text("+" + amount + " XP de Engenharia", NamedTextColor.GOLD));
            if (after > before) {
                p.sendMessage(Component.text("✦ Engenharia subiu para o nivel " + after + "!", NamedTextColor.GOLD, TextDecoration.BOLD));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                p.sendMessage(Component.text(engineer.levelSummary(after), NamedTextColor.GRAY));
            }
        }
    }

    private void loadPlayers() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(playersFile);
        for (String u : y.getStringList("fx-off")) { try { fxOff.add(UUID.fromString(u)); } catch (Exception ignored) { } }
        for (String k : y.getKeys(false)) {
            if (k.equals("fx-off")) continue;
            try {
                PData d = new PData();
                d.world = y.getString(k + ".world");
                d.xp = y.getInt(k + ".xp");
                d.exp = y.getInt(k + ".exp");
                players.put(UUID.fromString(k), d);
            } catch (Exception ignored) { }
        }
    }

    void savePlayers() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PData> e : players.entrySet()) {
            PData d = e.getValue();
            String k = e.getKey().toString();
            y.set(k + ".world", d.world);
            y.set(k + ".xp", d.xp);
            y.set(k + ".exp", d.exp);
        }
        y.set("fx-off", fxOff.stream().map(UUID::toString).toList());
        try { y.save(playersFile); } catch (IOException ex) { getLogger().warning("players.yml: " + ex.getMessage()); }
    }

    // ---------- ciclo de vida ----------

    /** Zera o XP se o jogador esta num mundo hc diferente do que ganhou o XP (nova vida). */
    void checkLife(Player p) {
        engineer.discover(p);
        explorer.discover(p);
        if (!isRunWorld(p.getWorld())) return;
        String base = baseOf(p.getWorld().getName());
        PData d = data(p.getUniqueId());
        if (d.world != null && !base.equals(d.world)) {
            if (d.xp > 0 || d.exp > 0) p.sendMessage(Component.text("☠ Nova vida: suas habilidades voltaram ao nivel 1.", NamedTextColor.RED));
            d.xp = 0; d.exp = 0;
            explorer.resetLife(p.getUniqueId());
        }
        d.world = base;
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { checkLife(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { checkLife(e.getPlayer()); }

    // ---------- comando ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("admin") && s.hasPermission("bclasses.admin")) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { s.sendMessage("Jogador offline."); return true; }
            int lvl = Math.max(1, Math.min(5, Integer.parseInt(a[2])));
            boolean exp = a.length >= 4 && a[3].toLowerCase(Locale.ROOT).startsWith("expl");
            if (exp) data(t.getUniqueId()).exp = EXP_XP[lvl - 1]; else data(t.getUniqueId()).xp = LEVEL_XP[lvl - 1];
            s.sendMessage(t.getName() + " agora tem " + (exp ? "Exploracao" : "Engenharia") + " nivel " + lvl);
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Somente jogadores."); return true; }
        if (a.length >= 2 && a[0].equalsIgnoreCase("dar") && p.hasPermission("bclasses.admin")) {
            ItemStack it = a[1].equalsIgnoreCase("ancora") ? explorer.anchorItem() : engineer.item(a[1].toLowerCase(Locale.ROOT));
            if (it == null) { p.sendMessage("Itens: " + String.join(", ", Engineer.TYPES)); return true; }
            p.getInventory().addItem(it);
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("info")) {
            int lvl = level(p.getUniqueId());
            int xp = data(p.getUniqueId()).xp;
            String next = lvl >= 5 ? "maximo" : xp + "/" + LEVEL_XP[lvl];
            p.sendMessage(Component.text("Engenharia nivel " + lvl + " (XP " + next + ")", NamedTextColor.GOLD, TextDecoration.BOLD));
            p.sendMessage(Component.text(engineer.status(p), NamedTextColor.GRAY));
            int el = explorerLevel(p.getUniqueId()), ex = data(p.getUniqueId()).exp;
            p.sendMessage(Component.text("Exploracao nivel " + el + " (XP " + (el >= 5 ? "maximo" : ex + "/" + EXP_XP[el]) + ")", NamedTextColor.AQUA, TextDecoration.BOLD));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("particulas")) { toggleFx(p); return true; }
        openHub(p);
        return true;
    }

    // ---------- menu de habilidades ----------

    static final class SkillMenu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static ItemStack skillIcon(Material mat, String name, NamedTextColor color, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(name, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, s.startsWith("Em breve") ? NamedTextColor.RED : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    void openHub(Player p) {
        SkillMenu h = new SkillMenu();
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Habilidades"));
        h.inv = inv;
        ItemStack filler = skillIcon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        int lvl = level(p.getUniqueId()), xp = data(p.getUniqueId()).xp;
        String next = lvl >= 5 ? "Nivel maximo" : "XP " + xp + "/" + LEVEL_XP[lvl];
        int el = explorerLevel(p.getUniqueId()), ex = data(p.getUniqueId()).exp;
        inv.setItem(11, skillIcon(Material.COMPASS, "Exploracao - nivel " + el, NamedTextColor.AQUA, List.of(
                el >= 5 ? "Nivel maximo" : "XP " + ex + "/" + EXP_XP[el], "", "Companheiro (lobo, raposa, gato),", "Ancora de Perola e bonus de carne.",
                "Sobe visitando chunks novos.", "", "Clique para abrir.")));
        inv.setItem(13, skillIcon(Material.DISPENSER, "Engenharia - nivel " + lvl, NamedTextColor.GOLD, List.of(
                next, "", "Torres, geradores, cabos e postes.", "Sobe montando, reparando e com", "abates das suas torres.", "", "Clique para abrir.")));
        inv.setItem(15, skillIcon(Material.GOLDEN_HOE, "Coleta", NamedTextColor.GREEN, List.of(
                "Farm e plantio automatizado.", "", "Em breve")));
        inv.setItem(22, skillIcon(Material.BOOK, "Como funciona", NamedTextColor.WHITE, List.of(
                "Ninguem escolhe classe: todo mundo", "pode fazer tudo. Cada habilidade", "tem nivel proprio e libera mais", "coisas conforme sobe.", "", "Os niveis zeram quando a vida acaba.")));
        p.openInventory(inv);
    }

    @EventHandler
    public void onHubClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof SkillMenu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (e.getRawSlot() == 13) engineer.openPanel(p);
        else if (e.getRawSlot() == 11) explorer.openPanel(p);
        else if (e.getRawSlot() == 15) {
            p.sendMessage(Component.text("Essa habilidade ainda esta em construcao.", NamedTextColor.GRAY));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        }
    }

    @EventHandler
    public void onHubDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof SkillMenu) e.setCancelled(true); }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) {
            List<String> o = new ArrayList<>(List.of("info", "particulas"));
            if (s.hasPermission("bclasses.admin")) { o.add("admin"); o.add("dar"); }
            return o;
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("dar")) { List<String> o = new ArrayList<>(Arrays.asList(Engineer.TYPES)); o.add("ancora"); return o; }
        if (a.length == 4 && a[0].equalsIgnoreCase("admin")) return List.of("engenharia", "exploracao");
        return null;
    }
}
