"""
Imagens no estilo do jogo: telas de craft e menus do Classforge, montadas com as texturas e a fonte
do proprio Minecraft (tiradas do jar do cliente, como em diagrams.py) e com os itens nos slots que o
plugin usa. Saem em docs/img/ingame/.

    python docs/ingame.py

Nao sao screenshots: sao renderizacoes fieis ao layout do codigo (Engineer.openPanel, openCatalog,
Classforge.openHub e as receitas de mesa de trabalho).
"""
import os
import zipfile
from pathlib import Path

from PIL import Image, ImageEnhance

DOCS = Path(__file__).resolve().parent
TEX = DOCS / ".textures"
OUT = DOCS / "img" / "ingame"
S = 4  # escala da GUI (como "Escala da interface: 4")

COLORS = {"gold": (255, 170, 0), "green": (85, 255, 85), "yellow": (255, 255, 85), "gray": (170, 170, 170),
          "aqua": (85, 255, 255), "red": (255, 85, 85), "white": (255, 255, 255), "dark_gray": (85, 85, 85),
          "title": (64, 64, 64)}


# ---------------------------------------------------------------- texturas
def _jar():
    if os.environ.get("MC_JAR"):
        return Path(os.environ["MC_JAR"])
    base = Path(os.environ.get("APPDATA", Path.home())) / ".minecraft" / "versions"
    jars = [d / (d.name + ".jar") for d in base.glob("*") if (d / (d.name + ".jar")).is_file()
            and d.name[:1].isdigit() and "-" not in d.name]
    key = lambda j: [int(p) if p.isdigit() else 0 for p in j.stem.split(".")]
    return max(jars, key=key) if jars else None


def ensure_textures():
    need = {"gui/crafting_table.png": "assets/minecraft/textures/gui/container/crafting_table.png",
            "gui/generic_54.png": "assets/minecraft/textures/gui/container/generic_54.png",
            "font/ascii.png": "assets/minecraft/textures/font/ascii.png",
            "entity/shield.png": "assets/minecraft/textures/entity/shield/shield_base_nopattern.png"}
    missing = {k: v for k, v in need.items() if not (TEX / k).is_file()}
    if not missing and (TEX / "item").is_dir():
        return
    jar = _jar()
    if not jar:
        raise SystemExit("jar do Minecraft nao encontrado (defina MC_JAR)")
    with zipfile.ZipFile(jar) as z:
        for k, v in missing.items():
            (TEX / k).parent.mkdir(parents=True, exist_ok=True)
            (TEX / k).write_bytes(z.read(v))
        if not (TEX / "item").is_dir():
            for n in z.namelist():
                for kind in ("item", "block"):
                    pre = f"assets/minecraft/textures/{kind}/"
                    if n.startswith(pre) and n.endswith(".png") and "/" not in n[len(pre):]:
                        out = TEX / kind / n[len(pre):]
                        out.parent.mkdir(parents=True, exist_ok=True)
                        out.write_bytes(z.read(n))


def tex(path):
    im = Image.open(TEX / (path + ".png")).convert("RGBA")
    if im.height > im.width:
        im = im.crop((0, 0, im.width, im.width))
    return im


# ---------------------------------------------------------------- icones
def cube(top, left, right):
    """Bloco em 3D como no inventario (16 px de lado, escalado por S)."""
    W = 16 * S
    T = 16 * S
    faces = []
    # (textura, origem, eixo u, eixo v, brilho)
    specs = [(left, (1, 4.25), (7, 3.75), (0, 7.5), 0.8),
             (right, (8, 8), (7, -3.75), (0, 7.5), 0.6),
             (top, (1, 4.25), (7, -3.75), (7, 3.75), 1.0)]
    out = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    for t, o, u, v, light in specs:
        img = tex(t).resize((T, T), Image.NEAREST)
        if light != 1.0:
            rgb = ImageEnhance.Brightness(img.convert("RGB")).enhance(light)
            img = Image.merge("RGBA", (*rgb.split(), img.split()[3]))
        ox, oy = o[0] * S, o[1] * S
        ux, uy = u[0] * S / T, u[1] * S / T
        vx, vy = v[0] * S / T, v[1] * S / T
        det = ux * vy - vx * uy
        a, b = vy / det, -vx / det
        d, e = -uy / det, ux / det
        c = -(a * ox + b * oy)
        f = -(d * ox + e * oy)
        face = img.transform((W, W), Image.AFFINE, (a, b, c, d, e, f), resample=Image.NEAREST, fillcolor=(0, 0, 0, 0))
        out.alpha_composite(face)
    return out


def flat(path):
    return tex(path).resize((16 * S, 16 * S), Image.NEAREST)


def shield():
    im = Image.open(TEX / "entity/shield.png").convert("RGBA").crop((2, 2, 12, 22))
    canvas = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    small = im.resize((8, 16), Image.NEAREST)
    canvas.alpha_composite(small, (4, 0))
    return canvas.resize((16 * S, 16 * S), Image.NEAREST)


ITEM = {
    "SMITHING_TABLE": lambda: cube("block/smithing_table_top", "block/smithing_table_front", "block/smithing_table_side"),
    "CRAFTING_TABLE": lambda: cube("block/crafting_table_top", "block/crafting_table_front", "block/crafting_table_side"),
    "DISPENSER": lambda: cube("block/furnace_top", "block/dispenser_front", "block/furnace_side"),
    "OBSIDIAN": lambda: cube("block/obsidian", "block/obsidian", "block/obsidian"),
    "CRYING_OBSIDIAN": lambda: cube("block/crying_obsidian", "block/crying_obsidian", "block/crying_obsidian"),
    "COPPER_BLOCK": lambda: cube("block/copper_block", "block/copper_block", "block/copper_block"),
    "CARVED_PUMPKIN": lambda: cube("block/pumpkin_top", "block/carved_pumpkin", "block/pumpkin_side"),
    "PISTON": lambda: cube("block/piston_top", "block/piston_side", "block/piston_side"),
    "SHIELD": shield,
    "LIGHTNING_ROD": lambda: flat("block/lightning_rod"),
    "IRON_BARS": lambda: flat("block/iron_bars"),
    "COMPASS": lambda: flat("item/compass_00"),
}
for _m in ["IRON_INGOT", "REDSTONE", "WHEAT_SEEDS", "IRON_HOE", "ENDER_PEARL", "GOLDEN_HOE", "BOOK", "ARROW",
           "EXPERIENCE_BOTTLE", "REPEATER", "LEATHER", "IRON_NUGGET", "COPPER_NUGGET", "COPPER_INGOT", "GOLD_INGOT",
           "DIAMOND", "NETHERITE_INGOT"]:
    ITEM[_m] = (lambda n: lambda: flat("item/" + n))(_m.lower())
for _c in ["GRAY", "BLACK", "RED", "YELLOW", "LIGHT_BLUE", "ORANGE"]:
    ITEM[_c + "_STAINED_GLASS_PANE"] = (lambda n: lambda: flat("block/" + n + "_stained_glass"))(_c.lower())

_icon_cache = {}


def icon(mat, glint=False):
    key = (mat, glint)
    if key not in _icon_cache:
        im = ITEM[mat]()
        if glint:
            over = Image.new("RGBA", im.size, (160, 90, 255, 0))
            mask = im.split()[3].point(lambda a: 55 if a > 0 else 0)
            over.putalpha(mask)
            im = im.copy()
            im.alpha_composite(over)
        _icon_cache[key] = im
    return _icon_cache[key]


# ---------------------------------------------------------------- fonte bitmap
_font = None
_widths = {}


def _glyph(ch):
    global _font
    if _font is None:
        _font = Image.open(TEX / "font/ascii.png").convert("RGBA")
    code = ord(ch) if ord(ch) < 256 else ord("?")
    gx, gy = (code % 16) * 8, (code // 16) * 8
    g = _font.crop((gx, gy, gx + 8, gy + 8))
    if ch not in _widths:
        if ch == " ":
            _widths[ch] = 3
        else:
            box = g.getbbox()
            _widths[ch] = (box[2] if box else 0)
    return g, _widths[ch]


def text_width(s):
    return sum(_glyph(c)[1] + 1 for c in s)


def draw_text(img, x, y, s, color, shadow=True):
    """x, y em pixels de GUI (antes da escala)."""
    for dx, dy, col in ([(1, 1, tuple(c // 4 for c in color))] if shadow else []) + [(0, 0, color)]:
        cx = x
        for ch in s:
            g, w = _glyph(ch)
            tinted = Image.new("RGBA", g.size, (*col, 255))
            tinted.putalpha(g.split()[3])
            img.alpha_composite(tinted.resize((8 * S, 8 * S), Image.NEAREST), ((cx + dx) * S, (y + dy) * S))
            cx += w + 1


# ---------------------------------------------------------------- telas
def chest(title, rows, items, tooltip_slot=None, tooltip=None):
    """items: {slot: (MATERIAL, glint)}; tooltip: [(texto, cor), ...]."""
    g = Image.open(TEX / "gui/generic_54.png").convert("RGBA")
    H = 17 + rows * 18
    gui = Image.new("RGBA", (176, H + 96), (0, 0, 0, 0))
    gui.paste(g.crop((0, 0, 176, H)), (0, 0))
    gui.paste(g.crop((0, 126, 176, 222)), (0, H))
    gui = gui.resize((176 * S, (H + 96) * S), Image.NEAREST)
    draw_text(gui, 8, 6, title, COLORS["title"], shadow=False)
    draw_text(gui, 8, H + 3, "Inventario", COLORS["title"], shadow=False)
    for slot, spec in items.items():
        mat, glint = spec if isinstance(spec, tuple) else (spec, False)
        x, y = 8 + (slot % 9) * 18, 18 + (slot // 9) * 18
        gui.alpha_composite(icon(mat, glint), (x * S, y * S))
    if tooltip_slot is not None:
        x, y = 8 + (tooltip_slot % 9) * 18, 18 + (tooltip_slot // 9) * 18
        hl = Image.new("RGBA", (16 * S, 16 * S), (255, 255, 255, 80))
        gui.alpha_composite(hl, (x * S, y * S))
        return with_tooltip(gui, 176 + 6, max(4, y - 12), tooltip)
    return pad(gui)


def crafting(grid, result, count=1):
    """grid: 3 linhas de 3 materiais (None = vazio)."""
    g = Image.open(TEX / "gui/crafting_table.png").convert("RGBA").crop((0, 0, 176, 166))
    gui = g.resize((176 * S, 166 * S), Image.NEAREST)
    draw_text(gui, 28, 6, "Crafting", COLORS["title"], shadow=False)
    draw_text(gui, 8, 72, "Inventario", COLORS["title"], shadow=False)
    for r, row in enumerate(grid):
        for c, mat in enumerate(row):
            if mat:
                gui.alpha_composite(icon(mat), ((30 + c * 18) * S, (17 + r * 18) * S))
    gui.alpha_composite(icon(result), (124 * S, 35 * S))
    if count > 1:
        draw_text(gui, 124 + 17 - text_width(str(count)), 35 + 9, str(count), COLORS["white"])
    return pad(gui)


def with_tooltip(gui, tx, ty, lines):
    w = max(text_width(t) for t, _ in lines) + 6
    h = 8 + (len(lines) - 1) * 10 + (2 if len(lines) > 1 else 0) + 6
    gw, gh = gui.width // S, gui.height // S
    cw, ch = max(gw, tx + w + 4), max(gh, ty + h + 4)
    top = max(0, -ty + 4)
    canvas = Image.new("RGBA", (cw * S, (ch + top) * S), (0, 0, 0, 0))
    canvas.alpha_composite(gui, (0, top * S))
    ty += top
    tip = Image.new("RGBA", (w * S, h * S), (16, 0, 16, 240))
    canvas.alpha_composite(tip, (tx * S, ty * S))
    for i in range(h):  # borda roxa em degrade
        f = i / max(1, h - 1)
        col = (int(80 - 40 * f), 0, int(255 - 128 * f), 200)
        for xx in (1, w - 2):
            canvas.alpha_composite(Image.new("RGBA", (S, S), col), ((tx + xx) * S, (ty + i) * S))
    for xx in range(1, w - 1):
        canvas.alpha_composite(Image.new("RGBA", (S, S), (80, 0, 255, 200)), ((tx + xx) * S, (ty + 1) * S))
        canvas.alpha_composite(Image.new("RGBA", (S, S), (40, 0, 127, 200)), ((tx + xx) * S, (ty + h - 2) * S))
    yy = ty + 4
    for i, (t, c) in enumerate(lines):
        if t:
            draw_text(canvas, tx + 3, yy, t, COLORS[c])
        yy += 10 + (2 if i == 0 else 0)
    return pad(canvas)


def pad(img, m=10):
    out = Image.new("RGBA", (img.width + 2 * m * S, img.height + 2 * m * S), (30, 31, 34, 255))
    out.alpha_composite(img, (m * S, m * S))
    return out


# ---------------------------------------------------------------- cenas
GRAY = "GRAY_STAINED_GLASS_PANE"


def filled(n, extra):
    d = {i: GRAY for i in range(n)}
    d.update(extra)
    return d


def scenes():
    out = {}
    out["recipe-bancada.png"] = crafting([[None, "REDSTONE", None],
                                          ["IRON_INGOT", "CRAFTING_TABLE", "IRON_INGOT"],
                                          ["IRON_INGOT", "IRON_INGOT", "IRON_INGOT"]], "SMITHING_TABLE")
    out["recipe-plantador.png"] = crafting([[None, "CARVED_PUMPKIN", None],
                                            ["WHEAT_SEEDS", "COPPER_BLOCK", "WHEAT_SEEDS"],
                                            [None, "IRON_HOE", None]], "CARVED_PUMPKIN")
    out["recipe-ancora.png"] = crafting([[None, "ENDER_PEARL", None],
                                         ["OBSIDIAN", "COMPASS", "OBSIDIAN"],
                                         ["OBSIDIAN", "OBSIDIAN", "OBSIDIAN"]], "CRYING_OBSIDIAN")

    out["menu-habilidades.png"] = chest("Habilidades", 3, filled(27, {
        11: "COMPASS", 13: "DISPENSER", 15: "GOLDEN_HOE", 22: "BOOK"}), 13, [
        ("Engenharia - nivel 3", "gold"), ("XP 60/125", "gray"), ("", "gray"),
        ("Torres, energia, linha industrial", "gray"), ("e tuneladora. Sobe montando e com", "gray"),
        ("abates das suas torres.", "gray"), ("", "gray"), ("Clique para abrir.", "gray")])

    out["menu-engenharia.png"] = chest("Engenharia", 6, filled(54, {
        4: "DISPENSER", 6: "EXPERIENCE_BOTTLE", 10: "SMITHING_TABLE",
        12: "SHIELD", 13: "LIGHTNING_ROD", 14: "PISTON", 15: "REPEATER",
        29: "COPPER_INGOT", 30: "IRON_INGOT", 31: "GOLD_INGOT", 32: "BLACK_STAINED_GLASS_PANE",
        33: "BLACK_STAINED_GLASS_PANE", 46: "REDSTONE", 48: "LIGHTNING_ROD"}), 31, [
        ("Nivel 3", "green"), ("Precisa de 35 XP", "gray"), ("", "gray"),
        ("+ Ate 4 torres", "gray"), ("+ Alcance 16 blocos", "gray"), ("+ 1.4 tiros por segundo", "gray"),
        ("+ Flecha de fogo (30%)", "gray"), ("+ Fornalha Eletrica, Triturador, Plantadeira", "gray"),
        ("+ Bobinadeira, Cadinho, Gravadora", "gray"), ("", "gray"), ("Liberado", "gray")])

    defense = filled(54, {i: "RED_STAINED_GLASS_PANE" for i in range(9, 18)})
    defense.update({1: ("SHIELD", True), 3: "LIGHTNING_ROD", 5: "PISTON", 7: "REPEATER",
                    19: "DISPENSER", 20: ("DISPENSER", True), 21: "IRON_BARS", 22: "LIGHTNING_ROD",
                    23: "LEATHER", 24: "IRON_NUGGET", 25: "COPPER_NUGGET", 49: "ARROW"})
    out["menu-defesa.png"] = chest("Engenharia: Defesa", 6, defense, 20, [
        ("Torre Laser", "gold"), ("Mira com laser e lanca TNT nos mobs.", "gray"),
        ("O TNT nao explode: so da dano em area", "gray"), ("(8+ no centro, raio 3.5), sem quebrar blocos.", "gray"),
        ("Municao: TNT dentro dela. 25 energia por tiro.", "gray"), ("Conta no limite de torres. Ligue com cabos.", "gray"),
        ("", "gray"), ("Custo:", "yellow"), (" 1x Dispenser", "green"), (" 4x TNT", "red"), (" 2x Bloco de Redstone", "green"),
        (" 4x Chapa de Aco", "red"), (" 2x Motor Eletrico", "red"), (" 1x Circuito Avancado", "red"),
        ("Requer nivel 4", "red")])
    return out


if __name__ == "__main__":
    ensure_textures()
    OUT.mkdir(parents=True, exist_ok=True)
    for name, img in scenes().items():
        img.save(OUT / name)
        print("ok", name)
