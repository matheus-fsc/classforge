package dev.math.bclasses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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
        String world; int xp;
    }

    static final int[] LEVEL_XP = {0, 25, 75, 175, 350};

    private final Map<UUID, PData> players = new HashMap<>();
    private final Set<UUID> fxOff = new HashSet<>();
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
        for (Player p : Bukkit.getOnlinePlayers()) checkLife(p);
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
        }
        y.set("fx-off", fxOff.stream().map(UUID::toString).toList());
        try { y.save(playersFile); } catch (IOException ex) { getLogger().warning("players.yml: " + ex.getMessage()); }
    }

    // ---------- ciclo de vida ----------

    /** Zera o XP se o jogador esta num mundo hc diferente do que ganhou o XP (nova vida). */
    void checkLife(Player p) {
        engineer.discover(p);
        if (!isRunWorld(p.getWorld())) return;
        String base = baseOf(p.getWorld().getName());
        PData d = data(p.getUniqueId());
        if (d.world != null && !base.equals(d.world)) {
            if (d.xp > 0) p.sendMessage(Component.text("☠ Nova vida: sua Engenharia voltou ao nivel 1.", NamedTextColor.RED));
            d.xp = 0;
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
            data(t.getUniqueId()).xp = LEVEL_XP[lvl - 1];
            s.sendMessage(t.getName() + " agora tem Engenharia nivel " + lvl);
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
            int lvl = level(p.getUniqueId());
            int xp = data(p.getUniqueId()).xp;
            String next = lvl >= 5 ? "maximo" : xp + "/" + LEVEL_XP[lvl];
            p.sendMessage(Component.text("Engenharia nivel " + lvl + " (XP " + next + ")", NamedTextColor.GOLD, TextDecoration.BOLD));
            p.sendMessage(Component.text(engineer.status(p), NamedTextColor.GRAY));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("particulas")) { toggleFx(p); return true; }
        engineer.openPanel(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) {
            List<String> o = new ArrayList<>(List.of("info", "particulas"));
            if (s.hasPermission("bclasses.admin")) { o.add("admin"); o.add("dar"); }
            return o;
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("dar")) return Arrays.asList(Engineer.TYPES);
        return null;
    }
}
