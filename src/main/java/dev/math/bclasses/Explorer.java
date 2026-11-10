package dev.math.bclasses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Exploracao: XP por chunk novo visitado na vida, companheiro (lobo, raposa ou gato) invocado
 * num ritual de velas, bonus de saturacao com carne e a Ancora de Perola (teleporte entre 2 pontos).
 */
final class Explorer implements Listener {

    static final int[] RANGE = {0, 500, 1000, 2000, 4000};   // alcance da ancora por nivel
    static final int ANCHOR_LEVEL = 2;
    static final int BLOCKS_PER_LEVEL = 150;                   // custo em niveis de XP por distancia
    static final long TP_COOLDOWN_MS = 120_000;
    static final int CHANNEL_TICKS = 60;

    enum Kind {
        LOBO("Lobo", Material.BONE_BLOCK, Material.BONE, NamedTextColor.GRAY),
        RAPOSA("Raposa", Material.HAY_BLOCK, Material.SWEET_BERRIES, NamedTextColor.GOLD),
        GATO("Gato", Material.WHITE_WOOL, Material.COD, NamedTextColor.YELLOW);
        final String label; final Material altar, icon; final NamedTextColor color;
        Kind(String label, Material altar, Material icon, NamedTextColor color) { this.label = label; this.altar = altar; this.icon = icon; this.color = color; }
    }

    record Cost(String label, Set<Material> mats, int amount) { }

    static final Set<Material> RAW_MEAT = EnumSet.of(Material.BEEF, Material.PORKCHOP, Material.MUTTON, Material.CHICKEN, Material.RABBIT);
    static final Set<Material> MEAT = EnumSet.of(Material.BEEF, Material.COOKED_BEEF, Material.PORKCHOP, Material.COOKED_PORKCHOP,
            Material.MUTTON, Material.COOKED_MUTTON, Material.CHICKEN, Material.COOKED_CHICKEN, Material.RABBIT, Material.COOKED_RABBIT, Material.RABBIT_STEW);

    static final class PetData {
        Kind kind; UUID pet; boolean alive; boolean stay; int deaths;
        Inventory pack; ItemStack[] packSaved;
        final Set<String> chunks = new HashSet<>();
        long howlAt;
    }

    record Anchor(String world, int x, int y, int z, UUID owner) {
        String key() { return world + ":" + x + ":" + y + ":" + z; }
        Location top(World w) { return new Location(w, x + 0.5, y + 1, z + 0.5); }
    }

    static final class Channel { Location start; Anchor to; int ticks; BukkitTask task; }

    static final class PackHolder implements InventoryHolder {
        final UUID owner; Inventory inv;
        PackHolder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inv; }
    }

    static final class Panel implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final BabylonClasses plugin;
    private final NamespacedKey petKey, itemKey, recipeKey;
    private final Map<UUID, PetData> data = new HashMap<>();
    private final Map<String, Anchor> anchors = new HashMap<>();
    private final Map<UUID, Channel> channels = new HashMap<>();
    private final Map<UUID, Long> tpCooldown = new HashMap<>();
    private final Map<UUID, Location> lastSpot = new HashMap<>();
    private final File file;

    Explorer(BabylonClasses plugin) {
        this.plugin = plugin;
        this.petKey = new NamespacedKey(plugin, "pet-owner");
        this.itemKey = new NamespacedKey(plugin, "explorer-item");
        this.recipeKey = new NamespacedKey(plugin, "ancora");
        this.file = new File(plugin.getDataFolder(), "explorer.yml");
    }

    void enable() {
        load();
        ShapedRecipe r = new ShapedRecipe(recipeKey, anchorItem());
        r.shape(" P ", "OCO", "OOO");
        r.setIngredient('P', Material.ENDER_PEARL);
        r.setIngredient('O', Material.OBSIDIAN);
        r.setIngredient('C', Material.COMPASS);
        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(r);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickPets, 20L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickSlow, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 1200L, 1200L);
    }

    void discover(Player p) { p.discoverRecipe(recipeKey); }

    PetData pd(UUID id) { return data.computeIfAbsent(id, k -> new PetData()); }

    int level(UUID id) { return plugin.explorerLevel(id); }

    static int packSize(int lvl) { return lvl >= 5 ? 27 : lvl >= 3 ? 18 : 9; }

    String levelSummary(int lvl) {
        String s = "Nivel " + lvl + ": pet com mais vida";
        if (lvl >= ANCHOR_LEVEL) s += ", Ancora de Perola (alcance " + RANGE[lvl - 1] + ")";
        s += ", mochila da raposa " + packSize(lvl);
        if (lvl >= 4) s += ", uivo do lobo";
        return s;
    }

    /** Nova vida: zera chunks, pet, mochila e ancoras. */
    void resetLife(UUID id) {
        PetData d = data.remove(id);
        if (d != null && d.pet != null) { Entity e = Bukkit.getEntity(d.pet); if (e != null) e.remove(); }
        anchors.values().removeIf(a -> a.owner.equals(id));
    }

    // ---------- XP por chunk novo ----------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if ((f.getBlockX() >> 4) == (t.getBlockX() >> 4) && (f.getBlockZ() >> 4) == (t.getBlockZ() >> 4) && f.getWorld() == t.getWorld()) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
        if (!BabylonClasses.isRunWorld(t.getWorld())) return;
        String k = t.getWorld().getName() + ":" + (t.getBlockX() >> 4) + ":" + (t.getBlockZ() >> 4);
        if (pd(p.getUniqueId()).chunks.add(k)) plugin.addExp(p.getUniqueId(), 1);
    }

    // ---------- bonus de carne ----------

    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (!MEAT.contains(e.getItem().getType())) return;
        Player p = e.getPlayer();
        float bonus = 0.6f * level(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + bonus)));
    }

    // ---------- ritual ----------

    private List<Cost> costs(Kind k, boolean revive) {
        Cost gem = revive ? new Cost("Totem da imortalidade", EnumSet.of(Material.TOTEM_OF_UNDYING), 1)
                : new Cost("Diamante", EnumSet.of(Material.DIAMOND), 1);
        return switch (k) {
            case LOBO -> List.of(new Cost("Osso", EnumSet.of(Material.BONE), 1), new Cost("Carne crua (qualquer)", RAW_MEAT, 8), gem);
            case RAPOSA -> List.of(new Cost("Frutas doces", EnumSet.of(Material.SWEET_BERRIES), 16), new Cost("Bau", EnumSet.of(Material.CHEST), 1), gem);
            case GATO -> List.of(new Cost("Bacalhau cru", EnumSet.of(Material.COD), 8), new Cost("Linha", EnumSet.of(Material.STRING), 1), gem);
        };
    }

    /** Ja teve pet nesta vida e ele morreu: reviver/invocar custa totem. */
    private boolean needsTotem(PetData d) { return d.deaths > 0 && !d.alive; }

    int xpCost(PetData d) { return 10 + 5 * d.deaths; }

    private static boolean isCandle(Block b) {
        String n = b.getType().name();
        return (n.equals("CANDLE") || n.endsWith("_CANDLE")) && b.getBlockData() instanceof Lightable l && l.isLit();
    }

    private static int count(Player p, Set<Material> mats) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it != null && mats.contains(it.getType()) && !(it.hasItemMeta() && it.getItemMeta().hasDisplayName())) n += it.getAmount();
        return n;
    }

    private static void take(Player p, Set<Material> mats, int amount) {
        ItemStack[] st = p.getInventory().getStorageContents();
        for (int i = 0; i < st.length && amount > 0; i++) {
            ItemStack it = st[i];
            if (it == null || !mats.contains(it.getType()) || (it.hasItemMeta() && it.getItemMeta().hasDisplayName())) continue;
            int t = Math.min(amount, it.getAmount());
            it.setAmount(it.getAmount() - t); amount -= t;
            if (it.getAmount() <= 0) st[i] = null;
        }
        p.getInventory().setStorageContents(st);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAltar(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        Kind kind = null;
        for (Kind k : Kind.values()) if (k.altar == b.getType()) kind = k;
        if (kind == null) return;
        Block[] candles = {b.getRelative(1, 0, 1), b.getRelative(1, 0, -1), b.getRelative(-1, 0, 1), b.getRelative(-1, 0, -1)};
        for (Block c : candles) if (!isCandle(c)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!BabylonClasses.isRunWorld(p.getWorld())) { p.sendMessage(Component.text("O ritual so funciona no mundo da run.", NamedTextColor.RED)); return; }
        PetData d = pd(p.getUniqueId());
        Entity cur = d.pet == null ? null : Bukkit.getEntity(d.pet);
        if (d.alive && d.kind == kind) { p.sendMessage(Component.text("Seu " + kind.label.toLowerCase(Locale.ROOT) + " ja esta vivo.", NamedTextColor.GRAY)); return; }
        boolean revive = needsTotem(d);
        List<Cost> cost = costs(kind, revive);
        int xp = xpCost(d);
        List<String> missing = new ArrayList<>();
        for (Cost c : cost) { int has = count(p, c.mats()); if (has < c.amount()) missing.add((c.amount() - has) + "x " + c.label()); }
        if (p.getLevel() < xp) missing.add((xp - p.getLevel()) + " niveis de XP");
        if (!missing.isEmpty()) {
            p.sendMessage(Component.text("O ritual de " + kind.label + " pede ainda: " + String.join(", ", missing) + ".", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_CANDLE_EXTINGUISH, 0.8f, 0.8f);
            return;
        }
        for (Cost c : cost) take(p, c.mats(), c.amount());
        p.setLevel(p.getLevel() - xp);
        for (Block c : candles) if (c.getBlockData() instanceof Lightable l) { l.setLit(false); c.setBlockData(l); }

        // troca de especie: o antigo vai embora e a mochila cai no altar
        if (d.alive && d.kind != kind) {
            if (cur != null) cur.remove();
            dropPack(d, b.getLocation().add(0.5, 1.2, 0.5));
        }
        if (d.kind != kind) dropPack(d, b.getLocation().add(0.5, 1.2, 0.5));
        Location at = b.getLocation().add(0.5, 1, 0.5);
        World w = b.getWorld();
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 60, 0.6, 0.8, 0.6, 0.03);
        w.spawnParticle(Particle.END_ROD, at, 30, 0.4, 1, 0.4, 0.05);
        w.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 0.8f);
        w.playSound(at, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.7f, 1.4f);
        Kind fk = kind;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            summon(p, d, fk, at);
            p.sendMessage(Component.text("✦ " + (revive ? "Seu companheiro voltou: " : "Novo companheiro: ") + fk.label + "!", fk.color, TextDecoration.BOLD));
        }, 30L);
    }

    private void dropPack(PetData d, Location at) {
        ItemStack[] items = d.pack != null ? d.pack.getContents() : d.packSaved;
        if (items != null) for (ItemStack it : items) if (it != null && it.getType() != Material.AIR) at.getWorld().dropItemNaturally(at, it);
        if (d.pack != null) d.pack.clear();
        d.packSaved = null;
    }

    private void summon(Player p, PetData d, Kind k, Location at) {
        World w = at.getWorld();
        Class<? extends LivingEntity> cls = switch (k) { case LOBO -> Wolf.class; case RAPOSA -> Fox.class; case GATO -> Cat.class; };
        LivingEntity e = w.spawn(at, cls, en -> {
            en.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, p.getUniqueId().toString());
            en.setPersistent(true);
            en.setRemoveWhenFarAway(false);
            en.customName(Component.text(k.label + " de " + p.getName(), k.color));
            en.setCustomNameVisible(false);
            if (en instanceof Tameable t) { t.setTamed(true); t.setOwner(p); }
            if (en instanceof Fox f) { f.setFirstTrustedPlayer(p); f.setFoxType(Fox.Type.RED); }
            if (en instanceof Ageable a) a.setAdult();
        });
        d.kind = k; d.pet = e.getUniqueId(); d.alive = true; d.stay = false;
        applyStats(e, k, level(p.getUniqueId()));
        e.setHealth(maxHealth(e));
        save();
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private void applyStats(LivingEntity e, Kind k, int lvl) {
        double hp = switch (k) { case LOBO -> 40 + 5 * (lvl - 1); case RAPOSA, GATO -> 14 + 4 * (lvl - 1); };
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        if (a != null && a.getBaseValue() != hp) a.setBaseValue(hp);
        if (k == Kind.LOBO) {
            AttributeInstance d = e.getAttribute(Attribute.ATTACK_DAMAGE);
            if (d != null) d.setBaseValue(4 + (lvl - 1));
        }
        if (e.getHealth() > maxHealth(e)) e.setHealth(maxHealth(e));
    }

    private UUID ownerOf(Entity e) {
        String s = e.getPersistentDataContainer().get(petKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    @EventHandler
    public void onPetDeath(EntityDeathEvent e) {
        UUID owner = ownerOf(e.getEntity());
        if (owner == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        PetData d = data.get(owner);
        if (d == null || !e.getEntity().getUniqueId().equals(d.pet)) return;
        if (d.pack != null) d.packSaved = d.pack.getContents();
        d.alive = false; d.deaths++;
        Player p = Bukkit.getPlayer(owner);
        if (p != null) {
            p.sendMessage(Component.text("☠ Seu " + d.kind.label.toLowerCase(Locale.ROOT) + " morreu.", NamedTextColor.RED, TextDecoration.BOLD));
            p.sendMessage(Component.text("Refaca o ritual com um Totem da imortalidade no lugar do diamante (" + xpCost(d) + " niveis). A mochila fica guardada.", NamedTextColor.GRAY));
        }
        save();
    }

    /** Pet que nao e mais o atual (trocou de especie, morreu, nova vida) some ao carregar. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            UUID owner = ownerOf(en);
            if (owner == null) continue;
            PetData d = data.get(owner);
            if (d == null || !d.alive || !en.getUniqueId().equals(d.pet)) en.remove();
        }
    }

    // ---------- comportamento ----------

    private void tickPets() {
        for (Map.Entry<UUID, PetData> en : data.entrySet()) {
            PetData d = en.getValue();
            if (!d.alive || d.kind != Kind.RAPOSA || d.pet == null) continue;
            if (!(Bukkit.getEntity(d.pet) instanceof Fox f) || f.isDead()) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            if (f.isSleeping()) f.setSleeping(false);
            if (p == null || d.stay) { f.setSitting(d.stay); continue; }
            f.setSitting(false);
            if (p.getWorld() != f.getWorld()) continue;
            double dist = p.getLocation().distanceSquared(f.getLocation());
            if (dist > 24 * 24 && p.isOnGround()) { f.teleport(p.getLocation()); f.getPathfinder().stopPathfinding(); }
            else if (dist > 9) f.getPathfinder().moveTo(p, dist > 100 ? 1.6 : 1.25);
            else f.getPathfinder().stopPathfinding();
            // pega itens do chao perto dela
            Inventory pack = pack(en.getKey(), d);
            for (Entity it : f.getNearbyEntities(3, 2, 3)) {
                if (!(it instanceof Item item) || item.getPickupDelay() > 0 || en.getKey().equals(item.getThrower())) continue;
                ItemStack st = item.getItemStack();
                Map<Integer, ItemStack> left = pack.addItem(st.clone());
                if (left.isEmpty()) item.remove();
                else { int l = left.values().iterator().next().getAmount(); if (l == st.getAmount()) continue; st.setAmount(l); item.setItemStack(st); }
                f.getWorld().playSound(f.getLocation(), Sound.ENTITY_FOX_EAT, 0.4f, 1.5f);
            }
        }
    }

    private void tickSlow() {
        for (Map.Entry<UUID, PetData> en : data.entrySet()) {
            PetData d = en.getValue();
            if (!d.alive || d.pet == null) continue;
            if (!(Bukkit.getEntity(d.pet) instanceof LivingEntity e) || e.isDead()) continue;
            int lvl = level(en.getKey());
            applyStats(e, d.kind, lvl);
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || d.kind != Kind.GATO || p.getWorld() != e.getWorld()) continue;
            // gato: dono parado do lado regenera
            Location last = lastSpot.put(p.getUniqueId(), p.getLocation());
            boolean still = last != null && last.getWorld() == p.getWorld() && last.distanceSquared(p.getLocation()) < 0.04;
            if (still && e.getLocation().distanceSquared(p.getLocation()) < 25)
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, lvl >= 5 ? 1 : 0, true, false, true));
        }
    }

    /** Lobo nivel 4+: ao morder, uiva e marca os hostis em volta. */
    @EventHandler(ignoreCancelled = true)
    public void onWolfBite(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Wolf w)) return;
        UUID owner = ownerOf(w);
        if (owner == null || level(owner) < 4) return;
        PetData d = data.get(owner);
        long now = System.currentTimeMillis();
        if (d == null || now - d.howlAt < 20_000) return;
        d.howlAt = now;
        w.getWorld().playSound(w.getLocation(), Sound.ENTITY_WOLF_ANGRY_GROWL, 1f, 0.8f);
        for (Entity en : w.getNearbyEntities(16, 8, 16))
            if (en instanceof Enemy && en instanceof LivingEntity le) le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 160, 0, false, false));
    }

    // ---------- mochila da raposa ----------

    private Inventory pack(UUID owner, PetData d) {
        int size = packSize(level(owner));
        if (d.pack == null || d.pack.getSize() != size) {
            ItemStack[] old = d.pack != null ? d.pack.getContents() : d.packSaved;
            PackHolder h = new PackHolder(owner);
            Inventory inv = Bukkit.createInventory(h, size, Component.text("Mochila da Raposa"));
            h.inv = inv;
            if (old != null) for (ItemStack it : old) if (it != null) for (ItemStack l : inv.addItem(it).values()) {
                Player p = Bukkit.getPlayer(owner);
                if (p != null) p.getWorld().dropItemNaturally(p.getLocation(), l);
            }
            if (d.pack != null) for (HumanEntity v : new ArrayList<>(d.pack.getViewers())) v.closeInventory();
            d.pack = inv; d.packSaved = null;
        }
        return d.pack;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPetClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Fox f)) return;
        UUID owner = ownerOf(f);
        if (owner == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!owner.equals(p.getUniqueId())) { p.sendActionBar(Component.text("Essa raposa nao e sua.", NamedTextColor.GRAY)); return; }
        PetData d = pd(owner);
        if (p.isSneaking()) {
            d.stay = !d.stay;
            p.sendActionBar(Component.text(d.stay ? "A raposa vai ficar aqui." : "A raposa vai te seguir.", NamedTextColor.GOLD));
            return;
        }
        p.openInventory(pack(owner, d));
        p.playSound(p.getLocation(), Sound.ENTITY_FOX_AMBIENT, 0.6f, 1.2f);
    }

    @EventHandler
    public void onPackClose(InventoryCloseEvent e) { if (e.getInventory().getHolder() instanceof PackHolder) save(); }

    // ---------- Ancora de Perola ----------

    ItemStack anchorItem() {
        ItemStack it = new ItemStack(Material.CRYING_OBSIDIAN);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Ancora de Perola", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : List.of("Coloque duas: elas se ligam sozinhas.", "Clique numa segurando perola do End", "para ir ate a outra (3 s parado).",
                "Custa 1 perola + 1 nivel de XP", "a cada " + BLOCKS_PER_LEVEL + " blocos. Recarga 2 min.", "Exploracao nivel " + ANCHOR_LEVEL + "+."))
            l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "ancora");
        it.setItemMeta(m);
        return it;
    }

    private boolean isAnchorItem(ItemStack it) {
        return it != null && it.hasItemMeta() && "ancora".equals(it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING));
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!isAnchorItem(e.getInventory().getResult())) return;
        if (!(e.getView().getPlayer() instanceof Player p) || level(p.getUniqueId()) < ANCHOR_LEVEL) e.getInventory().setResult(null);
    }

    private List<Anchor> anchorsOf(UUID owner) {
        List<Anchor> l = new ArrayList<>();
        for (Anchor a : anchors.values()) if (a.owner.equals(owner)) l.add(a);
        return l;
    }

    private Anchor other(Anchor a) {
        for (Anchor b : anchors.values()) if (b != a && b.owner.equals(a.owner)) return b;
        return null;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onAnchorPlace(BlockPlaceEvent e) {
        if (!isAnchorItem(e.getItemInHand())) return;
        Player p = e.getPlayer();
        if (level(p.getUniqueId()) < ANCHOR_LEVEL) { e.setCancelled(true); p.sendMessage(Component.text("Precisa de Exploracao nivel " + ANCHOR_LEVEL + ".", NamedTextColor.RED)); return; }
        if (anchorsOf(p.getUniqueId()).size() >= 2) { e.setCancelled(true); p.sendMessage(Component.text("Voce ja tem 2 ancoras. Quebre uma para mudar de lugar.", NamedTextColor.RED)); return; }
        Block b = e.getBlockPlaced();
        Anchor a = new Anchor(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), p.getUniqueId());
        anchors.put(a.key(), a);
        Anchor o = other(a);
        if (o == null) p.sendMessage(Component.text("⚓ Ancora colocada. Coloque a segunda para ligar as duas.", NamedTextColor.LIGHT_PURPLE));
        else p.sendMessage(Component.text("⚓ Ancoras ligadas" + (o.world.equals(a.world) ? " a " + (int) dist(a, o) + " blocos." : ", mas em dimensoes diferentes: nao funciona."), NamedTextColor.LIGHT_PURPLE));
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 0.8f, 1.2f);
        save();
    }

    private static double dist(Anchor a, Anchor b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Anchor anchorAt(Block b) { return anchors.get(b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ()); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onAnchorBreak(BlockBreakEvent e) {
        Anchor a = anchorAt(e.getBlock());
        if (a == null) return;
        e.setDropItems(false);
        anchors.remove(a.key());
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE)
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), anchorItem());
        save();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnchorUse(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Anchor a = anchorAt(e.getClickedBlock());
        if (a == null) return;
        Player p = e.getPlayer();
        Anchor o = other(a);
        boolean pearl = p.getInventory().getItemInMainHand().getType() == Material.ENDER_PEARL;
        if (pearl) e.setCancelled(true);
        if (o == null) { p.sendActionBar(Component.text("Ancora sem par: coloque a segunda.", NamedTextColor.GRAY)); return; }
        if (!o.world.equals(a.world)) { p.sendActionBar(Component.text("A outra ancora esta em outra dimensao.", NamedTextColor.RED)); return; }
        double dist = dist(a, o);
        int range = RANGE[level(a.owner) - 1];
        int cost = Math.max(1, (int) Math.ceil(dist / BLOCKS_PER_LEVEL));
        if (!pearl) {
            p.sendActionBar(Component.text("⚓ Ligada a " + o.x + " " + o.y + " " + o.z + " (" + (int) dist + " blocos): 1 perola + " + cost + " niveis", NamedTextColor.LIGHT_PURPLE));
            return;
        }
        if (dist > range) { p.sendMessage(Component.text("Distancia " + (int) dist + " passa do alcance " + range + " (nivel de Exploracao do dono).", NamedTextColor.RED)); return; }
        long cd = tpCooldown.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        if (cd > 0) { p.sendActionBar(Component.text("Recarga: " + (cd / 1000 + 1) + " s", NamedTextColor.RED)); return; }
        if (p.getLevel() < cost && p.getGameMode() != GameMode.CREATIVE) { p.sendMessage(Component.text("Precisa de " + cost + " niveis de XP.", NamedTextColor.RED)); return; }
        if (channels.containsKey(p.getUniqueId())) return;
        Channel c = new Channel();
        c.start = p.getLocation(); c.to = o;
        channels.put(p.getUniqueId(), c);
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.5f, 1.6f);
        c.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> channelTick(p, c, cost), 5L, 5L);
    }

    private void cancelChannel(Player p, String why) {
        Channel c = channels.remove(p.getUniqueId());
        if (c == null) return;
        c.task.cancel();
        if (why != null && p.isOnline()) p.sendActionBar(Component.text("Teleporte cancelado: " + why, NamedTextColor.RED));
    }

    private void channelTick(Player p, Channel c, int cost) {
        if (!p.isOnline() || p.isDead()) { cancelChannel(p, null); return; }
        if (p.getWorld() != c.start.getWorld() || p.getLocation().distanceSquared(c.start) > 0.36) { cancelChannel(p, "voce se mexeu."); return; }
        c.ticks += 5;
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.4);
        p.sendActionBar(Component.text("⚓ Teleportando... " + String.format(Locale.ROOT, "%.1f", (CHANNEL_TICKS - c.ticks) / 20.0) + " s", NamedTextColor.LIGHT_PURPLE));
        if (c.ticks < CHANNEL_TICKS) return;
        cancelChannel(p, null);
        World w = Bukkit.getWorld(c.to.world);
        if (w == null || anchorAt(w.getBlockAt(c.to.x, c.to.y, c.to.z)) == null) { p.sendMessage(Component.text("A ancora de destino sumiu.", NamedTextColor.RED)); return; }
        Location dest = c.to.top(w);
        if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()) { p.sendMessage(Component.text("Destino bloqueado: libere 2 blocos em cima da ancora.", NamedTextColor.RED)); return; }
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        if (!creative) {
            if (count(p, EnumSet.of(Material.ENDER_PEARL)) < 1) { p.sendMessage(Component.text("Voce precisa de uma perola do End.", NamedTextColor.RED)); return; }
            if (p.getLevel() < cost) { p.sendMessage(Component.text("Precisa de " + cost + " niveis de XP.", NamedTextColor.RED)); return; }
            take(p, EnumSet.of(Material.ENDER_PEARL), 1);
            p.setLevel(p.getLevel() - cost);
        }
        dest.setYaw(p.getLocation().getYaw()); dest.setPitch(p.getLocation().getPitch());
        Location from = p.getLocation();
        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        p.teleport(dest);
        w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
        w.spawnParticle(Particle.REVERSE_PORTAL, dest.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.05);
        tpCooldown.put(p.getUniqueId(), System.currentTimeMillis() + TP_COOLDOWN_MS);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && channels.containsKey(p.getUniqueId())) cancelChannel(p, "voce levou dano.");
    }

    // ---------- painel ----------

    private static ItemStack icon(Material mat, String name, NamedTextColor color, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(name, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, s.startsWith("+") ? NamedTextColor.GREEN : s.startsWith("!") ? NamedTextColor.RED : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        m.lore(l);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    void openPanel(Player p) {
        Panel h = new Panel();
        Inventory inv = Bukkit.createInventory(h, 45, Component.text("Exploracao"));
        h.inv = inv;
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < 45; i++) inv.setItem(i, filler);
        UUID id = p.getUniqueId();
        int lvl = level(id), xp = plugin.data(id).exp;
        PetData d = pd(id);
        String next = lvl >= 5 ? "Nivel maximo" : "XP " + xp + "/" + BabylonClasses.EXP_XP[lvl] + " para o nivel " + (lvl + 1);
        inv.setItem(4, icon(Material.COMPASS, "Exploracao - nivel " + lvl, NamedTextColor.AQUA, List.of(
                next, "Chunks visitados nesta vida: " + d.chunks.size(), "", "+1 XP por chunk novo (mundo da run).",
                "Carne da +" + String.format(Locale.ROOT, "%.1f", 0.6 * lvl) + " de saturacao extra.", "", "O nivel zera quando a vida acaba.")));

        boolean revive = needsTotem(d);
        int slot = 10;
        for (Kind k : Kind.values()) {
            List<String> lore = new ArrayList<>();
            switch (k) {
                case LOBO -> lore.addAll(List.of("Combate: segue, morde quem voce", "ataca e quem te ataca.", "Vida e dano sobem com o nivel.", "Nivel 4: uivo marca os hostis.", "Aceita armadura de lobo."));
                case RAPOSA -> lore.addAll(List.of("Coleta: mochila de " + packSize(lvl) + " espacos", "(9 / 18 no nivel 3 / 27 no 5).", "Pega itens do chao perto dela.", "Clique: abre a mochila.", "Agachado + clique: fica / segue."));
                case GATO -> lore.addAll(List.of("Sobrevivencia: creepers e", "phantoms fogem dele.", "Parado do lado dele voce", "regenera vida (mais no nivel 5).", "Traz presentes de manha."));
            }
            lore.add("");
            lore.add("Ritual: " + pretty(k.altar) + " no centro,");
            lore.add("4 velas acesas nas diagonais.");
            lore.add("Clique no centro com:");
            for (Cost c : costs(k, revive)) lore.add((count(p, c.mats()) >= c.amount() ? "+ " : "! ") + c.amount() + "x " + c.label());
            lore.add((p.getLevel() >= xpCost(d) ? "+ " : "! ") + xpCost(d) + " niveis de XP");
            if (d.alive && d.kind == k) { lore.add(""); lore.add("+ Seu companheiro atual"); }
            else if (d.alive) { lore.add(""); lore.add("! Troca o " + d.kind.label.toLowerCase(Locale.ROOT) + " atual (mochila cai)."); }
            inv.setItem(slot, icon(k.icon, k.label, k.color, lore));
            slot += 2;
        }
        List<String> st = new ArrayList<>();
        if (d.kind == null) st.add("Nenhum ainda. Faca um ritual.");
        else {
            st.add(d.kind.label + (d.alive ? ": vivo" : ": morto"));
            if (d.alive && d.pet != null && Bukkit.getEntity(d.pet) instanceof LivingEntity le)
                st.add("Vida " + (int) le.getHealth() + "/" + (int) maxHealth(le));
            if (!d.alive) st.add("! Reviver: totem + " + xpCost(d) + " niveis");
            st.add("Mortes nesta vida: " + d.deaths);
        }
        inv.setItem(16, icon(Material.LEAD, "Seu companheiro", NamedTextColor.WHITE, st));

        List<Anchor> mine = anchorsOf(id);
        List<String> al = new ArrayList<>(List.of("Receita (mesa de trabalho):", "  _ P _", "  O B O", "  O O O",
                "P = perola do End, B = bussola,", "O = obsidiana", "",
                "Duas ancoras se ligam sozinhas.", "Clique numa com perola na mao:", "3 s parado (dano cancela).",
                "Custo: 1 perola + 1 nivel a cada " + BLOCKS_PER_LEVEL, "blocos. Recarga 2 min. Mesma dimensao.", ""));
        if (lvl < ANCHOR_LEVEL) al.add("! Libera no nivel " + ANCHOR_LEVEL);
        else al.add("+ Alcance " + RANGE[lvl - 1] + " blocos. Colocadas: " + mine.size() + "/2");
        inv.setItem(22, icon(Material.CRYING_OBSIDIAN, "Ancora de Perola", NamedTextColor.LIGHT_PURPLE, al));

        Material[] mats = {Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS, Material.IRON_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS};
        for (int i = 0; i < 5; i++) {
            int L = i + 1;
            boolean got = lvl >= L;
            List<String> lore = new ArrayList<>();
            lore.add(L == 1 ? "Inicial" : "Precisa de " + BabylonClasses.EXP_XP[i] + " XP");
            lore.add("");
            lore.add("+ Pet mais forte, carne +" + String.format(Locale.ROOT, "%.1f", 0.6 * L));
            if (L == 1) lore.add("+ Ritual do companheiro");
            if (L == 2) lore.add("+ Ancora de Perola (500 blocos)");
            if (L == 3) lore.add("+ Alcance 1000, mochila 18");
            if (L == 4) lore.add("+ Alcance 2000, uivo do lobo");
            if (L == 5) lore.add("+ Alcance 4000, mochila 27, regen II");
            lore.add("");
            lore.add(got ? "Liberado" : "Bloqueado");
            inv.setItem(29 + i, icon(got ? mats[i] : Material.BLACK_STAINED_GLASS_PANE, "Nivel " + L, got ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY, lore));
        }
        inv.setItem(36, icon(Material.ARROW, "Voltar", NamedTextColor.WHITE, List.of("Volta para as habilidades.")));
        p.openInventory(inv);
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
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

    @SuppressWarnings("unchecked")
    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String s : y.getStringList("anchors")) {
            try {
                String[] a = s.split(";");
                Anchor an = new Anchor(a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), UUID.fromString(a[4]));
                anchors.put(an.key(), an);
            } catch (Exception ex) { plugin.getLogger().warning("ancora " + s + ": " + ex.getMessage()); }
        }
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps == null) return;
        for (String k : ps.getKeys(false)) {
            try {
                ConfigurationSection c = ps.getConfigurationSection(k);
                PetData d = new PetData();
                String kind = c.getString("kind");
                d.kind = kind == null ? null : Kind.valueOf(kind);
                String pet = c.getString("pet");
                d.pet = pet == null ? null : UUID.fromString(pet);
                d.alive = c.getBoolean("alive");
                d.stay = c.getBoolean("stay");
                d.deaths = c.getInt("deaths");
                d.chunks.addAll(c.getStringList("chunks"));
                List<?> pack = c.getList("pack");
                if (pack != null) d.packSaved = pack.stream().map(o -> o instanceof ItemStack i ? i : null).toArray(ItemStack[]::new);
                data.put(UUID.fromString(k), d);
            } catch (Exception ex) { plugin.getLogger().warning("explorer " + k + ": " + ex.getMessage()); }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("anchors", anchors.values().stream().map(a -> a.world + ";" + a.x + ";" + a.y + ";" + a.z + ";" + a.owner).toList());
        for (Map.Entry<UUID, PetData> en : data.entrySet()) {
            PetData d = en.getValue();
            String k = "players." + en.getKey();
            if (d.kind != null) y.set(k + ".kind", d.kind.name());
            if (d.pet != null) y.set(k + ".pet", d.pet.toString());
            y.set(k + ".alive", d.alive);
            y.set(k + ".stay", d.stay);
            y.set(k + ".deaths", d.deaths);
            y.set(k + ".chunks", new ArrayList<>(d.chunks));
            ItemStack[] items = d.pack != null ? d.pack.getContents() : d.packSaved;
            if (items != null) y.set(k + ".pack", Arrays.asList(items));
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("explorer.yml: " + ex.getMessage()); }
    }
}
