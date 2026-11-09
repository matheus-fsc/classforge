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
    static final double FIREBALL_COST = 8;
    static final double LINK_RANGE = 12;

    static final class Device {
        final String type; final String world; final int x, y, z; final UUID owner;
        double energy, lastRate; int burn; long nextShot;
        TextDisplay tag;
        boolean tagOn = true; int filter = 0b111111; int prio; int ammoPref;
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
    private EnergyFx fx;
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
        fx = new EnergyFx(plugin);
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
            case "torre" -> { mat = Material.DISPENSER; name = "Torre Sentinela"; lore = List.of("Atira flechas ou bolas de fogo guardadas nela", "em mobs hostis. Flecha gasta " + (int) SHOT_COST + " de energia,", "bola de fogo gasta " + (int) FIREBALL_COST + ".", "Precisa de gerador ou bateria a ate " + (int) LINK_RANGE + " blocos."); }
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
        if (type.equals("torre")) p.sendMessage(Component.text("Coloque flechas ou bolas de fogo dentro dela e um gerador ou bateria a ate " + (int) LINK_RANGE + " blocos.", NamedTextColor.GRAY));
    }

    /** Remove o dispositivo: derruba o conteudo, o item customizado e vira ar. */
    private void breakDevice(Block b, Device d, boolean dropItem) {
        devices.remove(d.key());
        removeTag(d);
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
                showRange(p, d, LINK_RANGE, Color.fromRGB(255, 200, 40));
            }
            case "carvao" -> {
                p.sendActionBar(Component.text("Gerador: " + (int) d.energy + "/" + (int) d.cap() + " energia, queimando " + d.burn + "s", NamedTextColor.GOLD));
                showRange(p, d, LINK_RANGE, Color.fromRGB(255, 200, 40));
            }
            case "torre" -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (p.isSneaking() && hand.getType().isBlock() && hand.getType() != Material.AIR) return; // agachado com bloco: deixa colocar
                e.setCancelled(true);
                openTurret(p, d);
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
        int slot = 10;
        for (Recipe r : recipes) inv.setItem(slot++, recipeIcon(p, r, "Clique para montar"));
        p.openInventory(inv);
    }

    private ItemStack recipeIcon(Player p, Recipe r, String action) {
        int lvl = plugin.level(p.getUniqueId());
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
        else lore.add(Component.text(action, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    // ---------- painel da classe (/classe) ----------

    static final class ClassPanel implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static ItemStack icon(Material mat, String name, NamedTextColor color, String... lore) {
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
        ClassPanel h = new ClassPanel();
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Engenheiro"));
        h.inv = inv;
        UUID id = p.getUniqueId();
        int lvl = plugin.level(id), xp = plugin.data(id).xp;
        String w = p.getWorld().getName();
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        String next = lvl >= 5 ? "Nivel maximo" : "XP " + xp + "/" + BabylonClasses.LEVEL_XP[lvl] + " para o nivel " + (lvl + 1);
        inv.setItem(4, icon(Material.DISPENSER, "Engenheiro - nivel " + lvl, NamedTextColor.GOLD,
                next, "", "Ganha XP montando na bancada (+3)", "e com abates das suas torres (+1).", "", "A classe zera quando voce morre."));

        inv.setItem(10, icon(Material.SMITHING_TABLE, "Bancada de Engenharia", NamedTextColor.GOLD,
                "Receita na mesa de trabalho:", "  _ R _", "  I M I", "  I I I",
                "R = redstone, I = ferro, M = mesa de trabalho", "", "Clique para ver o craft."));
        int slot = 12;
        for (Recipe r : recipes) inv.setItem(slot++, recipeIcon(p, r, "Clique para ver o craft"));

        Material[] mats = {Material.COPPER_INGOT, Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.NETHERITE_INGOT};
        for (int i = 0; i < 5; i++) {
            int L = i + 1;
            boolean got = lvl >= L;
            List<String> lore = new ArrayList<>();
            lore.add(L == 1 ? "Inicial" : "Precisa de " + BabylonClasses.LEVEL_XP[i] + " XP");
            lore.add("");
            lore.add("+ Ate " + MAX_TURRETS[i] + " torres");
            lore.add("+ Alcance " + RANGE[i] + " blocos");
            lore.add("+ " + String.format(Locale.ROOT, "%.1f", 20.0 / COOLDOWN[i]) + " tiros por segundo");
            if (L == 2) lore.add("+ Libera Geotermico e Bateria");
            if (L == 3) lore.add("+ Flecha de fogo (30%)");
            if (L == 5) lore.add("+ Tiro duplo");
            lore.add("");
            lore.add(got ? "Liberado" : "Bloqueado");
            inv.setItem(29 + i, icon(got ? mats[i] : Material.BLACK_STAINED_GLASS_PANE, "Nivel " + L, got ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY, lore.toArray(new String[0])));
        }

        inv.setItem(46, icon(Material.REDSTONE, "Sua base", NamedTextColor.RED,
                "Torres: " + count(id, w, true) + "/" + MAX_TURRETS[lvl - 1],
                "Geradores e baterias: " + count(id, w, false) + "/" + MAX_POWER_DEVICES,
                "Energia guardada: " + (int) storedEnergy(id, w)));
        inv.setItem(48, icon(Material.LIGHTNING_ROD, "Como funciona a energia", NamedTextColor.YELLOW,
                "Sem fios: geradores e baterias a ate " + (int) LINK_RANGE,
                "blocos alimentam a torre.", "Cada tiro gasta " + (int) SHOT_COST + " de energia.",
                "Geradores enchem as baterias proximas.", "",
                "Carvao: 4/s enquanto queima", "Geotermico: 0.5/s por lava (x2 no Nether)", "Solar: 1.5/s de dia, 0 a noite"));
        inv.setItem(50, icon(Material.ARROW, "Municao", NamedTextColor.WHITE,
                "Flechas ou bolas de fogo dentro da torre,", "ou abasteca com funil.", "Bola de fogo gasta " + (int) FIREBALL_COST + " de energia.",
                "Aceita flecha com efeito e espectral.", "Nunca acerta jogador, pet ou animal."));
        boolean fxOn = plugin.fxEnabled(p);
        inv.setItem(8, icon(fxOn ? Material.GLOWSTONE_DUST : Material.GUNPOWDER, "Particulas de energia: " + (fxOn ? "ligadas" : "desligadas"),
                fxOn ? NamedTextColor.GREEN : NamedTextColor.GRAY, "Mostra a energia correndo pelos", "cabos ate as maquinas.", "", "Clique para " + (fxOn ? "desligar" : "ligar") + "."));
        inv.setItem(52, icon(Material.ENDER_EYE, "Trocar de classe", NamedTextColor.LIGHT_PURPLE,
                "Custa " + BabylonClasses.CHANGE_COST_LEVELS + " niveis de XP.", "Zera o nivel da classe."));
        p.openInventory(inv);
    }

    private double storedEnergy(UUID owner, String world) {
        String base = BabylonClasses.baseOf(world);
        double s = 0;
        for (Device d : devices.values()) if (d.owner.equals(owner) && d.isSource() && BabylonClasses.baseOf(d.world).equals(base)) s += d.energy;
        return s;
    }

    @EventHandler
    public void onPanelClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ClassPanel)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int raw = e.getRawSlot();
        if (raw == 52) plugin.openMenu(p);
        else if (raw == 8) { plugin.toggleFx(p); openPanel(p); }
        else if (raw == 10) openRecipe(p, "bancada");
        else if (raw >= 12 && raw < 12 + recipes.size()) openRecipe(p, recipes.get(raw - 12).type());
    }

    // ---------- tela de craft de um item ----------

    static final class RecipeView implements InventoryHolder {
        Inventory inv; String type;
        @Override public Inventory getInventory() { return inv; }
    }

    private Recipe recipe(String type) {
        for (Recipe r : recipes) if (r.type().equals(type)) return r;
        return null;
    }

    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};

    private ItemStack ingredient(Player p, Material m, int need) {
        int has = countMat(p, m);
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(need, m.getMaxStackSize())));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(need + "x " + pretty(m), has >= need ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Voce tem " + has, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        return it;
    }

    private boolean nearBench(Player p) {
        for (Device d : devices.values())
            if (d.type.equals("bancada") && d.world.equals(p.getWorld().getName())
                    && p.getLocation().distanceSquared(new Location(p.getWorld(), d.x + 0.5, d.y + 0.5, d.z + 0.5)) <= 36) return true;
        return false;
    }

    void openRecipe(Player p, String type) {
        RecipeView h = new RecipeView();
        h.type = type;
        Inventory inv = Bukkit.createInventory(h, 45, Component.text("Craft: " + label(type)));
        h.inv = inv;
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 0; i < 45; i++) inv.setItem(i, filler);
        for (int g : GRID) inv.setItem(g, null);

        if (type.equals("bancada")) {
            // mesma forma da receita na mesa de trabalho
            Material[] shape = {null, Material.REDSTONE, null,
                    Material.IRON_INGOT, Material.CRAFTING_TABLE, Material.IRON_INGOT,
                    Material.IRON_INGOT, Material.IRON_INGOT, Material.IRON_INGOT};
            for (int i = 0; i < 9; i++) if (shape[i] != null) inv.setItem(GRID[i], ingredient(p, shape[i], 1));
            inv.setItem(23, icon(Material.CRAFTING_TABLE, "Mesa de trabalho", NamedTextColor.YELLOW,
                    "Monte nesta forma numa", "mesa de trabalho comum.", "So engenheiros conseguem."));
        } else {
            Recipe r = recipe(type);
            int i = 0;
            for (Map.Entry<Material, Integer> c : r.cost().entrySet()) inv.setItem(GRID[i++], ingredient(p, c.getKey(), c.getValue()));
            boolean lvlOk = plugin.level(p.getUniqueId()) >= r.minLevel();
            boolean near = nearBench(p);
            List<String> lore = new ArrayList<>();
            if (!lvlOk) lore.add("Requer Engenheiro nivel " + r.minLevel());
            else if (!near) { lore.add("Chegue perto de uma"); lore.add("Bancada de Engenharia (6 blocos)."); }
            else lore.add("Clique para montar");
            inv.setItem(23, icon(lvlOk && near ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                    "Montar", lvlOk && near ? NamedTextColor.GREEN : NamedTextColor.RED, lore.toArray(new String[0])));
        }
        inv.setItem(25, item(type));
        inv.setItem(36, icon(Material.ARROW, "Voltar", NamedTextColor.WHITE, "Volta para o painel da classe."));
        p.openInventory(inv);
    }

    @EventHandler
    public void onRecipeClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RecipeView h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (e.getRawSlot() == 36) { openPanel(p); return; }
        if (e.getRawSlot() != 23 || h.type.equals("bancada")) return;
        if (!nearBench(p)) { p.sendMessage(Component.text("Chegue perto de uma Bancada de Engenharia.", NamedTextColor.RED)); return; }
        if (craft(p, recipe(h.type))) openRecipe(p, h.type);
    }

    @EventHandler
    public void onRecipeDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof RecipeView) e.setCancelled(true); }

    @EventHandler
    public void onPanelDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof ClassPanel) e.setCancelled(true); }

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
        if (craft(p, recipes.get(idx))) openBench(p);
    }

    private boolean craft(Player p, Recipe r) {
        if (plugin.level(p.getUniqueId()) < r.minLevel()) { p.sendMessage(Component.text("Requer Engenheiro nivel " + r.minLevel() + ".", NamedTextColor.RED)); return false; }
        for (Map.Entry<Material, Integer> c : r.cost().entrySet())
            if (countMat(p, c.getKey()) < c.getValue()) {
                p.sendMessage(Component.text("Falta " + pretty(c.getKey()) + ".", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return false;
            }
        for (Map.Entry<Material, Integer> c : r.cost().entrySet()) takeMat(p, c.getKey(), c.getValue());
        for (ItemStack left : p.getInventory().addItem(item(r.type())).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1f);
        plugin.addXp(p.getUniqueId(), 3);
        return true;
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
            if (!valid(d, b)) { it.remove(); removeTag(d); continue; }
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
                if (d.burn > 0 && b.getState() instanceof org.bukkit.block.Furnace fresh && fresh.getBurnTime() < 30) {
                    // mantem a fornalha acesa (chama e luz vanilla) enquanto o gerador queima
                    fresh.setBurnTime((short) 40);
                    fresh.update(true, false);
                }
            }
            d.lastRate = rate(d, b);
            d.energy = Math.min(d.cap(), d.energy + d.lastRate);
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
                World gw = Bukkit.getWorld(g.world);
                if (gw != null) fx.pulse(gw, g.x, g.y, g.z, bt.x, bt.y, bt.z, Color.fromRGB(255, 210, 40));
                if (g.energy <= 0) break;
            }
        }
        updateTags();
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
        World w = Bukkit.getWorld(t.world);
        for (Device d : src) {
            double take = Math.min(d.energy, amount);
            if (take <= 0) continue;
            d.energy -= take; amount -= take;
            if (w != null) fx.pulse(w, d.x, d.y, d.z, t.x, t.y, t.z, Color.fromRGB(255, 120, 30));
            if (amount <= 0) break;
        }
        return true;
    }

    // ---------- torres ----------

    /** {flechas, bolas de fogo} */
    private int[] ammo(Block b) {
        int[] n = new int[2];
        if (!(b.getState() instanceof Container c)) return n;
        for (ItemStack it : c.getInventory().getContents()) {
            if (it == null) continue;
            if (isArrow(it.getType())) n[0] += it.getAmount();
            else if (it.getType() == Material.FIRE_CHARGE) n[1] += it.getAmount();
        }
        return n;
    }

    private static boolean isAmmo(Material m) { return isArrow(m) || m == Material.FIRE_CHARGE; }

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
            if (!valid(t, b)) { it.remove(); removeTag(t); continue; }
            int lvl = plugin.level(t.owner);
            int range = RANGE[lvl - 1];
            Location c = t.center(w);

            if (!(b.getState() instanceof Container cont)) continue;
            ItemStack ammo = null; int slot = -1;
            ItemStack[] inv = cont.getInventory().getContents();
            for (int pass = 0; pass < 2 && ammo == null; pass++)
                for (int i = 0; i < inv.length; i++) {
                    if (inv[i] == null || !isAmmo(inv[i].getType())) continue;
                    boolean fire = inv[i].getType() == Material.FIRE_CHARGE;
                    boolean wanted = t.ammoPref == 0 || (t.ammoPref == 1 && !fire) || (t.ammoPref == 2 && fire);
                    if (pass == 0 && !wanted) continue; // primeiro a preferida, depois qualquer uma
                    ammo = inv[i]; slot = i; break;
                }
            if (ammo == null) { t.nextShot = tick + 40; continue; }
            boolean fireball = ammo.getType() == Material.FIRE_CHARGE;
            double speed = fireball ? 1.8 : 2.6;

            List<LivingEntity> cands = new ArrayList<>();
            for (Entity en : w.getNearbyEntities(c, range, range, range)) {
                if (!(en instanceof LivingEntity le) || !hostile(le)) continue;
                if ((t.filter & (1 << category(le))) == 0) continue;
                if (le.getLocation().distanceSquared(c) > range * range) continue;
                cands.add(le);
            }
            if (cands.isEmpty()) continue;
            Comparator<LivingEntity> byDist = Comparator.comparingDouble(le -> le.getLocation().distanceSquared(c));
            cands.sort(switch (t.prio) {
                case 1 -> Comparator.<LivingEntity>comparingDouble(LivingEntity::getHealth).thenComparing(byDist);
                case 2 -> Comparator.<LivingEntity>comparingDouble(le -> -le.getHealth()).thenComparing(byDist);
                default -> byDist;
            });
            LivingEntity best = null; Vector bestAim = null;
            int checks = 0;
            for (LivingEntity le : cands) {
                if (checks++ >= 8) break;
                Vector toEye = le.getEyeLocation().toVector().subtract(c.toVector());
                if (toEye.lengthSquared() < 0.01) continue;
                Location muzzle = c.clone().add(toEye.clone().normalize().multiply(0.8));
                Vector fromMuzzle = le.getEyeLocation().toVector().subtract(muzzle.toVector());
                RayTraceResult hit = w.rayTraceBlocks(muzzle, fromMuzzle.clone().normalize(), fromMuzzle.length(), FluidCollisionMode.NEVER, true);
                if (hit != null) continue;
                best = le; bestAim = aimPoint(le, c, speed, !fireball);
                break;
            }
            if (best == null) continue;
            if (!drain(t, fireball ? FIREBALL_COST : SHOT_COST)) { t.nextShot = tick + 40; w.spawnParticle(Particle.SMOKE, c.clone().add(0, 0.6, 0), 3, 0.1, 0.1, 0.1, 0.01); continue; }

            ItemStack shot = ammo.clone(); shot.setAmount(1);
            ammo.setAmount(ammo.getAmount() - 1);
            cont.getInventory().setItem(slot, ammo.getAmount() > 0 ? ammo : null);

            int shots = lvl >= 5 ? 2 : 1;
            if (fireball) {
                for (int s = 0; s < shots; s++) fireball(w, c, bestAim, t, s == 0 ? 0 : 0.08);
                w.playSound(c, Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.2f);
            } else {
                for (int s = 0; s < shots; s++) fire(w, c, bestAim, shot, t, lvl, s == 0 ? 0.4f : 2.5f);
                w.playSound(c, Sound.BLOCK_DISPENSER_LAUNCH, 0.7f, 1.4f);
            }
            t.nextShot = tick + COOLDOWN[lvl - 1];
        }
    }

    /** Mira na frente do alvo (velocidade medida entre scans) e compensa a queda da flecha. */
    private Vector aimPoint(LivingEntity le, Location from, double speed, boolean gravity) {
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
        double ticks = dist / speed;
        target.add(vel.multiply(ticks));
        if (gravity) target.setY(target.getY() + 0.025 * ticks * ticks);
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

    private void fireball(World w, Location c, Vector aim, Device t, double jitter) {
        Vector dir = aim.clone().subtract(c.toVector()).normalize();
        if (jitter > 0) dir.add(new Vector(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5).multiply(jitter)).normalize();
        Location muzzle = c.clone().add(dir.clone().multiply(0.9));
        muzzle.setDirection(dir);
        Vector d2 = dir.clone();
        w.spawn(muzzle, SmallFireball.class, f -> {
            f.setIsIncendiary(false); // nao poe fogo em blocos da base
            f.setDirection(d2);
            f.setVelocity(d2.clone().multiply(1.8));
            f.getPersistentDataContainer().set(arrowKey, PersistentDataType.STRING, t.owner.toString());
        });
    }

    private boolean fromTurret(Entity damager) {
        return damager instanceof Projectile pr && pr.getPersistentDataContainer().has(arrowKey, PersistentDataType.STRING);
    }

    /** Projetil da torre nao fere jogador, pet, aldeao nem animal. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onArrowHit(EntityDamageByEntityEvent e) {
        if (!fromTurret(e.getDamager())) return;
        if (e.getEntity() instanceof LivingEntity le && le instanceof Enemy) return;
        e.setCancelled(true);
        e.getDamager().remove();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onTurretIgnite(org.bukkit.event.entity.EntityCombustByEntityEvent e) {
        if (fromTurret(e.getCombuster()) && !(e.getEntity() instanceof Enemy)) e.setCancelled(true);
    }

    // ---------- etiquetas flutuantes ----------

    void removeTag(Device d) {
        if (d.tag != null) { d.tag.remove(); d.tag = null; }
    }

    void clearTags() { for (Device d : devices.values()) removeTag(d); }

    private static String bar(double v, double max) {
        int n = (int) Math.round(10 * Math.max(0, Math.min(1, v / max)));
        return "|".repeat(n) + ".".repeat(10 - n);
    }

    private Component tagText(Device d, Block b, World w) {
        Component name = Component.text(label(d.type), NamedTextColor.GOLD, TextDecoration.BOLD);
        Component nl = Component.newline();
        NamedTextColor g = NamedTextColor.GRAY;
        switch (d.type) {
            case "bancada" -> { return name; }
            case "torre" -> {
                int lvl = plugin.level(d.owner);
                int[] am = ammo(b);
                double net = networkEnergy(d, w);
                Component state;
                if (am[0] + am[1] == 0) state = Component.text("SEM MUNICAO", NamedTextColor.RED, TextDecoration.BOLD);
                else if (net < SHOT_COST) state = Component.text("SEM ENERGIA", NamedTextColor.RED, TextDecoration.BOLD);
                else state = Component.text("⚡ " + (int) net + " na rede", NamedTextColor.YELLOW);
                return name.append(nl).append(Component.text("Nivel " + lvl + "  •  alcance " + RANGE[lvl - 1], g))
                        .append(nl).append(Component.text("➶ " + am[0] + " flechas  ☄ " + am[1] + " fogo", NamedTextColor.WHITE))
                        .append(nl).append(state);
            }
            default -> {
                Component line = Component.text("⚡ " + (int) d.energy + "/" + (int) d.cap() + "  ", NamedTextColor.YELLOW)
                        .append(Component.text(bar(d.energy, d.cap()), d.energy > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
                Component out = name.append(nl).append(line);
                if (!d.type.equals("bateria")) {
                    out = out.append(nl).append(d.lastRate > 0
                            ? Component.text(String.format(Locale.ROOT, "+%.1f/s", d.lastRate), NamedTextColor.GREEN)
                            : Component.text(d.type.equals("carvao") ? "sem combustivel" : "parado", NamedTextColor.RED));
                    if (d.type.equals("carvao") && d.burn > 0) out = out.append(Component.text("  (" + d.burn + "s)", g));
                }
                return out.append(nl).append(Component.text("alcance " + (int) LINK_RANGE + " blocos", g));
            }
        }
    }

    /** Agrupa paineis solares encostados (inclusive diagonal): o lider fica com a etiqueta do grupo. */
    private Map<Device, List<Device>> solarClusters(Set<Device> followers) {
        Map<Device, List<Device>> out = new HashMap<>();
        Set<Device> seen = new HashSet<>();
        for (Device d : devices.values()) {
            if (!d.type.equals("solar") || seen.contains(d)) continue;
            List<Device> group = new ArrayList<>();
            ArrayDeque<Device> q = new ArrayDeque<>(List.of(d));
            seen.add(d);
            while (!q.isEmpty()) {
                Device c = q.poll();
                group.add(c);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    Device n = devices.get(c.world + ":" + (c.x + dx) + ":" + (c.y + dy) + ":" + (c.z + dz));
                    if (n != null && n.type.equals("solar") && seen.add(n)) q.add(n);
                }
            }
            group.sort(Comparator.comparingInt((Device g) -> g.x).thenComparingInt(g -> g.z).thenComparingInt(g -> g.y));
            out.put(group.get(0), group);
            for (int i = 1; i < group.size(); i++) followers.add(group.get(i));
        }
        return out;
    }

    private Component solarText(List<Device> g) {
        double e = 0, cap = 0, rate = 0;
        for (Device d : g) { e += d.energy; cap += d.cap(); rate += d.lastRate; }
        Component nl = Component.newline();
        String title = g.size() == 1 ? "Painel Solar" : "Paineis Solares x" + g.size();
        return Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(nl).append(Component.text("⚡ " + (int) e + "/" + (int) cap + "  ", NamedTextColor.YELLOW)
                        .append(Component.text(bar(e, cap), e > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)))
                .append(nl).append(rate > 0 ? Component.text(String.format(Locale.ROOT, "+%.1f/s", rate), NamedTextColor.GREEN)
                        : Component.text("parado (noite ou sombra)", NamedTextColor.RED))
                .append(nl).append(Component.text("alcance " + (int) LINK_RANGE + " blocos", NamedTextColor.GRAY));
    }

    private void updateTags() {
        Set<Device> followers = new HashSet<>();
        Map<Device, List<Device>> clusters = solarClusters(followers);
        for (Device d : devices.values()) {
            World w = Bukkit.getWorld(d.world);
            if (!loaded(d, w)) { d.tag = null; continue; }
            if (followers.contains(d) || (d.type.equals("torre") && !d.tagOn)) { removeTag(d); continue; }
            Block b = w.getBlockAt(d.x, d.y, d.z);
            Location at = new Location(w, d.x + 0.5, d.y + 1.55, d.z + 0.5);
            Component text;
            List<Device> group = clusters.get(d);
            if (group != null) {
                double sx = 0, sz = 0; int maxY = d.y;
                for (Device g : group) { sx += g.x + 0.5; sz += g.z + 0.5; maxY = Math.max(maxY, g.y); }
                at = new Location(w, sx / group.size(), maxY + 0.9, sz / group.size());
                text = solarText(group);
            } else text = tagText(d, b, w);
            if (d.tag == null || !d.tag.isValid()) {
                Component tx = text;
                d.tag = w.spawn(at, TextDisplay.class, td -> {
                    td.setPersistent(false);
                    td.setBillboard(Display.Billboard.CENTER);
                    td.setShadowed(true);
                    td.setViewRange(0.35f);
                    td.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
                    td.setAlignment(TextDisplay.TextAlignment.CENTER);
                    td.text(tx);
                });
            } else {
                d.tag.text(text);
                if (d.tag.getLocation().distanceSquared(at) > 0.01) d.tag.teleport(at);
            }
        }
    }

    // ---------- menu da torre ----------

    static final String[] CATS = {"Zumbis", "Esqueletos", "Creepers", "Aranhas", "Mobs do Nether", "Outros"};
    static final Material[] CAT_ICON = {Material.ZOMBIE_HEAD, Material.SKELETON_SKULL, Material.CREEPER_HEAD,
            Material.SPIDER_EYE, Material.BLAZE_POWDER, Material.PHANTOM_MEMBRANE};
    static final String[] PRIOS = {"Mais perto", "Menos vida", "Mais vida"};
    static final String[] AMMOS = {"Qualquer", "Flechas", "Bolas de fogo"};

    static int category(LivingEntity e) {
        if (e instanceof PigZombie || e instanceof PiglinAbstract || e instanceof Blaze || e instanceof Ghast
                || e instanceof MagmaCube || e instanceof Hoglin || e instanceof Zoglin) return 4;
        if (e instanceof Zombie) return 0;
        if (e instanceof AbstractSkeleton) return 1;
        if (e instanceof Creeper) return 2;
        if (e instanceof Spider) return 3;
        return 5;
    }

    static final class TurretMenu implements InventoryHolder {
        Inventory inv; String key;
        @Override public Inventory getInventory() { return inv; }
    }

    void openTurret(Player p, Device d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null) return;
        TurretMenu h = new TurretMenu();
        h.key = d.key();
        Inventory inv = Bukkit.createInventory(h, 45, Component.text("Torre Sentinela"));
        h.inv = inv;
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 0; i < 45; i++) inv.setItem(i, filler);
        Block b = w.getBlockAt(d.x, d.y, d.z);
        int lvl = plugin.level(d.owner);
        int[] am = ammo(b);
        OfflinePlayer owner = Bukkit.getOfflinePlayer(d.owner);
        double net = networkEnergy(d, w);

        inv.setItem(4, icon(Material.DISPENSER, "Torre Sentinela", NamedTextColor.GOLD,
                "Dono: " + (owner.getName() == null ? "?" : owner.getName()) + " (Engenheiro nivel " + lvl + ")",
                "Alcance " + RANGE[lvl - 1] + " blocos, " + String.format(Locale.ROOT, "%.1f", 20.0 / COOLDOWN[lvl - 1]) + " tiros/s",
                "Energia na rede: " + (int) net));
        inv.setItem(10, icon(Material.CHEST, "Municao", NamedTextColor.WHITE,
                am[0] + " flechas, " + am[1] + " bolas de fogo", "", "Clique para abrir e colocar", "flechas ou bolas de fogo."));
        Material am2 = d.ammoPref == 2 ? Material.FIRE_CHARGE : d.ammoPref == 1 ? Material.ARROW : Material.BOW;
        inv.setItem(12, icon(am2, "Municao preferida: " + AMMOS[d.ammoPref], NamedTextColor.YELLOW,
                "Usa esta primeiro. Se acabar,", "usa a outra.", "", "Clique para trocar."));
        inv.setItem(14, icon(Material.TARGET, "Prioridade: " + PRIOS[d.prio], NamedTextColor.YELLOW,
                "Qual alvo a torre escolhe", "entre os que estao a vista.", "", "Clique para trocar."));
        inv.setItem(16, icon(d.tagOn ? Material.NAME_TAG : Material.PAPER, "Etiqueta: " + (d.tagOn ? "ligada" : "desligada"),
                d.tagOn ? NamedTextColor.GREEN : NamedTextColor.GRAY, "Texto flutuante com municao", "e energia em cima da torre.", "", "Clique para " + (d.tagOn ? "desligar" : "ligar") + "."));
        for (int i = 0; i < CATS.length; i++) {
            boolean on = (d.filter & (1 << i)) != 0;
            inv.setItem(28 + i + (i >= 3 ? 1 : 0), icon(on ? CAT_ICON[i] : Material.BARRIER, CATS[i] + ": " + (on ? "atirando" : "ignorando"),
                    on ? NamedTextColor.GREEN : NamedTextColor.RED, "Clique para " + (on ? "ignorar" : "atirar") + "."));
        }
        inv.setItem(22, icon(Material.OAK_SIGN, "Filtro de mobs", NamedTextColor.WHITE, "Escolha abaixo em quem", "a torre atira."));
        inv.setItem(40, icon(Material.ENDER_EYE, "Mostrar alcance", NamedTextColor.LIGHT_PURPLE, "Desenha o alcance no chao", "por 3 segundos."));
        p.openInventory(inv);
    }

    @EventHandler
    public void onTurretClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof TurretMenu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        Device d = devices.get(h.key);
        if (d == null) { p.closeInventory(); return; }
        World w = Bukkit.getWorld(d.world);
        int raw = e.getRawSlot();
        switch (raw) {
            case 10 -> {
                if (w != null && w.getBlockAt(d.x, d.y, d.z).getState() instanceof Container c) p.openInventory(c.getInventory());
                return;
            }
            case 12 -> d.ammoPref = (d.ammoPref + 1) % AMMOS.length;
            case 14 -> d.prio = (d.prio + 1) % PRIOS.length;
            case 16 -> d.tagOn = !d.tagOn;
            case 40 -> { p.closeInventory(); showRange(p, d, RANGE[plugin.level(d.owner) - 1], Color.fromRGB(230, 60, 40)); return; }
            default -> {
                int i = raw - 28;
                if (i >= 4) i--; // slot 31 e o espaco do meio
                if (raw == 31 || i < 0 || i >= CATS.length) return;
                d.filter ^= 1 << i;
            }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        openTurret(p, d);
    }

    @EventHandler
    public void onTurretDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof TurretMenu) e.setCancelled(true); }

    /** Desenha um circulo de particulas so para o jogador, por 3 segundos. */
    private void showRange(Player p, Device d, double r, Color color) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.3f);
        int points = (int) (r * 7);
        for (int k = 0; k < 3; k++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                for (int i = 0; i < points; i++) {
                    double a = 2 * Math.PI * i / points;
                    p.spawnParticle(Particle.DUST, d.x + 0.5 + Math.cos(a) * r, d.y + 0.6, d.z + 0.5 + Math.sin(a) * r, 1, 0, 0, 0, 0, dust);
                }
            }, k * 20L);
        }
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        lastPos.remove(e.getEntity().getUniqueId());
        if (!(e.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent ed)) return;
        if (!(ed.getDamager() instanceof Projectile a)) return;
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
                d.tagOn = c.getBoolean("tag", true);
                d.filter = c.getInt("filter", 0b111111);
                d.prio = c.getInt("prio");
                d.ammoPref = c.getInt("ammo");
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
            if (d.type.equals("torre")) {
                y.set(k + ".tag", d.tagOn); y.set(k + ".filter", d.filter);
                y.set(k + ".prio", d.prio); y.set(k + ".ammo", d.ammoPref);
            }
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("devices.yml: " + ex.getMessage()); }
    }
}
