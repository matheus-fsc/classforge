package dev.math.bclasses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Plantacao: XP colhendo plantacao madura, colheita extra por nivel e os Plantadores, golems de
 * cobre presos a um bau que pegam as sementes dele e plantam na terra arada em volta.
 * A colheita continua manual (ou por redstone com agua e funil).
 */
final class Farmer implements Listener {

    // por nivel 1..5
    static final int[] RADIUS = {4, 5, 6, 7, 8};
    static final int[] MAX_GOLEMS = {1, 2, 2, 3, 4};
    /** Segundos de descanso do golem entre uma semente e outra. */
    static final int[] REST = {4, 3, 3, 2, 1};
    /** Chance (%) de a colheita manual render 1 a mais. */
    static final int[] BONUS = {5, 10, 15, 20, 25};
    static final double XP_HARVEST = 0.25, XP_PLANT = 0.1;
    /** Depois disso sem conseguir chegar, o golem e levado ate o canteiro. */
    static final int WALK_TIMEOUT = 12;

    static final class Planter {
        final UUID owner; final String world; final int x, y, z;
        int[] target; Material seed; int wait, rest;
        Planter(UUID owner, String world, int x, int y, int z) { this.owner = owner; this.world = world; this.x = x; this.y = y; this.z = z; }
    }

    static final class Panel implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final BabylonClasses plugin;
    private final NamespacedKey itemKey, golemKey, recipeKey;
    private final Map<UUID, Planter> planters = new HashMap<>();
    private final Map<UUID, Double> frac = new HashMap<>();
    private final Random rnd = new Random();
    private final File file;

    Farmer(BabylonClasses plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "farm-item");
        this.golemKey = new NamespacedKey(plugin, "planter-owner");
        this.recipeKey = new NamespacedKey(plugin, "plantador");
        this.file = new File(plugin.getDataFolder(), "farm.yml");
    }

    void enable() {
        load();
        ShapedRecipe r = new ShapedRecipe(recipeKey, planterItem());
        r.shape(" P ", "SCS", " H ");
        r.setIngredient('P', Material.CARVED_PUMPKIN);
        r.setIngredient('S', Material.WHEAT_SEEDS);
        r.setIngredient('C', Material.COPPER_BLOCK);
        r.setIngredient('H', Material.IRON_HOE);
        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(r);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 1200L, 1200L);
    }

    void discover(Player p) { p.discoverRecipe(recipeKey); }

    int level(UUID id) { return plugin.farmLevel(id); }

    String levelSummary(int lvl) {
        return "Nivel " + lvl + ": " + MAX_GOLEMS[lvl - 1] + " plantador(es), raio " + RADIUS[lvl - 1] + ", +" + BONUS[lvl - 1] + "% de colheita";
    }

    private void xp(UUID id, double amount) {
        double v = frac.getOrDefault(id, 0.0) + amount;
        int whole = (int) v;
        frac.put(id, v - whole);
        if (whole > 0) plugin.addFarm(id, whole);
    }

    /** Nova vida: os plantadores ficam para tras. */
    void resetLife(UUID id) {
        frac.remove(id);
        for (Iterator<Map.Entry<UUID, Planter>> it = planters.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Planter> e = it.next();
            if (!e.getValue().owner.equals(id)) continue;
            Entity en = Bukkit.getEntity(e.getKey());
            if (en != null) en.remove();
            it.remove();
        }
    }

    // ---------- item ----------

    ItemStack planterItem() {
        ItemStack it = new ItemStack(Material.CARVED_PUMPKIN);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Plantador de Cobre", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : List.of("Clique num bau ou barril com sementes:", "nasce um golem de cobre que planta", "na terra arada em volta dele.", "Agachado + clique no golem: recolhe."))
            l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        l.add(Component.text("Plantacao", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "plantador");
        it.setItemMeta(m);
        return it;
    }

    private boolean isPlanterItem(ItemStack it) {
        return it != null && it.hasItemMeta() && "plantador".equals(it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING));
    }

    /** O item nao e uma abobora de verdade: nao coloca nem veste. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (isPlanterItem(e.getItemInHand())) e.setCancelled(true); }

    private long countOf(UUID owner) { return planters.values().stream().filter(p -> p.owner.equals(owner)).count(); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isPlanterItem(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (b == null || !(b.getState() instanceof Container)) {
            p.sendActionBar(Component.text("Clique num bau ou barril com sementes.", NamedTextColor.YELLOW));
            return;
        }
        int lvl = level(p.getUniqueId());
        if (countOf(p.getUniqueId()) >= MAX_GOLEMS[lvl - 1]) {
            p.sendMessage(Component.text("Limite de " + MAX_GOLEMS[lvl - 1] + " plantador(es) no nivel " + lvl + " de Plantacao.", NamedTextColor.RED));
            return;
        }
        for (Planter o : planters.values())
            if (o.world.equals(b.getWorld().getName()) && o.x == b.getX() && o.y == b.getY() && o.z == b.getZ()) {
                p.sendMessage(Component.text("Esse bau ja tem um plantador.", NamedTextColor.RED));
                return;
            }
        Block spot = b.getRelative(e.getBlockFace());
        if (!spot.isPassable()) spot = b.getRelative(BlockFace.UP);
        Location at = spot.getLocation().add(0.5, 0, 0.5);
        UUID owner = p.getUniqueId();
        CopperGolem g = b.getWorld().spawn(at, CopperGolem.class, cg -> {
            cg.setOxidizing(CopperGolem.Oxidizing.waxed()); // nunca vira estatua
            cg.setPersistent(true);
            cg.setRemoveWhenFarAway(false);
            cg.customName(Component.text("Plantador de " + p.getName(), NamedTextColor.GREEN));
            cg.getPersistentDataContainer().set(golemKey, PersistentDataType.STRING, owner.toString());
        });
        planters.put(g.getUniqueId(), new Planter(owner, b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        ItemStack hand = e.getItem();
        hand.setAmount(hand.getAmount() - 1);
        b.getWorld().playSound(at, Sound.BLOCK_COPPER_PLACE, 1f, 1.2f);
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 0.6, 0), 12, 0.3, 0.4, 0.3, 0);
        p.sendMessage(Component.text("🌱 Plantador ligado a este bau: planta num raio de " + RADIUS[lvl - 1] + " blocos. Guarde sementes no bau.", NamedTextColor.GREEN));
        save();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGolemClick(PlayerInteractEntityEvent e) {
        Planter pl = planters.get(e.getRightClicked().getUniqueId());
        if (pl == null) return;
        e.setCancelled(true); // sem tosquiar, raspar ou encerar o plantador
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (p.isSneaking()) {
            if (!pl.owner.equals(p.getUniqueId()) && !p.hasPermission("bclasses.admin")) {
                p.sendActionBar(Component.text("Esse plantador nao e seu.", NamedTextColor.RED));
                return;
            }
            planters.remove(e.getRightClicked().getUniqueId());
            e.getRightClicked().remove();
            for (ItemStack left : p.getInventory().addItem(planterItem()).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            p.playSound(p.getLocation(), Sound.BLOCK_COPPER_BREAK, 0.8f, 1.2f);
            save();
            return;
        }
        int lvl = level(pl.owner);
        p.sendActionBar(Component.text("Plantador: bau em " + pl.x + " " + pl.y + " " + pl.z + ", raio " + RADIUS[lvl - 1]
                + (pl.target != null ? ", indo plantar" : ", esperando") + ". Agachado + clique recolhe.", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onGolemDeath(EntityDeathEvent e) {
        if (planters.remove(e.getEntity().getUniqueId()) == null) return;
        e.getDrops().clear();
        e.getDrops().add(planterItem()); // morreu: o item volta, e so religar
        save();
    }

    // ---------- trabalho (1x por segundo) ----------

    private static boolean soilFor(Material seed, Material soil) {
        return seed == Material.NETHER_WART ? soil == Material.SOUL_SAND : soil == Material.FARMLAND;
    }

    private void tick() {
        for (Map.Entry<UUID, Planter> en : planters.entrySet()) {
            Planter pl = en.getValue();
            World w = Bukkit.getWorld(pl.world);
            if (w == null || !w.isChunkLoaded(pl.x >> 4, pl.z >> 4)) continue;
            if (!(Bukkit.getEntity(en.getKey()) instanceof CopperGolem g) || g.isDead()) continue;
            Location home = new Location(w, pl.x + 0.5, pl.y, pl.z + 0.5);
            int lvl = level(pl.owner), r = RADIUS[lvl - 1];
            if (g.getWorld() != w || g.getLocation().distanceSquared(home) > (r + 6) * (r + 6)) { g.teleport(home.clone().add(0, 1, 0)); pl.target = null; }
            if (pl.rest > 0) { pl.rest--; continue; }
            Block chest = w.getBlockAt(pl.x, pl.y, pl.z);
            if (!(chest.getState() instanceof Container c)) { idle(g, pl, 5); continue; }
            Inventory inv = c.getInventory();
            if (pl.target == null && !pickTarget(g, pl, w, inv, r)) { idle(g, pl, 5); continue; }
            Block soil = w.getBlockAt(pl.target[0], pl.target[1], pl.target[2]), top = soil.getRelative(BlockFace.UP);
            if (!soilFor(pl.seed, soil.getType()) || !top.getType().isAir()) { pl.target = null; continue; }
            Location spot = top.getLocation().add(0.5, 0, 0.5);
            if (g.getLocation().distanceSquared(spot) > 2.5 * 2.5) {
                if (++pl.wait < WALK_TIMEOUT) { g.getPathfinder().moveTo(spot, 1.1); continue; }
                g.teleport(spot); // caminho travado: nao fica preso para sempre
            }
            int slot = seedSlot(inv, pl.seed);
            if (slot < 0) { pl.target = null; continue; }
            ItemStack seed = inv.getItem(slot);
            seed.setAmount(seed.getAmount() - 1);
            inv.setItem(slot, seed.getAmount() > 0 ? seed : null);
            top.setType(Engineer.cropFor(pl.seed));
            w.playSound(spot, Sound.ITEM_CROP_PLANT, 0.7f, 1f);
            w.spawnParticle(Particle.HAPPY_VILLAGER, spot.clone().add(0, 0.3, 0), 3, 0.2, 0.1, 0.2, 0);
            g.swingMainHand();
            xp(pl.owner, XP_PLANT);
            pl.target = null;
            pl.rest = REST[lvl - 1] - 1;
        }
    }

    private void idle(CopperGolem g, Planter pl, int seconds) {
        pl.target = null; pl.rest = seconds;
        if (g.getEquipment() != null) g.getEquipment().setItemInMainHand(null);
    }

    private static int seedSlot(Inventory inv, Material seed) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == seed && !it.hasItemMeta()) return i;
        }
        return -1;
    }

    /** Escolhe o canteiro vazio mais perto do golem para uma semente que exista no bau. */
    private boolean pickTarget(CopperGolem g, Planter pl, World w, Inventory inv, int r) {
        boolean normal = false, wart = false;
        Material normalSeed = null;
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.hasItemMeta() || Engineer.cropFor(it.getType()) == null) continue;
            if (it.getType() == Material.NETHER_WART) wart = true;
            else if (!normal) { normal = true; normalSeed = it.getType(); }
        }
        if (!normal && !wart) return false;
        Location at = g.getLocation();
        int[] best = null; double bestD = Double.MAX_VALUE; Material bestSeed = null;
        for (int dy = -2; dy <= 1; dy++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    int x = pl.x + dx, y = pl.y + dy, z = pl.z + dz;
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Material soil = w.getBlockAt(x, y, z).getType();
                    Material seed = soil == Material.FARMLAND && normal ? normalSeed : soil == Material.SOUL_SAND && wart ? Material.NETHER_WART : null;
                    if (seed == null || !w.getBlockAt(x, y + 1, z).getType().isAir()) continue;
                    double d = at.distanceSquared(new Location(w, x + 0.5, y + 1, z + 0.5));
                    if (d < bestD) { bestD = d; best = new int[]{x, y, z}; bestSeed = seed; }
                }
        if (best == null) return false;
        pl.target = best; pl.seed = bestSeed; pl.wait = 0;
        if (g.getEquipment() != null) g.getEquipment().setItemInMainHand(new ItemStack(bestSeed));
        return true;
    }

    // ---------- colheita manual ----------

    private static Material produce(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT; case CARROTS -> Material.CARROT; case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT; case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHarvest(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material prod = produce(b.getType());
        if (prod == null || !(b.getBlockData() instanceof Ageable a) || a.getAge() < a.getMaximumAge()) return;
        Player p = e.getPlayer();
        if (!BabylonClasses.isRunWorld(p.getWorld())) return;
        int lvl = level(p.getUniqueId());
        xp(p.getUniqueId(), XP_HARVEST);
        if (rnd.nextInt(100) < BONUS[lvl - 1]) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.3, 0.5), new ItemStack(prod));
            b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.4, 0.5), 4, 0.2, 0.1, 0.2, 0);
        }
    }

    // ---------- painel ----------

    private static ItemStack icon(Material mat, String name, NamedTextColor color, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(name, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, s.startsWith("+") ? NamedTextColor.GREEN : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    void openPanel(Player p) {
        Panel h = new Panel();
        Inventory inv = Bukkit.createInventory(h, 45, Component.text("Plantacao"));
        h.inv = inv;
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < 45; i++) inv.setItem(i, filler);
        UUID id = p.getUniqueId();
        int lvl = level(id), xp = plugin.data(id).farm;
        String next = lvl >= 5 ? "Nivel maximo" : "XP " + xp + "/" + BabylonClasses.FARM_XP[lvl] + " para o nivel " + (lvl + 1);
        inv.setItem(4, icon(Material.GOLDEN_HOE, "Plantacao - nivel " + lvl, NamedTextColor.GREEN, List.of(
                next, "", "Plantadores: " + countOf(id) + "/" + MAX_GOLEMS[lvl - 1] + " (raio " + RADIUS[lvl - 1] + ")",
                "Colheita extra: " + BONUS[lvl - 1] + "%", "", "O nivel zera quando a vida acaba.")));
        inv.setItem(6, icon(Material.EXPERIENCE_BOTTLE, "Como ganhar XP", NamedTextColor.GREEN, List.of(
                "+ Colher plantacao madura (trigo, cenoura,", "  batata, beterraba, fungo do Nether):", "  +1 a cada 4",
                "+ Cada semente que um plantador seu", "  planta: +1 a cada 10", "", "So conta no mundo da run.")));
        inv.setItem(11, icon(Material.CARVED_PUMPKIN, "Plantador de Cobre", NamedTextColor.GOLD, List.of(
                "Receita (mesa de trabalho):", "  _ A _", "  S C S", "  _ E _",
                "A = abobora esculpida, C = bloco de cobre,", "S = sementes de trigo, E = enxada de ferro", "",
                "Clique com ele num bau ou barril:", "nasce um golem de cobre que pega as", "sementes do bau e planta na terra",
                "arada em volta (fungo do Nether na", "areia das almas).", "Agachado + clique no golem: recolhe.", "Se morrer, solta o item de volta.")));
        inv.setItem(13, icon(Material.WATER_BUCKET, "Colheita", NamedTextColor.AQUA, List.of(
                "O plantador so planta. Colher e com", "voce (da XP e colheita extra) ou com", "redstone: agua derrubando a plantacao", "para um funil.")));
        inv.setItem(15, icon(Material.BARREL, "Plantadeira eletrica", NamedTextColor.YELLOW, List.of(
                "A versao eletrica fica na Engenharia", "(nivel 3): planta sem golem, mas", "gasta energia da rede.")));
        Material[] mats = {Material.WOODEN_HOE, Material.STONE_HOE, Material.IRON_HOE, Material.DIAMOND_HOE, Material.NETHERITE_HOE};
        for (int i = 0; i < 5; i++) {
            int L = i + 1;
            boolean got = lvl >= L;
            List<String> lore = new ArrayList<>();
            lore.add(L == 1 ? "Inicial" : "Precisa de " + BabylonClasses.FARM_XP[i] + " XP");
            lore.add("");
            lore.add("+ " + MAX_GOLEMS[i] + " plantador(es)");
            lore.add("+ Raio " + RADIUS[i] + " blocos em volta do bau");
            lore.add("+ 1 semente a cada " + REST[i] + "s por plantador");
            lore.add("+ Colheita extra: " + BONUS[i] + "%");
            lore.add("");
            lore.add(got ? "Liberado" : "Bloqueado");
            inv.setItem(29 + i, icon(got ? mats[i] : Material.BLACK_STAINED_GLASS_PANE, "Nivel " + L, got ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY, lore));
        }
        inv.setItem(36, icon(Material.ARROW, "Voltar", NamedTextColor.WHITE, List.of("Volta para as habilidades.")));
        p.openInventory(inv);
    }

    @EventHandler
    public void onPanelClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Panel)) return;
        e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p && e.getClickedInventory() == e.getInventory() && e.getRawSlot() == 36) plugin.openHub(p);
    }

    @EventHandler
    public void onPanelDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Panel) e.setCancelled(true); }

    // ---------- persistencia ----------

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("planters");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                ConfigurationSection c = s.getConfigurationSection(k);
                planters.put(UUID.fromString(k), new Planter(UUID.fromString(c.getString("owner")), c.getString("world"), c.getInt("x"), c.getInt("y"), c.getInt("z")));
            } catch (Exception ex) { plugin.getLogger().warning("plantador " + k + ": " + ex.getMessage()); }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Planter> e : planters.entrySet()) {
            String k = "planters." + e.getKey();
            Planter p = e.getValue();
            y.set(k + ".owner", p.owner.toString()); y.set(k + ".world", p.world);
            y.set(k + ".x", p.x); y.set(k + ".y", p.y); y.set(k + ".z", p.z);
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("farm.yml: " + ex.getMessage()); }
    }
}
