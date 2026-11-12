"""
Gera os diagramas do README e da wiki em docs/img/.

    python docs/diagrams.py

Requer matplotlib. Os numeros aqui espelham o codigo (Engineer, Farmer, Explorer, Relics,
HardcoreRouletteClasses); ao mudar um balanceamento, atualize a tabela correspondente e rode de novo.
"""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch

OUT = Path(__file__).resolve().parent / "img"
OUT.mkdir(exist_ok=True)

# ---------------------------------------------------------------- estilo
BG = "#ffffff"
INK = "#1f2328"
MUTED = "#57606a"
LINE = "#8c959f"
ENG = "#d1495b"     # Engenharia
EXP = "#2e86ab"     # Exploracao
FARM = "#3c9d5d"    # Plantacao
GOLD = "#e0a100"    # componentes / destaque
SOFT = {ENG: "#fbe9eb", EXP: "#e6f2f8", FARM: "#e8f5ec", GOLD: "#fdf3d6", LINE: "#f3f4f6"}

plt.rcParams.update({
    "font.family": "DejaVu Sans",
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
    if title:
        ax.text(0.4, h - 0.45, title, fontsize=17, weight="bold", va="center")
    if sub:
        ax.text(0.4, h - 0.85, sub, fontsize=10, color=MUTED, va="center")
    return fig, ax


def box(ax, x, y, w, h, title, lines=(), color=LINE, fs=10, title_fs=11.5, align="left"):
    """Caixa com faixa colorida a esquerda. (x, y) e o canto inferior esquerdo."""
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0,rounding_size=0.12",
                                fc=SOFT.get(color, "#f6f8fa"), ec=color, lw=1.4))
    ax.add_patch(FancyBboxPatch((x, y), 0.09, h, boxstyle="round,pad=0,rounding_size=0.04",
                                fc=color, ec="none"))
    tx = x + 0.25 if align == "left" else x + w / 2
    ha = "left" if align == "left" else "center"
    ax.text(tx, y + h - 0.28, title, fontsize=title_fs, weight="bold", va="center", ha=ha)
    for i, ln in enumerate(lines):
        ax.text(tx, y + h - 0.62 - i * 0.29, ln, fontsize=fs, color=INK, va="center", ha=ha)


def arrow(ax, a, b, color=LINE, rad=0.0, lw=1.6, style="-|>"):
    ax.add_patch(FancyArrowPatch(a, b, arrowstyle=style, mutation_scale=14, color=color, lw=lw,
                                 connectionstyle=f"arc3,rad={rad}", shrinkA=2, shrinkB=2))


def save(fig, name):
    fig.savefig(OUT / name, bbox_inches="tight", pad_inches=0.15)
    plt.close(fig)
    print("ok", name)


# ---------------------------------------------------------------- 1. visao geral
def visao_geral():
    fig, ax = canvas(14, 7.0, "HardcoreRouletteClasses: habilidades",
                     "Ninguem escolhe classe: todo jogador sobe as tres. O nivel libera o conteudo.")
    box(ax, 5.2, 4.2, 3.6, 1.35, "Jogador (vida hardcore)",
        ["XP preso ao mundo hc_* da run", "Morreu: tudo volta ao nivel 1"], LINE, align="center")

    box(ax, 0.4, 0.9, 4.2, 2.6, "Engenharia", [
        "Sobe: fundir, montar na bancada,",
        "fornalha, maquinas, abates de torre",
        "XP: 0 / 25 / 75 / 175 / 350",
        "",
        "Libera: torres, energia, cabos,",
        "maquinas, linha industrial,",
        "Tuneladora (nivel 5)",
    ], ENG)
    box(ax, 4.9, 0.9, 4.2, 2.6, "Exploracao", [
        "Sobe: cada chunk novo visitado",
        "XP: 0 / 150 / 500 / 1200 / 2500",
        "",
        "Libera: companheiro (ritual de velas),",
        "Ancora de Perola (500 a 4000 blocos),",
        "orbes +4% a +20%, bonus de carne,",
        "Livros Antigos em baus de estrutura",
    ], EXP)
    box(ax, 9.4, 0.9, 4.2, 2.6, "Plantacao", [
        "Sobe: colher maduro (+1 a cada 4),",
        "sementes dos plantadores (+1 a cada 10)",
        "XP: 0 / 60 / 200 / 500 / 1000",
        "",
        "Libera: Plantador de Cobre (golem),",
        "mais golems, raio maior,",
        "colheita extra de 5% a 25%",
    ], FARM)
    for cx, c in ((2.5, ENG), (7.0, EXP), (11.5, FARM)):
        arrow(ax, (7.0, 4.2), (cx, 3.52), c, lw=2)

    ax.text(7.0, 0.45, "Party: parte do XP de classe ganho e dividido com quem esta na party "
            "(HardcoreRoulette).", ha="center", fontsize=10, color=MUTED)
    save(fig, "visao-geral.png")


# ---------------------------------------------------------------- 2. niveis da engenharia
ENG_UNLOCKS = {
    1: ["Fundicao Termica", "Torre Sentinela", "Cano da Torre", "Gerador a Carvao",
        "Painel Solar", "Inversor CC/CA", "Cabo e Juncao", "Postes"],
    2: ["Gerador Geotermico", "Bateria", "Forja de Municao", "Prensa Hidraulica",
        "Torno Mecanico", "Sola Isolante"],
    3: ["Motor Eletrico", "Bobinadeira", "Cadinho de Liga", "Gravadora",
        "Fornalha Eletrica", "Triturador", "Plantadeira", "Flecha de fogo 30%"],
    4: ["Torre Laser", "Montadora", "Escudo Eletrico", "Circuito Avancado",
        "Laminadora", "Broca de Diamante"],
    5: ["Mineradora", "Estacao Tuneladora", "Trem Tunelador", "Modulo de Coleta",
        "Tiro duplo"],
}


def engenharia_niveis():
    fig, ax = canvas(15, 5.6, "Engenharia: o que cada nivel libera",
                     "Torres por nivel 2 a 6, alcance 12 a 20 blocos, 1.0 a 2.0 tiros por segundo")
    xp = [0, 25, 75, 175, 350]
    turrets = [2, 3, 4, 5, 6]
    rng = [12, 14, 16, 18, 20]
    for i in range(5):
        x = 0.4 + i * 2.9
        n = len(ENG_UNLOCKS[i + 1])
        h = 1.25 + n * 0.29 + 0.2
        top = 4.6
        box(ax, x, top - h, 2.65, h, f"Nivel {i + 1}",
            [f"{xp[i]} XP, {turrets[i]} torres, alc. {rng[i]}", ""] + ENG_UNLOCKS[i + 1], ENG, fs=9.5)
        if i < 4:
            arrow(ax, (x + 2.66, top - 0.3), (x + 2.89, top - 0.3), ENG)
    save(fig, "engenharia-niveis.png")


# ---------------------------------------------------------------- 3. linha industrial
def linha_industrial():
    fig, ax = canvas(16, 9.6, "Linha industrial",
                     "Do bloco de ferro ate as maquinas avancadas. Numero entre parenteses: nivel minimo.")
    W, H = 2.6, 1.0

    def node(x, y, t, sub, c):
        box(ax, x, y, W, H, t, [sub], c, fs=9, title_fs=10.5)
        return (x, y)

    # coluna 0: materia prima
    node(0.3, 7.0, "Bloco de ferro", "vanilla", LINE)
    node(0.3, 5.4, "Bloco de cobre", "vanilla", LINE)
    node(0.3, 3.0, "Carvao / ouro", "redstone, quartzo", LINE)
    # coluna 1: fundicao
    node(3.5, 6.2, "Fundicao Termica (1)", "carvao ou lava, 20 s", ENG)
    # coluna 2: industriais
    node(6.7, 7.0, "Ferro Industrial", "base de tudo", GOLD)
    node(6.7, 5.4, "Cobre Industrial", "base de tudo", GOLD)
    # coluna 3: maquinas de processo
    node(9.9, 7.9, "Prensa (2)", "chapas ferro/cobre/aco", ENG)
    node(9.9, 6.6, "Torno (2)", "fio de cobre, engrenagem", ENG)
    node(9.9, 5.3, "Cadinho (3)", "aco, liga condutora", ENG)
    node(9.9, 4.0, "Bobinadeira (3)", "fio + chapa = bobina", ENG)
    node(9.9, 2.7, "Gravadora (3/4)", "circuito, circ. avancado", ENG)
    # coluna 4: componentes chave
    node(13.1, 7.0, "Motor Eletrico (3)", "bobina, 2 engren., chapa", GOLD)
    node(13.1, 4.6, "Circuito Avancado", "2 circuitos, liga, diamante", GOLD)

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
        ("Torre Sentinela", "2 Ferro Ind., motor, circuito"),
        ("Torre Laser", "4 chapa aco, 2 motores, circ. avancado"),
        ("Triturador", "chapa, engrenagem, motor"),
        ("Montadora", "chapa de aco, motor, circuito"),
        ("Escudo Eletrico", "chapa de aco, bobina, circuito"),
        ("Mineradora", "chapa de aco, motor, circ. avancado"),
    ]
    ax.text(0.3, 1.95, "Quem consome os componentes", fontsize=12, weight="bold")
    for i, (m, p) in enumerate(uses):
        x = 0.3 + (i % 3) * 5.2
        y = 1.35 - (i // 3) * 0.55
        ax.text(x, y, m, fontsize=10, weight="bold", color=ENG)
        ax.text(x + 1.75, y, p, fontsize=9.5, color=INK)
    save(fig, "linha-industrial.png")


# ---------------------------------------------------------------- 4. rede eletrica
def rede_eletrica():
    fig, ax = canvas(15, 7.4, "Rede eletrica",
                     "Tudo que encosta num cabo, juncao ou poste da mesma rede divide a energia.")
    box(ax, 0.4, 4.6, 3.3, 1.6, "Gerador a Carvao", ["4/s enquanto queima"], ENG)
    box(ax, 0.4, 2.8, 3.3, 1.6, "Geotermico", ["0.5/s por fonte de lava", "dobro no Nether"], ENG)
    box(ax, 0.4, 0.6, 3.3, 2.0, "Painel Solar", ["1.5/s de dia, ceu aberto", "corrente continua:", "precisa de Inversor"], ENG)
    box(ax, 4.5, 0.9, 2.5, 1.4, "Inversor CC/CA", ["ate 8/s, 90%"], GOLD)

    box(ax, 7.6, 2.4, 3.0, 3.0, "Rede", ["Cabo (corrente de cobre)", "Juncao (cobre encerado)",
                                          "Poste: ate 24 blocos", "Alavanca = disjuntor", "Cabo da choque"], LINE)
    box(ax, 7.6, 0.4, 3.0, 1.6, "Bateria", ["guarda ate 5000", "3 modos"], GOLD)

    box(ax, 11.4, 4.3, 3.2, 1.9, "Defesa", ["Torre Sentinela 2/s + tiro", "Torre Laser +25 por tiro",
                                            "Escudo 3/s + 1 por mob"], ENG)
    box(ax, 11.4, 1.9, 3.2, 2.1, "Maquinas", ["Fornalha 4/s, Triturador 5/s", "Prensa 6/s, Torno 4/s",
                                              "Cadinho 8/s, Gravadora 6/s", "Mineradora 12 por bloco"], ENG)
    box(ax, 11.4, 0.2, 3.2, 1.4, "Tuneladora", ["Trem 10/s escavando"], ENG)

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
    fig, axs = plt.subplots(1, 3, figsize=(15, 4.4), dpi=150)
    fig.suptitle("Curvas por nivel", fontsize=16, weight="bold", x=0.02, ha="left")

    a = axs[0]
    a.plot(lv, [2, 3, 4, 5, 6], marker="o", color=ENG, lw=2.2, label="torres")
    a.plot(lv, [x / 4 for x in [12, 14, 16, 18, 20]], marker="s", color=GOLD, lw=2.2, label="alcance / 4")
    a.plot(lv, [1.0, 1.2, 1.4, 1.7, 2.0], marker="^", color=MUTED, lw=2.2, label="tiros/s")
    a.set_title("Engenharia", loc="left", weight="bold", color=ENG)

    a = axs[1]
    a.bar(lv, [4, 8, 12, 16, 20], color=EXP, width=0.6, label="bonus orbes %")
    a.plot(lv, [7, 10, 13, 16, 19], marker="o", color=GOLD, lw=2.2, label="chance livro %")
    a.set_title("Exploracao", loc="left", weight="bold", color=EXP)

    a = axs[2]
    a.bar(lv, [5, 10, 15, 20, 25], color=FARM, width=0.6, label="colheita extra %")
    a.plot(lv, [4, 5, 6, 7, 8], marker="o", color=GOLD, lw=2.2, label="raio do golem")
    a.plot(lv, [1, 2, 2, 3, 4], marker="s", color=MUTED, lw=2.2, label="golems")
    a.set_title("Plantacao", loc="left", weight="bold", color=FARM)

    for a in axs:
        a.set_xticks(lv)
        a.set_xlabel("nivel")
        a.grid(axis="y", color="#eaeef2")
        a.spines[["top", "right"]].set_visible(False)
        a.legend(frameon=False, fontsize=9, loc="upper left")
    fig.tight_layout(rect=(0, 0, 1, 0.93))
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
        box(ax, x, y, 4.6, 1.25, f"{name}  {lv}", [f"{tgt}: {eff}"], TARGET_COLOR[tgt], fs=9.5)
    ax.text(0.4, 0.35, "Aplicar: pegue o livro com o cursor e clique sobre o item no inventario. "
            "Qualquer jogador usa o item encantado; so o achado depende da Exploracao.",
            fontsize=10, color=MUTED)
    save(fig, "livros-antigos.png")


# ---------------------------------------------------------------- 7. arquitetura do codigo
def arquitetura():
    fig, ax = canvas(15, 8.4, "Arquitetura do plugin (dev.math.hrclasses)",
                     "Linhas aproximadas de cada classe Java. Setas: quem cria ou chama quem.")
    box(ax, 5.6, 6.0, 3.8, 1.3, "HardcoreRouletteClasses", ["main, comandos, XP e niveis (400)"], LINE)
    box(ax, 0.3, 3.4, 3.4, 1.6, "Engineer", ["torres, energia, rede,", "bancada, maquinas (2830)"], ENG)
    box(ax, 4.0, 3.4, 3.2, 1.6, "Explorer", ["chunks, companheiro,", "ancora, orbes (915)"], EXP)
    box(ax, 7.5, 3.4, 3.2, 1.6, "Farmer", ["Plantador de Cobre,", "colheita extra (430)"], FARM)
    box(ax, 11.0, 3.4, 3.6, 1.6, "Relics", ["Livros Antigos,", "loot de estrutura (445)"], EXP)
    box(ax, 0.3, 0.6, 3.4, 1.6, "Factory", ["linha industrial,", "tela das maquinas (475)"], ENG)
    box(ax, 4.0, 0.6, 3.2, 1.6, "Tunnel", ["Tuneladora e trem (600)"], ENG)
    box(ax, 7.5, 0.6, 3.2, 1.6, "EnergyFx", ["particulas nos cabos (80)"], ENG)
    box(ax, 11.0, 0.6, 3.6, 1.6, "RecipeGrid", ["tela 3x3 de receita (100)"], LINE)
    box(ax, 10.6, 6.0, 4.0, 1.3, "HardcoreRoulette", ["party: divide XP (reflexao)"], GOLD)

    for cx in (2.0, 5.6, 9.1, 12.8):
        arrow(ax, (7.5, 6.0), (cx, 5.02))
    arrow(ax, (2.0, 3.4), (2.0, 2.22), ENG)
    arrow(ax, (2.6, 3.4), (5.6, 2.22), ENG)
    arrow(ax, (3.2, 3.4), (9.1, 2.22), ENG)
    arrow(ax, (9.1, 3.4), (12.4, 2.22), FARM)
    arrow(ax, (5.6, 3.4), (12.0, 2.22), EXP, 0.15)
    arrow(ax, (9.4, 6.65), (10.6, 6.65), GOLD, style="<|-|>")
    save(fig, "arquitetura.png")


if __name__ == "__main__":
    visao_geral()
    engenharia_niveis()
    linha_industrial()
    rede_eletrica()
    graficos_nivel()
    livros_antigos()
    arquitetura()
