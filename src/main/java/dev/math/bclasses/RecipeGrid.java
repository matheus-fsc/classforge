package dev.math.bclasses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Tela de receita (grade 3x3 da mesa de trabalho) para itens de classe feitos com receita vanilla. */
final class RecipeGrid implements Listener {

    static final class View implements InventoryHolder {
        Inventory inv; Consumer<Player> back;
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};

    static void register(Plugin plugin) { Bukkit.getPluginManager().registerEvents(new RecipeGrid(), plugin); }

    /** Abre a receita registrada em {@code key}; {@code back} reabre a tela anterior. */
    static void open(Player p, NamespacedKey key, String title, Consumer<Player> back) {
        if (!(Bukkit.getRecipe(key) instanceof ShapedRecipe r)) return;
        View h = new View();
        h.back = back;
        Inventory inv = Bukkit.createInventory(h, 45, Component.text("Receita: " + title));
        h.inv = inv;
        ItemStack filler = Engineer.icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 0; i < 45; i++) inv.setItem(i, filler);
        for (int g : GRID) inv.setItem(g, null);

        String[] shape = r.getShape();
        Map<Character, RecipeChoice> choices = r.getChoiceMap();
        Map<Material, Integer> need = new HashMap<>();
        for (String row : shape) for (char ch : row.toCharArray()) {
            Material m = mat(choices.get(ch));
            if (m != null) need.merge(m, 1, Integer::sum);
        }
        for (int y = 0; y < shape.length; y++)
            for (int x = 0; x < shape[y].length(); x++) {
                Material m = mat(choices.get(shape[y].charAt(x)));
                if (m == null) continue;
                int has = count(p, m), n = need.get(m);
                ItemStack it = new ItemStack(m);
                ItemMeta meta = it.getItemMeta();
                meta.displayName(Component.text(Engineer.pretty(m), has >= n ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(Component.text("Precisa de " + n + ", voce tem " + has, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
                it.setItemMeta(meta);
                inv.setItem(GRID[y * 3 + x], it);
            }
        inv.setItem(23, Engineer.icon(Material.CRAFTING_TABLE, "Mesa de trabalho", NamedTextColor.YELLOW,
                "Monte nesta forma numa", "mesa de trabalho comum."));
        inv.setItem(25, r.getResult().clone());
        inv.setItem(36, Engineer.icon(Material.ARROW, "Voltar", NamedTextColor.WHITE, "Volta para o painel da classe."));
        p.openInventory(inv);
    }

    private static Material mat(RecipeChoice c) {
        if (c instanceof RecipeChoice.MaterialChoice mc && !mc.getChoices().isEmpty()) return mc.getChoices().get(0);
        if (c instanceof RecipeChoice.ExactChoice ec && !ec.getChoices().isEmpty()) return ec.getChoices().get(0).getType();
        return null;
    }

    private static int count(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.getType() == m) n += it.getAmount();
        return n;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof View h)) return;
        e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p && e.getClickedInventory() == e.getInventory() && e.getRawSlot() == 36) h.back.accept(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof View) e.setCancelled(true); }
}
