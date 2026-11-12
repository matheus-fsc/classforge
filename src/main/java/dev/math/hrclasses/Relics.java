package dev.math.hrclasses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Livros Antigos (Exploracao): encantamentos que so aparecem em baus de estrutura gerados pelo mapa,
 * com chance pelo nivel de Exploracao de quem abre o bau. Clique com o livro sobre o item para aplicar.
 *  - Veio (picareta, I-III): agachado, minera o veio inteiro do minerio (6/12/20 blocos).
 *  - Replantio (enxada, I): colher plantacao madura replanta usando uma semente do proprio drop.
 *  - Decapitacao (espada/machado, I-III): 4/8/12% de chance de dropar a cabeca do mob ou jogador.
 *  - Protecao Antiga (armadura, I-III): cada nivel somado nas pecas da 4% de chance de cortar o golpe pela metade (max 24%).
 */
final class Relics implements Listener {

    record Relic(String id, String name, int max, String target, String[] desc) {}

    static final Relic[] RELICS = {
            new Relic("veio", "Veio", 3, "picareta", new String[]{"Agachado, minera o veio inteiro", "do minerio: 6 / 12 / 20 blocos.", "Gasta durabilidade por bloco."}),
            new Relic("replantio", "Replantio", 1, "enxada", new String[]{"Colher plantacao madura replanta", "sozinho, usando uma semente", "do proprio drop."}),
            new Relic("decapitacao", "Decapitacao", 3, "espada ou machado", new String[]{"Chance de dropar a cabeca", "de quem voce mata: 4 / 8 / 12%."}),
            new Relic("protecao", "Protecao Antiga", 3, "armadura", new String[]{"Cada nivel nas pecas vestidas da 4%", "de chance de cortar o golpe pela", "metade (maximo 24%)."}),
            new Relic("lenhador", "Lenhador", 1, "machado", new String[]{"Agachado, derruba o tronco inteiro", "da arvore (ate 48 blocos).", "Gasta durabilidade por bloco."}),
            new Relic("colheita", "Colheita", 1, "enxada", new String[]{"Colher plantacao madura colhe", "tambem as maduras em volta (3x3)."}),
            new Relic("arado", "Arado", 1, "enxada", new String[]{"Arar a terra ara 3x3 de uma vez.", "Gasta durabilidade por bloco."}),
            new Relic("sabedoria", "Sabedoria", 3, "picareta", new String[]{"Minerios dao mais XP:", "+50% / +100% / +150%."}),
            new Relic("visao", "Visao Noturna", 1, "capacete", new String[]{"Visao noturna enquanto veste."}),
            new Relic("sereia", "Sereia", 1, "capacete", new String[]{"Respira debaixo d'agua enquanto veste."}),
            new Relic("molas", "Molas", 2, "botas", new String[]{"Pulo um pouco maior enquanto veste:", "pulo I / pulo II."}),
            new Relic("implantes", "Implantes", 1, "capacete", new String[]{"Andando, a fome enche aos poucos", "(1 ponto a cada 6 s)."}),
    };
    static int[] VEIN = {0, 6, 12, 20};

    private final HardcoreRouletteClasses plugin;
    private final NamespacedKey bookKey;
    private final Map<String, NamespacedKey> keys = new HashMap<>();
    private final Set<UUID> veining = new HashSet<>();

    Relics(HardcoreRouletteClasses plugin) {
        this.plugin = plugin;
        bookKey = new NamespacedKey(plugin, "relic_book");
        for (Relic r : RELICS) keys.put(r.id(), new NamespacedKey(plugin, "relic_" + r.id()));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::wornTick, 40L, 40L);
    }

    static Relic relic(String id) { for (Relic r : RELICS) if (r.id().equals(id)) return r; return null; }

    static String roman(int n) { return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; default -> String.valueOf(n); }; }

    private static Component plain(String s, NamedTextColor c) { return Component.text(s, c).decoration(TextDecoration.ITALIC, false); }

    // ---------- livro ----------

    ItemStack book(String id, int lvl) {
        Relic r = relic(id);
        if (r == null) return null;
        lvl = Math.max(1, Math.min(r.max(), lvl));
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta m = it.getItemMeta();
        m.displayName(plain("Livro Antigo: " + r.name() + (r.max() > 1 ? " " + roman(lvl) : ""), NamedTextColor.LIGHT_PURPLE));
        List<Component> lore = new ArrayList<>();
        lore.add(plain("Para: " + r.target(), NamedTextColor.YELLOW));
        for (String d : r.desc()) lore.add(plain(d, NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(plain("Pegue o livro e clique com ele", NamedTextColor.DARK_GRAY));
        lore.add(plain("sobre o item no inventario.", NamedTextColor.DARK_GRAY));
        m.lore(lore);
        m.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, id + ":" + lvl);
        it.setItemMeta(m);
        return it;
    }

    private static boolean fits(String id, Material m) {
        String n = m.name();
        return switch (id) {
            case "veio" -> Tag.ITEMS_PICKAXES.isTagged(m);
            case "replantio" -> Tag.ITEMS_HOES.isTagged(m);
            case "decapitacao" -> Tag.ITEMS_SWORDS.isTagged(m) || Tag.ITEMS_AXES.isTagged(m);
            case "protecao" -> n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
            case "lenhador" -> Tag.ITEMS_AXES.isTagged(m);
            case "colheita", "arado" -> Tag.ITEMS_HOES.isTagged(m);
            case "sabedoria" -> Tag.ITEMS_PICKAXES.isTagged(m);
            case "visao", "sereia", "implantes" -> n.endsWith("_HELMET") || m == Material.TURTLE_HELMET;
            case "molas" -> n.endsWith("_BOOTS");
            default -> false;
        };
    }

    int level(ItemStack it, String id) {
        if (Settings.BOOKS_OFF.contains(id)) return 0;
        if (it == null || !it.hasItemMeta()) return 0;
        Integer v = it.getItemMeta().getPersistentDataContainer().get(keys.get(id), PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    /** Clicar com o livro (no cursor) sobre um item do inventario aplica o encantamento. */
    @EventHandler(ignoreCancelled = true)
    public void onApply(InventoryClickEvent e) {
        ItemStack cursor = e.getCursor(), target = e.getCurrentItem();
        if (cursor == null || cursor.getType() != Material.ENCHANTED_BOOK || !cursor.hasItemMeta()) return;
        String tag = cursor.getItemMeta().getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
        if (tag == null || target == null || target.getType().isAir() || !(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory().getType() != InventoryType.PLAYER) return;
        String[] parts = tag.split(":");
        Relic r = relic(parts[0]);
        int lvl = Integer.parseInt(parts[1]);
        if (r == null || target.getType() == Material.ENCHANTED_BOOK) return;
        e.setCancelled(true);
        if (!fits(r.id(), target.getType())) { p.sendActionBar(plain(r.name() + " so vai em " + r.target() + ".", NamedTextColor.RED)); return; }
        if (level(target, r.id()) >= lvl) { p.sendActionBar(plain("Esse item ja tem " + r.name() + " igual ou melhor.", NamedTextColor.RED)); return; }
        ItemMeta m = target.getItemMeta();
        m.getPersistentDataContainer().set(keys.get(r.id()), PersistentDataType.INTEGER, lvl);
        m.setEnchantmentGlintOverride(true);
        m.lore(withRelicLore(m));
        target.setItemMeta(m);
        e.setCurrentItem(target);
        cursor.setAmount(cursor.getAmount() - 1);
        p.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        p.sendActionBar(plain(r.name() + (r.max() > 1 ? " " + roman(lvl) : "") + " aplicado.", NamedTextColor.LIGHT_PURPLE));
    }

    /** Lore: linhas dos encantamentos antigos no topo, o resto do lore original embaixo. */
    private List<Component> withRelicLore(ItemMeta m) {
        Set<String> names = new HashSet<>();
        for (Relic r : RELICS) names.add(r.name());
        List<Component> rest = new ArrayList<>();
        if (m.lore() != null) for (Component c : m.lore()) {
            String s = PlainTextComponentSerializer.plainText().serialize(c);
            String head = s.replaceAll(" (I|II|III)$", "");
            if (!names.contains(head)) rest.add(c);
        }
        List<Component> out = new ArrayList<>();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        for (Relic r : RELICS) {
            Integer v = pdc.get(keys.get(r.id()), PersistentDataType.INTEGER);
            if (v != null && v > 0) out.add(plain(r.name() + (r.max() > 1 ? " " + roman(v) : ""), NamedTextColor.LIGHT_PURPLE));
        }
        out.addAll(rest);
        return out;
    }

    // ---------- onde aparece: baus de estrutura ----------

    static double findChance(int explorerLevel) { return Settings.BOOK_BASE + Settings.BOOK_PER_LEVEL * explorerLevel; } // nivel 1: 7% ... nivel 5: 19%

    @EventHandler(ignoreCancelled = true)
    public void onLoot(LootGenerateEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getInventoryHolder() == null) return;
        if (!HardcoreRouletteClasses.isRunWorld(p.getWorld())) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int lvl = plugin.explorerLevel(p.getUniqueId());
        if (rnd.nextDouble() >= findChance(lvl)) return;
        List<Relic> pool = new ArrayList<>();
        for (Relic x : RELICS) if (!Settings.BOOKS_OFF.contains(x.id())) pool.add(x);
        if (pool.isEmpty()) return;
        Relic r = pool.get(rnd.nextInt(pool.size()));
        double roll = rnd.nextDouble();
        int bl = roll < 0.10 + 0.02 * lvl ? 3 : roll < 0.40 + 0.03 * lvl ? 2 : 1;
        e.getLoot().add(book(r.id(), Math.min(bl, r.max())));
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 0.6f);
            p.sendActionBar(plain("Seu faro de explorador achou um Livro Antigo neste bau!", NamedTextColor.LIGHT_PURPLE));
        });
        plugin.getLogger().info(p.getName() + " achou Livro Antigo " + r.id() + " " + bl);
    }

    // ---------- Veio ----------

    private static String oreFamily(Material m) {
        String n = m.name();
        if (!n.endsWith("_ORE") && m != Material.ANCIENT_DEBRIS) return null;
        return n.replace("DEEPSLATE_", "");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onVein(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (veining.contains(p.getUniqueId()) || !p.isSneaking() || p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        int lvl = level(tool, "veio");
        String fam = oreFamily(e.getBlock().getType());
        if (lvl <= 0 || fam == null || e.getBlock().getType() == Material.ANCIENT_DEBRIS) return;
        List<Block> vein = new ArrayList<>();
        Deque<Block> q = new ArrayDeque<>(List.of(e.getBlock()));
        Set<Block> seen = new HashSet<>(q);
        while (!q.isEmpty() && vein.size() < VEIN[lvl]) {
            Block b = q.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Block n = b.getRelative(dx, dy, dz);
                if (seen.add(n) && fam.equals(oreFamily(n.getType()))) { q.add(n); vein.add(n); if (vein.size() >= VEIN[lvl]) break; }
            }
        }
        if (vein.isEmpty()) return;
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            veining.add(id);
            try {
                for (Block b : vein) {
                    ItemStack t = p.getInventory().getItemInMainHand();
                    if (level(t, "veio") <= 0) break; // ferramenta quebrou ou trocou
                    if (fam.equals(oreFamily(b.getType()))) p.breakBlock(b);
                }
            } finally { veining.remove(id); }
        });
    }

    // ---------- Replantio ----------

    private static Material seedOf(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onReplant(BlockDropItemEvent e) {
        BlockState st = e.getBlockState();
        Material seed = seedOf(st.getType());
        if (seed == null || !(st.getBlockData() instanceof Ageable ag) || ag.getAge() < ag.getMaximumAge()) return;
        if (level(e.getPlayer().getInventory().getItemInMainHand(), "replantio") <= 0) return;
        for (Item it : e.getItems()) {
            ItemStack s = it.getItemStack();
            if (s.getType() != seed) continue;
            if (s.getAmount() <= 1) e.getItems().remove(it); else { s.setAmount(s.getAmount() - 1); it.setItemStack(s); }
            Block b = e.getBlock();
            Material crop = st.getType();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Material below = b.getRelative(BlockFace.DOWN).getType();
                if (!b.getType().isAir() || (below != Material.FARMLAND && below != Material.SOUL_SAND)) return;
                b.setType(crop);
                b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.4, 0.5), 4, 0.25, 0.1, 0.25);
            });
            return;
        }
    }

    // ---------- Decapitacao ----------

    private static Material headOf(EntityType t) {
        return switch (t) {
            case ZOMBIE, HUSK, DROWNED -> Material.ZOMBIE_HEAD;
            case SKELETON, STRAY, BOGGED -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case CREEPER -> Material.CREEPER_HEAD;
            case PIGLIN, PIGLIN_BRUTE, ZOMBIFIED_PIGLIN -> Material.PIGLIN_HEAD;
            case PLAYER -> Material.PLAYER_HEAD;
            default -> null;
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        int lvl = level(k.getInventory().getItemInMainHand(), "decapitacao");
        Material head = headOf(e.getEntityType());
        if (lvl <= 0 || head == null || ThreadLocalRandom.current().nextDouble() >= 0.04 * lvl) return;
        for (ItemStack d : e.getDrops()) if (d.getType() == head) return; // ja dropou
        ItemStack h = new ItemStack(head);
        if (e.getEntity() instanceof Player victim && h.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(victim);
            h.setItemMeta(sm);
        }
        e.getDrops().add(h);
        k.playSound(k.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.6f, 0.5f);
    }

    // ---------- Lenhador ----------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFell(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (veining.contains(p.getUniqueId()) || !p.isSneaking() || p.getGameMode() == GameMode.CREATIVE) return;
        Material log = e.getBlock().getType();
        if (!Tag.LOGS.isTagged(log) || level(p.getInventory().getItemInMainHand(), "lenhador") <= 0) return;
        List<Block> tree = new ArrayList<>();
        Deque<Block> q = new ArrayDeque<>(List.of(e.getBlock()));
        Set<Block> seen = new HashSet<>(q);
        while (!q.isEmpty() && tree.size() < 48) {
            Block b = q.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Block n = b.getRelative(dx, dy, dz);
                if (seen.add(n) && n.getType() == log) { q.add(n); tree.add(n); if (tree.size() >= 48) break; }
            }
        }
        breakAll(p, tree, "lenhador", log);
    }

    /** Quebra os blocos como o jogador (eventos, drops e durabilidade), parando se a ferramenta quebrar. */
    private void breakAll(Player p, List<Block> blocks, String relic, Material only) {
        if (blocks.isEmpty()) return;
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            veining.add(id);
            try {
                for (Block b : blocks) {
                    if (level(p.getInventory().getItemInMainHand(), relic) <= 0) break;
                    if (only == null || b.getType() == only) p.breakBlock(b);
                }
            } finally { veining.remove(id); }
        });
    }

    // ---------- Colheita ----------

    private static boolean mature(Block b) {
        return seedOf(b.getType()) != null && b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHarvest(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (veining.contains(p.getUniqueId()) || !mature(e.getBlock())) return;
        if (level(p.getInventory().getItemInMainHand(), "colheita") <= 0) return;
        List<Block> around = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Block n = e.getBlock().getRelative(dx, 0, dz);
            if ((dx != 0 || dz != 0) && mature(n)) around.add(n);
        }
        breakAll(p, around, "colheita", null);
    }

    // ---------- Arado ----------

    private static boolean tillable(Material m) { return m == Material.DIRT || m == Material.GRASS_BLOCK || m == Material.DIRT_PATH; }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTill(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null
                || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hoe = p.getInventory().getItemInMainHand();
        Block c = e.getClickedBlock();
        if (level(hoe, "arado") <= 0 || !tillable(c.getType()) || !c.getRelative(BlockFace.UP).getType().isAir()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (c.getType() != Material.FARMLAND) return; // o centro nao foi arado (protecao, etc.)
            int done = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                Block n = c.getRelative(dx, 0, dz);
                if ((dx == 0 && dz == 0) || !tillable(n.getType()) || !n.getRelative(BlockFace.UP).getType().isAir()) continue;
                ItemStack t = p.getInventory().getItemInMainHand();
                if (level(t, "arado") <= 0) break;
                n.setType(Material.FARMLAND);
                done++;
                if (p.getGameMode() != GameMode.CREATIVE) p.getInventory().setItemInMainHand(t.damage(1, p));
            }
            if (done > 0) c.getWorld().playSound(c.getLocation(), Sound.ITEM_HOE_TILL, 1f, 0.9f);
        });
    }

    // ---------- Sabedoria ----------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onOreXp(BlockBreakEvent e) {
        int lvl = level(e.getPlayer().getInventory().getItemInMainHand(), "sabedoria");
        if (lvl <= 0 || e.getExpToDrop() <= 0 || oreFamily(e.getBlock().getType()) == null) return;
        e.setExpToDrop((int) Math.round(e.getExpToDrop() * (1 + 0.5 * lvl)));
    }

    // ---------- efeitos de vestir (visao, sereia, molas, implantes) ----------

    private final Map<UUID, org.bukkit.Location> lastPos = new HashMap<>();
    private final Map<UUID, Integer> walkTicks = new HashMap<>();

    private void wornTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            ItemStack helm = p.getInventory().getHelmet(), boots = p.getInventory().getBoots();
            if (level(helm, "visao") > 0) give(p, org.bukkit.potion.PotionEffectType.NIGHT_VISION, 0, 300);
            if (level(helm, "sereia") > 0) give(p, org.bukkit.potion.PotionEffectType.WATER_BREATHING, 0, 80);
            int mol = level(boots, "molas");
            if (mol > 0) give(p, org.bukkit.potion.PotionEffectType.JUMP_BOOST, mol - 1, 80);
            org.bukkit.Location now = p.getLocation(), was = lastPos.put(p.getUniqueId(), now);
            if (level(helm, "implantes") > 0 && was != null && was.getWorld() == now.getWorld() && was.distanceSquared(now) > 1) {
                int w = walkTicks.merge(p.getUniqueId(), 1, Integer::sum);
                if (w >= 3) { // 3 x 2 s andando
                    walkTicks.put(p.getUniqueId(), 0);
                    if (p.getFoodLevel() < 20) p.setFoodLevel(p.getFoodLevel() + 1);
                }
            }
        }
    }

    private static void give(Player p, org.bukkit.potion.PotionEffectType t, int amp, int ticks) {
        org.bukkit.potion.PotionEffect cur = p.getPotionEffect(t);
        if (cur != null && (cur.getAmplifier() > amp || (cur.getAmplifier() == amp && cur.getDuration() > ticks - 45))) return;
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(t, ticks, amp, true, false, true));
    }

    // ---------- Protecao Antiga ----------

    private static final Set<EntityDamageEvent.DamageCause> HITS = EnumSet.of(EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK, EntityDamageEvent.DamageCause.PROJECTILE,
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, EntityDamageEvent.DamageCause.BLOCK_EXPLOSION,
            EntityDamageEvent.DamageCause.MAGIC, EntityDamageEvent.DamageCause.THORNS, EntityDamageEvent.DamageCause.SONIC_BOOM);

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHit(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !HITS.contains(e.getCause())) return;
        int sum = 0;
        for (ItemStack a : p.getInventory().getArmorContents()) sum += level(a, "protecao");
        if (sum <= 0 || ThreadLocalRandom.current().nextDouble() >= Math.min(0.24, 0.04 * sum)) return;
        e.setDamage(e.getDamage() * 0.5);
        p.getWorld().spawnParticle(Particle.ENCHANTED_HIT, p.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.1);
        p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.4f);
    }
}
