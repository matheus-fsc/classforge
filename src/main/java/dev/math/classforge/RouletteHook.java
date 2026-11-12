package dev.math.classforge;

import dev.math.hcroulette.api.HardcoreRouletteAPI;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Ponte com o HardcoreRoulette (API oficial dev.math.hcroulette.api).
 * So e carregada quando o HardcoreRoulette esta ligado; sem ele, o Classforge nunca toca nesta classe.
 */
final class RouletteHook {

    private RouletteHook() {}

    /** Quem recebe XP compartilhado de {@code p}, ou lista vazia. */
    static List<Player> shareTargets(Player p) {
        HardcoreRouletteAPI api = HardcoreRouletteAPI.get();
        return api == null ? List.of() : api.xpShareTargets(p);
    }

    static double shareRatio() {
        HardcoreRouletteAPI api = HardcoreRouletteAPI.get();
        return api == null ? 0 : api.xpShareRatio();
    }
}
