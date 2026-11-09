package dev.math.bclasses;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Pulsos de energia em particulas que andam por um "cabo" virtual entre dois dispositivos.
 * O caminho e um A* pelo grid de blocos livres, preferindo encostar em paredes e chao e
 * evitando curvas, para parecer fiacao. Caminhos ficam em cache e sao refeitos a cada 15 s.
 */
final class EnergyFx {

    private static final int MAX_EXPANDED = 4000;
    private static final int MARGIN = 4;
    private static final long CACHE_MS = 15_000;
    private static final int MAX_PULSES = 250;
    private static final double VIEW = 40;

    private record CachedPath(List<int[]> nodes, long at) { }

    private static final class Pulse {
        final World w; final List<int[]> path; final Particle.DustOptions dust, trail; int idx;
        Pulse(World w, List<int[]> path, Color c) {
            this.w = w; this.path = path;
            this.dust = new Particle.DustOptions(c, 1.1f);
            this.trail = new Particle.DustOptions(c, 0.6f);
        }
    }

    private final BabylonClasses plugin;
    private final Map<String, CachedPath> cache = new HashMap<>();
    private final List<Pulse> pulses = new ArrayList<>();

    EnergyFx(BabylonClasses plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::render, 2L, 2L);
    }

    /** Dispara um pulso de 'from' ate 'to' (coordenadas de bloco). */
    void pulse(World w, int fx, int fy, int fz, int tx, int ty, int tz, Color color) {
        if (pulses.size() >= MAX_PULSES || !anyViewer(w, fx, fy, fz)) return;
        String key = w.getName() + ":" + fx + "," + fy + "," + fz + ">" + tx + "," + ty + "," + tz;
        CachedPath cp = cache.get(key);
        long now = System.currentTimeMillis();
        if (cp == null || now - cp.at() > CACHE_MS) {
            List<int[]> nodes = astar(w, new int[]{fx, fy, fz}, new int[]{tx, ty, tz});
            if (nodes == null) nodes = straight(fx, fy, fz, tx, ty, tz);
            cp = new CachedPath(nodes, now);
            cache.put(key, cp);
            if (cache.size() > 500) cache.clear();
        }
        pulses.add(new Pulse(w, cp.nodes(), color));
    }

    private boolean anyViewer(World w, double x, double y, double z) {
        for (Player p : w.getPlayers())
            if (plugin.fxEnabled(p) && p.getLocation().distanceSquared(new org.bukkit.Location(w, x, y, z)) < VIEW * VIEW) return true;
        return false;
    }

    private void render() {
        if (pulses.isEmpty()) return;
        Map<World, List<Player>> viewers = new HashMap<>();
        for (Iterator<Pulse> it = pulses.iterator(); it.hasNext(); ) {
            Pulse p = it.next();
            List<Player> vs = viewers.computeIfAbsent(p.w, w -> {
                List<Player> l = new ArrayList<>();
                for (Player pl : w.getPlayers()) if (plugin.fxEnabled(pl)) l.add(pl);
                return l;
            });
            // anda 2 blocos por passo (20 blocos/s), desenhando a cabeca e um rastro
            for (int step = 0; step < 2 && p.idx < p.path.size(); step++, p.idx++) {
                int[] n = p.path.get(p.idx);
                double x = n[0] + 0.5, y = n[1] + 0.5, z = n[2] + 0.5;
                for (Player pl : vs) {
                    if (pl.getLocation().distanceSquared(new org.bukkit.Location(p.w, x, y, z)) > VIEW * VIEW) continue;
                    pl.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, p.dust);
                    if (p.idx > 0) {
                        int[] b = p.path.get(p.idx - 1);
                        pl.spawnParticle(Particle.DUST, (b[0] + n[0]) / 2.0 + 0.5, (b[1] + n[1]) / 2.0 + 0.5, (b[2] + n[2]) / 2.0 + 0.5, 1, 0, 0, 0, 0, p.trail);
                    }
                }
            }
            if (p.idx >= p.path.size()) it.remove();
        }
    }

    // ---------- caminho ----------

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static boolean free(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return false;
        return w.getBlockAt(x, y, z).isPassable();
    }

    private static boolean hugging(World w, int x, int y, int z) {
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (w.isChunkLoaded(nx >> 4, nz >> 4) && !w.getBlockAt(nx, ny, nz).isPassable()) return true;
        }
        return false;
    }

    private record Node(int x, int y, int z, double g, double f, int dir, Node parent) { }

    private List<int[]> astar(World w, int[] s, int[] t) {
        int minX = Math.min(s[0], t[0]) - MARGIN, maxX = Math.max(s[0], t[0]) + MARGIN;
        int minY = Math.min(s[1], t[1]) - MARGIN, maxY = Math.max(s[1], t[1]) + MARGIN;
        int minZ = Math.min(s[2], t[2]) - MARGIN, maxZ = Math.max(s[2], t[2]) + MARGIN;
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<Long, Double> best = new HashMap<>();
        open.add(new Node(s[0], s[1], s[2], 0, h(s[0], s[1], s[2], t), -1, null));
        best.put(pack(s[0], s[1], s[2]), 0.0);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_EXPANDED) {
            Node n = open.poll();
            if (n.x == t[0] && n.y == t[1] && n.z == t[2]) {
                LinkedList<int[]> out = new LinkedList<>();
                for (Node c = n; c != null; c = c.parent) out.addFirst(new int[]{c.x, c.y, c.z});
                return new ArrayList<>(out);
            }
            if (n.g > best.getOrDefault(pack(n.x, n.y, n.z), Double.MAX_VALUE)) continue;
            for (int di = 0; di < 6; di++) {
                int[] d = DIRS[di];
                int x = n.x + d[0], y = n.y + d[1], z = n.z + d[2];
                if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) continue;
                boolean goal = x == t[0] && y == t[1] && z == t[2];
                if (!goal && !free(w, x, y, z)) continue;
                double cost = 1.0;
                if (!goal && !hugging(w, x, y, z)) cost += 0.6; // cabo prefere correr junto de blocos
                if (n.dir >= 0 && n.dir != di) cost += 0.4;    // e evita curvas
                double g = n.g + cost;
                long k = pack(x, y, z);
                if (g >= best.getOrDefault(k, Double.MAX_VALUE)) continue;
                best.put(k, g);
                open.add(new Node(x, y, z, g, g + h(x, y, z, t), di, n));
            }
        }
        return null;
    }

    private static double h(int x, int y, int z, int[] t) {
        return Math.abs(x - t[0]) + Math.abs(y - t[1]) + Math.abs(z - t[2]);
    }

    /** Sem caminho livre: anda em L (x, depois z, depois y), como um cabo embutido. */
    private static List<int[]> straight(int fx, int fy, int fz, int tx, int ty, int tz) {
        List<int[]> out = new ArrayList<>();
        int x = fx, y = fy, z = fz;
        out.add(new int[]{x, y, z});
        while (x != tx) { x += Integer.signum(tx - x); out.add(new int[]{x, y, z}); }
        while (z != tz) { z += Integer.signum(tz - z); out.add(new int[]{x, y, z}); }
        while (y != ty) { y += Integer.signum(ty - y); out.add(new int[]{x, y, z}); }
        return out;
    }
}
