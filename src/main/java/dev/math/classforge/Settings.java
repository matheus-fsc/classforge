package dev.math.classforge;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * Le o config.yml e aplica nos campos de balanceamento das outras classes.
 * Toda chave tem como padrao o valor que estava fixo no codigo; chave que faltar no arquivo
 * e escrita de volta com o padrao (config antiga ganha as chaves novas sozinha).
 */
final class Settings {

    private Settings() {}

    // ---------- geral ----------
    static String WORLD_MODE = "all";
    static String WORLD_PREFIX = "hc_";
    static List<String> WORLD_LIST = List.of();
    static boolean RESET_ON_NEW_WORLD = false;
    static boolean PARTY_SHARE = true;

    // ---------- modulos ----------
    static boolean ENGINEERING = true, EXPLORATION = true, FARMING = true, BOOKS = true;

    // ---------- energia ----------
    static double GEN_COAL = 4, GEN_GEO = 0.5, GEN_SOLAR = 1.5, GEN_SOLAR_STORM = 0.5, BATTERY_CAP = 5000;

    // ---------- livros antigos ----------
    static double BOOK_BASE = 0.04, BOOK_PER_LEVEL = 0.03;
    static Set<String> BOOKS_OFF = Set.of();

    private static FileConfiguration c;
    private static boolean dirty;
    private static Classforge plugin;

    static boolean isRunWorld(World w) {
        if (w == null) return false;
        String base = Classforge.baseOf(w.getName());
        return switch (WORLD_MODE) {
            case "all" -> true;
            case "list" -> WORLD_LIST.contains(base);
            default -> base.startsWith(WORLD_PREFIX);
        };
    }

    static void load(Classforge p) {
        plugin = p;
        p.saveDefaultConfig();
        p.reloadConfig();
        c = p.getConfig();
        dirty = false;

        WORLD_MODE = str("worlds.mode", "all").toLowerCase(Locale.ROOT);
        WORLD_PREFIX = str("worlds.prefix", "hc_");
        WORLD_LIST = strList("worlds.list", List.of());
        RESET_ON_NEW_WORLD = bool("progress.reset-on-new-world", false);
        PARTY_SHARE = bool("progress.party-share", true);

        ENGINEERING = bool("modules.engineering", true);
        EXPLORATION = bool("modules.exploration", true);
        FARMING = bool("modules.farming", true);
        BOOKS = bool("modules.ancient-books", true);

        // Engenharia
        Classforge.LEVEL_XP = levels("engineering.level-xp", Classforge.LEVEL_XP);
        Engineer.MAX_TURRETS = levels("engineering.turrets.max-per-level", Engineer.MAX_TURRETS);
        Engineer.RANGE = levels("engineering.turrets.range-per-level", Engineer.RANGE);
        Engineer.COOLDOWN = levels("engineering.turrets.cooldown-ticks-per-level", Engineer.COOLDOWN);
        Engineer.BARREL_MAX = num("engineering.turrets.barrel-shots", Engineer.BARREL_MAX);
        Engineer.SHOT_COST = dbl("engineering.turrets.energy-per-arrow", Engineer.SHOT_COST);
        Engineer.FIREBALL_COST = dbl("engineering.turrets.energy-per-fireball", Engineer.FIREBALL_COST);
        Engineer.BURST = num("engineering.bullets.burst", Engineer.BURST);
        Engineer.COPPER_DMG = dbl("engineering.bullets.copper-damage", Engineer.COPPER_DMG);
        Engineer.IRON_DMG = dbl("engineering.bullets.iron-damage", Engineer.IRON_DMG);
        Engineer.COPPER_COST = dbl("engineering.bullets.copper-energy", Engineer.COPPER_COST);
        Engineer.IRON_COST = dbl("engineering.bullets.iron-energy", Engineer.IRON_COST);
        Engineer.LASER_COST = dbl("engineering.laser.energy-per-shot", Engineer.LASER_COST);
        Engineer.LASER_DMG = dbl("engineering.laser.damage", Engineer.LASER_DMG);
        Engineer.LASER_RADIUS = dbl("engineering.laser.radius", Engineer.LASER_RADIUS);

        GEN_COAL = dbl("engineering.energy.coal-generator", GEN_COAL);
        GEN_GEO = dbl("engineering.energy.geothermal-per-lava", GEN_GEO);
        GEN_SOLAR = dbl("engineering.energy.solar-day", GEN_SOLAR);
        GEN_SOLAR_STORM = dbl("engineering.energy.solar-storm", GEN_SOLAR_STORM);
        BATTERY_CAP = dbl("engineering.energy.battery-capacity", BATTERY_CAP);
        Engineer.INVERTER_RATE = dbl("engineering.energy.inverter-rate", Engineer.INVERTER_RATE);
        Engineer.INVERTER_EFF = dbl("engineering.energy.inverter-efficiency", Engineer.INVERTER_EFF);

        Engineer.POLE_RANGE = dbl("engineering.network.pole-range", Engineer.POLE_RANGE);
        Engineer.MAX_NET_NODES = num("engineering.network.max-nodes", Engineer.MAX_NET_NODES);
        Engineer.MAX_POWER_DEVICES = num("engineering.network.max-power-devices", Engineer.MAX_POWER_DEVICES);
        Engineer.SHOCK_DMG = dbl("engineering.network.shock-damage", Engineer.SHOCK_DMG);
        Engineer.SHOCK_MS = num("engineering.network.shock-interval-ms", (int) Engineer.SHOCK_MS);

        Engineer.INDUSTRIAL_TIME = num("engineering.machines.foundry-seconds", Engineer.INDUSTRIAL_TIME);
        Engineer.FUEL_COAL_BLOCK = num("engineering.machines.foundry-fuel-coal-block", Engineer.FUEL_COAL_BLOCK);
        Engineer.FUEL_LAVA = num("engineering.machines.foundry-fuel-lava", Engineer.FUEL_LAVA);
        Engineer.FORGE_RATE = dbl("engineering.machines.forge-energy", Engineer.FORGE_RATE);
        Engineer.SMELT_RATE = dbl("engineering.machines.furnace-energy", Engineer.SMELT_RATE);
        Engineer.SMELT_TIME = num("engineering.machines.furnace-seconds", Engineer.SMELT_TIME);
        Engineer.CRUSH_RATE = dbl("engineering.machines.crusher-energy", Engineer.CRUSH_RATE);
        Engineer.CRUSH_TIME = num("engineering.machines.crusher-seconds", Engineer.CRUSH_TIME);
        Engineer.CRUSH_BONUS = dbl("engineering.machines.crusher-bonus-chance", Engineer.CRUSH_BONUS);
        Engineer.PLANT_COST = dbl("engineering.machines.planter-energy-per-seed", Engineer.PLANT_COST);
        Engineer.ASSEMBLE_RATE = dbl("engineering.machines.assembler-energy", Engineer.ASSEMBLE_RATE);
        Engineer.ASSEMBLE_TIME = num("engineering.machines.assembler-seconds", Engineer.ASSEMBLE_TIME);
        Engineer.SHIELD_UPKEEP = dbl("engineering.machines.shield-energy", Engineer.SHIELD_UPKEEP);
        Engineer.SHIELD_PUSH = dbl("engineering.machines.shield-energy-per-mob", Engineer.SHIELD_PUSH);
        Engineer.MINE_COST = dbl("engineering.machines.miner-energy-per-block", Engineer.MINE_COST);
        Engineer.MINE_TIME = num("engineering.machines.miner-seconds-per-block", Engineer.MINE_TIME);
        Engineer.MINE_R = num("engineering.machines.miner-radius", Engineer.MINE_R);

        Tunnel.STEP_SECONDS = num("engineering.tunnel.seconds-per-block", Tunnel.STEP_SECONDS);
        Tunnel.STEP_RATE = dbl("engineering.tunnel.energy", Tunnel.STEP_RATE);
        Tunnel.DRILL_MAX = num("engineering.tunnel.drill-durability", Tunnel.DRILL_MAX);
        Tunnel.MAX_LEN = num("engineering.tunnel.max-length", Tunnel.MAX_LEN);
        Tunnel.SWAP_SECONDS = num("engineering.tunnel.drill-swap-seconds", Tunnel.SWAP_SECONDS);

        Engineer.XP_REPAIR = num("engineering.xp.replace-barrel", Engineer.XP_REPAIR);
        Engineer.XP_KILL = num("engineering.xp.turret-kill", Engineer.XP_KILL);
        Engineer.XP_MECH = num("engineering.xp.craft-mechanical", Engineer.XP_MECH);
        Engineer.XP_ANVIL = num("engineering.xp.anvil-repair", Engineer.XP_ANVIL);
        Engineer.XP_INDUSTRIAL = dbl("engineering.xp.foundry-ingot", Engineer.XP_INDUSTRIAL);
        Engineer.XP_INGOT = dbl("engineering.xp.furnace-ingot", Engineer.XP_INGOT);
        Engineer.XP_MACHINE_ITEM = dbl("engineering.xp.machine-item", Engineer.XP_MACHINE_ITEM);
        Engineer.XP_FORGE = dbl("engineering.xp.forge-batch", Engineer.XP_FORGE);
        Factory.XP_BATCH = dbl("engineering.xp.factory-batch", Factory.XP_BATCH);
        Tunnel.XP_STEP = dbl("engineering.xp.tunnel-block", Tunnel.XP_STEP);

        Engineer.TAG_SCALE = (float) dbl("engineering.display.tag-scale", Engineer.TAG_SCALE);
        EnergyFx.MAX_PULSES = num("engineering.display.max-energy-particles", EnergyFx.MAX_PULSES);
        EnergyFx.VIEW = dbl("engineering.display.energy-particles-view", EnergyFx.VIEW);

        // Exploracao
        Classforge.EXP_XP = levels("exploration.level-xp", Classforge.EXP_XP);
        Explorer.ANCHOR_LEVEL = num("exploration.anchor.min-level", Explorer.ANCHOR_LEVEL);
        Explorer.RANGE = levels("exploration.anchor.range-per-level", Explorer.RANGE);
        Explorer.BLOCKS_PER_LEVEL = num("exploration.anchor.blocks-per-xp-level", Explorer.BLOCKS_PER_LEVEL);
        Explorer.TP_COOLDOWN_MS = num("exploration.anchor.cooldown-seconds", (int) (Explorer.TP_COOLDOWN_MS / 1000)) * 1000L;
        Explorer.CHANNEL_TICKS = num("exploration.anchor.channel-ticks", Explorer.CHANNEL_TICKS);
        Explorer.XP_BONUS = levels("exploration.orb-xp-bonus-percent", Explorer.XP_BONUS);

        // Plantacao
        Classforge.FARM_XP = levels("farming.level-xp", Classforge.FARM_XP);
        Farmer.RADIUS = levels("farming.radius-per-level", Farmer.RADIUS);
        Farmer.MAX_GOLEMS = levels("farming.max-planters-per-level", Farmer.MAX_GOLEMS);
        Farmer.REST = levels("farming.seconds-per-seed-per-level", Farmer.REST);
        Farmer.BONUS = levels("farming.harvest-bonus-percent", Farmer.BONUS);
        Farmer.XP_HARVEST = dbl("farming.xp-per-harvest", Farmer.XP_HARVEST);
        Farmer.XP_PLANT = dbl("farming.xp-per-planted-seed", Farmer.XP_PLANT);

        // Livros Antigos
        BOOK_BASE = dbl("ancient-books.chance-base", BOOK_BASE);
        BOOK_PER_LEVEL = dbl("ancient-books.chance-per-exploration-level", BOOK_PER_LEVEL);
        Relics.VEIN = intList("ancient-books.vein-blocks", Relics.VEIN, 4);
        BOOKS_OFF = new HashSet<>(strList("ancient-books.disabled", List.of()));

        if (dirty) p.saveConfig();
    }

    /**
     * Receitas da Bancada: na primeira vez escreve todas em engineering.recipes; depois le de la.
     * enabled: false tira o item do jogo; level, materials e parts trocam o que a receita pede.
     */
    static void applyRecipes(List<Engineer.Recipe> recipes) {
        ConfigurationSection sec = c.getConfigurationSection("engineering.recipes");
        if (sec == null) {
            for (Engineer.Recipe r : recipes) {
                String b = "engineering.recipes." + r.type();
                c.set(b + ".enabled", true);
                c.set(b + ".level", r.minLevel());
                if (r.amount() != 1) c.set(b + ".amount", r.amount());
                Map<String, Integer> mats = new LinkedHashMap<>();
                r.cost().forEach((m, n) -> mats.put(m.name(), n));
                c.set(b + ".materials", mats);
                if (!r.parts().isEmpty()) c.set(b + ".parts", new LinkedHashMap<>(r.parts()));
            }
            plugin.saveConfig();
            return;
        }
        ListIterator<Engineer.Recipe> it = recipes.listIterator();
        while (it.hasNext()) {
            Engineer.Recipe r = it.next();
            ConfigurationSection s = sec.getConfigurationSection(r.type());
            if (s == null) continue;
            if (!s.getBoolean("enabled", true)) { it.remove(); continue; }
            Map<Material, Integer> cost = r.cost();
            ConfigurationSection ms = s.getConfigurationSection("materials");
            if (ms != null) {
                cost = new LinkedHashMap<>();
                for (String k : ms.getKeys(false)) {
                    Material m = Material.matchMaterial(k);
                    if (m == null) { plugin.getLogger().warning("config: material desconhecido em recipes." + r.type() + ": " + k); continue; }
                    cost.put(m, ms.getInt(k));
                }
            }
            Map<String, Integer> parts = r.parts();
            ConfigurationSection ps = s.getConfigurationSection("parts");
            if (ps != null) {
                parts = new LinkedHashMap<>();
                for (String k : ps.getKeys(false)) parts.put(k, ps.getInt(k));
            }
            it.set(new Engineer.Recipe(r.type(), s.getInt("level", r.minLevel()), cost, s.getInt("amount", r.amount()), parts));
        }
    }

    // ---------- leitura com padrao ----------

    private static boolean bool(String k, boolean def) {
        if (!c.contains(k)) { c.set(k, def); dirty = true; }
        return c.getBoolean(k, def);
    }

    private static String str(String k, String def) {
        if (!c.contains(k)) { c.set(k, def); dirty = true; }
        return c.getString(k, def);
    }

    private static List<String> strList(String k, List<String> def) {
        if (!c.contains(k)) { c.set(k, def); dirty = true; }
        return c.getStringList(k);
    }

    private static int num(String k, int def) {
        if (!c.contains(k)) { c.set(k, def); dirty = true; }
        return c.getInt(k, def);
    }

    private static double dbl(String k, double def) {
        if (!c.contains(k)) { c.set(k, def); dirty = true; }
        return c.getDouble(k, def);
    }

    /** Lista de 5 valores (um por nivel). Tamanho errado: avisa e fica com o padrao. */
    private static int[] levels(String k, int[] def) { return intList(k, def, 5); }

    private static int[] intList(String k, int[] def, int size) {
        if (!c.contains(k)) {
            List<Integer> l = new ArrayList<>();
            for (int v : def) l.add(v);
            c.set(k, l);
            dirty = true;
            return def;
        }
        List<Integer> l = c.getIntegerList(k);
        if (l.size() != size) {
            plugin.getLogger().warning("config: " + k + " precisa de " + size + " valores; usando o padrao.");
            return def;
        }
        int[] out = new int[size];
        for (int i = 0; i < size; i++) out[i] = l.get(i);
        return out;
    }
}
