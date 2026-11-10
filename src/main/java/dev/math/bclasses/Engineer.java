package dev.math.bclasses;

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

    static final String[] TYPES = {"bancada", "torre", "carvao", "geotermico", "solar", "inversor", "bateria", "cabo", "juncao", "cano", "forja", "bala_ferro", "bala_cobre"};

    // por nivel 1..5
    static final int[] MAX_TURRETS = {2, 3, 4, 5, 6};
    static final int[] RANGE = {12, 14, 16, 18, 20};
    static final int[] COOLDOWN = {20, 17, 14, 12, 10}; // ticks entre tiros
    static final int MAX_POWER_DEVICES = 16;
    static final double SHOT_COST = 5;
    static final double FIREBALL_COST = 10;
    /** Manutencao da torre ligada, por segundo, mesmo sem atirar. Sobe com o nivel (2.0 ate 3.0). */
    static double upkeep(int lvl) { return 2.0 + 0.25 * (lvl - 1); }
    static final double INVERTER_RATE = 8;     // energia/s que um inversor converte
    static final double INVERTER_EFF = 0.9;
    static final int MAX_NET_NODES = 4096;
    static final double POLE_RANGE = 24;
    /** Tiros que um cano aguenta (bola de fogo gasta 2). */
    static final int BARREL_MAX = 300;
    static final int XP_CRAFT = 3, XP_REPAIR = 5, XP_KILL = 1;
    /** Forja: energia por segundo enquanto trabalha, segundos por lote (9 pepitas viram 16 balas). */
    static final double FORGE_RATE = 4;
    static final int FORGE_TIME = 6, FORGE_IN = 9, FORGE_OUT = 16;
    /**
     * Balas: rajada de BURST balas por disparo (3x a cadencia da flecha), cada uma com pouco dano.
     * Flecha da ~6: cobre 1/3 disso (2), ferro metade (3). Energia por bala; cano gasta 1 (cobre)
     * ou 2 (ferro) por rajada.
     */
    static final int BURST = 3;
    static final double COPPER_DMG = 2, IRON_DMG = 3, COPPER_COST = 2, IRON_COST = 3;

    static final class Device {
        final String type; final String world; final int x, y, z; final UUID owner;
        double energy, lastRate; int burn; long nextShot; boolean powered; int barrel = BARREL_MAX;
        TextDisplay tag;
        boolean tagOn = true, enabled = true; int filter = 0b1111111; int prio; int ammoPref; int mode;
        Device(String type, String world, int x, int y, int z, UUID owner) {
            this.type = type; this.world = world; this.x = x; this.y = y; this.z = z; this.owner = owner;
        }
        String key() { return world + ":" + x + ":" + y + ":" + z; }
        Location center(World w) { return new Location(w, x + 0.5, y + 0.5, z + 0.5); }
        double cap() {
            return switch (type) { case "carvao", "inversor" -> 400; case "geotermico", "solar" -> 200; case "bateria" -> 5000; default -> 0; };
        }
        boolean isSource() { return cap() > 0; }
    }

    record Recipe(String type, int minLevel, Map<Material, Integer> cost, int amount) {
        Recipe(String type, int minLevel, Map<Material, Integer> cost) { this(type, minLevel, cost, 1); }
    }

    private final BabylonClasses plugin;
    private final NamespacedKey devKey, arrowKey, recipeKey, barrelKey;
    private final Map<String, Device> devices = new HashMap<>();
    private final Map<UUID, double[]> lastPos = new HashMap<>(); // x, y, z, tick
    private final File file;
    private long tick;
    private int powerTicks;
    private EnergyFx fx;
    private final List<Recipe> recipes = new ArrayList<>();

    Engineer(BabylonClasses plugin) {
        this.plugin = plugin;
        this.devKey = new NamespacedKey(plugin, "device");
        this.arrowKey = new NamespacedKey(plugin, "turret-arrow");
        this.recipeKey = new NamespacedKey(plugin, "bancada");
        this.barrelKey = new NamespacedKey(plugin, "barrel");
        this.file = new File(plugin.getDataFolder(), "devices.yml");
        recipes.add(new Recipe("torre", 1, costs(Material.DISPENSER, 1, Material.CROSSBOW, 1, Material.IRON_BLOCK, 4, Material.REDSTONE_BLOCK, 4)));
        recipes.add(new Recipe("cano", 1, costs(Material.IRON_BLOCK, 2, Material.REDSTONE_BLOCK, 1, Material.COPPER_BLOCK, 1)));
        recipes.add(new Recipe("carvao", 1, costs(Material.FURNACE, 1, Material.IRON_INGOT, 4, Material.COPPER_INGOT, 2, Material.REDSTONE, 2)));
        recipes.add(new Recipe("solar", 1, costs(Material.DAYLIGHT_DETECTOR, 1, Material.COPPER_INGOT, 4, Material.GLASS, 2, Material.REDSTONE, 2)));
        recipes.add(new Recipe("inversor", 1, costs(Material.COPPER_INGOT, 4, Material.REDSTONE, 2, Material.GOLD_INGOT, 1, Material.IRON_INGOT, 1)));
        recipes.add(new Recipe("cabo", 1, costs(Material.COPPER_INGOT, 2), 8));
        recipes.add(new Recipe("juncao", 1, costs(Material.COPPER_BLOCK, 1, Material.IRON_INGOT, 1, Material.REDSTONE, 2), 2));
        recipes.add(new Recipe("geotermico", 2, costs(Material.COBBLED_DEEPSLATE, 6, Material.COPPER_INGOT, 4, Material.IRON_INGOT, 2, Material.GOLD_INGOT, 1)));
        recipes.add(new Recipe("forja", 2, costs(Material.BLAST_FURNACE, 1, Material.IRON_BLOCK, 2, Material.COPPER_BLOCK, 2, Material.REDSTONE_BLOCK, 1)));
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
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickShock, 10L, 5L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 1200L, 1200L);
    }

    void discover(Player p) { p.discoverRecipe(recipeKey); }

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
            case "bancada" -> { mat = Material.SMITHING_TABLE; name = "Bancada de Engenharia"; lore = List.of("Clique com o direito para montar dispositivos."); }
            case "torre" -> { mat = Material.DISPENSER; name = "Torre Sentinela"; lore = List.of("Atira flechas ou bolas de fogo guardadas nela", "em mobs hostis. Ligada, consome " + upkeep(1) + "/s", "mesmo parada; cada flecha gasta mais " + (int) SHOT_COST + ",", "bola de fogo " + (int) FIREBALL_COST + ". Ligue com cabos."); }
            case "carvao" -> { mat = Material.FURNACE; name = "Gerador a Carvao"; lore = List.of("Coloque carvao no espaco de combustivel.", "Funil pela lateral tambem abastece.", "4 energia/s enquanto queima."); }
            case "geotermico" -> { mat = Material.MAGMA_BLOCK; name = "Gerador Geotermico"; lore = List.of("0.5 energia/s por fonte de lava encostada.", "Dobro no Nether. Nunca para."); }
            case "solar" -> { mat = Material.DAYLIGHT_DETECTOR; name = "Painel Solar"; lore = List.of("1.5 energia/s de dia com ceu aberto.", "Gera corrente continua (CC): precisa", "de um Inversor na rede para abastecer."); }
            case "bateria" -> { mat = Material.LODESTONE; name = "Bateria"; lore = List.of("Guarda ate 5000 de energia.", "Geradores na mesma rede carregam ela."); }
            case "inversor" -> { mat = Material.WAXED_COPPER_BULB; name = "Inversor CC/CA"; lore = List.of("Converte a energia dos paineis solares", "da rede (CC) para as maquinas (CA).", "Ate " + (int) INVERTER_RATE + " energia/s, 90% de eficiencia."); }
            case "cabo" -> { mat = Material.WAXED_COPPER_CHAIN; name = "Cabo de Energia"; lore = List.of("Liga maquinas encostadas nele.", "Uma alavanca no meio do cabo", "funciona como disjuntor.", "Energizado da choque em quem encostar."); }
            case "cano" -> { mat = Material.IRON_BARS; name = "Cano da Torre"; lore = List.of("Peca de reposicao da Torre Sentinela.", "Aguenta " + BARREL_MAX + " tiros (bola de fogo conta 2).", "Troque pelo menu da torre."); }
            case "forja" -> { mat = Material.BLAST_FURNACE; name = "Forja de Municao"; lore = List.of("Transforma pepitas em balas para a torre.", "9 pepitas de ferro ou cobre = " + FORGE_OUT + " balas.", "Gasta " + (int) FORGE_RATE + " energia/s, " + FORGE_TIME + "s por lote.", "Funil em cima abastece, embaixo retira."); }
            case "bala_ferro" -> { mat = Material.IRON_NUGGET; name = "Bala de Ferro"; lore = List.of("Municao da Torre Sentinela.", "Rajada de " + BURST + " tiros retos,", IRON_DMG + " de dano cada (flecha ~6).", "Gasta " + (int) IRON_COST + " energia por bala", "e 2 de cano por rajada."); }
            case "bala_cobre" -> { mat = Material.COPPER_NUGGET; name = "Bala de Cobre"; lore = List.of("Municao da Torre Sentinela.", "Rajada de " + BURST + " tiros retos,", COPPER_DMG + " de dano cada (flecha ~6).", "Gasta " + (int) COPPER_COST + " energia por bala", "e 1 de cano por rajada."); }
            case "juncao" -> { mat = Material.WAXED_COPPER_BLOCK; name = "Juncao de Cabo"; lore = List.of("Bloco de cobre que conduz como o cabo.", "Use nas curvas e emendas, pode empilhar.", "Energizado da choque em quem encostar."); }
            default -> { return null; }
        }
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(name, NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        l.add(Component.text("Engenharia", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
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
            case "geotermico" -> "Gerador Geotermico"; case "solar" -> "Painel Solar"; case "bateria" -> "Bateria";
            case "inversor" -> "Inversor CC/CA"; case "cabo" -> "Cabo de Energia"; case "juncao" -> "Juncao de Cabo"; case "cano" -> "Cano da Torre";
            case "forja" -> "Forja de Municao"; case "bala_ferro" -> "Bala de Ferro"; case "bala_cobre" -> "Bala de Cobre"; default -> type;
        };
    }

    // ---------- crafting da bancada ----------

    /** Itens do plugin (cano, cabo...) nao entram em receitas vanilla. */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if ("bancada".equals(typeOf(e.getInventory().getResult()))) return;
        for (ItemStack it : e.getInventory().getMatrix())
            if (typeOf(it) != null) { e.getInventory().setResult(null); return; }
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
        if (type.equals("cabo") || type.equals("juncao")) { // qualquer um da party pode puxar fio
            Block cb = e.getBlockPlaced();
            cables.add(key(cb.getWorld().getName(), cb.getX(), cb.getY(), cb.getZ()));
            invalidate();
            return;
        }
        if (type.equals("cano") || type.startsWith("bala")) { e.setCancelled(true); return; }
        Block b = e.getBlockPlaced();
        int lvl = plugin.level(p.getUniqueId());
        if (type.equals("torre") && count(p.getUniqueId(), b.getWorld().getName(), true) >= MAX_TURRETS[lvl - 1]) {
            e.setCancelled(true);
            p.sendMessage(Component.text("Limite de " + MAX_TURRETS[lvl - 1] + " torres no nivel " + lvl + ".", NamedTextColor.RED));
            return;
        }
        Device d = new Device(type, b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), p.getUniqueId());
        Integer worn = e.getItemInHand().getItemMeta().getPersistentDataContainer().get(barrelKey, PersistentDataType.INTEGER);
        if (worn != null) d.barrel = worn;
        if (d.isSource() && count(p.getUniqueId(), b.getWorld().getName(), false) >= MAX_POWER_DEVICES) {
            e.setCancelled(true);
            p.sendMessage(Component.text("Limite de " + MAX_POWER_DEVICES + " geradores/baterias.", NamedTextColor.RED));
            return;
        }
        devices.put(d.key(), d);
        invalidate();
        p.sendMessage(Component.text("⚙ " + label(type) + " instalado.", NamedTextColor.GOLD));
        if (type.equals("torre")) p.sendMessage(Component.text("Coloque flechas ou bolas de fogo dentro dela e ligue com cabo num gerador ou bateria.", NamedTextColor.GRAY));
        if (type.equals("solar")) p.sendMessage(Component.text("Painel solar gera CC: ligue um Inversor na mesma rede.", NamedTextColor.GRAY));
    }

    /** Remove o dispositivo: derruba o conteudo, o item customizado e vira ar. */
    private void breakDevice(Block b, Device d, boolean dropItem) {
        devices.remove(d.key());
        invalidate();
        removeTag(d);
        BlockState st = b.getState();
        if (st instanceof Container c) {
            for (ItemStack it : c.getInventory().getContents())
                if (it != null && it.getType() != Material.AIR) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), it);
            c.getInventory().clear();
        }
        b.setType(Material.AIR);
        if (dropItem) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), d.type.equals("torre") ? turretItem(d.barrel) : item(d.type));
    }

    private ItemStack turretItem(int barrel) {
        ItemStack it = item("torre");
        if (barrel >= BARREL_MAX) return it;
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(barrelKey, PersistentDataType.INTEGER, barrel);
        List<Component> l = new ArrayList<>(Objects.requireNonNullElse(m.lore(), List.of()));
        l.add(0, Component.text("Cano: " + barrel + "/" + BARREL_MAX + " tiros", barrel > 0 ? NamedTextColor.YELLOW : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        it.setItemMeta(m);
        return it;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block bb = e.getBlock();
        String pk = poleBlocks.get(key(bb.getWorld().getName(), bb.getX(), bb.getY(), bb.getZ()));
        if (pk != null && poles.containsKey(pk)) {
            removePole(poles.get(pk));
            e.getPlayer().sendActionBar(Component.text("Poste desmontado.", NamedTextColor.GRAY));
            return;
        }
        if (isCable(e.getBlock())) {
            e.setCancelled(true);
            breakCable(e.getBlock(), e.getPlayer().getGameMode() != GameMode.CREATIVE);
            return;
        }
        Device d = at(e.getBlock());
        if (d == null) return;
        e.setCancelled(true);
        breakDevice(e.getBlock(), d, e.getPlayer().getGameMode() != GameMode.CREATIVE);
    }

    private void explode(List<Block> blocks) {
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block b = it.next();
            String pk = poleBlocks.get(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
            if (pk != null && poles.containsKey(pk)) removePole(poles.get(pk));
            if (isCable(b)) { it.remove(); breakCable(b, true); continue; }
            Device d = at(b);
            if (d != null) { it.remove(); breakDevice(b, d, true); }
        }
    }

    @EventHandler(ignoreCancelled = true) public void onExplode(EntityExplodeEvent e) { explode(e.blockList()); }
    @EventHandler(ignoreCancelled = true) public void onBlockExplode(BlockExplodeEvent e) { explode(e.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (at(b) != null || isCable(b) || poleBlocks.containsKey(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()))) { e.setCancelled(true); return; } }
    @EventHandler(ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (at(b) != null || isCable(b) || poleBlocks.containsKey(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()))) { e.setCancelled(true); return; } }
    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }

    /** Torre nunca despeja flechas com redstone. */
    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }

    /** Gerador a carvao nao queima combustivel do jeito vanilla: quem consome e o plugin. */
    @EventHandler(ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }

    /** Fornalha de maquina (gerador, forja) acesa nao derrete minerio de graca. */
    @EventHandler(ignoreCancelled = true)
    public void onFurnaceSmelt(org.bukkit.event.inventory.FurnaceSmeltEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }

    // ---------- interacao ----------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onLever(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.LEVER || e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        boolean touches = false;
        for (int[] d : DIRS6) {
            String k = key(b.getWorld().getName(), b.getX() + d[0], b.getY() + d[1], b.getZ() + d[2]);
            if (cables.contains(k) || devices.containsKey(k)) { touches = true; break; }
        }
        if (!touches) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            invalidate();
            boolean on = b.getBlockData() instanceof org.bukkit.block.data.Powerable pw && pw.isPowered();
            p.sendActionBar(Component.text("Disjuntor " + (on ? "ligado" : "desligado"), on ? NamedTextColor.GREEN : NamedTextColor.RED));
            p.playSound(b.getLocation(), on ? Sound.BLOCK_COPPER_BULB_TURN_ON : Sound.BLOCK_COPPER_BULB_TURN_OFF, 0.8f, 1f);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Device d = at(e.getClickedBlock());
        if (d == null) return;
        Player p = e.getPlayer();
        switch (d.type) {
            case "bancada" -> {
                e.setCancelled(true);
                openBench(p);
            }
            case "bateria", "geotermico", "solar", "carvao", "inversor", "forja" -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (p.isSneaking() && hand.getType().isBlock() && hand.getType() != Material.AIR) return;
                e.setCancelled(true);
                openMachine(p, d);
            }
            case "torre" -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (p.isSneaking() && hand.getType().isBlock() && hand.getType() != Material.AIR) return; // agachado com bloco: deixa colocar
                e.setCancelled(true);
                openTurret(p, d);
            }
        }
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
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Engenharia"));
        h.inv = inv;
        UUID id = p.getUniqueId();
        int lvl = plugin.level(id), xp = plugin.data(id).xp;
        String w = p.getWorld().getName();
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        String next = lvl >= 5 ? "Nivel maximo" : "XP " + xp + "/" + BabylonClasses.LEVEL_XP[lvl] + " para o nivel " + (lvl + 1);
        inv.setItem(4, icon(Material.DISPENSER, "Engenharia - nivel " + lvl, NamedTextColor.GOLD,
                next, "", "Todo mundo pode montar, o nivel", "e que libera mais coisas.", "",
                "XP: montar na bancada (+" + XP_CRAFT + "), trocar", "cano de torre (+" + XP_REPAIR + "), abate de torre (+" + XP_KILL + ").", "", "O nivel zera quando a vida acaba."));

        inv.setItem(10, icon(Material.SMITHING_TABLE, "Bancada de Engenharia", NamedTextColor.GOLD,
                "Receita na mesa de trabalho:", "  _ R _", "  I M I", "  I I I",
                "R = redstone, I = ferro, M = mesa de trabalho", "", "Clique para ver o craft."));
        int slot = 11;
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
            if (L == 2) lore.add("+ Libera Geotermico, Bateria e Forja");
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
                "Ligue as maquinas com Cabos de Energia.", "Tudo encostado no mesmo cabo e uma rede.",
                "Alavanca no meio do cabo = disjuntor.", "Juncao (bloco de cobre) faz curvas.", "Cabo energizado da choque!",
                "Poste: muro de pedregulho, madeira e", "cobre talhado no topo. Liga postes", "a ate " + (int) POLE_RANGE + " blocos sem fio.", "Torre ligada: " + upkeep(1) + "/s parada (sobe com", "o nivel) + " + (int) SHOT_COST + " por flecha, " + (int) FIREBALL_COST + " por fogo.",
                "Geradores enchem as baterias da rede.", "Solar e CC: precisa de Inversor na rede.", "",
                "Carvao: 4/s enquanto queima", "Geotermico: 0.5/s por lava (x2 no Nether)", "Solar: 1.5/s de dia, 0 a noite"));
        inv.setItem(50, icon(Material.ARROW, "Municao", NamedTextColor.WHITE,
                "Flechas, bolas de fogo ou balas dentro", "da torre, ou abasteca com funil.", "Bola de fogo gasta " + (int) FIREBALL_COST + " de energia.",
                "Balas saem da Forja (nivel 2): rajada de", BURST + " tiros retos, cobre " + COPPER_DMG + " e ferro " + IRON_DMG + " cada.",
                "Aceita flecha com efeito e espectral.", "Nunca acerta jogador, pet ou animal."));
        boolean fxOn = plugin.fxEnabled(p);
        inv.setItem(8, icon(fxOn ? Material.GLOWSTONE_DUST : Material.GUNPOWDER, "Particulas de energia: " + (fxOn ? "ligadas" : "desligadas"),
                fxOn ? NamedTextColor.GREEN : NamedTextColor.GRAY, "Mostra a energia correndo pelos", "cabos ate as maquinas.", "", "Clique para " + (fxOn ? "desligar" : "ligar") + "."));
        inv.setItem(45, icon(Material.ARROW, "Voltar", NamedTextColor.WHITE, "Volta para as habilidades."));
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
        if (raw == 45) plugin.openHub(p);
        else if (raw == 8) { plugin.toggleFx(p); openPanel(p); }
        else if (raw == 10) openRecipe(p, "bancada");
        else if (raw >= 11 && raw < 11 + recipes.size()) openRecipe(p, recipes.get(raw - 11).type());
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
                    "Monte nesta forma numa", "mesa de trabalho comum."));
        } else {
            Recipe r = recipe(type);
            int i = 0;
            for (Map.Entry<Material, Integer> c : r.cost().entrySet()) inv.setItem(GRID[i++], ingredient(p, c.getKey(), c.getValue()));
            boolean lvlOk = plugin.level(p.getUniqueId()) >= r.minLevel();
            boolean near = nearBench(p);
            List<String> lore = new ArrayList<>();
            if (!lvlOk) lore.add("Requer Engenharia nivel " + r.minLevel());
            else if (!near) { lore.add("Chegue perto de uma"); lore.add("Bancada de Engenharia (6 blocos)."); }
            else lore.add("Clique para montar");
            inv.setItem(23, icon(lvlOk && near ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                    "Montar", lvlOk && near ? NamedTextColor.GREEN : NamedTextColor.RED, lore.toArray(new String[0])));
        }
        ItemStack res = item(type); Recipe rr = recipe(type); if (rr != null) res.setAmount(rr.amount());
        inv.setItem(25, res);
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
        if (plugin.level(p.getUniqueId()) < r.minLevel()) { p.sendMessage(Component.text("Requer Engenharia nivel " + r.minLevel() + ".", NamedTextColor.RED)); return false; }
        for (Map.Entry<Material, Integer> c : r.cost().entrySet())
            if (countMat(p, c.getKey()) < c.getValue()) {
                p.sendMessage(Component.text("Falta " + pretty(c.getKey()) + ".", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return false;
            }
        for (Map.Entry<Material, Integer> c : r.cost().entrySet()) takeMat(p, c.getKey(), c.getValue());
        ItemStack out = item(r.type()); out.setAmount(r.amount());
        for (ItemStack left : p.getInventory().addItem(out).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1f);
        plugin.addXp(p.getUniqueId(), XP_CRAFT);
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
            case "geotermico" -> Material.MAGMA_BLOCK; case "solar" -> Material.DAYLIGHT_DETECTOR; case "inversor" -> Material.WAXED_COPPER_BULB;
            case "forja" -> Material.BLAST_FURNACE;
            default -> Material.LODESTONE;
        };
        return b.getType() == want;
    }

    // ---------- redes ----------

    static final int[][] DIRS6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    static final class Net {
        final List<Device> devs = new ArrayList<>();
        final List<int[]> nodes = new ArrayList<>();
        final List<double[]> wires = new ArrayList<>();
        int count(String type) { int n = 0; for (Device d : devs) if (d.type.equals(type)) n++; return n; }
    }

    private final Set<String> cables = new HashSet<>();
    private final Map<String, Net> netCache = new HashMap<>();
    private final Map<String, List<int[]>> pathCache = new HashMap<>();
    private long lastInvalidate;
    private final Set<String> live = new HashSet<>();
    private final Map<UUID, Long> lastShock = new HashMap<>();

    /** Marca os cabos de redes que tem energia CA disponivel. */
    private void refreshLive() {
        live.clear();
        Set<Net> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Device d : devices.values()) {
            World w = Bukkit.getWorld(d.world);
            if (!loaded(d, w)) continue;
            Net n = net(d);
            if (!seen.add(n)) continue;
            double e = 0;
            for (Device m : n.devs)
                if (acGenerator(m) || (m.type.equals("bateria") && m.enabled && m.mode != 1)) e += m.energy;
            if (e < 1) continue;
            for (int[] c : n.nodes) {
                String k = key(d.world, c[0], c[1], c[2]);
                if (cables.contains(k)) live.add(k);
            }
        }
    }

    /** Encostar num cabo energizado: dano e lentidao II. */
    private void tickShock() {
        if (live.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            GameMode gm = p.getGameMode();
            if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE || p.isDead()) continue;
            org.bukkit.util.BoundingBox bb = p.getBoundingBox().expand(0.08);
            String wn = p.getWorld().getName();
            Block hit = null;
            outer:
            for (int x = (int) Math.floor(bb.getMinX()); x <= (int) Math.floor(bb.getMaxX()); x++)
                for (int y = (int) Math.floor(bb.getMinY()); y <= (int) Math.floor(bb.getMaxY()); y++)
                    for (int z = (int) Math.floor(bb.getMinZ()); z <= (int) Math.floor(bb.getMaxZ()); z++)
                        if (live.contains(key(wn, x, y, z))) {
                            Block b = p.getWorld().getBlockAt(x, y, z);
                            // a corrente e fina: so conta se a caixa do jogador tocar a forma real dela
                            if (b.getBoundingBox().overlaps(bb)) { hit = b; break outer; }
                        }
            if (hit == null) continue;
            if (now - lastShock.getOrDefault(p.getUniqueId(), 0L) < 1000) continue;
            lastShock.put(p.getUniqueId(), now);
            p.damage(2.0);
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 40, 1, false, true, true));
            p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
            p.playSound(p.getLocation(), Sound.BLOCK_COPPER_BULB_TURN_ON, 0.6f, 2f);
            p.sendActionBar(Component.text("⚡ Choque! Cabo energizado.", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
    }

    static String key(String w, int x, int y, int z) { return w + ":" + x + ":" + y + ":" + z; }

    private boolean isCable(Block b) {
        return cables.contains(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) || b.getType().name().endsWith("COPPER_CHAIN");
    }

    private static boolean cableBlock(Material m) {
        String n = m.name();
        return n.endsWith("COPPER_CHAIN") || n.matches("(WAXED_)?(EXPOSED_|WEATHERED_|OXIDIZED_)?COPPER(_BLOCK)?");
    }

    private void breakCable(Block b, boolean drop) {
        cables.remove(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        String kind = b.getType().name().endsWith("COPPER_CHAIN") ? "cabo" : "juncao";
        b.setType(Material.AIR);
        if (drop) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), item(kind));
        invalidate();
    }

    static final class Pole {
        final String world; final int x, z, base, top;
        Pole(String world, int x, int z, int base, int top) { this.world = world; this.x = x; this.z = z; this.base = base; this.top = top; }
        String topKey() { return key(world, x, top, z); }
        String save() { return world + ";" + x + ";" + z + ";" + base + ";" + top; }
    }

    private final Map<String, Pole> poles = new HashMap<>();        // chave do topo -> poste
    private final Map<String, String> poleBlocks = new HashMap<>(); // qualquer bloco do poste -> chave do topo

    private static boolean isWood(Material m) {
        String n = m.name();
        if (n.contains("NETHER_BRICK")) return false;
        return n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_FENCE") || n.endsWith("_PLANKS")
                || n.endsWith("_STEM") || n.endsWith("_HYPHAE") || m == Material.BAMBOO_BLOCK;
    }

    private static boolean isPoleBase(Material m) { return m == Material.COBBLESTONE_WALL || m == Material.MOSSY_COBBLESTONE_WALL; }

    private static boolean isPoleTop(Material m) { return m.name().contains("CHISELED_COPPER"); }

    private void addPole(Pole pl) {
        poles.put(pl.topKey(), pl);
        for (int y = pl.base; y <= pl.top; y++) poleBlocks.put(key(pl.world, pl.x, y, pl.z), pl.topKey());
    }

    private void removePole(Pole pl) {
        poles.remove(pl.topKey());
        for (int y = pl.base; y <= pl.top; y++) poleBlocks.remove(key(pl.world, pl.x, y, pl.z));
        invalidate();
    }

    /** Confere a estrutura (muro de pedregulho, madeira, cobre talhado). Se quebrou, desmonta o poste. */
    private boolean validPole(World w, Pole pl) {
        if (!w.isChunkLoaded(pl.x >> 4, pl.z >> 4)) return true;
        boolean ok = isPoleTop(w.getBlockAt(pl.x, pl.top, pl.z).getType()) && isPoleBase(w.getBlockAt(pl.x, pl.base, pl.z).getType());
        for (int y = pl.base + 1; ok && y < pl.top; y++) ok = isWood(w.getBlockAt(pl.x, y, pl.z).getType());
        if (!ok) removePole(pl);
        return ok;
    }

    private List<Pole> linkedPoles(Pole pl) {
        List<Pole> out = new ArrayList<>();
        double r2 = POLE_RANGE * POLE_RANGE;
        for (Pole o : poles.values()) {
            if (o == pl || !o.world.equals(pl.world)) continue;
            double dx = o.x - pl.x, dy = o.top - pl.top, dz = o.z - pl.z;
            if (dx * dx + dy * dy + dz * dz <= r2) out.add(o);
        }
        return out;
    }

    /** Pontos de um fio entre dois postes, com uma barriga no meio. */
    private static List<double[]> wire(int ax, int ay, int az, int bx, int by, int bz) {
        List<double[]> out = new ArrayList<>();
        double sx = ax + 0.5, sy = ay + 1.0, sz = az + 0.5, ex = bx + 0.5, ey = by + 1.0, ez = bz + 0.5;
        double len = Math.sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy) + (ez - sz) * (ez - sz));
        int steps = Math.max(2, (int) (len / 0.5));
        double sag = len * 0.06;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            out.add(new double[]{sx + (ex - sx) * t, sy + (ey - sy) * t - sag * 4 * t * (1 - t), sz + (ez - sz) * t});
        }
        return out;
    }

    /** Vizinhos que conduzem: os 6 lados e, se for topo de poste, os postes ao alcance. */
    private List<int[]> neighbors(World w, int[] c) {
        List<int[]> out = new ArrayList<>(8);
        for (int[] o : DIRS6) {
            int x = c[0] + o[0], y = c[1] + o[1], z = c[2] + o[2];
            if (conducts(w, x, y, z)) out.add(new int[]{x, y, z});
        }
        Pole pl = poles.get(key(w.getName(), c[0], c[1], c[2]));
        if (pl != null && validPole(w, pl))
            for (Pole o : linkedPoles(pl)) out.add(new int[]{o.x, o.top, o.z});
        return out;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPolePlace(BlockPlaceEvent e) {
        Block top = e.getBlockPlaced();
        if (top.getType().name().endsWith("COPPER_CHAIN")) {
            cables.add(key(top.getWorld().getName(), top.getX(), top.getY(), top.getZ()));
            invalidate();
            return;
        }
        if (!isPoleTop(top.getType())) return;
        World w = top.getWorld();
        int y = top.getY() - 1, wood = 0;
        while (wood < 8 && isWood(w.getBlockAt(top.getX(), y, top.getZ()).getType())) { wood++; y--; }
        if (wood == 0 || !isPoleBase(w.getBlockAt(top.getX(), y, top.getZ()).getType())) return;
        Pole pl = new Pole(w.getName(), top.getX(), top.getZ(), y, top.getY());
        addPole(pl);
        invalidate();
        List<Pole> links = linkedPoles(pl);
        Player p = e.getPlayer();
        p.sendMessage(Component.text("⚡ Poste montado. Liga em outros postes a ate " + (int) POLE_RANGE + " blocos ("
                + links.size() + " ao alcance). Puxe energia com cabo ou juncao encostado em qualquer parte dele.", NamedTextColor.AQUA));
        List<double[]> pts = new ArrayList<>();
        for (Pole o : links) pts.addAll(wire(pl.x, pl.top, pl.z, o.x, o.top, o.z));
        for (int k = 0; k < 4; k++)
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) fx.flash(p, pts, Color.fromRGB(80, 230, 255)); }, k * 15L);
    }

    void invalidate() { netCache.clear(); pathCache.clear(); pointCache.clear(); lastInvalidate = System.currentTimeMillis(); }

    /** O bloco conduz energia? cabo, maquina ou alavanca ligada encostada na rede. */
    private boolean conducts(World w, int x, int y, int z) {
        String k = key(w.getName(), x, y, z);
        if (devices.containsKey(k) || poleBlocks.containsKey(k)) return true;
        if (cables.contains(k)) {
            if (w.isChunkLoaded(x >> 4, z >> 4) && !cableBlock(w.getBlockAt(x, y, z).getType())) { cables.remove(k); return false; }
            return true;
        }
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
        Block b = w.getBlockAt(x, y, z);
        // qualquer corrente de cobre e cabo, mesmo sem o item da bancada (pick block, encerada na mao etc.)
        if (b.getType().name().endsWith("COPPER_CHAIN")) { cables.add(k); return true; }
        return b.getType() == Material.LEVER && b.getBlockData() instanceof org.bukkit.block.data.Powerable pw && pw.isPowered();
    }

    Net net(Device d) {
        Net cached = netCache.get(d.key());
        if (cached != null) return cached;
        Net n = new Net();
        World w = Bukkit.getWorld(d.world);
        if (w == null) { n.devs.add(d); return n; }
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(new int[]{d.x, d.y, d.z});
        seen.add(d.key());
        while (!q.isEmpty() && seen.size() < MAX_NET_NODES) {
            int[] c = q.poll();
            n.nodes.add(c);
            Device dev = devices.get(key(d.world, c[0], c[1], c[2]));
            if (dev != null) n.devs.add(dev);
            for (int[] nb : neighbors(w, c)) {
                if (Math.abs(nb[0] - c[0]) + Math.abs(nb[1] - c[1]) + Math.abs(nb[2] - c[2]) > 1)
                    n.wires.addAll(wire(c[0], c[1], c[2], nb[0], nb[1], nb[2])); // fio entre postes
                String k = key(d.world, nb[0], nb[1], nb[2]);
                if (seen.contains(k)) continue;
                seen.add(k);
                q.add(nb);
            }
        }
        for (Device m : n.devs) netCache.put(m.key(), n);
        return n;
    }

    /** Caminho pelos cabos entre duas maquinas da mesma rede (BFS), para o pulso de particulas. */
    private final Map<String, List<double[]>> pointCache = new HashMap<>();

    private List<double[]> cablePath(Device a, Device b) {
        String pk = a.key() + ">" + b.key();
        if (pointCache.containsKey(pk)) return pointCache.get(pk);
        World w = Bukkit.getWorld(a.world);
        if (w == null) return null;
        Map<String, String> parent = new HashMap<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{a.x, a.y, a.z});
        parent.put(a.key(), "");
        String goal = b.key();
        while (!q.isEmpty() && parent.size() < MAX_NET_NODES) {
            int[] c = q.poll();
            String ck = key(a.world, c[0], c[1], c[2]);
            if (ck.equals(goal)) break;
            for (int[] nb : neighbors(w, c)) {
                String k = key(a.world, nb[0], nb[1], nb[2]);
                if (parent.containsKey(k)) continue;
                // maquinas no meio do caminho nao sao atravessadas pelo pulso
                if (!k.equals(goal) && devices.containsKey(k)) continue;
                parent.put(k, ck);
                q.add(nb);
            }
        }
        if (!parent.containsKey(goal)) { pointCache.put(pk, null); return null; }
        LinkedList<int[]> nodes = new LinkedList<>();
        for (String k = goal; !k.isEmpty(); k = parent.get(k)) {
            String[] sp = k.split(":");
            int n = sp.length;
            nodes.addFirst(new int[]{Integer.parseInt(sp[n - 3]), Integer.parseInt(sp[n - 2]), Integer.parseInt(sp[n - 1])});
        }
        List<double[]> pts = new ArrayList<>();
        int[] prev = null;
        for (int[] c : nodes) {
            if (prev != null && Math.abs(c[0] - prev[0]) + Math.abs(c[1] - prev[1]) + Math.abs(c[2] - prev[2]) > 1)
                pts.addAll(wire(prev[0], prev[1], prev[2], c[0], c[1], c[2]));
            pts.add(new double[]{c[0] + 0.5, c[1] + 0.5, c[2] + 0.5});
            prev = c;
        }
        pointCache.put(pk, pts);
        return pts;
    }

    private void pulse(Device from, Device to, Color c) {
        World w = Bukkit.getWorld(from.world);
        if (w != null) fx.pulse(w, cablePath(from, to), c);
    }

    static final Color AC = Color.fromRGB(255, 150, 30), DC = Color.fromRGB(80, 200, 255), CHARGE = Color.fromRGB(255, 220, 50);

    /** Fonte de CA para torres e baterias. Painel solar nao entra: e CC. */
    private static boolean acGenerator(Device d) {
        return d.enabled && (d.type.equals("carvao") || d.type.equals("geotermico") || d.type.equals("inversor"));
    }

    private void tickPower() {
        if (lastPos.size() > 2000) lastPos.clear();
        if (System.currentTimeMillis() - lastInvalidate > 30_000) invalidate(); // pega mudancas feitas por fora
        List<Device> gens = new ArrayList<>(), inverters = new ArrayList<>();
        for (Iterator<Device> it = devices.values().iterator(); it.hasNext(); ) {
            Device d = it.next();
            if (!d.isSource()) continue;
            World w = Bukkit.getWorld(d.world);
            if (!loaded(d, w)) continue;
            Block b = w.getBlockAt(d.x, d.y, d.z);
            if (!valid(d, b)) { it.remove(); removeTag(d); invalidate(); continue; }
            if (d.type.equals("bateria")) continue;
            if (d.type.equals("inversor")) { inverters.add(d); continue; }
            if (d.type.equals("carvao")) {
                if (d.burn > 0 && d.enabled) d.burn--;
                if (d.enabled && d.burn <= 0 && d.energy < d.cap() && b.getState() instanceof org.bukkit.block.Furnace f) {
                    ItemStack fuel = f.getInventory().getFuel();
                    int secs = fuel == null ? 0 : fuelSeconds(fuel.getType());
                    if (secs > 0) {
                        fuel.setAmount(fuel.getAmount() - 1);
                        f.getInventory().setFuel(fuel.getAmount() > 0 ? fuel : null);
                        d.burn = secs;
                    }
                }
                if (d.enabled && d.burn > 0 && b.getState() instanceof org.bukkit.block.Furnace fresh && fresh.getBurnTime() < 30) {
                    // mantem a fornalha acesa (chama e luz vanilla) enquanto o gerador queima
                    fresh.setBurnTime((short) 40);
                    fresh.update(true, false);
                }
            }
            d.lastRate = d.enabled ? rate(d, b) : 0;
            d.energy = Math.min(d.cap(), d.energy + d.lastRate);
            if (!d.type.equals("solar")) gens.add(d);
        }
        // inversores puxam CC dos paineis solares da rede e entregam CA
        for (Device inv : inverters) {
            inv.lastRate = 0;
            World w = Bukkit.getWorld(inv.world);
            if (inv.enabled) {
                double want = Math.min(INVERTER_RATE, (inv.cap() - inv.energy) / INVERTER_EFF);
                Device firstPanel = null;
                for (Device sp : net(inv).devs) {
                    if (want <= 0) break;
                    if (!sp.type.equals("solar") || !sp.enabled || sp.energy <= 0) continue;
                    double take = Math.min(sp.energy, want);
                    sp.energy -= take; want -= take;
                    inv.energy += take * INVERTER_EFF; inv.lastRate += take * INVERTER_EFF;
                    if (firstPanel == null) firstPanel = sp;
                }
                if (firstPanel != null) pulse(firstPanel, inv, DC);
            }
            if (w != null && loaded(inv, w)) {
                Block b = w.getBlockAt(inv.x, inv.y, inv.z);
                if (b.getBlockData() instanceof org.bukkit.block.data.Lightable l && l.isLit() != (inv.lastRate > 0)) {
                    l.setLit(inv.lastRate > 0);
                    b.setBlockData(l, false);
                }
            }
            gens.add(inv);
        }
        // torres ligadas pagam manutencao todo segundo; sem pagar, ficam desligadas
        powerTicks++;
        for (Device f : devices.values()) if (f.type.equals("forja")) tickForge(f);
        for (Device t : devices.values()) {
            if (!t.type.equals("torre")) continue;
            World w = Bukkit.getWorld(t.world);
            if (!t.enabled || t.barrel <= 0 || !loaded(t, w)) { t.powered = false; continue; }
            t.powered = drain(t, upkeep(plugin.level(t.owner)), powerTicks % 3 == 0);
        }
        // geradores despejam o excedente nas baterias da mesma rede
        for (Device g : gens) {
            if (g.energy <= 0 || !acGenerator(g)) continue;
            for (Device bt : net(g).devs) {
                if (!bt.type.equals("bateria") || !bt.enabled || bt.mode == 2) continue;
                double move = Math.min(g.energy, bt.cap() - bt.energy);
                if (move <= 0) continue;
                g.energy -= move; bt.energy += move;
                pulse(g, bt, CHARGE);
                if (g.energy <= 0) break;
            }
        }
        refreshLive();
        updateTags();
    }

    /** Pepita vanilla que a forja aceita e a bala que sai dela. */
    private String forgeOutput(ItemStack in) {
        if (in == null || typeOf(in) != null || in.getAmount() < FORGE_IN) return null;
        return in.getType() == Material.IRON_NUGGET ? "bala_ferro" : in.getType() == Material.COPPER_NUGGET ? "bala_cobre" : null;
    }

    private void tickForge(Device f) {
        f.lastRate = 0;
        World w = Bukkit.getWorld(f.world);
        if (!f.enabled || !loaded(f, w)) return;
        Block b = w.getBlockAt(f.x, f.y, f.z);
        if (!valid(f, b) || !(b.getState() instanceof org.bukkit.block.Furnace fu)) return;
        org.bukkit.inventory.FurnaceInventory inv = fu.getInventory();
        String out = forgeOutput(inv.getSmelting());
        ItemStack res = inv.getResult();
        boolean room = out != null && (res == null || res.getType() == Material.AIR
                || (out.equals(typeOf(res)) && res.getAmount() + FORGE_OUT <= res.getMaxStackSize()));
        if (!room) { f.burn = 0; return; }
        if (!drain(f, FORGE_RATE, powerTicks % 3 == 0)) return; // sem energia: pausa, mantem o progresso
        f.lastRate = FORGE_RATE;
        f.burn++;
        Location c = f.center(w);
        if (fu.getBurnTime() < 30) { fu.setBurnTime((short) 40); fu.update(true, false); }
        w.spawnParticle(Particle.LAVA, c.clone().add(0, 0.6, 0), 1, 0.2, 0.1, 0.2, 0);
        if (f.burn < FORGE_TIME) { w.playSound(c, Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE, 0.6f, 1f); return; }
        f.burn = 0;
        // re-le o estado depois do update para nao perder itens colocados por funil
        if (!(b.getState() instanceof org.bukkit.block.Furnace fresh)) return;
        inv = fresh.getInventory();
        ItemStack in = inv.getSmelting();
        if (!out.equals(forgeOutput(in))) return;
        in.setAmount(in.getAmount() - FORGE_IN);
        inv.setSmelting(in.getAmount() > 0 ? in : null);
        ItemStack r = inv.getResult();
        if (r == null || r.getType() == Material.AIR) { r = item(out); r.setAmount(FORGE_OUT); }
        else r.setAmount(r.getAmount() + FORGE_OUT);
        inv.setResult(r);
        w.playSound(c, Sound.BLOCK_ANVIL_USE, 0.5f, 1.4f);
        w.spawnParticle(Particle.FLAME, c.clone().add(0, 0.6, 0), 8, 0.2, 0.2, 0.2, 0.02);
    }

    /** Fontes que podem pagar um tiro: geradores CA primeiro, depois baterias que fornecem. */
    private List<Device> sourcesFor(Device t) {
        List<Device> out = new ArrayList<>();
        for (Device d : net(t).devs) {
            if (acGenerator(d)) out.add(d);
            else if (d.type.equals("bateria") && d.enabled && d.mode != 1) out.add(d);
        }
        out.sort(Comparator.comparingInt(d -> d.type.equals("bateria") ? 1 : 0));
        return out;
    }

    private double networkEnergy(Device t, World w) {
        double s = 0;
        for (Device d : sourcesFor(t)) s += d.energy;
        return s;
    }

    private boolean drain(Device t, double amount) { return drain(t, amount, true); }

    private boolean drain(Device t, double amount, boolean show) {
        List<Device> src = sourcesFor(t);
        double total = 0;
        for (Device d : src) total += d.energy;
        if (total < amount) return false;
        for (Device d : src) {
            double take = Math.min(d.energy, amount);
            if (take <= 0) continue;
            d.energy -= take; amount -= take;
            if (show) pulse(d, t, AC);
            if (amount <= 0) break;
        }
        return true;
    }

    private String netSummary(Net n) {
        int gen = n.count("carvao") + n.count("geotermico");
        return gen + " geradores, " + n.count("solar") + " paineis, " + n.count("inversor") + " inversores, "
                + n.count("bateria") + " baterias, " + n.count("torre") + " torres";
    }

    // ---------- menu das maquinas ----------

    static final class MachineMenu implements InventoryHolder {
        Inventory inv; String key;
        @Override public Inventory getInventory() { return inv; }
    }

    static final String[] BAT_MODES = {"Carrega e fornece", "So carrega", "So fornece"};

    void openMachine(Player p, Device d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null) return;
        MachineMenu h = new MachineMenu();
        h.key = d.key();
        Inventory inv = Bukkit.createInventory(h, 27, Component.text(label(d.type)));
        h.inv = inv;
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        Net n = net(d);
        List<String> info = new ArrayList<>();
        if (d.isSource()) info.add("Energia: " + (int) d.energy + "/" + (int) d.cap());
        if (!d.type.equals("bateria") && !d.type.equals("forja")) info.add(String.format(Locale.ROOT, "Gerando: %.1f/s", d.lastRate));
        if (d.type.equals("carvao")) info.add("Queimando: " + d.burn + "s");
        if (d.type.equals("forja")) { info.clear(); info.add(d.lastRate > 0 ? "Forjando: " + d.burn + "/" + FORGE_TIME + "s" : "Parada"); info.add("Consome " + (int) FORGE_RATE + "/s trabalhando"); info.add("Energia na rede: " + (int) networkEnergy(d, w)); }
        if (d.type.equals("solar") && n.count("inversor") == 0) info.add("SEM INVERSOR: energia parada");
        if (d.type.equals("inversor")) info.add("Paineis na rede: " + n.count("solar"));
        OfflinePlayer owner = Bukkit.getOfflinePlayer(d.owner);
        info.add("Dono: " + (owner.getName() == null ? "?" : owner.getName()));
        inv.setItem(4, icon(item(d.type).getType(), label(d.type), NamedTextColor.GOLD, info.toArray(new String[0])));

        inv.setItem(10, icon(d.enabled ? Material.LIME_DYE : Material.GRAY_DYE, d.enabled ? "Ligado" : "Desligado",
                d.enabled ? NamedTextColor.GREEN : NamedTextColor.RED, "Clique para " + (d.enabled ? "desligar" : "ligar") + "."));
        if (d.type.equals("carvao"))
            inv.setItem(12, icon(Material.COAL, "Combustivel", NamedTextColor.WHITE, "Abre a fornalha para colocar", "carvao, bloco de carvao, vara", "de blaze ou bloco de alga seca."));
        if (d.type.equals("forja"))
            inv.setItem(12, icon(Material.IRON_NUGGET, "Abrir forja", NamedTextColor.WHITE, "Pepitas de ferro ou cobre em cima,", "balas saem no resultado.", "9 pepitas = " + FORGE_OUT + " balas."));
        if (d.type.equals("bateria"))
            inv.setItem(12, icon(Material.COMPARATOR, "Modo: " + BAT_MODES[d.mode], NamedTextColor.YELLOW,
                    "Carrega e fornece: normal.", "So carrega: guarda para depois.", "So fornece: nao aceita carga.", "", "Clique para trocar."));
        inv.setItem(14, icon(d.tagOn ? Material.NAME_TAG : Material.PAPER, "Etiqueta: " + (d.tagOn ? "ligada" : "desligada"),
                d.tagOn ? NamedTextColor.GREEN : NamedTextColor.GRAY, "Clique para " + (d.tagOn ? "desligar" : "ligar") + "."));
        inv.setItem(16, icon(Material.WAXED_COPPER_CHAIN, "Mostrar rede", NamedTextColor.AQUA,
                netSummary(n), "", "Acende os cabos da rede", "por alguns segundos."));
        p.openInventory(inv);
    }

    private void showNet(Player p, Device d) {
        Net n = net(d);
        List<double[]> pts = new ArrayList<>(n.wires);
        for (int[] c : n.nodes) pts.add(new double[]{c[0] + 0.5, c[1] + 0.5, c[2] + 0.5});
        for (int k = 0; k < 4; k++)
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) fx.flash(p, pts, Color.fromRGB(80, 230, 255)); }, k * 15L);
    }

    @EventHandler
    public void onMachineClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MachineMenu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        Device d = devices.get(h.key);
        if (d == null) { p.closeInventory(); return; }
        World w = Bukkit.getWorld(d.world);
        switch (e.getRawSlot()) {
            case 10 -> d.enabled = !d.enabled;
            case 12 -> {
                if (d.type.equals("bateria")) d.mode = (d.mode + 1) % BAT_MODES.length;
                else if ((d.type.equals("carvao") || d.type.equals("forja")) && w != null && w.getBlockAt(d.x, d.y, d.z).getState() instanceof Container c) { p.openInventory(c.getInventory()); return; }
                else return;
            }
            case 14 -> d.tagOn = !d.tagOn;
            case 16 -> { p.closeInventory(); showNet(p, d); return; }
            default -> { return; }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        openMachine(p, d);
    }

    @EventHandler
    public void onMachineDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof MachineMenu) e.setCancelled(true); }

    // ---------- torres ----------

    /** {flechas, bolas de fogo, balas} */
    private int[] ammo(Block b) {
        int[] n = new int[3];
        if (!(b.getState() instanceof Container c)) return n;
        for (ItemStack it : c.getInventory().getContents()) {
            int k = ammoKind(it);
            if (k > 0) n[k - 1] += it.getAmount();
        }
        return n;
    }

    /** 0 nada, 1 flecha, 2 bola de fogo, 3 bala. */
    private int ammoKind(ItemStack it) {
        if (it == null) return 0;
        String t = typeOf(it);
        if (t != null) return t.startsWith("bala") ? 3 : 0;
        if (isArrow(it.getType())) return 1;
        return it.getType() == Material.FIRE_CHARGE ? 2 : 0;
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
            if (!t.type.equals("torre") || !t.enabled || !t.powered || t.barrel <= 0 || t.nextShot > tick) continue;
            World w = Bukkit.getWorld(t.world);
            if (!loaded(t, w)) continue;
            Block b = w.getBlockAt(t.x, t.y, t.z);
            if (!valid(t, b)) { it.remove(); removeTag(t); invalidate(); continue; }
            int lvl = plugin.level(t.owner);
            int range = RANGE[lvl - 1];
            Location c = t.center(w);

            if (!(b.getState() instanceof Container cont)) continue;
            ItemStack ammo = null; int slot = -1;
            ItemStack[] inv = cont.getInventory().getContents();
            for (int pass = 0; pass < 2 && ammo == null; pass++)
                for (int i = 0; i < inv.length; i++) {
                    int kind = ammoKind(inv[i]);
                    if (kind == 0) continue;
                    boolean wanted = t.ammoPref == 0 || t.ammoPref == kind;
                    if (pass == 0 && !wanted) continue; // primeiro a preferida, depois qualquer uma
                    ammo = inv[i]; slot = i; break;
                }
            if (ammo == null) { t.nextShot = tick + 40; continue; }
            boolean fireball = ammo.getType() == Material.FIRE_CHARGE;
            String bullet = typeOf(ammo); // bala_ferro, bala_cobre ou null
            boolean iron = "bala_ferro".equals(bullet);
            double speed = fireball ? 1.8 : bullet != null ? 3.5 : 2.6;

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
                best = le; bestAim = aimPoint(le, c, speed, !fireball && bullet == null);
                break;
            }
            if (best == null) continue;
            int shots = lvl >= 5 ? 2 : 1;
            int use = bullet != null ? Math.min(BURST * shots, ammo.getAmount()) : 1;
            double cost = fireball ? FIREBALL_COST : bullet == null ? SHOT_COST : use * (iron ? IRON_COST : COPPER_COST);
            if (!drain(t, cost)) { t.nextShot = tick + 40; w.spawnParticle(Particle.SMOKE, c.clone().add(0, 0.6, 0), 3, 0.1, 0.1, 0.1, 0.01); continue; }

            ItemStack shot = ammo.clone(); shot.setAmount(1);
            ammo.setAmount(ammo.getAmount() - use);
            cont.getInventory().setItem(slot, ammo.getAmount() > 0 ? ammo : null);

            t.barrel = Math.max(0, t.barrel - shots * (fireball || iron ? 2 : 1)); // bala: por rajada
            if (t.barrel == 0) {
                w.playSound(c, Sound.ENTITY_ITEM_BREAK, 1f, 0.7f);
                w.spawnParticle(Particle.LARGE_SMOKE, c.clone().add(0, 0.6, 0), 10, 0.2, 0.2, 0.2, 0.02);
                Player o = Bukkit.getPlayer(t.owner);
                if (o != null) o.sendMessage(Component.text("⚠ O cano de uma Torre Sentinela gastou (" + t.x + " " + t.y + " " + t.z + "). Troque pelo menu dela.", NamedTextColor.RED));
            }
            if (fireball) {
                for (int s = 0; s < shots; s++) fireball(w, c, bestAim, t, s == 0 ? 0 : 0.08);
                w.playSound(c, Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.2f);
            } else if (bullet != null) {
                double dmg = (iron ? IRON_DMG : COPPER_DMG) + 0.25 * (lvl - 1);
                LivingEntity target = best;
                for (int s = 0; s < use; s++) {
                    double jit = s == 0 ? 0 : 0.04;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!target.isValid()) return;
                        Vector aim = aimPoint(target, c, 3.5, false);
                        bullet(w, c, aim, t, shot, dmg, jit);
                        w.playSound(c, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.5f, iron ? 1.6f : 1.9f);
                        w.spawnParticle(Particle.SMOKE, c.clone().add(aim.clone().subtract(c.toVector()).normalize().multiply(0.9)), 2, 0.05, 0.05, 0.05, 0.01);
                    }, s * 2L);
                }
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

    private final NamespacedKey bulletKey() { return new NamespacedKey(plugin, "bullet-dmg"); }

    /** Bala: projetil reto e rapido (sem gravidade); o dano e aplicado no impacto. */
    private void bullet(World w, Location c, Vector aim, Device t, ItemStack look, double dmg, double jitter) {
        Vector dir = aim.clone().subtract(c.toVector()).normalize();
        if (jitter > 0) dir.add(new Vector(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5).multiply(jitter)).normalize();
        Location muzzle = c.clone().add(dir.clone().multiply(0.9));
        Snowball sb = w.spawn(muzzle, Snowball.class, f -> {
            f.setItem(look);
            f.setGravity(false);
            f.setVelocity(dir.clone().multiply(3.5));
            f.getPersistentDataContainer().set(arrowKey, PersistentDataType.STRING, t.owner.toString());
            f.getPersistentDataContainer().set(bulletKey(), PersistentDataType.DOUBLE, dmg);
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (sb.isValid()) sb.remove(); }, 30L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBulletHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        Double dmg = e.getEntity().getPersistentDataContainer().get(bulletKey(), PersistentDataType.DOUBLE);
        if (dmg == null) return;
        e.setCancelled(true);
        Projectile pr = e.getEntity();
        Location at = pr.getLocation();
        if (e.getHitEntity() instanceof LivingEntity le && le instanceof Enemy) {
            le.setNoDamageTicks(0);
            le.damage(dmg, pr);
            at.getWorld().spawnParticle(Particle.CRIT, le.getLocation().add(0, le.getHeight() * 0.6, 0), 6, 0.2, 0.2, 0.2, 0.1);
        } else if (e.getHitBlock() != null) {
            at.getWorld().spawnParticle(Particle.BLOCK, at, 6, 0.05, 0.05, 0.05, e.getHitBlock().getBlockData());
        }
        pr.remove();
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
                if (!d.enabled) state = Component.text("DESLIGADA", NamedTextColor.GRAY, TextDecoration.BOLD);
                else if (d.barrel <= 0) state = Component.text("CANO GASTO", NamedTextColor.RED, TextDecoration.BOLD);
                else if (sourcesFor(d).isEmpty()) state = Component.text("SEM REDE", NamedTextColor.RED, TextDecoration.BOLD);
                else if (am[0] + am[1] + am[2] == 0) state = Component.text("SEM MUNICAO", NamedTextColor.RED, TextDecoration.BOLD);
                else if (!d.powered || net < SHOT_COST) state = Component.text("SEM ENERGIA", NamedTextColor.RED, TextDecoration.BOLD);
                else state = Component.text("⚡ " + (int) net + " na rede  ", NamedTextColor.YELLOW)
                        .append(Component.text(String.format(Locale.ROOT, "-%.2f/s", upkeep(lvl)), NamedTextColor.RED));
                return name.append(nl).append(Component.text("Nivel " + lvl + "  •  alcance " + RANGE[lvl - 1], g))
                        .append(nl).append(Component.text("➶ " + am[0] + " flechas  ☄ " + am[1] + " fogo  • " + am[2] + " balas  ", NamedTextColor.WHITE))
                        .append(Component.text("cano " + (100 * d.barrel / BARREL_MAX) + "%", d.barrel > BARREL_MAX / 5 ? NamedTextColor.GRAY : NamedTextColor.RED))
                        .append(nl).append(state);
            }
            default -> {
                Component line = Component.text("⚡ " + (int) d.energy + "/" + (int) d.cap() + "  ", NamedTextColor.YELLOW)
                        .append(Component.text(bar(d.energy, d.cap()), d.energy > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
                Component out = name.append(nl).append(line);
                if (!d.enabled) return out.append(nl).append(Component.text("DESLIGADO", NamedTextColor.GRAY, TextDecoration.BOLD));
                if (!d.type.equals("bateria")) {
                    String idle = switch (d.type) { case "carvao" -> "sem combustivel"; case "inversor" -> "sem energia solar"; default -> "parado"; };
                    out = out.append(nl).append(d.lastRate > 0
                            ? Component.text(String.format(Locale.ROOT, "+%.1f/s", d.lastRate), NamedTextColor.GREEN)
                            : Component.text(idle, NamedTextColor.RED));
                    if (d.type.equals("carvao") && d.burn > 0) out = out.append(Component.text("  (" + d.burn + "s)", g));
                } else if (d.mode != 0) out = out.append(nl).append(Component.text(BAT_MODES[d.mode], g));
                Net n = net(d);
                int users = n.count("torre") + n.count("bateria") - (d.type.equals("bateria") ? 1 : 0);
                return out.append(nl).append(n.devs.size() <= 1
                        ? Component.text("sem cabo", NamedTextColor.RED)
                        : Component.text("rede: " + (n.devs.size() - 1) + " maquinas", g));
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
                .append(nl).append(net(g.get(0)).count("inversor") == 0
                        ? Component.text("SEM INVERSOR (CC)", NamedTextColor.RED, TextDecoration.BOLD)
                        : Component.text("ligado ao inversor", NamedTextColor.AQUA));
    }

    private void updateTags() {
        Set<Device> followers = new HashSet<>();
        Map<Device, List<Device>> clusters = solarClusters(followers);
        for (Device d : devices.values()) {
            World w = Bukkit.getWorld(d.world);
            if (!loaded(d, w)) { d.tag = null; continue; }
            if (followers.contains(d) || !d.tagOn) { removeTag(d); continue; }
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

    static final String[] CATS = {"Zumbis", "Esqueletos", "Creepers", "Aranhas", "Mobs do Nether", "Outros", "Aldeoes zumbis"};
    static final Material[] CAT_ICON = {Material.ZOMBIE_HEAD, Material.SKELETON_SKULL, Material.CREEPER_HEAD,
            Material.SPIDER_EYE, Material.BLAZE_POWDER, Material.PHANTOM_MEMBRANE, Material.ZOMBIE_VILLAGER_SPAWN_EGG};
    static final String[] PRIOS = {"Mais perto", "Menos vida", "Mais vida"};
    static final String[] AMMOS = {"Qualquer", "Flechas", "Bolas de fogo", "Balas"};

    static int category(LivingEntity e) {
        if (e instanceof PigZombie || e instanceof PiglinAbstract || e instanceof Blaze || e instanceof Ghast
                || e instanceof MagmaCube || e instanceof Hoglin || e instanceof Zoglin) return 4;
        if (e instanceof ZombieVillager) return 6;
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
                "Dono: " + (owner.getName() == null ? "?" : owner.getName()) + " (Engenharia nivel " + lvl + ")",
                "Alcance " + RANGE[lvl - 1] + " blocos, " + String.format(Locale.ROOT, "%.1f", 20.0 / COOLDOWN[lvl - 1]) + " tiros/s",
                "Energia na rede: " + (int) net,
                String.format(Locale.ROOT, "Consumo ligada: %.2f/s + %d por tiro", upkeep(lvl), (int) SHOT_COST),
                d.enabled && !d.powered ? "SEM ENERGIA: nao esta atirando" : ""));
        inv.setItem(10, icon(Material.CHEST, "Municao", NamedTextColor.WHITE,
                am[0] + " flechas, " + am[1] + " bolas de fogo, " + am[2] + " balas", "", "Clique para abrir e colocar", "flechas, bolas de fogo ou balas."));
        Material am2 = switch (d.ammoPref) { case 1 -> Material.ARROW; case 2 -> Material.FIRE_CHARGE; case 3 -> Material.IRON_NUGGET; default -> Material.BOW; };
        inv.setItem(12, icon(am2, "Municao preferida: " + AMMOS[d.ammoPref], NamedTextColor.YELLOW,
                "Usa esta primeiro. Se acabar,", "usa a outra.", "", "Clique para trocar."));
        inv.setItem(14, icon(Material.TARGET, "Prioridade: " + PRIOS[d.prio], NamedTextColor.YELLOW,
                "Qual alvo a torre escolhe", "entre os que estao a vista.", "", "Clique para trocar."));
        inv.setItem(16, icon(d.tagOn ? Material.NAME_TAG : Material.PAPER, "Etiqueta: " + (d.tagOn ? "ligada" : "desligada"),
                d.tagOn ? NamedTextColor.GREEN : NamedTextColor.GRAY, "Texto flutuante com municao", "e energia em cima da torre.", "", "Clique para " + (d.tagOn ? "desligar" : "ligar") + "."));
        for (int i = 0; i < CATS.length; i++) {
            boolean on = (d.filter & (1 << i)) != 0;
            inv.setItem(28 + i, icon(on ? CAT_ICON[i] : Material.BARRIER, CATS[i] + ": " + (on ? "atirando" : "ignorando"),
                    on ? NamedTextColor.GREEN : NamedTextColor.RED, "Clique para " + (on ? "ignorar" : "atirar") + "."));
        }
        int pct = 100 * d.barrel / BARREL_MAX;
        inv.setItem(20, icon(d.barrel > 0 ? Material.IRON_BARS : Material.BARRIER, "Cano: " + d.barrel + "/" + BARREL_MAX + " tiros (" + pct + "%)",
                d.barrel > BARREL_MAX / 5 ? NamedTextColor.GREEN : NamedTextColor.RED,
                bar(d.barrel, BARREL_MAX), "Cada tiro gasta 1; bola de fogo e", "bala de ferro gastam 2.", "Gasto: a torre para de atirar.", "",
                "Clique com um Cano da Torre no", "inventario para trocar (+" + XP_REPAIR + " XP)."));
        inv.setItem(22, icon(Material.OAK_SIGN, "Filtro de mobs", NamedTextColor.WHITE, "Escolha abaixo em quem", "a torre atira."));
        inv.setItem(38, icon(d.enabled ? Material.LIME_DYE : Material.GRAY_DYE, d.enabled ? "Ligada" : "Desligada",
                d.enabled ? NamedTextColor.GREEN : NamedTextColor.RED, "Clique para " + (d.enabled ? "desligar" : "ligar") + "."));
        inv.setItem(40, icon(Material.ENDER_EYE, "Mostrar alcance", NamedTextColor.LIGHT_PURPLE, "Desenha o alcance no chao", "por 3 segundos."));
        inv.setItem(42, icon(Material.WAXED_COPPER_CHAIN, "Mostrar rede", NamedTextColor.AQUA, netSummary(net(d)), "", "Acende os cabos da rede."));
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
            case 20 -> {
                if (!takeOne(p, "cano")) {
                    p.sendMessage(Component.text("Voce precisa de um Cano da Torre (Bancada de Engenharia).", NamedTextColor.RED));
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                    return;
                }
                d.barrel = BARREL_MAX;
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.3f);
                p.sendMessage(Component.text("⚙ Cano trocado.", NamedTextColor.GOLD));
                plugin.addXp(p.getUniqueId(), XP_REPAIR);
                invalidate();
            }
            case 40 -> { p.closeInventory(); showRange(p, d, RANGE[plugin.level(d.owner) - 1], Color.fromRGB(230, 60, 40)); return; }
            case 38 -> d.enabled = !d.enabled;
            case 42 -> { p.closeInventory(); showNet(p, d); return; }
            default -> {
                int i = raw - 28;
                if (i < 0 || i >= CATS.length) return;
                d.filter ^= 1 << i;
            }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        openTurret(p, d);
    }

    private boolean takeOne(Player p, String type) {
        ItemStack[] st = p.getInventory().getStorageContents();
        for (int i = 0; i < st.length; i++) {
            if (st[i] == null || !type.equals(typeOf(st[i]))) continue;
            st[i].setAmount(st[i].getAmount() - 1);
            if (st[i].getAmount() <= 0) st[i] = null;
            p.getInventory().setStorageContents(st);
            return true;
        }
        return false;
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
        try { plugin.addXp(UUID.fromString(owner), XP_KILL); } catch (IllegalArgumentException ignored) { }
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
        cables.addAll(y.getStringList("cables"));
        for (String ps : y.getStringList("poles")) {
            try {
                String[] a = ps.split(";");
                addPole(new Pole(a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4])));
            } catch (Exception ex) { plugin.getLogger().warning("poste " + ps + ": " + ex.getMessage()); }
        }
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
                d.enabled = c.getBoolean("on", true);
                d.mode = c.getInt("mode");
                d.filter = c.getInt("filter", 0b1111111);
                d.prio = c.getInt("prio");
                d.ammoPref = c.getInt("ammo");
                d.barrel = c.getInt("barrel", BARREL_MAX);
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
            y.set(k + ".tag", d.tagOn); y.set(k + ".on", d.enabled); y.set(k + ".mode", d.mode);
            if (d.type.equals("torre")) {
                y.set(k + ".filter", d.filter);
                y.set(k + ".prio", d.prio); y.set(k + ".ammo", d.ammoPref);
                y.set(k + ".barrel", d.barrel);
            }
        }
        y.set("cables", new ArrayList<>(cables));
        y.set("poles", poles.values().stream().map(Pole::save).toList());
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("devices.yml: " + ex.getMessage()); }
    }
}
