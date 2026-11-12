package dev.math.hrclasses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Linha industrial da Engenharia: maquinas de processo (prensa, torno, bobinadeira, cadinho, gravadora)
 * que transformam Ferro/Cobre Industrial em componentes (chapas, fios, engrenagens, bobinas, aco,
 * circuitos). Cada maquina tem interface propria: o inventario dela E a tela (entradas, barra de
 * progresso, saidas, medidor de energia e receitas), compartilhada por todo mundo que abrir.
 */
final class Factory implements Listener {

    /** Ingrediente: material vanilla (mat) ou item da Engenharia (part). */
    record Ing(Material mat, String part, int n) { }

    /** Receita de processo: so sai da maquina indicada. */
    record Proc(String machine, String out, int amount, int seconds, double rate, int minLevel, List<Ing> ins) { }

    static final Set<String> TYPES = Set.of("prensa", "torno", "bobinadeira", "cadinho", "gravadora", "laminadora");

    static final int SIZE = 54;
    static final int[] IN = {19, 20, 21}, BAR = {22, 23}, OUT = {24, 25};
    static final int[] RECIPE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    static final int[] GAUGE = {37, 38, 39, 40, 41, 42, 43};
    static final int S_INFO = 4, S_ENERGY = 36, S_POWER = 45, S_TAG = 47, S_AUTO = 49, S_NET = 51;
    /** XP de Engenharia por lote pronto. */
    static double XP_BATCH = 0.5;
    /** Itens que a maquina puxa do bau de cima por segundo. */
    static final int PULL_PER_TICK = 4;

    static final List<Proc> PROCS = List.of(
            new Proc("prensa", "chapa_ferro", 2, 6, 6, 2, List.of(part("ferro_industrial", 1))),
            new Proc("prensa", "chapa_cobre", 2, 6, 6, 2, List.of(part("cobre_industrial", 1))),
            new Proc("prensa", "chapa_aco", 2, 10, 6, 3, List.of(part("aco", 1))),
            new Proc("torno", "fio_cobre", 6, 5, 4, 2, List.of(part("cobre_industrial", 1))),
            new Proc("torno", "engrenagem", 2, 6, 4, 2, List.of(part("chapa_ferro", 1))),
            new Proc("bobinadeira", "bobina", 2, 8, 5, 3, List.of(part("fio_cobre", 6), part("chapa_ferro", 1))),
            new Proc("cadinho", "aco", 2, 15, 8, 3, List.of(part("ferro_industrial", 2), mat(Material.COAL, 4))),
            new Proc("cadinho", "liga", 2, 12, 8, 3, List.of(part("cobre_industrial", 1), mat(Material.GOLD_INGOT, 1), mat(Material.REDSTONE, 4))),
            new Proc("gravadora", "circuito", 1, 10, 6, 3, List.of(part("chapa_cobre", 1), mat(Material.REDSTONE, 2), mat(Material.QUARTZ, 1))),
            new Proc("gravadora", "processador", 1, 20, 6, 4, List.of(part("circuito", 2), part("liga", 1), mat(Material.DIAMOND, 1))),
            // saida "v:MATERIAL" = item vanilla; ingrediente "#madeira" = qualquer tabua ou tronco
            new Proc("laminadora", "v:RAIL", 16, 8, 5, 4, List.of(mat(Material.IRON_INGOT, 4), part("#madeira", 2))));

    static boolean vanilla(Proc p) { return p.out().startsWith("v:"); }
    static Material vanillaOut(Proc p) { return Material.valueOf(p.out().substring(2)); }
    static String outLabel(Proc p) { return vanilla(p) ? Engineer.pretty(vanillaOut(p)) : Engineer.label(p.out()); }
    ItemStack outItem(Proc p) { return vanilla(p) ? new ItemStack(vanillaOut(p)) : eng.item(p.out()); }

    private static Ing part(String type, int n) { return new Ing(null, type, n); }
    private static Ing mat(Material m, int n) { return new Ing(m, null, n); }

    static Proc procFor(String out) {
        for (Proc p : PROCS) if (p.out().equals(out)) return p;
        return null;
    }

    static List<Proc> procsOf(String machine) {
        List<Proc> out = new ArrayList<>();
        for (Proc p : PROCS) if (p.machine().equals(machine)) out.add(p);
        return out;
    }

    static String ingText(Ing g) {
        if ("#madeira".equals(g.part())) return g.n() + "x Madeira (tabua ou tronco)";
        return g.n() + "x " + (g.part() != null ? Engineer.label(g.part()) : Engineer.pretty(g.mat()));
    }

    /** "1x Ferro Industrial + 4x Coal" */
    static String insText(Proc p) {
        StringBuilder sb = new StringBuilder();
        for (Ing g : p.ins()) { if (sb.length() > 0) sb.append(" + "); sb.append(ingText(g)); }
        return sb.toString();
    }

    static final class Menu implements InventoryHolder {
        final String key; Inventory inv;
        Menu(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final HardcoreRouletteClasses plugin;
    private final Engineer eng;

    Factory(HardcoreRouletteClasses plugin, Engineer eng) { this.plugin = plugin; this.eng = eng; }

    // ---------- inventario / tela ----------

    /** A tela da maquina. Os espacos IN e OUT sao o inventario de verdade dela. */
    Inventory inv(Engineer.Device d) {
        if (d.gui != null) return d.gui;
        Menu h = new Menu(d.key());
        Inventory g = Bukkit.createInventory(h, SIZE, Component.text(Engineer.label(d.type)));
        h.inv = g;
        ItemStack filler = Engineer.icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 0; i < SIZE; i++) g.setItem(i, filler);
        for (int s : IN) g.setItem(s, null);
        for (int s : OUT) g.setItem(s, null);
        g.setItem(18, Engineer.icon(Material.HOPPER, "Entrada", NamedTextColor.AQUA, "Coloque os materiais nos", "3 espacos ao lado.", "Shift-clique no seu inventario", "manda direto para ca."));
        g.setItem(26, Engineer.icon(Material.CHEST, "Saida", NamedTextColor.GREEN, "Retire o que ficou pronto", "nos 2 espacos ao lado."));
        g.setItem(S_AUTO, Engineer.icon(Material.HOPPER, "Automacao", NamedTextColor.WHITE,
                "Bau (ou barril) EM CIMA: a maquina", "puxa os materiais que ela usa.", "Bau EMBAIXO: recebe o que ficou pronto.",
                "Outra maquina de processo embaixo", "recebe direto na entrada dela", "(linha de producao em pilha)."));
        d.gui = g;
        return g;
    }

    void open(Player p, Engineer.Device d) {
        Inventory g = inv(d);
        draw(d);
        p.openInventory(g);
    }

    private boolean matches(Ing g, ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        String t = eng.typeOf(it);
        if ("#madeira".equals(g.part())) return t == null && (org.bukkit.Tag.PLANKS.isTagged(it.getType()) || org.bukkit.Tag.LOGS.isTagged(it.getType()));
        return g.part() != null ? g.part().equals(t) : t == null && it.getType() == g.mat();
    }

    /** O item serve de ingrediente para alguma receita desta maquina? */
    boolean usable(String machine, ItemStack it) {
        for (Proc p : PROCS) {
            if (!p.machine().equals(machine)) continue;
            for (Ing g : p.ins()) if (matches(g, it)) return true;
        }
        return false;
    }

    private int count(Inventory g, Ing ing) {
        int n = 0;
        for (int s : IN) { ItemStack it = g.getItem(s); if (matches(ing, it)) n += it.getAmount(); }
        return n;
    }

    private boolean has(Inventory g, Proc p) {
        for (Ing ing : p.ins()) if (count(g, ing) < ing.n()) return false;
        return true;
    }

    private void take(Inventory g, Ing ing) {
        int left = ing.n();
        for (int s : IN) {
            if (left <= 0) break;
            ItemStack it = g.getItem(s);
            if (!matches(ing, it)) continue;
            int t = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - t);
            g.setItem(s, it.getAmount() > 0 ? it : null);
            left -= t;
        }
    }

    /** Quanto de `it` cabe nos espacos dados. */
    static int room(Inventory g, int[] slots, ItemStack it) {
        int room = 0;
        for (int s : slots) {
            ItemStack cur = g.getItem(s);
            if (cur == null || cur.getType().isAir()) room += it.getMaxStackSize();
            else if (cur.isSimilar(it)) room += Math.max(0, cur.getMaxStackSize() - cur.getAmount());
        }
        return room;
    }

    /** Coloca ate `amount` unidades de `it` nos espacos; devolve quanto sobrou de fora. */
    static int add(Inventory g, int[] slots, ItemStack it, int amount) {
        for (int pass = 0; pass < 2 && amount > 0; pass++)
            for (int s : slots) {
                if (amount <= 0) break;
                ItemStack cur = g.getItem(s);
                boolean empty = cur == null || cur.getType().isAir();
                if (pass == 0) { // primeiro completa pilhas
                    if (empty || !cur.isSimilar(it)) continue;
                    int t = Math.min(amount, cur.getMaxStackSize() - cur.getAmount());
                    if (t <= 0) continue;
                    cur.setAmount(cur.getAmount() + t);
                    g.setItem(s, cur);
                    amount -= t;
                } else if (empty) {
                    int t = Math.min(amount, it.getMaxStackSize());
                    ItemStack put = it.clone(); put.setAmount(t);
                    g.setItem(s, put);
                    amount -= t;
                }
            }
        return amount;
    }

    // ---------- trabalho (1x por segundo) ----------

    void tick(Engineer.Device d, Block b) {
        Inventory g = inv(d);
        if (!d.enabled) { d.status = "desligada"; d.powered = false; visual(d, b, false); render(d); return; }
        automate(d, b, g);
        Proc r = null; int needLevel = 0;
        int lvl = plugin.level(d.owner);
        for (Proc p : PROCS) {
            if (!p.machine().equals(d.type) || !has(g, p)) continue;
            if (lvl < p.minLevel()) { needLevel = p.minLevel(); continue; }
            r = p; break;
        }
        if (r == null) {
            d.burn = 0;
            d.status = needLevel > 0 ? "requer nivel " + needLevel : "sem material";
            visual(d, b, false); render(d);
            return;
        }
        if (!r.out().equals(d.sel)) { d.sel = r.out(); d.burn = 0; }
        ItemStack out = outItem(r);
        if (room(g, OUT, out) < r.amount()) { d.status = "saida cheia"; visual(d, b, false); render(d); return; }
        if (!eng.work(d, r.rate())) { visual(d, b, false); render(d); return; }
        visual(d, b, true);
        if (++d.burn >= r.seconds()) {
            d.burn = 0;
            for (Ing ing : r.ins()) take(g, ing);
            add(g, OUT, out, r.amount());
            World w = b.getWorld();
            Location c = d.center(w);
            w.playSound(c, Sound.BLOCK_ANVIL_USE, 0.4f, 1.5f);
            w.spawnParticle(Particle.HAPPY_VILLAGER, c.clone().add(0, 0.7, 0), 4, 0.25, 0.1, 0.25, 0);
            eng.xpFrac(d.owner, XP_BATCH);
        }
        render(d);
    }

    /** Bau em cima abastece; bau (ou outra maquina de processo) embaixo recebe. */
    private void automate(Engineer.Device d, Block b, Inventory g) {
        Block upB = b.getRelative(BlockFace.UP), downB = b.getRelative(BlockFace.DOWN);
        Inventory up = eng.at(upB) == null ? Engineer.containerAt(upB) : null;
        if (up != null) {
            int budget = PULL_PER_TICK;
            for (int i = 0; i < up.getSize() && budget > 0; i++) {
                ItemStack it = up.getItem(i);
                if (it == null || !usable(d.type, it)) continue;
                int want = Math.min(budget, Math.min(it.getAmount(), room(g, IN, it)));
                if (want <= 0) continue;
                add(g, IN, it, want);
                it.setAmount(it.getAmount() - want);
                up.setItem(i, it.getAmount() > 0 ? it : null);
                budget -= want;
            }
        }
        Engineer.Device below = eng.at(downB);
        Inventory down = below == null ? Engineer.containerAt(downB) : null;
        for (int s : OUT) {
            ItemStack it = g.getItem(s);
            if (it == null || it.getType().isAir()) continue;
            int left = it.getAmount();
            if (below != null && TYPES.contains(below.type)) {
                if (!usable(below.type, it)) continue;
                left = add(inv(below), IN, it, left);
            } else if (below != null && below.type.equals(Tunnel.STATION)) {
                left = eng.tunnel.accept(below, it, left); // laminadora em cima da estacao entrega os trilhos
            } else if (down != null) {
                Map<Integer, ItemStack> rest = down.addItem(it.clone());
                left = rest.isEmpty() ? 0 : rest.values().iterator().next().getAmount();
            } else continue;
            it.setAmount(left);
            g.setItem(s, left > 0 ? it : null);
        }
    }

    private void visual(Engineer.Device d, Block b, boolean working) {
        if (d.type.equals("cadinho")) { // o cadinho enche de metal derretido enquanto trabalha
            if (working && b.getType() != Material.LAVA_CAULDRON) b.setType(Material.LAVA_CAULDRON, false);
            else if (!working && b.getType() != Material.CAULDRON) b.setType(Material.CAULDRON, false);
        }
        if (!working) return;
        World w = b.getWorld();
        Location c = d.center(w), top = c.clone().add(0, 0.6, 0);
        switch (d.type) {
            case "prensa" -> { w.playSound(c, Sound.BLOCK_PISTON_EXTEND, 0.35f, 0.7f); w.spawnParticle(Particle.CRIT, top, 4, 0.2, 0.05, 0.2, 0.02); }
            case "torno" -> { w.playSound(c, Sound.UI_STONECUTTER_TAKE_RESULT, 0.4f, 1.3f); w.spawnParticle(Particle.ELECTRIC_SPARK, top, 3, 0.2, 0.05, 0.2, 0.02); }
            case "bobinadeira" -> { w.playSound(c, Sound.UI_LOOM_TAKE_RESULT, 0.4f, 1.4f); w.spawnParticle(Particle.WAX_ON, top, 2, 0.2, 0.05, 0.2, 0); }
            case "cadinho" -> { w.playSound(c, Sound.BLOCK_LAVA_POP, 0.6f, 0.8f); w.spawnParticle(Particle.LAVA, top, 1, 0.15, 0.05, 0.15, 0); }
            default -> { w.playSound(c, Sound.BLOCK_COPPER_BULB_TURN_ON, 0.3f, 1.8f); w.spawnParticle(Particle.ELECTRIC_SPARK, top, 2, 0.2, 0.05, 0.2, 0.01); }
        }
    }

    /** Linha da etiqueta flutuante: o que esta fazendo e quanto falta. */
    String progress(Engineer.Device d) {
        Proc r = procFor(d.sel);
        if (r == null || !r.machine().equals(d.type)) return "";
        return "fazendo: " + outLabel(r) + " " + d.burn + "/" + r.seconds() + "s";
    }

    // ---------- desenho da tela ----------

    /** Atualiza a tela so se tiver alguem olhando. */
    void render(Engineer.Device d) { if (d.gui != null && !d.gui.getViewers().isEmpty()) draw(d); }

    private void draw(Engineer.Device d) {
        Inventory g = d.gui;
        if (g == null) return;
        boolean working = d.enabled && d.lastRate > 0;
        Proc cur = procFor(d.sel);
        if (cur != null && !cur.machine().equals(d.type)) cur = null;
        int lvl = plugin.level(d.owner);

        double energy = 0, cap = 0;
        List<Engineer.Device> src = eng.sourcesFor(d);
        for (Engineer.Device s : src) { energy += s.energy; cap += s.cap(); }

        List<String> info = new ArrayList<>();
        info.add(!d.enabled ? "Desligada" : working ? "+ Trabalhando" : "Parada: " + (d.status.isEmpty() ? "-" : d.status));
        if (working && cur != null) info.add("Fazendo: " + outLabel(cur) + " (" + d.burn + "/" + cur.seconds() + "s)");
        info.add(src.isEmpty() ? "SEM REDE: ligue com cabo" : "Energia na rede: " + (int) energy + "/" + (int) cap);
        org.bukkit.OfflinePlayer owner = Bukkit.getOfflinePlayer(d.owner);
        info.add("Dono: " + (owner.getName() == null ? "?" : owner.getName()) + " (Engenharia nivel " + lvl + ")");
        g.setItem(S_INFO, Engineer.icon(eng.item(d.type).getType(), Engineer.label(d.type), NamedTextColor.GOLD, info.toArray(new String[0])));

        // receitas desta maquina
        List<Proc> mine = procsOf(d.type);
        for (int i = 0; i < RECIPE_SLOTS.length && i < mine.size(); i++) {
            Proc p = mine.get(i);
            boolean ok = lvl >= p.minLevel(), active = working && p == cur;
            List<String> lore = new ArrayList<>();
            lore.add("Entra:");
            for (Ing ing : p.ins()) lore.add("  " + ingText(ing));
            lore.add("Sai: " + p.amount() + "x " + outLabel(p));
            lore.add(p.seconds() + "s, " + (int) p.rate() + " energia/s (" + (int) (p.rate() * p.seconds()) + " por lote)");
            lore.add("");
            lore.add(!ok ? "Requer Engenharia nivel " + p.minLevel() : active ? "+ Em producao" : "Coloque os materiais na entrada.");
            ItemStack ic = Engineer.icon(ok ? outItem(p).getType() : Material.BARRIER, p.amount() + "x " + outLabel(p),
                    !ok ? NamedTextColor.RED : active ? NamedTextColor.GREEN : NamedTextColor.YELLOW, lore.toArray(new String[0]));
            ic.setAmount(Math.max(1, Math.min(64, p.amount())));
            ItemMeta m = ic.getItemMeta(); m.setEnchantmentGlintOverride(active); ic.setItemMeta(m);
            g.setItem(RECIPE_SLOTS[i], ic);
        }

        // barra de progresso
        double frac = working && cur != null ? (double) d.burn / cur.seconds() : 0;
        String ptxt = working && cur != null ? "Progresso: " + d.burn + "/" + cur.seconds() + "s" : "Parada";
        for (int i = 0; i < BAR.length; i++) {
            double lo = (double) i / BAR.length, hi = (double) (i + 1) / BAR.length;
            Material m = !working ? Material.WHITE_STAINED_GLASS_PANE : frac >= hi ? Material.LIME_STAINED_GLASS_PANE
                    : frac > lo || i == 0 ? Material.YELLOW_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE;
            g.setItem(BAR[i], Engineer.icon(m, ptxt, working ? NamedTextColor.GREEN : NamedTextColor.GRAY, d.status.isEmpty() ? "" : d.status));
        }

        // medidor de energia da rede
        int lit = cap <= 0 ? 0 : (int) Math.ceil(GAUGE.length * Math.max(0, Math.min(1, energy / cap)));
        String etxt = src.isEmpty() ? "Sem rede" : "Energia: " + (int) energy + "/" + (int) cap;
        g.setItem(S_ENERGY, Engineer.icon(Material.REDSTONE, "Energia da rede", NamedTextColor.YELLOW, etxt,
                "Geradores e baterias ligados", "por cabo nesta maquina."));
        for (int i = 0; i < GAUGE.length; i++) {
            Material m = src.isEmpty() ? Material.RED_STAINED_GLASS_PANE : i < lit ? Material.YELLOW_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE;
            g.setItem(GAUGE[i], Engineer.icon(m, etxt, NamedTextColor.YELLOW));
        }

        g.setItem(S_POWER, Engineer.icon(d.enabled ? Material.LIME_DYE : Material.GRAY_DYE, d.enabled ? "Ligada" : "Desligada",
                d.enabled ? NamedTextColor.GREEN : NamedTextColor.RED, "Clique para " + (d.enabled ? "desligar" : "ligar") + "."));
        g.setItem(S_TAG, Engineer.icon(d.tagOn ? Material.NAME_TAG : Material.PAPER, "Etiqueta: " + (d.tagOn ? "ligada" : "desligada"),
                d.tagOn ? NamedTextColor.GREEN : NamedTextColor.GRAY, "Clique para " + (d.tagOn ? "desligar" : "ligar") + "."));
        g.setItem(S_NET, Engineer.icon(Material.WAXED_COPPER_CHAIN, "Mostrar rede", NamedTextColor.AQUA,
                eng.netSummary(eng.net(d)), "", "Acende os cabos da rede", "por alguns segundos."));
    }

    // ---------- cliques ----------

    private static boolean in(int[] slots, int raw) { for (int s : slots) if (s == raw) return true; return false; }

    private static boolean empty(ItemStack it) { return it == null || it.getType().isAir(); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        if (!(e.getWhoClicked() instanceof Player p)) { e.setCancelled(true); return; }
        Engineer.Device d = eng.device(h.key);
        if (d == null) { e.setCancelled(true); p.closeInventory(); return; }
        if (e.getClickedInventory() == null) return; // clique fora: soltar o cursor
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR || e.getClick() == ClickType.SWAP_OFFHAND) { e.setCancelled(true); return; }
        Inventory top = e.getInventory();
        int raw = e.getRawSlot();

        if (e.getClickedInventory() != top) {
            // inventario do jogador: shift-clique manda so ingredientes, e so para a entrada
            if (!e.isShiftClick()) return;
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            if (empty(it) || !usable(d.type, it)) return;
            int left = add(top, IN, it, it.getAmount());
            if (left == it.getAmount()) return;
            it.setAmount(left);
            e.setCurrentItem(left > 0 ? it : null);
            return;
        }
        if (in(IN, raw)) {
            // entrada: so aceita o que a maquina usa
            ItemStack incoming = e.getClick() == ClickType.NUMBER_KEY ? p.getInventory().getItem(e.getHotbarButton()) : e.getCursor();
            if (!empty(incoming) && !usable(d.type, incoming)) e.setCancelled(true);
            return;
        }
        if (in(OUT, raw)) {
            // saida: so retirar
            boolean takeOnly = empty(e.getCursor()) && switch (e.getClick()) {
                case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, DROP, CONTROL_DROP -> true;
                default -> false;
            };
            if (!takeOnly) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        switch (raw) {
            case S_POWER -> d.enabled = !d.enabled;
            case S_TAG -> d.tagOn = !d.tagOn;
            case S_NET -> { p.closeInventory(); eng.showNet(p, d); return; }
            default -> { return; }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        draw(d);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        Engineer.Device d = eng.device(h.key);
        for (int raw : e.getRawSlots()) {
            if (raw >= SIZE) continue;
            if (d == null || !in(IN, raw) || !usable(d.type, e.getOldCursor())) { e.setCancelled(true); return; }
        }
    }

    // ---------- quebrar / salvar ----------

    /** Fecha a tela e derruba o que estava na entrada e na saida. */
    void drop(Engineer.Device d, Location at) {
        if (d.gui == null) return;
        for (HumanEntity v : new ArrayList<>(d.gui.getViewers())) v.closeInventory();
        for (int[] group : new int[][]{IN, OUT})
            for (int s : group) {
                ItemStack it = d.gui.getItem(s);
                if (!empty(it)) at.getWorld().dropItemNaturally(at, it);
                d.gui.setItem(s, null);
            }
    }

    /** Espacos com item, para o devices.yml. */
    Map<Integer, ItemStack> contents(Engineer.Device d) {
        Map<Integer, ItemStack> out = new LinkedHashMap<>();
        if (d.gui == null) return out;
        for (int[] group : new int[][]{IN, OUT})
            for (int s : group) { ItemStack it = d.gui.getItem(s); if (!empty(it)) out.put(s, it); }
        return out;
    }

    void restore(Engineer.Device d, int slot, ItemStack it) {
        if (it == null || !(in(IN, slot) || in(OUT, slot))) return;
        inv(d).setItem(slot, it);
    }
}
