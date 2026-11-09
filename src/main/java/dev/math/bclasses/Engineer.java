package dev.math.bclasses;

import dev.math.bclasses.BabylonClasses.PClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Engenheiro: bancada, torres sentinela, geradores (carvao, geotermico, solar) e bateria. */
final class Engineer implements Listener {

    static final String[] TYPES = {"bancada", "torre", "carvao", "geotermico", "solar", "bateria"};

    // por nivel 1..5
    static final int[] MAX_TURRETS = {2, 3, 4, 5, 6};
    static final int[] RANGE = {12, 14, 16, 18, 20};
    static final int[] COOLDOWN = {20, 17, 14, 12, 10}; // ticks entre tiros
    static final int MAX_POWER_DEVICES = 10;
    static final double SHOT_COST = 4;
    static final double LINK_RANGE = 12;

    static final class Device {
        final String type; final String world; final int x, y, z; final UUID owner;
        double energy; int burn; long nextShot;
        Device(String type, String world, int x, int y, int z, UUID owner) {
            this.type = type; this.world = world; this.x = x; this.y = y; this.z = z; this.owner = owner;
        }
        String key() { return world + ":" + x + ":" + y + ":" + z; }
        Location center(World w) { return new Location(w, x + 0.5, y + 0.5, z + 0.5); }
        double cap() {
            return switch (type) { case "carvao" -> 400; case "geotermico", "solar" -> 200; case "bateria" -> 5000; default -> 0; };
        }
        boolean isSource() { return cap() > 0; }
    }

    record Recipe(String type, int minLevel, Map<Material, Integer> cost) { }

    private final BabylonClasses plugin;
    private final NamespacedKey devKey, arrowKey, recipeKey;
    private final Map<String, Device> devices = new HashMap<>();
    private final Map<UUID, double[]> lastPos = new HashMap<>(); // x, y, z, tick
    private final File file;
    private long tick;
    private final List<Recipe> recipes = new ArrayList<>();

    Engineer(BabylonClasses plugin) {
        this.plugin = plugin;
        this.devKey = new NamespacedKey(plugin, "device");
        this.arrowKey = new NamespacedKey(plugin, "turret-arrow");
        this.recipeKey = new NamespacedKey(plugin, "bancada");
        this.file = new File(plugin.getDataFolder(), "devices.yml");
        recipes.add(new Recipe("torre", 1, costs(Material.DISPENSER, 1, Material.BOW, 1, Material.IRON_INGOT, 4, Material.REDSTONE, 4)));
        recipes.add(new Recipe("carvao", 1, costs(Material.FURNACE, 1, Material.IRON_INGOT, 4, Material.COPPER_INGOT, 2, Material.REDSTONE, 2)));
        recipes.add(new Recipe("solar", 1, costs(Material.DAYLIGHT_DETECTOR, 1, Material.COPPER_INGOT, 4, Material.GLASS, 2, Material.REDSTONE, 2)));
        recipes.add(new Recipe("geotermico", 2, costs(Material.COBBLED_DEEPSLATE, 6, Material.COPPER_INGOT, 4, Material.IRON_INGOT, 2, Material.GOLD_INGOT, 1)));
        recipes.add(new Recipe("bateria", 2, costs(Material.COPPER_INGOT, 6, Material.REDSTONE_BLOCK, 1, Material.IRON_INGOT, 2)));
    }

    private static Map<Material, Integer> costs(Object... kv) {
        Map<Material, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((Material) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    void enable() {
        load();
        ShapedRecipe r = new ShapedRecipe(recipeKey, item("bancada"));
        r.shape(" R ", "ICI", "III");
        r.setIngredient('R', Material.REDSTONE);
        r.setIngredient('I', Material.IRON_INGOT);
        r.setIngredient('C', Material.CRAFTING_TABLE);
        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(r);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickTurrets, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickPower, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 1200L, 1200L);
    }

    void onBecome(Player p) {
        p.discoverRecipe(recipeKey);
        p.sendMessage(Component.text("⚒ Engenheiro: crafte a Bancada de Engenharia (mesa de trabalho + 5 ferro + redstone) e abra ela para montar torres e geradores.", NamedTextColor.GOLD));
        p.sendMessage(Component.text(levelSummary(1), NamedTextColor.GRAY));
    }

    String levelSummary(int lvl) {
        int i = lvl - 1;
        String s = "Nivel " + lvl + ": ate " + MAX_TURRETS[i] + " torres, alcance " + RANGE[i] + ", " +
                String.format(Locale.ROOT, "%.1f", 20.0 / COOLDOWN[i]) + " tiros/s";
        if (lvl >= 3) s += ", flecha de fogo (30%)";
        if (lvl >= 5) s += ", tiro duplo";
        return s;
    }

    // ---------- itens ----------

    ItemStack item(String type) {
        Material mat; String name; List<String> lore;
        switch (type) {
            case "bancada" -> { mat = Material.SMITHING_TABLE; name = "Bancada de Engenharia"; lore = List.of("Clique com o direito para montar dispositivos.", "So engenheiros conseguem usar."); }
            case "torre" -> { mat = Material.DISPENSER; name = "Torre Sentinela"; lore = List.of("Atira flechas guardadas nela em mobs hostis.", "Gasta " + (int) SHOT_COST + " de energia por tiro.", "Precisa de gerador ou bateria a ate " + (int) LINK_RANGE + " blocos."); }
            case "carvao" -> { mat = Material.FURNACE; name = "Gerador a Carvao"; lore = List.of("Coloque carvao no espaco de combustivel.", "Funil pela lateral tambem abastece.", "4 energia/s enquanto queima."); }
            case "geotermico" -> { mat = Material.MAGMA_BLOCK; name = "Gerador Geotermico"; lore = List.of("0.5 energia/s por fonte de lava encostada.", "Dobro no Nether. Nunca para."); }
            case "solar" -> { mat = Material.DAYLIGHT_DETECTOR; name = "Painel Solar"; lore = List.of("1.5 energia/s de dia com ceu aberto.", "Nao gera a noite."); }
            case "bateria" -> { mat = Material.LODESTONE; name = "Bateria"; lore = List.of("Guarda ate 5000 de energia.", "Geradores a ate " + (int) LINK_RANGE + " blocos carregam ela."); }
            default -> { return null; }
        }
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(name, NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        l.add(Component.text("Engenheiro", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        m.getPersistentDataContainer().set(devKey, PersistentDataType.STRING, type);
        it.setItemMeta(m);
        return it;
    }

    String typeOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(devKey, PersistentDataType.STRING);
    }

    static String label(String type) {
        return switch (type) {
            case "bancada" -> "Bancada de Engenharia"; case "torre" -> "Torre Sentinela"; case "carvao" -> "Gerador a Carvao";
            case "geotermico" -> "Gerador Geotermico"; case "solar" -> "Painel Solar"; case "bateria" -> "Bateria"; default -> type;
        };
    }

    // ---------- crafting da bancada ----------

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!"bancada".equals(typeOf(e.getInventory().getResult()))) return;
        if (!(e.getView().getPlayer() instanceof Player p) || !plugin.is(p, PClass.ENGENHEIRO))
            e.getInventory().setResult(null);
    }

    // ---------- colocar / quebrar ----------

    private Device at(Block b) { return devices.get(b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ()); }

    private long count(UUID owner, String world, boolean turrets) {
        String base = BabylonClasses.baseOf(world);
        return devices.values().stream().filter(d -> d.owner.equals(owner)
                && BabylonClasses.baseOf(d.world).equals(base)
                && (turrets ? d.type.equals("torre") : d.isSource())).count();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        String type = typeOf(e.getItemInHand());
        if (type == null) return;
        Player p = e.getPlayer();
        if (!plugin.is(p, PClass.ENGENHEIRO)) {
            e.setCancelled(true);
            p.sendMessage(Component.text("So engenheiros sabem montar isso.", NamedTextColor.RED));
            return;
        }
        Block b = e.getBlockPlaced();
        int lvl = plugin.level(p.getUniqueId());
        if (type.equals("torre") && count(p.getUniqueId(), b.getWorld().getName(), true) >= MAX_TURRETS[lvl - 1]) {
            e.setCancelled(true);
            p.sendMessage(Component.text("Limite de " + MAX_TURRETS[lvl - 1] + " torres no nivel " + lvl + ".", NamedTextColor.RED));
            return;
        }
        Device d = new Device(type, b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), p.getUniqueId());
        if (d.isSource() && count(p.getUniqueId(), b.getWorld().getName(), false) >= MAX_POWER_DEVICES) {
            e.setCancelled(true);
            p.sendMessage(Component.text("Limite de " + MAX_POWER_DEVICES + " geradores/baterias.", NamedTextColor.RED));
            return;
        }
        devices.put(d.key(), d);
        p.sendMessage(Component.text("⚙ " + label(type) + " instalado.", NamedTextColor.GOLD));
        if (type.equals("torre")) p.sendMessage(Component.text("Coloque flechas dentro dela e um gerador ou bateria a ate " + (int) LINK_RANGE + " blocos.", NamedTextColor.GRAY));
    }

    /** Remove o dispositivo: derruba o conteudo, o item customizado e vira ar. */
    private void breakDevice(Block b, Device d, boolean dropItem) {
        devices.remove(d.key());
        BlockState st = b.getState();
        if (st instanceof Container c) {
            for (ItemStack it : c.getInventory().getContents())
                if (it != null && it.getType() != Material.AIR) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), it);
            c.getInventory().clear();
        }
        b.setType(Material.AIR);
        if (dropItem) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), item(d.type));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Device d = at(e.getBlock());
        if (d == null) return;
        e.setCancelled(true);
        breakDevice(e.getBlock(), d, e.getPlayer().getGameMode() != GameMode.CREATIVE);
    }

    private void explode(List<Block> blocks) {
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block b = it.next();
            Device d = at(b);
            if (d != null) { it.remove(); breakDevice(b, d, true); }
        }
    }

    @EventHandler(ignoreCancelled = true) public void onExplode(EntityExplodeEvent e) { explode(e.blockList()); }
    @EventHandler(ignoreCancelled = true) public void onBlockExplode(BlockExplodeEvent e) { explode(e.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }
    @EventHandler(ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }
    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }

    /** Torre nunca despeja flechas com redstone. */
    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }

    /** Gerador a carvao nao queima combustivel do jeito vanilla: quem consome e o plugin. */
    @EventHandler(ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }

    // ---------- interacao ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Device d = at(e.getClickedBlock());
        if (d == null) return;
        Player p = e.getPlayer();
        switch (d.type) {
            case "bancada" -> {
                e.setCancelled(true);
                if (!plugin.is(p, PClass.ENGENHEIRO)) { p.sendMessage(Component.text("So engenheiros entendem essa bancada.", NamedTextColor.RED)); return; }
                openBench(p);
            }
            case "bateria", "geotermico", "solar" -> {
                e.setCancelled(true);
                p.sendActionBar(Component.text(label(d.type) + ": " + (int) d.energy + "/" + (int) d.cap() + " energia" + rateText(d, e.getClickedBlock()), NamedTextColor.GOLD));
            }
            case "carvao" -> p.sendActionBar(Component.text("Gerador: " + (int) d.energy + "/" + (int) d.cap() + " energia, queimando " + d.burn + "s", NamedTextColor.GOLD));
            case "torre" -> {
                World w = e.getClickedBlock().getWorld();
                p.sendActionBar(Component.text("Torre (nivel " + plugin.level(d.owner) + "): " + (int) networkEnergy(d, w) + " energia na rede, " + arrows(e.getClickedBlock()) + " flechas", NamedTextColor.GOLD));
            }
        }
    }

    private String rateText(Device d, Block b) {
        double r = rate(d, b);
        return r > 0 ? String.format(Locale.ROOT, " (+%.1f/s)", r) : " (parado)";
    }

    // ---------- bancada (menu) ----------

    static final class BenchMenu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private void openBench(Player p) {
        BenchMenu h = new BenchMenu();
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Bancada de Engenharia"));
        h.inv = inv;
        int lvl = plugin.level(p.getUniqueId());
        int slot = 10;
        for (Recipe r : recipes) {
            ItemStack it = item(r.type());
            ItemMeta m = it.getItemMeta();
            List<Component> lore = new ArrayList<>(Objects.requireNonNullElse(m.lore(), List.of()));
            lore.add(Component.empty());
            lore.add(Component.text("Custo:", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            for (Map.Entry<Material, Integer> c : r.cost().entrySet()) {
                boolean has = countMat(p, c.getKey()) >= c.getValue();
                lore.add(Component.text(" " + c.getValue() + "x " + pretty(c.getKey()), has ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            }
            if (lvl < r.minLevel()) lore.add(Component.text("Requer nivel " + r.minLevel(), NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            else lore.add(Component.text("Clique para montar", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            m.lore(lore);
            it.setItemMeta(m);
            inv.setItem(slot, it);
            slot += 1;
        }
        p.openInventory(inv);
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private int countMat(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it != null && it.getType() == m && typeOf(it) == null) n += it.getAmount();
        return n;
    }

    private void takeMat(Player p, Material m, int amount) {
        ItemStack[] st = p.getInventory().getStorageContents();
        for (int i = 0; i < st.length && amount > 0; i++) {
            ItemStack it = st[i];
            if (it == null || it.getType() != m || typeOf(it) != null) continue;
            int take = Math.min(amount, it.getAmount());
            it.setAmount(it.getAmount() - take);
            amount -= take;
            if (it.getAmount() <= 0) st[i] = null;
        }
        p.getInventory().setStorageContents(st);
    }

    @EventHandler
    public void onBenchClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof BenchMenu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int idx = e.getRawSlot() - 10;
        if (idx < 0 || idx >= recipes.size()) return;
        Recipe r = recipes.get(idx);
        if (plugin.level(p.getUniqueId()) < r.minLevel()) { p.sendMessage(Component.text("Requer Engenheiro nivel " + r.minLevel() + ".", NamedTextColor.RED)); return; }
        for (Map.Entry<Material, Integer> c : r.cost().entrySet())
            if (countMat(p, c.getKey()) < c.getValue()) {
                p.sendMessage(Component.text("Falta " + pretty(c.getKey()) + ".", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return;
            }
        for (Map.Entry<Material, Integer> c : r.cost().entrySet()) takeMat(p, c.getKey(), c.getValue());
        for (ItemStack left : p.getInventory().addItem(item(r.type())).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1f);
        plugin.addXp(p.getUniqueId(), 3);
        openBench(p);
    }

    @EventHandler
    public void onBenchDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof BenchMenu) e.setCancelled(true); }

    // ---------- energia ----------

    private double rate(Device d, Block b) {
        World w = b.getWorld();
        switch (d.type) {
            case "geotermico" -> {
                int lava = 0;
                for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                    Block n = b.getRelative(f);
                    if (n.getType() == Material.LAVA && n.getBlockData() instanceof Levelled lv && lv.getLevel() == 0) lava++;
                }
                return lava * 0.5 * (w.getEnvironment() == World.Environment.NETHER ? 2 : 1);
            }
            case "solar" -> {
                if (w.getEnvironment() != World.Environment.NORMAL) return 0;
                long t = w.getTime();
                if (t > 12300 && t < 23850) return 0;
                if (b.getRelative(BlockFace.UP).getLightFromSky() < 15) return 0;
                return w.hasStorm() ? 0.5 : 1.5;
            }
            case "carvao" -> { return d.burn > 0 ? 4 : 0; }
            default -> { return 0; }
        }
    }

    private static int fuelSeconds(Material m) {
        return switch (m) {
            case COAL, CHARCOAL -> 80; case COAL_BLOCK -> 800; case BLAZE_ROD -> 120; case DRIED_KELP_BLOCK -> 200;
            default -> 0;
        };
    }

    private boolean loaded(Device d, World w) { return w != null && w.isChunkLoaded(d.x >> 4, d.z >> 4); }

    /** Confere se o bloco ainda e o dispositivo (pode ter sido trocado por fora). */
    private boolean valid(Device d, Block b) {
        Material want = switch (d.type) {
            case "bancada" -> Material.SMITHING_TABLE; case "torre" -> Material.DISPENSER; case "carvao" -> Material.FURNACE;
            case "geotermico" -> Material.MAGMA_BLOCK; case "solar" -> Material.DAYLIGHT_DETECTOR; default -> Material.LODESTONE;
        };
        return b.getType() == want;
    }

    private void tickPower() {
        if (lastPos.size() > 2000) lastPos.clear();
        List<Device> gens = new ArrayList<>(), batts = new ArrayList<>();
        for (Iterator<Device> it = devices.values().iterator(); it.hasNext(); ) {
            Device d = it.next();
            if (!d.isSource()) continue;
            World w = Bukkit.getWorld(d.world);
            if (!loaded(d, w)) continue;
            Block b = w.getBlockAt(d.x, d.y, d.z);
            if (!valid(d, b)) { it.remove(); continue; }
            if (d.type.equals("bateria")) { batts.add(d); continue; }
            if (d.type.equals("carvao")) {
                if (d.burn > 0) d.burn--;
                if (d.burn <= 0 && d.energy < d.cap() && b.getState() instanceof org.bukkit.block.Furnace f) {
                    ItemStack fuel = f.getInventory().getFuel();
                    int secs = fuel == null ? 0 : fuelSeconds(fuel.getType());
                    if (secs > 0) {
                        fuel.setAmount(fuel.getAmount() - 1);
                        f.getInventory().setFuel(fuel.getAmount() > 0 ? fuel : null);
                        d.burn = secs;
                    }
                }
                if (d.burn > 0) w.spawnParticle(Particle.SMOKE, d.x + 0.5, d.y + 1.1, d.z + 0.5, 2, 0.15, 0.05, 0.15, 0.01);
            }
            d.energy = Math.min(d.cap(), d.energy + rate(d, b));
            gens.add(d);
        }
        // geradores despejam o excedente nas baterias proximas
        for (Device g : gens) {
            if (g.energy <= 0) continue;
            for (Device bt : batts) {
                if (!bt.world.equals(g.world) || dist2(g, bt) > LINK_RANGE * LINK_RANGE) continue;
                double move = Math.min(g.energy, bt.cap() - bt.energy);
                if (move <= 0) continue;
                g.energy -= move; bt.energy += move;
                if (g.energy <= 0) break;
            }
        }
    }

    private static double dist2(Device a, Device b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private List<Device> sourcesFor(Device t) {
        List<Device> out = new ArrayList<>();
        for (Device d : devices.values())
            if (d.isSource() && d.world.equals(t.world) && dist2(d, t) <= LINK_RANGE * LINK_RANGE) out.add(d);
        out.sort(Comparator.comparingInt(d -> d.type.equals("bateria") ? 1 : 0)); // geradores primeiro
        return out;
    }

    private double networkEnergy(Device t, World w) {
        double s = 0;
        for (Device d : sourcesFor(t)) s += d.energy;
        return s;
    }

    private boolean drain(Device t, double amount) {
        List<Device> src = sourcesFor(t);
        double total = 0;
        for (Device d : src) total += d.energy;
        if (total < amount) return false;
        for (Device d : src) {
            double take = Math.min(d.energy, amount);
            d.energy -= take; amount -= take;
            if (amount <= 0) break;
        }
        return true;
    }

    // ---------- torres ----------

    private int arrows(Block b) {
        if (!(b.getState() instanceof Container c)) return 0;
        int n = 0;
        for (ItemStack it : c.getInventory().getContents())
            if (it != null && isArrow(it.getType())) n += it.getAmount();
        return n;
    }

    private static boolean isArrow(Material m) { return m == Material.ARROW || m == Material.TIPPED_ARROW || m == Material.SPECTRAL_ARROW; }

    private static boolean hostile(LivingEntity e) {
        if (e.isDead() || !e.isValid() || e instanceof Player || e instanceof ArmorStand) return false;
        if (!(e instanceof Enemy)) return false;
        // neutros so contam quando estao atras de um jogador
        if (e instanceof PigZombie || e instanceof Piglin || e instanceof Enderman || e instanceof PiglinBrute)
            return e instanceof Mob m && m.getTarget() instanceof Player;
        return true;
    }

    private void tickTurrets() {
        tick += 5;
        for (Iterator<Device> it = devices.values().iterator(); it.hasNext(); ) {
            Device t = it.next();
            if (!t.type.equals("torre") || t.nextShot > tick) continue;
            World w = Bukkit.getWorld(t.world);
            if (!loaded(t, w)) continue;
            Block b = w.getBlockAt(t.x, t.y, t.z);
            if (!valid(t, b)) { it.remove(); continue; }
            int lvl = plugin.level(t.owner);
            int range = RANGE[lvl - 1];
            Location c = t.center(w);

            LivingEntity best = null; Vector bestAim = null; double bestD = Double.MAX_VALUE;
            for (Entity en : w.getNearbyEntities(c, range, range, range)) {
                if (!(en instanceof LivingEntity le) || !hostile(le)) continue;
                double dd = le.getLocation().distanceSquared(c);
                if (dd > range * range || dd >= bestD) continue;
                Vector aim = aimPoint(le, c);
                Vector dir = aim.clone().subtract(c.toVector());
                if (dir.lengthSquared() < 0.01) continue;
                Location muzzle = c.clone().add(dir.clone().normalize().multiply(0.8));
                Vector toEye = le.getEyeLocation().toVector().subtract(muzzle.toVector());
                RayTraceResult hit = w.rayTraceBlocks(muzzle, toEye.clone().normalize(), toEye.length(), FluidCollisionMode.NEVER, true);
                if (hit != null) continue;
                best = le; bestAim = aim; bestD = dd;
            }
            if (best == null) continue;
            if (!(b.getState() instanceof Container cont)) continue;
            ItemStack ammo = null; int slot = -1;
            ItemStack[] inv = cont.getInventory().getContents();
            for (int i = 0; i < inv.length; i++) if (inv[i] != null && isArrow(inv[i].getType())) { ammo = inv[i]; slot = i; break; }
            if (ammo == null) { t.nextShot = tick + 40; continue; }
            if (!drain(t, SHOT_COST)) { t.nextShot = tick + 40; w.spawnParticle(Particle.SMOKE, c.clone().add(0, 0.6, 0), 3, 0.1, 0.1, 0.1, 0.01); continue; }

            ItemStack shot = ammo.clone(); shot.setAmount(1);
            ammo.setAmount(ammo.getAmount() - 1);
            cont.getInventory().setItem(slot, ammo.getAmount() > 0 ? ammo : null);

            int shots = lvl >= 5 ? 2 : 1;
            for (int s = 0; s < shots; s++) fire(w, c, bestAim, shot, t, lvl, s == 0 ? 0.4f : 2.5f);
            w.playSound(c, Sound.BLOCK_DISPENSER_LAUNCH, 0.7f, 1.4f);
            t.nextShot = tick + COOLDOWN[lvl - 1];
        }
    }

    /** Mira na frente do alvo (velocidade medida entre scans) e compensa a queda da flecha. */
    private Vector aimPoint(LivingEntity le, Location from) {
        Location l = le.getLocation();
        Vector target = l.toVector().add(new Vector(0, le.getHeight() * 0.55, 0));
        double[] prev = lastPos.get(le.getUniqueId());
        Vector vel = new Vector();
        if (prev != null && tick > prev[3] && tick - prev[3] <= 20) {
            double dt = tick - prev[3];
            vel = new Vector((l.getX() - prev[0]) / dt, 0, (l.getZ() - prev[2]) / dt);
            if (vel.lengthSquared() > 1) vel = new Vector();
        }
        lastPos.put(le.getUniqueId(), new double[]{l.getX(), l.getY(), l.getZ(), tick});
        double dist = target.distance(from.toVector());
        double ticks = dist / 2.6;
        target.add(vel.multiply(ticks));
        target.setY(target.getY() + 0.025 * ticks * ticks);
        return target;
    }

    private void fire(World w, Location c, Vector aim, ItemStack ammo, Device t, int lvl, float spread) {
        Vector dir = aim.clone().subtract(c.toVector()).normalize();
        Location muzzle = c.clone().add(dir.clone().multiply(0.8));
        AbstractArrow a;
        if (ammo.getType() == Material.SPECTRAL_ARROW) a = w.spawnArrow(muzzle, dir, 2.6f, spread, SpectralArrow.class);
        else {
            Arrow ar = w.spawnArrow(muzzle, dir, 2.6f, spread, Arrow.class);
            if (ammo.getType() == Material.TIPPED_ARROW && ammo.getItemMeta() instanceof PotionMeta pm && pm.getBasePotionType() != null)
                ar.setBasePotionType(pm.getBasePotionType());
            a = ar;
        }
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        a.setDamage(2.0 + 0.25 * (lvl - 1));
        if (lvl >= 3 && Math.random() < 0.3) a.setFireTicks(100);
        a.getPersistentDataContainer().set(arrowKey, PersistentDataType.STRING, t.owner.toString());
    }

    /** Flecha da torre nao fere jogador, pet, aldeao nem animal. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onArrowHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow a) || !a.getPersistentDataContainer().has(arrowKey, PersistentDataType.STRING)) return;
        if (e.getEntity() instanceof LivingEntity le && le instanceof Enemy) return;
        e.setCancelled(true);
        a.remove();
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        lastPos.remove(e.getEntity().getUniqueId());
        if (!(e.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent ed)) return;
        if (!(ed.getDamager() instanceof AbstractArrow a)) return;
        String owner = a.getPersistentDataContainer().get(arrowKey, PersistentDataType.STRING);
        if (owner == null) return;
        try { plugin.addXp(UUID.fromString(owner), 1); } catch (IllegalArgumentException ignored) { }
    }

    // ---------- status / persistencia ----------

    String status(Player p) {
        String w = p.getWorld().getName();
        int lvl = plugin.level(p.getUniqueId());
        return levelSummary(lvl) + ". Torres: " + count(p.getUniqueId(), w, true) + "/" + MAX_TURRETS[lvl - 1]
                + ", geradores/baterias: " + count(p.getUniqueId(), w, false) + "/" + MAX_POWER_DEVICES;
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("devices");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                ConfigurationSection c = s.getConfigurationSection(k);
                Device d = new Device(c.getString("type"), c.getString("world"), c.getInt("x"), c.getInt("y"), c.getInt("z"),
                        UUID.fromString(c.getString("owner")));
                d.energy = c.getDouble("energy");
                d.burn = c.getInt("burn");
                devices.put(d.key(), d);
            } catch (Exception ex) { plugin.getLogger().warning("device " + k + ": " + ex.getMessage()); }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Device d : devices.values()) {
            String k = "devices.d" + (i++);
            y.set(k + ".type", d.type); y.set(k + ".world", d.world);
            y.set(k + ".x", d.x); y.set(k + ".y", d.y); y.set(k + ".z", d.z);
            y.set(k + ".owner", d.owner.toString());
            y.set(k + ".energy", Math.round(d.energy * 10) / 10.0);
            y.set(k + ".burn", d.burn);
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("devices.yml: " + ex.getMessage()); }
    }
}
