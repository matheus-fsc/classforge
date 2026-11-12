package dev.math.classforge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Tuneladora (Engenharia nivel 5): a Estacao lanca o Trem Tunelador, um carrinho de fornalha que
 * abre bem devagar um tunel reto 3x3, assenta trilho no chao e cabo de energia no teto.
 * Gasta trilhos, cabos e brocas guardados na estacao e muita energia da rede dela.
 * Com o Modulo de Coleta, o que foi escavado volta num vagao-bau e descarrega na estacao.
 */
final class Tunnel implements Listener {

    static final String STATION = "estacao";
    static final int SIZE = 54;
    static final int[] SUP = {19, 20, 21, 28, 29, 30}, OUT = {25, 26, 34, 35};
    static final int S_TRAIN = 23, S_MODULE = 32;
    static final int S_INFO = 4, S_POWER = 45, S_DRILL = 46, S_TAG = 47, S_CARGO = 49, S_NET = 51, S_SHOW = 53;
    /** Uma fatia 3x3 a cada STEP_SECONDS, gastando STEP_RATE de energia por segundo enquanto avanca. */
    static int STEP_SECONDS = 4;
    static double STEP_RATE = 10;
    /** Blocos que uma broca aguenta (ar nao gasta). */
    static int DRILL_MAX = 1800;
    static int SWAP_SECONDS = 10, MAX_LEN = 1024, CARGO_STACKS = 27, CART_SPEED = 8, PULL_PER_TICK = 8;
    static double XP_STEP = 0.25;
    static final String MARK = "bc_tunnel_cart";

    static final class Bore {
        int dx = 1, dz, len, drill, swap, transitLeft;
        boolean running;
        final List<ItemStack> cargo = new ArrayList<>(), transit = new ArrayList<>();
        Entity cart;
    }

    static final class Menu implements InventoryHolder {
        final String key; Inventory inv;
        Menu(String key) { this.key = key; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final Classforge plugin;
    private final Engineer eng;
    private final Map<String, Bore> bores = new HashMap<>();
    /** Cabo do teto da primeira fatia -> estacao (a ligacao eletrica entre os dois). */
    private final Map<String, String> tops = new HashMap<>();

    Tunnel(Classforge plugin, Engineer eng) { this.plugin = plugin; this.eng = eng; }

    Bore bore(Engineer.Device d) { return bores.computeIfAbsent(d.key(), k -> new Bore()); }

    private static String topKey(Engineer.Device d, Bore t) { return Engineer.key(d.world, d.x + t.dx, d.y + 2, d.z + t.dz); }

    /** Estacao recem colocada: o tunel sai na direcao em que o jogador esta olhando. */
    void placed(Engineer.Device d, Block b, Player p) {
        BlockFace f = p.getFacing();
        if (f.getModX() == 0 && f.getModZ() == 0) f = BlockFace.NORTH;
        Bore t = bore(d);
        t.dx = f.getModX(); t.dz = f.getModZ();
        tops.put(topKey(d, t), d.key());
        if (b.getBlockData() instanceof Directional dir) { dir.setFacing(f); b.setBlockData(dir, false); }
        p.sendMessage(Component.text("O tunel vai sair para " + dirName(t) + ". Coloque o Trem Tunelador, trilhos, cabos e brocas na estacao e ligue numa rede forte ("
                + (int) STEP_RATE + " energia/s).", NamedTextColor.GRAY));
    }

    private static String dirName(Bore t) { return t.dx > 0 ? "leste" : t.dx < 0 ? "oeste" : t.dz > 0 ? "sul" : "norte"; }

    /** Ligacao eletrica extra entre a estacao e o cabo do teto do tunel. */
    void link(World w, int[] c, List<int[]> out) {
        String k = Engineer.key(w.getName(), c[0], c[1], c[2]);
        Engineer.Device d = eng.device(k);
        if (d != null && d.type.equals(STATION)) {
            Bore t = bores.get(k);
            if (t != null && eng.hasCable(topKey(d, t))) out.add(new int[]{d.x + t.dx, d.y + 2, d.z + t.dz});
            return;
        }
        String st = tops.get(k);
        if (st == null) return;
        Engineer.Device s = eng.device(st);
        if (s != null && s.type.equals(STATION)) out.add(new int[]{s.x, s.y, s.z});
    }

    // ---------- itens ----------

    private boolean is(ItemStack it, String type) { return it != null && type.equals(eng.typeOf(it)); }

    private boolean isRail(ItemStack it) { return it != null && it.getType() == Material.RAIL && eng.typeOf(it) == null; }

    /** Em quais espacos da estacao este item entra (null = nao entra). */
    private int[] slotsFor(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        if (isRail(it) || is(it, "cabo") || is(it, "broca")) return SUP;
        if (is(it, "trem")) return new int[]{S_TRAIN};
        if (is(it, "modulo_coleta")) return new int[]{S_MODULE};
        return null;
    }

    private int count(Inventory g, java.util.function.Predicate<ItemStack> ok) {
        int n = 0;
        for (int s : SUP) { ItemStack it = g.getItem(s); if (it != null && ok.test(it)) n += it.getAmount(); }
        return n;
    }

    private boolean takeOne(Inventory g, java.util.function.Predicate<ItemStack> ok) {
        for (int s : SUP) {
            ItemStack it = g.getItem(s);
            if (it == null || !ok.test(it)) continue;
            it.setAmount(it.getAmount() - 1);
            g.setItem(s, it.getAmount() > 0 ? it : null);
            return true;
        }
        return false;
    }

    /** Recebe item de uma maquina em cima (ex.: Laminadora de Trilhos). Devolve o que sobrou. */
    int accept(Engineer.Device d, ItemStack it, int amount) {
        int[] slots = slotsFor(it);
        return slots == null ? amount : Factory.add(inv(d), slots, it, amount);
    }

    // ---------- tela ----------

    Inventory inv(Engineer.Device d) {
        if (d.gui != null) return d.gui;
        Menu h = new Menu(d.key());
        Inventory g = Bukkit.createInventory(h, SIZE, Component.text("Estacao da Tuneladora"));
        h.inv = g;
        ItemStack filler = Engineer.icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 0; i < SIZE; i++) g.setItem(i, filler);
        for (int s : SUP) g.setItem(s, null);
        for (int s : OUT) g.setItem(s, null);
        g.setItem(S_TRAIN, null);
        g.setItem(S_MODULE, null);
        g.setItem(10, Engineer.icon(Material.RAIL, "Suprimentos", NamedTextColor.AQUA, "Nos 6 espacos abaixo:", "Trilhos: 1 por bloco de tunel.", "Cabos de Energia: 1 por bloco (opcional,", "sem cabo o tunel fica sem energia).", "Brocas de Diamante: troca automatica.", "", "Bau em cima da estacao abastece.", "Laminadora em cima entrega os trilhos."));
        g.setItem(14, Engineer.icon(Material.FURNACE_MINECART, "Trem Tunelador", NamedTextColor.GOLD, "Coloque o trem no espaco abaixo.", "Parado, pode retirar: o tunel", "continua de onde parou."));
        g.setItem(41, Engineer.icon(Material.HOPPER_MINECART, "Modulo de Coleta", NamedTextColor.GOLD, "Upgrade: coloque no espaco acima.", "O trem suga o que escava e manda", "um vagao-bau de volta para a estacao.", "Sem ele, os itens caem no tunel."));
        g.setItem(16, Engineer.icon(Material.CHEST_MINECART, "Carga", NamedTextColor.GREEN, "O vagao descarrega nos 4 espacos", "abaixo, ou direto no bau que", "estiver embaixo da estacao."));
        d.gui = g;
        return g;
    }

    void open(Player p, Engineer.Device d) {
        Inventory g = inv(d);
        draw(d);
        p.openInventory(g);
    }

    void render(Engineer.Device d) { if (d.gui != null && !d.gui.getViewers().isEmpty()) draw(d); }

    private void draw(Engineer.Device d) {
        Inventory g = d.gui;
        if (g == null) return;
        Bore t = bore(d);
        boolean working = t.running && d.lastRate > 0;
        double energy = 0, cap = 0;
        List<Engineer.Device> src = eng.sourcesFor(d);
        for (Engineer.Device s : src) { energy += s.energy; cap += s.cap(); }
        int rails = count(g, this::isRail), cabos = count(g, it -> is(it, "cabo")), brocas = count(g, it -> is(it, "broca"));

        g.setItem(S_INFO, Engineer.icon(Material.OBSERVER, "Estacao da Tuneladora", NamedTextColor.GOLD,
                !t.running ? "Parada" + (d.status.isEmpty() ? "" : ": " + d.status) : working ? "+ Escavando" : "Esperando: " + (d.status.isEmpty() ? "-" : d.status),
                "Tunel: " + t.len + "/" + MAX_LEN + " blocos para " + dirName(t),
                "Trilhos " + rails + "  |  cabos " + cabos + "  |  brocas " + brocas,
                src.isEmpty() ? "SEM REDE: ligue com cabo" : "Energia na rede: " + (int) energy + "/" + (int) cap,
                "Consumo: " + (int) STEP_RATE + "/s, 1 bloco a cada " + STEP_SECONDS + "s",
                "Requer Engenharia nivel 5 (dono nivel " + plugin.level(d.owner) + ")"));
        g.setItem(S_POWER, Engineer.icon(t.running ? Material.LIME_DYE : Material.GRAY_DYE, t.running ? "Escavando" : "Parada",
                t.running ? NamedTextColor.GREEN : NamedTextColor.RED, "Clique para " + (t.running ? "parar" : "comecar") + ".",
                "Com o trem andando, ele e o", "modulo ficam travados na estacao."));
        int pct = 100 * Math.max(0, t.drill) / DRILL_MAX;
        g.setItem(S_DRILL, Engineer.icon(t.drill > 0 ? Material.POINTED_DRIPSTONE : Material.BARRIER, "Broca: " + pct + "%",
                t.drill > DRILL_MAX / 5 ? NamedTextColor.GREEN : NamedTextColor.RED,
                Math.max(0, t.drill) + "/" + DRILL_MAX + " blocos", t.swap > 0 ? "Trocando: " + t.swap + "s" : "Gasta, o trem troca sozinho por", "uma Broca de Diamante da estacao", "(" + SWAP_SECONDS + "s parado). Reservas: " + brocas));
        g.setItem(S_TAG, Engineer.icon(d.tagOn ? Material.NAME_TAG : Material.PAPER, "Etiqueta: " + (d.tagOn ? "ligada" : "desligada"),
                d.tagOn ? NamedTextColor.GREEN : NamedTextColor.GRAY, "Clique para " + (d.tagOn ? "desligar" : "ligar") + "."));
        boolean module = is(g.getItem(S_MODULE), "modulo_coleta");
        g.setItem(S_CARGO, Engineer.icon(module ? Material.CHEST_MINECART : Material.MINECART, module ? "Vagao de carga" : "Sem Modulo de Coleta",
                module ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                module ? "No trem: " + t.cargo.size() + "/" + CARGO_STACKS + " pilhas" : "Os itens escavados caem no tunel.",
                t.transit.isEmpty() ? "Vagao parado." : t.transitLeft > 0 ? "Vagao a caminho: " + t.transitLeft + "s" : "Vagao esperando espaco na estacao!"));
        g.setItem(S_NET, Engineer.icon(Material.WAXED_COPPER_CHAIN, "Mostrar rede", NamedTextColor.AQUA,
                eng.netSummary(eng.net(d)), "", "Acende os cabos da rede", "por alguns segundos."));
        g.setItem(S_SHOW, Engineer.icon(Material.ENDER_EYE, "Onde esta o trem", NamedTextColor.LIGHT_PURPLE,
                "Frente do tunel: " + (d.x + t.dx * t.len) + " " + d.y + " " + (d.z + t.dz * t.len)));
    }

    /** Linha da etiqueta flutuante. */
    String tagLine(Engineer.Device d) {
        Bore t = bore(d);
        return "tunel " + t.len + " blocos  •  broca " + (100 * Math.max(0, t.drill) / DRILL_MAX) + "%";
    }

    // ---------- trabalho (1x por segundo) ----------

    void tick(Engineer.Device d, Block b) {
        Bore t = bore(d);
        Inventory g = inv(d);
        World w = b.getWorld();
        supply(b, g);
        boolean hasTrain = is(g.getItem(S_TRAIN), "trem");
        boolean module = is(g.getItem(S_MODULE), "modulo_coleta");
        // carga parada no trem volta quando ele para (ou quando tiram o modulo, cai no tunel)
        if (!t.cargo.isEmpty() && !module) { dropAt(w, face(d, t, 0), t.cargo); t.cargo.clear(); }
        if (!t.cargo.isEmpty() && !t.running) dispatch(d, t, w);
        transit(d, t, g, b);
        if (t.running) advance(d, t, g, w, hasTrain, module);
        syncCart(d, t, w, hasTrain); // depois de avancar: o carrinho acompanha a frente do tunel
        render(d);
    }

    private void stop(Engineer.Device d, Bore t, String why) {
        t.running = false; d.burn = 0; d.status = why;
        Player o = Bukkit.getPlayer(d.owner);
        if (o != null) o.sendMessage(Component.text("⚠ Tuneladora parou: " + why + " (tunel com " + t.len + " blocos).", NamedTextColor.RED));
    }

    private Location face(Engineer.Device d, Bore t, int ahead) {
        World w = Bukkit.getWorld(d.world);
        int n = Math.max(1, t.len + ahead);
        return new Location(w, d.x + t.dx * n + 0.5, d.y + 0.1, d.z + t.dz * n + 0.5);
    }

    private void advance(Engineer.Device d, Bore t, Inventory g, World w, boolean hasTrain, boolean module) {
        if (!hasTrain) { stop(d, t, "sem trem na estacao"); return; }
        if (plugin.level(d.owner) < 5) { stop(d, t, "requer Engenharia nivel 5"); return; }
        if (t.len >= MAX_LEN) { stop(d, t, "tunel no tamanho maximo"); return; }
        if (d.y + 3 >= w.getMaxHeight() || d.y - 1 <= w.getMinHeight()) { stop(d, t, "altura invalida"); return; }
        if (t.drill <= 0) {
            if (t.swap > 0) {
                d.status = "trocando broca " + t.swap + "s";
                if (--t.swap == 0) { t.drill = DRILL_MAX; w.playSound(face(d, t, 0), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f); }
                return;
            }
            if (takeOne(g, it -> is(it, "broca"))) { t.swap = SWAP_SECONDS; d.status = "trocando broca"; }
            else d.status = "sem broca";
            return;
        }
        if (count(g, this::isRail) < 1) { d.status = "sem trilhos"; return; }
        if (module && t.cargo.size() >= CARGO_STACKS) {
            if (t.transit.isEmpty()) dispatch(d, t, w); else { d.status = "aguardando vagao"; return; }
        }
        int fx = d.x + t.dx * (t.len + 1), fz = d.z + t.dz * (t.len + 1);
        int lx = -t.dz, lz = t.dx; // eixo lateral
        if (!w.isChunkLoaded(fx >> 4, fz >> 4) || !w.isChunkLoaded((fx + t.dx) >> 4, (fz + t.dz) >> 4)) { d.status = "area descarregada"; return; }
        for (int lat = -1; lat <= 1; lat++)
            for (int v = 0; v <= 2; v++) {
                Block blk = w.getBlockAt(fx + lat * lx, d.y + v, fz + lat * lz);
                if (!blk.getType().isAir() && !blk.isLiquid() && eng.mineSkip(blk)) {
                    stop(d, t, "obstruido por " + Engineer.pretty(blk.getType()).toLowerCase(Locale.ROOT) + " em " + blk.getX() + " " + blk.getY() + " " + blk.getZ());
                    return;
                }
            }
        if (!eng.work(d, STEP_RATE)) return;
        Location at = new Location(w, fx + 0.5, d.y + 1.0, fz + 0.5);
        if (++d.burn < STEP_SECONDS) {
            w.playSound(at, Sound.BLOCK_GRINDSTONE_USE, 0.7f, 0.5f);
            w.spawnParticle(Particle.BLOCK, at.clone().subtract(t.dx * 0.5, 0, t.dz * 0.5), 14, 0.5, 0.6, 0.5, Material.STONE.createBlockData());
            return;
        }
        d.burn = 0;
        Material lining = d.y < 0 ? Material.COBBLED_DEEPSLATE : Material.COBBLESTONE;
        // revestimento: agua, lava, areia e cascalho em volta do tunel viram pedra antes de abrir
        for (int lat = -2; lat <= 2; lat++)
            for (int v = -1; v <= 3; v++) {
                if (Math.abs(lat) != 2 && v != -1 && v != 3) continue;
                Block ring = w.getBlockAt(fx + lat * lx, d.y + v, fz + lat * lz);
                if ((ring.isLiquid() || ring.getType().hasGravity()) && !eng.isProtected(ring)) ring.setType(lining, false);
            }
        List<ItemStack> drops = new ArrayList<>();
        ItemStack pick = new ItemStack(Material.DIAMOND_PICKAXE);
        for (int lat = -1; lat <= 1; lat++)
            for (int v = 0; v <= 2; v++) {
                Block blk = w.getBlockAt(fx + lat * lx, d.y + v, fz + lat * lz);
                if (blk.getType().isAir()) continue;
                if (blk.isLiquid()) { blk.setType(Material.AIR, false); continue; }
                drops.addAll(blk.getDrops(pick));
                w.spawnParticle(Particle.BLOCK, blk.getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, blk.getBlockData());
                blk.setType(Material.AIR, false);
                t.drill--;
            }
        // a parede da frente tambem nao pode vazar para dentro
        for (int lat = -1; lat <= 1; lat++)
            for (int v = 0; v <= 2; v++) {
                Block next = w.getBlockAt(fx + t.dx + lat * lx, d.y + v, fz + t.dz + lat * lz);
                if (next.isLiquid()) next.setType(lining, false);
            }
        Block floor = w.getBlockAt(fx, d.y - 1, fz);
        if (!floor.getType().isSolid() && !eng.isProtected(floor)) floor.setType(lining, false);
        w.getBlockAt(fx, d.y, fz).setType(Material.RAIL);
        takeOne(g, this::isRail);
        if (takeOne(g, it -> is(it, "cabo"))) {
            Block top = w.getBlockAt(fx, d.y + 2, fz);
            top.setType(Material.WAXED_COPPER_CHAIN, false);
            if (top.getBlockData() instanceof Orientable o) { o.setAxis(t.dx != 0 ? Axis.X : Axis.Z); top.setBlockData(o, false); }
            eng.addCable(top);
        }
        t.len++;
        w.playSound(at, Sound.BLOCK_STONE_BREAK, 1f, 0.6f);
        if (module) { for (ItemStack it : drops) stash(t.cargo, it); if (t.len % 16 == 0 && t.transit.isEmpty()) dispatch(d, t, w); }
        else dropAt(w, at, drops);
        eng.xpFrac(d.owner, XP_STEP);
        d.status = "";
    }

    private static void stash(List<ItemStack> list, ItemStack it) {
        int left = it.getAmount();
        for (ItemStack s : list) {
            if (left <= 0) break;
            if (!s.isSimilar(it)) continue;
            int take = Math.min(left, s.getMaxStackSize() - s.getAmount());
            if (take > 0) { s.setAmount(s.getAmount() + take); left -= take; }
        }
        while (left > 0) {
            ItemStack n = it.clone();
            n.setAmount(Math.min(left, it.getMaxStackSize()));
            left -= n.getAmount();
            list.add(n);
        }
    }

    private static void dropAt(World w, Location at, List<ItemStack> items) {
        if (w == null) return;
        for (ItemStack it : items) if (it != null && !it.getType().isAir()) w.dropItemNaturally(at, it);
    }

    /** Bau em cima abastece (trilho, cabo, broca); bau embaixo recebe a carga. */
    private void supply(Block b, Inventory g) {
        Block upB = b.getRelative(BlockFace.UP), downB = b.getRelative(BlockFace.DOWN);
        Inventory up = eng.at(upB) == null ? Engineer.containerAt(upB) : null;
        if (up != null) {
            int budget = PULL_PER_TICK;
            for (int i = 0; i < up.getSize() && budget > 0; i++) {
                ItemStack it = up.getItem(i);
                if (it == null || slotsFor(it) != SUP) continue;
                int want = Math.min(budget, Math.min(it.getAmount(), Factory.room(g, SUP, it)));
                if (want <= 0) continue;
                Factory.add(g, SUP, it, want);
                it.setAmount(it.getAmount() - want);
                up.setItem(i, it.getAmount() > 0 ? it : null);
                budget -= want;
            }
        }
        Inventory down = eng.at(downB) == null ? Engineer.containerAt(downB) : null;
        if (down == null) return;
        for (int s : OUT) {
            ItemStack it = g.getItem(s);
            if (it == null || it.getType().isAir()) continue;
            Map<Integer, ItemStack> rest = down.addItem(it.clone());
            int left = rest.isEmpty() ? 0 : rest.values().iterator().next().getAmount();
            it.setAmount(left);
            g.setItem(s, left > 0 ? it : null);
        }
    }

    // ---------- vagao de carga ----------

    private void dispatch(Engineer.Device d, Bore t, World w) {
        if (!t.transit.isEmpty() || t.cargo.isEmpty()) return;
        t.transit.addAll(t.cargo);
        t.cargo.clear();
        t.transitLeft = Math.max(3, t.len / CART_SPEED);
        animate(d, t, w, t.transitLeft);
    }

    private void transit(Engineer.Device d, Bore t, Inventory g, Block b) {
        if (t.transit.isEmpty()) return;
        if (t.transitLeft > 0) { t.transitLeft--; return; }
        Block downB = b.getRelative(BlockFace.DOWN);
        Inventory down = eng.at(downB) == null ? Engineer.containerAt(downB) : null;
        for (Iterator<ItemStack> it = t.transit.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            int left = s.getAmount();
            if (down != null) {
                Map<Integer, ItemStack> rest = down.addItem(s.clone());
                left = rest.isEmpty() ? 0 : rest.values().iterator().next().getAmount();
            }
            if (left > 0) left = Factory.add(g, OUT, s, left);
            if (left <= 0) it.remove(); else s.setAmount(left);
        }
        if (t.transit.isEmpty()) b.getWorld().playSound(d.center(b.getWorld()), Sound.BLOCK_CHEST_CLOSE, 0.8f, 0.9f);
    }

    private void protect(Minecart m) {
        m.setPersistent(false);
        m.addScoreboardTag(MARK);
        m.setInvulnerable(true);
        m.setGravity(false);
        m.setMaxSpeed(0);
        m.setSilent(true);
    }

    /** Vagao-bau visual voltando da frente do tunel ate a estacao. */
    private void animate(Engineer.Device d, Bore t, World w, int seconds) {
        Location from = face(d, t, 0);
        Location to = new Location(w, d.x + t.dx + 0.5, d.y + 0.1, d.z + t.dz + 0.5);
        if (w == null || !w.isChunkLoaded(from.getBlockX() >> 4, from.getBlockZ() >> 4)) return;
        from.setYaw(t.dx != 0 ? 0 : 90); // carrinho alinhado com o trilho
        StorageMinecart cart = w.spawn(from, StorageMinecart.class, this::protect);
        int total = seconds * 10;
        new BukkitRunnable() {
            int i;
            @Override public void run() {
                if (!cart.isValid() || ++i > total) { cart.remove(); cancel(); return; }
                double f = (double) i / total;
                Location l = from.clone().add((to.getX() - from.getX()) * f, 0, (to.getZ() - from.getZ()) * f);
                if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) { cart.remove(); cancel(); return; }
                cart.teleport(l);
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    /** O carrinho de fornalha que aparece na frente do tunel (so visual: quem manda e a estacao). */
    private void syncCart(Engineer.Device d, Bore t, World w, boolean hasTrain) {
        if (!hasTrain || t.len < 1) { removeCart(t); return; }
        Location at = face(d, t, 0);
        at.setYaw(t.dx != 0 ? 0 : 90);
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) { removeCart(t); return; }
        if (t.cart == null || !t.cart.isValid()) t.cart = w.spawn(at, PoweredMinecart.class, this::protect);
        else if (t.cart.getLocation().distanceSquared(at) > 0.01) t.cart.teleport(at);
        if (t.running && d.lastRate > 0) w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.clone().add(0, 1.1, 0), 1, 0.05, 0.05, 0.05, 0.01);
    }

    private void removeCart(Bore t) { if (t.cart != null) { t.cart.remove(); t.cart = null; } }

    /** Some com todos os carrinhos visuais (ao desligar o plugin). */
    void clear() {
        for (Bore t : bores.values()) removeCart(t);
        for (World w : Bukkit.getWorlds())
            for (Minecart m : w.getEntitiesByClass(Minecart.class)) if (m.getScoreboardTags().contains(MARK)) m.remove();
    }

    private Engineer.Device stationOf(Entity cart) {
        for (Map.Entry<String, Bore> e : bores.entrySet()) if (e.getValue().cart == cart) return eng.device(e.getKey());
        return null;
    }

    @EventHandler public void onCartDamage(VehicleDamageEvent e) { if (e.getVehicle().getScoreboardTags().contains(MARK)) e.setCancelled(true); }
    @EventHandler public void onCartDestroy(VehicleDestroyEvent e) { if (e.getVehicle().getScoreboardTags().contains(MARK)) e.setCancelled(true); }
    @EventHandler public void onCartPush(VehicleEntityCollisionEvent e) { if (e.getVehicle().getScoreboardTags().contains(MARK)) e.setCancelled(true); }

    @EventHandler
    public void onCartClick(PlayerInteractEntityEvent e) {
        if (!e.getRightClicked().getScoreboardTags().contains(MARK)) return;
        e.setCancelled(true);
        Engineer.Device d = stationOf(e.getRightClicked());
        if (d != null) open(e.getPlayer(), d);
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
        if (e.getClickedInventory() == null) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR || e.getClick() == ClickType.SWAP_OFFHAND) { e.setCancelled(true); return; }
        Bore t = bore(d);
        Inventory top = e.getInventory();
        int raw = e.getRawSlot();

        if (e.getClickedInventory() != top) {
            if (!e.isShiftClick()) return;
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            int[] slots = slotsFor(it);
            if (slots == null || (slots != SUP && t.running)) return;
            int left = Factory.add(top, slots, it, it.getAmount());
            if (left == it.getAmount()) return;
            it.setAmount(left);
            e.setCurrentItem(left > 0 ? it : null);
            return;
        }
        boolean special = raw == S_TRAIN || raw == S_MODULE;
        if (in(SUP, raw) || special) {
            if (special && t.running) { e.setCancelled(true); p.sendMessage(Component.text("Pare a tuneladora antes de mexer no trem ou no modulo.", NamedTextColor.RED)); return; }
            ItemStack incoming = e.getClick() == ClickType.NUMBER_KEY ? p.getInventory().getItem(e.getHotbarButton()) : e.getCursor();
            if (empty(incoming)) return;
            int[] slots = slotsFor(incoming);
            if (slots == null || !in(slots, raw)) e.setCancelled(true);
            return;
        }
        if (in(OUT, raw)) {
            boolean takeOnly = empty(e.getCursor()) && switch (e.getClick()) {
                case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, DROP, CONTROL_DROP -> true;
                default -> false;
            };
            if (!takeOnly) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        switch (raw) {
            case S_POWER -> { t.running = !t.running; d.status = ""; d.burn = 0; }
            case S_TAG -> d.tagOn = !d.tagOn;
            case S_NET -> { p.closeInventory(); eng.showNet(p, d); return; }
            default -> { return; }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        draw(d);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        int[] slots = slotsFor(e.getOldCursor());
        for (int raw : e.getRawSlots()) {
            if (raw >= SIZE) continue;
            if (slots != SUP || !in(SUP, raw)) { e.setCancelled(true); return; }
        }
    }

    // ---------- quebrar / salvar ----------

    private static final int[] STORED = {19, 20, 21, 28, 29, 30, 25, 26, 34, 35, S_TRAIN, S_MODULE};

    /** Estacao quebrada: fecha a tela, derruba tudo e recolhe o trem (o tunel fica). */
    void drop(Engineer.Device d, Location at) {
        Bore t = bores.remove(d.key());
        if (t != null) {
            removeCart(t);
            tops.remove(topKey(d, t));
            dropAt(at.getWorld(), at, t.cargo);
            dropAt(at.getWorld(), at, t.transit);
        }
        if (d.gui == null) return;
        for (HumanEntity v : new ArrayList<>(d.gui.getViewers())) v.closeInventory();
        for (int s : STORED) {
            ItemStack it = d.gui.getItem(s);
            if (!empty(it)) at.getWorld().dropItemNaturally(at, it);
            d.gui.setItem(s, null);
        }
    }

    void save(Engineer.Device d, YamlConfiguration y, String k) {
        Bore t = bore(d);
        y.set(k + ".tun.dx", t.dx); y.set(k + ".tun.dz", t.dz); y.set(k + ".tun.len", t.len);
        y.set(k + ".tun.drill", t.drill); y.set(k + ".tun.swap", t.swap); y.set(k + ".tun.running", t.running);
        y.set(k + ".tun.transitLeft", t.transitLeft);
        if (!t.cargo.isEmpty()) y.set(k + ".tun.cargo", new ArrayList<>(t.cargo));
        if (!t.transit.isEmpty()) y.set(k + ".tun.transit", new ArrayList<>(t.transit));
        if (d.gui == null) return;
        for (int s : STORED) { ItemStack it = d.gui.getItem(s); if (!empty(it)) y.set(k + ".inv.s" + s, it); }
    }

    void load(Engineer.Device d, ConfigurationSection c) {
        Bore t = bore(d);
        t.dx = c.getInt("tun.dx", 1); t.dz = c.getInt("tun.dz", 0);
        if (t.dx == 0 && t.dz == 0) t.dx = 1;
        t.len = c.getInt("tun.len"); t.drill = c.getInt("tun.drill"); t.swap = c.getInt("tun.swap");
        t.running = c.getBoolean("tun.running"); t.transitLeft = c.getInt("tun.transitLeft");
        for (Object o : c.getList("tun.cargo", List.of())) if (o instanceof ItemStack it) t.cargo.add(it);
        for (Object o : c.getList("tun.transit", List.of())) if (o instanceof ItemStack it) t.transit.add(it);
        tops.put(topKey(d, t), d.key());
        ConfigurationSection inv = c.getConfigurationSection("inv");
        if (inv == null) return;
        Inventory g = inv(d);
        for (String sk : inv.getKeys(false)) {
            int slot = Integer.parseInt(sk.substring(1));
            ItemStack it = inv.getItemStack(sk);
            if (it != null && in(STORED, slot)) g.setItem(slot, it);
        }
    }
}
