package dev.math.hrclasses;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;

/** Pulsos de energia em particulas que correm pelo caminho real dos cabos (pontos ja centralizados). */
final class EnergyFx {

    private static final int MAX_PULSES = 250;
    private static final double VIEW = 48;

    private static final class Pulse {
        final World w; final List<double[]> path; final Particle.DustOptions dust, trail; int idx;
        Pulse(World w, List<double[]> path, Color c) {
            this.w = w; this.path = path;
            this.dust = new Particle.DustOptions(c, 1.1f);
            this.trail = new Particle.DustOptions(c, 0.6f);
        }
    }

    private final HardcoreRouletteClasses plugin;
    private final List<Pulse> pulses = new ArrayList<>();

    EnergyFx(HardcoreRouletteClasses plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::render, 2L, 2L);
    }

    void pulse(World w, List<double[]> path, Color color) {
        if (path == null || path.size() < 2 || pulses.size() >= MAX_PULSES) return;
        double[] s = path.get(0);
        if (!anyViewer(w, s[0], s[1], s[2])) return;
        pulses.add(new Pulse(w, path, color));
    }

    /** Acende pontos (cabos e fios entre postes) por um instante, so para um jogador. */
    void flash(Player p, Collection<double[]> points, Color color) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 0.9f);
        for (double[] n : points) p.spawnParticle(Particle.DUST, n[0], n[1], n[2], 1, 0.08, 0.08, 0.08, 0, dust);
    }

    private boolean anyViewer(World w, double x, double y, double z) {
        Location l = new Location(w, x, y, z);
        for (Player p : w.getPlayers())
            if (plugin.fxEnabled(p) && p.getLocation().distanceSquared(l) < VIEW * VIEW) return true;
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
            // anda 2 pontos por passo, desenhando a cabeca e um rastro
            for (int step = 0; step < 2 && p.idx < p.path.size(); step++, p.idx++) {
                double[] n = p.path.get(p.idx);
                Location at = new Location(p.w, n[0], n[1], n[2]);
                for (Player pl : vs) {
                    if (pl.getLocation().distanceSquared(at) > VIEW * VIEW) continue;
                    pl.spawnParticle(Particle.DUST, n[0], n[1], n[2], 1, 0, 0, 0, 0, p.dust);
                    if (p.idx > 0) {
                        double[] b = p.path.get(p.idx - 1);
                        pl.spawnParticle(Particle.DUST, (b[0] + n[0]) / 2, (b[1] + n[1]) / 2, (b[2] + n[2]) / 2, 1, 0, 0, 0, 0, p.trail);
                    }
                }
            }
            if (p.idx >= p.path.size()) it.remove();
        }
    }
}
