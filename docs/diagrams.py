"""
Gera os diagramas do README e da wiki em docs/img/.

    python docs/diagrams.py

Requer matplotlib. Os icones dos itens saem das texturas do Minecraft instalado no PC
(o jar do cliente em .minecraft/versions, extraido para docs/.textures, que nao vai para o git).
Sem o jar, ou com MC_TEXTURES=off, os diagramas saem sem icones. MC_JAR aponta um jar especifico.

Os numeros aqui espelham o codigo (Engineer, Farmer, Explorer, Relics,
Classforge); ao mudar um balanceamento, atualize a tabela correspondente e rode de novo.
"""
import os
import zipfile
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, FancyArrowPatch
from matplotlib import font_manager

OUT = Path(__file__).resolve().parent / "img"
OUT.mkdir(exist_ok=True)
TEX = Path(__file__).resolve().parent / ".textures"


# ---------------------------------------------------------------- texturas
def _client_jar():
    if os.environ.get("MC_JAR"):
        return Path(os.environ["MC_JAR"])
    base = Path(os.environ.get("APPDATA", Path.home() / ".config")) / ".minecraft" / "versions"
    if not base.is_dir():
        base = Path.home() / ".minecraft" / "versions"
    jars = []
    for d in base.glob("*") if base.is_dir() else []:
        j = d / (d.name + ".jar")
        if j.is_file() and d.name[:1].isdigit() and "-" not in d.name:  # so versoes oficiais
            jars.append(j)
    key = lambda j: [int(p) if p.isdigit() else 0 for p in j.stem.split(".")]
    return max(jars, key=key) if jars else None


def _textures():
    if os.environ.get("MC_TEXTURES") == "off":
        return False
    if (TEX / "item").is_dir():
        return True
    jar = _client_jar()
    if not jar:
        print("aviso: jar do Minecraft nao encontrado, diagramas sem icones")
        return False
    print("extraindo texturas de", jar.name)
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            for kind in ("item", "block"):
                pre = f"assets/minecraft/textures/{kind}/"
                if n.startswith(pre) and n.endswith(".png") and "/" not in n[len(pre):]:
                    out = TEX / kind / n[len(pre):]
                    out.parent.mkdir(parents=True, exist_ok=True)
                    out.write_bytes(z.read(n))
    return True


HAS_TEX = _textures()

# id do item (no plugin ou vanilla) -> textura. Blocos usam uma face.
ICON = {
    # engenharia: dispositivos
    "torre": "block/dispenser_front", "torre_laser": "block/tnt_side", "cano": "block/iron_bars",
    "carvao": "block/furnace_front_on", "geotermico": "block/magma", "solar": "block/daylight_detector_top",
    "inversor": "block/copper_bulb_lit", "bateria": "block/lodestone_side", "cabo": "item/copper_chain",
    "juncao": "block/copper_block", "poste": "block/chiseled_copper", "forja": "block/furnace_front_on",
    "fundicao": "block/blast_furnace_front_on", "fornalha": "block/smoker_front_on",
    "triturador": "block/dropper_front", "plantadeira": "block/barrel_side", "montadora": "block/crafter_north",
    "escudo": "block/lightning_rod", "mineradora": "item/hopper", "prensa": "block/piston_side",
    "torno": "block/stonecutter_top", "bobinadeira": "block/loom_front", "cadinho": "item/cauldron",
    "gravadora": "block/cartography_table_top", "laminadora": "block/fletching_table_front",
    "broca": "item/pointed_dripstone", "estacao": "block/observer_front", "trem": "item/furnace_minecart",
    "modulo_coleta": "item/hopper_minecart", "modulo_isolante": "item/leather", "bancada": "block/smithing_table_front",
    # componentes
    "ferro_industrial": "item/iron_ingot", "cobre_industrial": "item/copper_ingot",
    "chapa_ferro": "block/iron_block", "chapa_cobre": "block/copper_trapdoor", "chapa_aco": "block/iron_trapdoor",
    "fio_cobre": "item/string", "engrenagem": "item/firework_star", "bobina": "block/copper_bars",
    "aco": "item/netherite_ingot", "liga": "item/gold_ingot", "circuito": "item/repeater",
    "processador": "item/comparator", "motor": "block/heavy_core", "bala": "item/iron_nugget",
    "flecha_fogo": "item/fire_charge", "tiro_duplo": "item/crossbow_standby",
    # vanilla
    "bloco_ferro": "block/iron_block", "bloco_cobre": "block/copper_block", "carvao_item": "item/coal",
    "redstone": "item/redstone", "diamante": "item/diamond",
    # habilidades
    "engenharia": "block/dispenser_front", "exploracao": "item/compass_00", "plantacao": "item/wheat",
    "jogador": "item/totem_of_undying", "party": "item/experience_bottle",
    "companheiro": "item/bone", "ancora": "item/ender_pearl", "golem": "item/copper_golem_spawn_egg",
    "livro": "item/enchanted_book", "carne": "item/cooked_beef",
    # alvos dos livros
    "picareta": "item/iron_pickaxe", "machado": "item/iron_axe", "espada/machado": "item/iron_sword",
    "enxada": "item/iron_hoe", "armadura": "item/iron_chestplate", "capacete": "item/iron_helmet",
    "botas": "item/iron_boots",
}

# texturas de modelo (varias faces num arquivo): recorta uma face
CROP = {"motor": (0, 0, 8, 8)}

_cache = {}


def tex(key):
    """Imagem RGBA da textura (primeiro quadro, se for animada) ou None."""
    if not HAS_TEX or key not in ICON:
        return None
    if key not in _cache:
        p = TEX / (ICON[key] + ".png")
        if not p.is_file():
            print("aviso: textura ausente", ICON[key])
            _cache[key] = None
        else:
            from PIL import Image
            import numpy as np
            im = Image.open(p).convert("RGBA")  # paleta e tons de cinza viram RGBA de verdade
            w = im.width
            if im.height > w:
                im = im.crop((0, 0, w, w))  # textura animada: primeiro quadro
            if key in CROP:
                im = im.crop(CROP[key])
            _cache[key] = np.asarray(im)
    return _cache[key]


def draw_icon(ax, key, cx, cy, size):
    img = tex(key)
    if img is None:
        return False
    h = size / 2
    ax.imshow(img, extent=(cx - h, cx + h, cy - h, cy + h), interpolation="nearest", aspect="auto", zorder=6)
    return True

# ---------------------------------------------------------------- estilo
BG = "#f6f7f9"       # fundo da pagina
CARD = "#ffffff"
EDGE = "#e2e5ea"
SHADOW = "#e9ebef"
INK = "#1f2328"
MUTED = "#57606a"
LINE = "#8c959f"
ENG = "#d1495b"     # Engenharia
EXP = "#2e86ab"     # Exploracao
FARM = "#3c9d5d"    # Plantacao
GOLD = "#e0a100"    # componentes / destaque
SOFT = {ENG: "#fbe9eb", EXP: "#e6f2f8", FARM: "#e8f5ec", GOLD: "#fdf3d6", LINE: "#f3f4f6"}

_FONTS = {f.name for f in font_manager.fontManager.ttflist}
FONT = next((f for f in ("Inter", "Segoe UI", "Helvetica Neue", "Arial") if f in _FONTS), "DejaVu Sans")

plt.rcParams.update({
    "font.family": FONT,
    "font.size": 10,
    "text.color": INK,
    "axes.edgecolor": LINE,
    "axes.labelcolor": INK,
    "xtick.color": MUTED,
    "ytick.color": MUTED,
    "figure.facecolor": BG,
    "axes.facecolor": BG,
    "savefig.facecolor": BG,
})


def canvas(w, h, title=None, sub=None):
    fig = plt.figure(figsize=(w, h), dpi=150)
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, w)
    ax.set_ylim(0, h)
    ax.axis("off")
    ax.autoscale(False)
    if title:
        ax.text(0.4, h - 0.45, title, fontsize=18, weight="bold", va="center")
    if sub:
        ax.text(0.4, h - 0.85, sub, fontsize=10.5, color=MUTED, va="center")
    return fig, ax


def box(ax, x, y, w, h, title, lines=(), color=LINE, fs=10, title_fs=11.5, align="left", icon=None, icons=None,
        icon_size=0.55):
    """Caixa com faixa colorida a esquerda. (x, y) e o canto inferior esquerdo.
    icon: textura grande no canto superior direito. icons: uma por linha (None = sem icone)."""
    ax.add_patch(Rectangle((x + 0.035, y - 0.045), w, h, fc=SHADOW, ec="none", zorder=1))
    ax.add_patch(Rectangle((x, y), w, h, fc=CARD, ec=EDGE, lw=0.8, zorder=2))
    ax.add_patch(Rectangle((x, y + h - 0.06), w, 0.06, fc=color, ec="none", zorder=3))
    tx = x + 0.22 if align == "left" else x + w / 2
    ha = "left" if align == "left" else "center"
    ax.text(tx, y + h - 0.34, title, fontsize=title_fs, weight="bold", va="center", ha=ha, zorder=4)
    if icon:
        sz = min(icon_size, h - 0.25)
        draw_icon(ax, icon, x + w - 0.15 - sz / 2, y + h - 0.16 - sz / 2, sz)
    for i, ln in enumerate(lines):
        ly = y + h - 0.68 - i * 0.29
        k = icons[i] if icons and i < len(icons) else None
        if k and draw_icon(ax, k, tx + 0.11, ly, 0.24):
            ax.text(tx + 0.32, ly, ln, fontsize=fs, color="#3d444d", va="center", ha=ha, zorder=4)
        else:
            ax.text(tx, ly, ln, fontsize=fs, color="#3d444d", va="center", ha=ha, zorder=4)


def arrow(ax, a, b, color=LINE, rad=0.0, lw=1.3, style="-|>"):
    ax.add_patch(FancyArrowPatch(a, b, arrowstyle=style, mutation_scale=11, color=color, lw=lw, alpha=0.85,
                                 connectionstyle=f"arc3,rad={rad}", shrinkA=3, shrinkB=3, zorder=1.5))


def style_axes(a):
    """Grafico no mesmo visual dos cards: fundo branco, grade leve, sem molduras."""
    a.set_facecolor(CARD)
    a.grid(axis="y", color="#edf0f3", lw=0.9)
    a.set_axisbelow(True)
    for sp in ("top", "right", "left"):
        a.spines[sp].set_visible(False)
    a.spines["bottom"].set_color(EDGE)
    a.tick_params(length=0)


def save(fig, name):
    fig.savefig(OUT / name, bbox_inches="tight", pad_inches=0.15)
    plt.close(fig)
    print("ok", name)


# ---------------------------------------------------------------- 1. visao geral
def visao_geral():
    fig, ax = canvas(14, 7.0, "Classforge: habilidades",
                     "Ninguem escolhe classe: todo jogador sobe as tres. O nivel libera o conteudo.")
    box(ax, 5.2, 4.2, 3.6, 1.35, "Jogador",
        ["Niveis 1 a 5 em cada habilidade", "HardcoreRoulette: zera a cada vida"], LINE, align="center", icon="jogador")

    box(ax, 0.4, 0.9, 4.2, 2.6, "Engenharia", [
        "Sobe: fundir, montar na bancada,",
        "fornalha, maquinas, abates de torre",
        "XP: 0 / 10 / 35 / 125 / 350",
        "",
        "Libera: torres, energia, cabos,",
        "maquinas, linha industrial,",
        "Tuneladora (nivel 5)",
    ], ENG, icon="engenharia")
    box(ax, 4.9, 0.9, 4.2, 2.6, "Exploracao", [
        "Sobe: cada chunk novo visitado",
        "XP: 0 / 70 / 265 / 900 / 2500",
        "",
        "Libera: companheiro (ritual de velas),",
        "Ancora de Perola (500 a 4000 blocos),",
        "orbes +4% a +20%, bonus de carne,",
        "Livros Antigos em baus de estrutura",
    ], EXP, icon="exploracao")
    box(ax, 9.4, 0.9, 4.2, 2.6, "Plantacao", [
        "Sobe: colher maduro (+1 a cada 4),",
        "sementes dos plantadores (+1 a cada 10)",
        "XP: 0 / 30 / 105 / 360 / 1000",
        "",
        "Libera: Plantador de Cobre (golem),",
        "mais golems, raio maior,",
        "colheita extra de 5% a 25%",
    ], FARM, icon="plantacao")
    for cx, c in ((2.5, ENG), (7.0, EXP), (11.5, FARM)):
        arrow(ax, (7.0, 4.2), (cx, 3.52), c, lw=2)

    ax.text(7.0, 0.45, "Party: parte do XP de classe ganho e dividido com quem esta na party "
            "(HardcoreRoulette).", ha="center", fontsize=10, color=MUTED)
    save(fig, "visao-geral.png")


# ---------------------------------------------------------------- 2. niveis da engenharia
ENG_UNLOCKS = {
    1: [("fundicao", "Fundicao Termica"), ("torre", "Torre Sentinela"), ("cano", "Cano da Torre"),
        ("carvao", "Gerador a Carvao"), ("solar", "Painel Solar"), ("inversor", "Inversor CC/CA"),
        ("cabo", "Cabo e Juncao"), ("poste", "Postes")],
    2: [("geotermico", "Gerador Geotermico"), ("bateria", "Bateria"), ("forja", "Forja de Municao"),
        ("prensa", "Prensa Hidraulica"), ("torno", "Torno Mecanico"), ("modulo_isolante", "Sola Isolante")],
    3: [("motor", "Motor Eletrico"), ("bobinadeira", "Bobinadeira"), ("cadinho", "Cadinho de Liga"),
        ("gravadora", "Gravadora"), ("fornalha", "Fornalha Eletrica"), ("triturador", "Triturador"),
        ("plantadeira", "Plantadeira"), ("flecha_fogo", "Flecha de fogo 30%")],
    4: [("torre_laser", "Torre Laser"), ("montadora", "Montadora"), ("escudo", "Escudo Eletrico"),
        ("processador", "Circuito Avancado"), ("laminadora", "Laminadora"), ("broca", "Broca de Diamante")],
    5: [("mineradora", "Mineradora"), ("estacao", "Estacao Tuneladora"), ("trem", "Trem Tunelador"),
        ("modulo_coleta", "Modulo de Coleta"), ("tiro_duplo", "Tiro duplo")],
}


def engenharia_niveis():
    fig, ax = canvas(15, 5.6, "Engenharia: o que cada nivel libera",
                     "Torres por nivel 2 a 6, alcance 12 a 20 blocos, 1.0 a 2.0 tiros por segundo")
    xp = [0, 10, 35, 125, 350]
    turrets = [2, 3, 4, 5, 6]
    rng = [12, 14, 16, 18, 20]
    for i in range(5):
        x = 0.4 + i * 2.9
        n = len(ENG_UNLOCKS[i + 1])
        h = 1.25 + n * 0.29 + 0.2
        top = 4.6
        items = ENG_UNLOCKS[i + 1]
        box(ax, x, top - h, 2.65, h, f"Nivel {i + 1}",
            [f"{xp[i]} XP, {turrets[i]} torres, alc. {rng[i]}", ""] + [t for _, t in items], ENG, fs=9.5,
            icons=[None, None] + [k for k, _ in items])
        if i < 4:
            arrow(ax, (x + 2.66, top - 0.3), (x + 2.89, top - 0.3), ENG)
    save(fig, "engenharia-niveis.png")


# ---------------------------------------------------------------- 3. linha industrial
def linha_industrial():
    fig, ax = canvas(16, 9.6, "Linha industrial",
                     "Do bloco de ferro ate as maquinas avancadas. Numero entre parenteses: nivel minimo.")
    W, H = 2.6, 1.0

    def node(x, y, t, sub, c, ic=None):
        box(ax, x, y, W, H, t, [sub], c, fs=9, title_fs=10.5, icon=ic, icon_size=0.4)
        return (x, y)

    # coluna 0: materia prima
    node(0.3, 7.0, "Bloco de ferro", "vanilla", LINE, "bloco_ferro")
    node(0.3, 5.4, "Bloco de cobre", "vanilla", LINE, "bloco_cobre")
    node(0.3, 3.0, "Carvao / ouro", "redstone, quartzo", LINE, "redstone")
    # coluna 1: fundicao
    node(3.5, 6.2, "Fundicao Termica (1)", "carvao ou lava, 20 s", ENG, "fundicao")
    # coluna 2: industriais
    node(6.7, 7.0, "Ferro Industrial", "base de tudo", GOLD, "ferro_industrial")
    node(6.7, 5.4, "Cobre Industrial", "base de tudo", GOLD, "cobre_industrial")
    # coluna 3: maquinas de processo
    node(9.9, 7.9, "Prensa (2)", "chapas ferro/cobre/aco", ENG, "prensa")
    node(9.9, 6.6, "Torno (2)", "fio de cobre, engrenagem", ENG, "torno")
    node(9.9, 5.3, "Cadinho (3)", "aco, liga condutora", ENG, "cadinho")
    node(9.9, 4.0, "Bobinadeira (3)", "fio + chapa = bobina", ENG, "bobinadeira")
    node(9.9, 2.7, "Gravadora (3/4)", "circuito, circ. avancado", ENG, "gravadora")
    # coluna 4: componentes chave
    node(13.1, 7.0, "Motor Eletrico (3)", "bobina, 2 engren., chapa", GOLD, "motor")
    node(13.1, 4.6, "Circuito Avancado", "2 circuitos, liga, diamante", GOLD, "processador")

    def a(p, q, c=LINE, rad=0.0):
        arrow(ax, p, q, c, rad)

    a((2.9, 7.5), (3.5, 6.8))
    a((2.9, 5.9), (3.5, 6.5))
    a((6.1, 6.9), (6.7, 7.4))
    a((6.1, 6.5), (6.7, 5.9))
    a((9.3, 7.8), (9.9, 8.4))            # ferro ind. -> prensa
    a((9.3, 7.2), (9.9, 5.9), rad=0.1)   # ferro ind. -> cadinho (aco)
    a((9.3, 6.3), (9.9, 8.1), rad=-0.1)  # cobre ind. -> prensa
    a((9.3, 6.0), (9.9, 7.0))            # cobre ind. -> torno
    a((9.3, 5.6), (9.9, 5.6))            # cobre ind. -> cadinho (liga)
    a((2.9, 3.4), (9.9, 3.1), LINE, -0.1)
    a((2.9, 3.6), (9.9, 5.5), LINE, 0.15)
    a((12.5, 4.5), (13.1, 7.2), GOLD, 0.2)   # bobina -> motor
    a((12.5, 7.1), (13.1, 7.4), GOLD)        # engrenagem -> motor
    a((12.5, 8.4), (13.1, 7.8), GOLD)        # chapa -> motor
    a((12.5, 3.2), (13.1, 4.8), GOLD)        # circuito -> avancado
    a((12.5, 5.6), (13.1, 5.2), GOLD)        # liga -> avancado
    ax.text(9.9, 2.25, "Chapas e fios da Prensa e do Torno alimentam Bobinadeira e Gravadora.",
            fontsize=9, color=MUTED)

    # rodape: quem usa o que
    uses = [
        ("torre", "Torre Sentinela", "2 Ferro Ind., motor, circuito"),
        ("torre_laser", "Torre Laser", "4 chapa aco, 2 motores, circ. avancado"),
        ("triturador", "Triturador", "chapa, engrenagem, motor"),
        ("montadora", "Montadora", "chapa de aco, motor, circuito"),
        ("escudo", "Escudo Eletrico", "chapa de aco, bobina, circuito"),
        ("mineradora", "Mineradora", "chapa de aco, motor, circ. avancado"),
    ]
    ax.text(0.3, 1.95, "Quem consome os componentes", fontsize=12, weight="bold")
    for i, (k, m, p) in enumerate(uses):
        x = 0.3 + (i % 3) * 5.2
        y = 1.35 - (i // 3) * 0.55
        dx = 0.36 if draw_icon(ax, k, x + 0.13, y, 0.28) else 0
        ax.text(x + dx, y, m, fontsize=10, weight="bold", color=ENG, va="center")
        ax.text(x + 1.75 + dx, y, p, fontsize=9.5, color=INK, va="center")
    save(fig, "linha-industrial.png")


# ---------------------------------------------------------------- 4. rede eletrica
def rede_eletrica():
    fig, ax = canvas(15, 7.4, "Rede eletrica",
                     "Tudo que encosta num cabo, juncao ou poste da mesma rede divide a energia.")
    box(ax, 0.4, 4.6, 3.3, 1.6, "Gerador a Carvao", ["4/s enquanto queima"], ENG, icon="carvao")
    box(ax, 0.4, 2.8, 3.3, 1.6, "Geotermico", ["0.5/s por fonte de lava", "dobro no Nether"], ENG, icon="geotermico")
    box(ax, 0.4, 0.6, 3.3, 2.0, "Painel Solar", ["1.5/s de dia, ceu aberto", "corrente continua:", "precisa de Inversor"], ENG, icon="solar")
    box(ax, 4.5, 0.9, 2.5, 1.4, "Inversor CC/CA", ["ate 8/s, 90%"], GOLD, icon="inversor")

    box(ax, 7.6, 2.4, 3.0, 3.0, "Rede", ["Cabo (corrente de cobre)", "Juncao (cobre encerado)",
                                          "Poste: ate 24 blocos", "Alavanca = disjuntor", "Cabo da choque"], LINE,
        icons=["cabo", "juncao", "poste", None, None])
    box(ax, 7.6, 0.4, 3.0, 1.6, "Bateria", ["guarda ate 5000", "3 modos"], GOLD, icon="bateria")

    box(ax, 11.4, 4.3, 3.2, 1.9, "Defesa", ["Torre Sentinela 2/s + tiro", "Torre Laser +25 por tiro",
                                            "Escudo 3/s + 1 por mob"], ENG,
        icons=["torre", "torre_laser", "escudo"])
    box(ax, 11.4, 1.9, 3.2, 2.1, "Maquinas", ["Fornalha 4/s, Triturador 5/s", "Prensa 6/s, Torno 4/s",
                                              "Cadinho 8/s, Gravadora 6/s", "Mineradora 12 por bloco"], ENG, icon="triturador")
    box(ax, 11.4, 0.2, 3.2, 1.4, "Tuneladora", ["Trem 10/s escavando"], ENG, icon="trem")

    arrow(ax, (3.7, 5.4), (7.6, 4.6), ENG)
    arrow(ax, (3.7, 3.6), (7.6, 3.9), ENG)
    arrow(ax, (3.7, 1.6), (4.5, 1.6), ENG)
    arrow(ax, (7.0, 1.6), (7.6, 2.9), GOLD)
    arrow(ax, (9.1, 2.4), (9.1, 2.0), GOLD, style="<|-|>")
    arrow(ax, (10.6, 4.6), (11.4, 5.2), LINE)
    arrow(ax, (10.6, 3.8), (11.4, 3.0), LINE)
    arrow(ax, (10.6, 2.8), (11.4, 0.9), LINE)
    save(fig, "rede-eletrica.png")


# ---------------------------------------------------------------- 5. graficos de nivel
def graficos_nivel():
    lv = [1, 2, 3, 4, 5]
    panels = [
        ("Torres por jogador", ENG, [2, 3, 4, 5, 6], "", "engineering.turrets.max-per-level"),
        ("Alcance das torres (blocos)", ENG, [12, 14, 16, 18, 20], "", "engineering.turrets.range-per-level"),
        ("Bonus no XP dos orbes", EXP, [4, 8, 12, 16, 20], "%", "exploration.orb-xp-bonus-percent"),
        ("Colheita extra", FARM, [5, 10, 15, 20, 25], "%", "farming.harvest-bonus-percent"),
    ]
    fig, axs = plt.subplots(1, 4, figsize=(16, 4.2), dpi=150)
    fig.suptitle("O que cada nivel melhora (padrao)", fontsize=16, weight="bold", x=0.015, ha="left")
    for a, (title, color, vals, unit, key) in zip(axs, panels):
        bars = a.bar(lv, vals, color=color, width=0.62)
        for r, v in zip(bars, vals):
            a.text(r.get_x() + r.get_width() / 2, v, f"{v}{unit}", ha="center", va="bottom", fontsize=10, weight="bold", color=INK)
        a.set_title(title, loc="left", weight="bold", color=color, fontsize=11.5)
        style_axes(a)
        a.set_yticks([])
        a.set_xticks(lv, [f"nv {n}" for n in lv])
        a.set_ylim(0, max(vals) * 1.18)
        a.text(0, -0.2, key, transform=a.transAxes, fontsize=8.5, color=MUTED, family="monospace")
    fig.tight_layout(rect=(0, 0.03, 1, 0.92))
    save(fig, "curvas-nivel.png")


# ---------------------------------------------------------------- 6. livros antigos
RELICS = [
    ("Veio", "I-III", "picareta", "agachado: 6/12/20 blocos do veio"),
    ("Sabedoria", "I-III", "picareta", "+50/100/150% XP de minerio"),
    ("Lenhador", "I", "machado", "agachado: arvore inteira (48)"),
    ("Decapitacao", "I-III", "espada/machado", "4% por nivel de cabeca"),
    ("Replantio", "I", "enxada", "replanta ao colher"),
    ("Colheita", "I", "enxada", "colhe 3x3 maduro"),
    ("Arado", "I", "enxada", "ara 3x3"),
    ("Protecao Antiga", "I-III", "armadura", "4%/nivel: golpe pela metade (max 24%)"),
    ("Visao Noturna", "I", "capacete", "visao noturna"),
    ("Sereia", "I", "capacete", "respira na agua"),
    ("Implantes", "I", "capacete", "+1 fome a cada 6 s andando"),
    ("Molas", "I-II", "botas", "pulo I / II"),
]
TARGET_COLOR = {"picareta": ENG, "machado": GOLD, "espada/machado": GOLD, "enxada": FARM,
                "armadura": EXP, "capacete": EXP, "botas": EXP}


def livros_antigos():
    fig, ax = canvas(15, 8.0, "Livros Antigos (Exploracao)",
                     "Baus gerados pelo mapa (estruturas, dungeons, tavernas). Chance 4% + 3% por nivel de Exploracao.")
    for i, (name, lv, tgt, eff) in enumerate(RELICS):
        col, row = i % 3, i // 3
        x = 0.4 + col * 4.85
        y = 5.6 - row * 1.5
        box(ax, x, y, 4.6, 1.25, f"{name}  {lv}", [f"{tgt}: {eff}"], TARGET_COLOR[tgt], fs=9.5, icon=tgt)
        draw_icon(ax, "livro", x + 4.6 - 0.86, y + 1.25 - 0.4, 0.42)
    ax.text(0.4, 0.35, "Aplicar: pegue o livro com o cursor e clique sobre o item no inventario. "
            "Qualquer jogador usa o item encantado; so o achado depende da Exploracao.",
            fontsize=10, color=MUTED)
    save(fig, "livros-antigos.png")


# ---------------------------------------------------------------- 7. arquitetura do codigo
def arquitetura():
    fig, ax = canvas(15, 8.4, "Arquitetura do plugin (dev.math.classforge)",
                     "Linhas aproximadas de cada classe Java. Setas: quem cria ou chama quem.")
    box(ax, 5.6, 6.0, 3.8, 1.3, "Classforge", ["main, comandos, XP e niveis (400)"], LINE)
    box(ax, 0.3, 3.4, 3.4, 1.6, "Engineer", ["torres, energia, rede,", "bancada, maquinas (2830)"], ENG)
    box(ax, 4.0, 3.4, 3.2, 1.6, "Explorer", ["chunks, companheiro,", "ancora, orbes (915)"], EXP)
    box(ax, 7.5, 3.4, 3.2, 1.6, "Farmer", ["Plantador de Cobre,", "colheita extra (430)"], FARM)
    box(ax, 11.0, 3.4, 3.6, 1.6, "Relics", ["Livros Antigos,", "loot de estrutura (445)"], EXP)
    box(ax, 0.3, 0.6, 3.4, 1.6, "Factory", ["linha industrial,", "tela das maquinas (475)"], ENG)
    box(ax, 4.0, 0.6, 3.2, 1.6, "Tunnel", ["Tuneladora e trem (600)"], ENG)
    box(ax, 7.5, 0.6, 3.2, 1.6, "EnergyFx", ["particulas nos cabos (80)"], ENG)
    box(ax, 11.0, 0.6, 3.6, 1.6, "RecipeGrid", ["tela 3x3 de receita (100)"], LINE)
    box(ax, 10.6, 6.0, 4.0, 1.3, "HardcoreRoulette (opcional)", ["party, vida por mundo (reflexao)"], GOLD)

    for cx in (2.0, 5.6, 9.1, 12.8):
        arrow(ax, (7.5, 6.0), (cx, 5.02))
    arrow(ax, (2.0, 3.4), (2.0, 2.22), ENG)
    arrow(ax, (2.6, 3.4), (5.6, 2.22), ENG)
    arrow(ax, (3.2, 3.4), (9.1, 2.22), ENG)
    arrow(ax, (9.1, 3.4), (12.4, 2.22), FARM)
    arrow(ax, (5.6, 3.4), (12.0, 2.22), EXP, 0.15)
    arrow(ax, (9.4, 6.65), (10.6, 6.65), GOLD, style="<|-|>")
    save(fig, "arquitetura.png")


# ---------------------------------------------------------------- 8. curva de XP
def curva_xp():
    import numpy as np
    curves = [("Engenharia", ENG, 350, [0, 25, 75, 175, 350], [0, 10, 35, 125, 350]),
              ("Exploracao", EXP, 2500, [0, 150, 500, 1200, 2500], [0, 70, 265, 900, 2500]),
              ("Plantacao", FARM, 1000, [0, 60, 200, 500, 1000], [0, 30, 105, 360, 1000])]
    fig, axs = plt.subplots(1, 3, figsize=(15, 4.4), dpi=150)
    fig.suptitle("XP total para chegar em cada nivel (padrao)", fontsize=16, weight="bold", x=0.02, ha="left")
    fig.text(0.02, 0.86, "Sobe devagar no comeco e acelera no fim. Ajuste em level-xp de cada habilidade "
             "(engineering, exploration, farming).", fontsize=10.5, color=MUTED)
    t = np.linspace(0, 1, 200)
    for a, (name, color, mx, old, new) in zip(axs, curves):
        y = mx * (0.1 * t + 0.9 * t ** 4)
        a.fill_between(1 + 4 * t, y, color=color, alpha=0.10)
        a.plot(1 + 4 * t, y, color=color, lw=2.4)
        a.plot(range(1, 6), new, "o", color=color, ms=7)
        for n, v in zip(range(1, 6), new):
            a.annotate(f"{v} XP", (n, v), textcoords="offset points", xytext=(-8, 8), fontsize=9.5, color=INK,
                       ha="right", weight="bold")
        a.set_title(name, loc="left", weight="bold", color=color)
        style_axes(a)
        a.set_xticks(range(1, 6), [f"nv {n}" for n in range(1, 6)])
        a.set_yticks([])
        a.set_ylim(0, mx * 1.15)
    fig.tight_layout(rect=(0, 0, 1, 0.84))
    save(fig, "curva-xp.png")


# ---------------------------------------------------------------- 9. mapa da configuracao
CONFIG_MAP = [
    # (secao, cor, icone, titulo, linhas)
    ("worlds", LINE, "exploracao", "Onde vale", ["Todos os mundos, por prefixo", "ou uma lista de mundos."]),
    ("progress", LINE, "jogador", "Progresso", ["Zerar niveis a cada vida;", "dividir XP com a party."]),
    ("modules", LINE, "bancada", "Modulos", ["Liga ou desliga cada", "habilidade inteira."]),
    ("engineering.turrets", ENG, "torre", "Torres", ["Quantas, alcance, cadencia,", "desgaste do cano, Laser."]),
    ("engineering.energy", ENG, "solar", "Energia", ["Quanto cada gerador produz,", "bateria e inversor."]),
    ("engineering.machines", ENG, "triturador", "Maquinas", ["Tempo e gasto de energia", "de cada maquina."]),
    ("engineering.recipes", ENG, "ferro_industrial", "Receitas", ["Nivel, materiais e pecas;", "desligar itens."]),
    ("engineering.tunnel", ENG, "trem", "Tuneladora", ["Velocidade, energia,", "broca e comprimento."]),
    ("exploration", EXP, "ancora", "Exploracao", ["XP por nivel, ancora,", "bonus nos orbes."]),
    ("farming", FARM, "golem", "Plantacao", ["Raio, plantadores, ritmo", "e colheita extra."]),
    ("ancient-books", EXP, "livro", "Livros Antigos", ["Chance de achar e", "livros desligados."]),
    ("*.level-xp", GOLD, "party", "Curva de XP", ["XP para cada nivel", "de cada habilidade."]),
]


def mapa_config():
    fig, ax = canvas(16, 7.6, "Mapa do config.yml",
                     "Cada card e uma secao de plugins/Classforge/config.yml. Use /classe reload depois de editar "
                     "(modulos e receitas pedem reinicio).")
    cols, w, h, gx, gy = 4, 3.65, 1.55, 0.25, 0.35
    for i, (key, color, ic, title, lines) in enumerate(CONFIG_MAP):
        col, row = i % cols, i // cols
        x = 0.4 + col * (w + gx)
        y = 4.75 - row * (h + gy)
        box(ax, x, y, w, h, title, lines, color, fs=9.8, icon=ic, icon_size=0.45)
        ax.text(x + 0.22, y + 0.22, key, fontsize=8.8, color=MUTED, family="monospace", va="center", zorder=4)
    ax.text(0.4, 0.35, "Cinza: geral   Vermelho: Engenharia   Azul: Exploracao   Verde: Plantacao   "
            "Amarelo: vale para todas", fontsize=10, color=MUTED)
    save(fig, "mapa-config.png")


if __name__ == "__main__":
    visao_geral()
    engenharia_niveis()
    linha_industrial()
    rede_eletrica()
    graficos_nivel()
    livros_antigos()
    arquitetura()
    curva_xp()
    mapa_config()
