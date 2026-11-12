"""Traducao dos textos dos diagramas para ingles (pt-BR -> en). Usado por diagrams.py."""
import re

EN = {
    # visao geral
    "Classforge: habilidades": "Classforge: skills",
    "Ninguem escolhe classe: todo jogador sobe as tres. O nivel libera o conteudo.":
        "Nobody picks a class: every player levels all three. Levels unlock the content.",
    "Jogador": "Player",
    "Niveis 1 a 5 em cada habilidade": "Levels 1 to 5 in each skill",
    "HardcoreRoulette: zera a cada vida": "HardcoreRoulette: resets every life",
    "Engenharia": "Engineering",
    "Sobe: fundir, montar na bancada,": "Earned by smelting, crafting at the bench,",
    "fornalha, maquinas, abates de torre": "furnaces, machines, turret kills",
    "Libera: torres, energia, cabos,": "Unlocks: turrets, power, cables,",
    "maquinas, linha industrial,": "machines, industrial chain,",
    "Tuneladora (nivel 5)": "Tunnel Borer (level 5)",
    "Exploracao": "Exploration",
    "Sobe: cada chunk novo visitado": "Earned by visiting new chunks",
    "Libera: companheiro (ritual de velas),": "Unlocks: companion (candle ritual),",
    "Ancora de Perola (500 a 4000 blocos),": "Pearl Anchor (500 to 4000 blocks),",
    "orbes +4% a +20%, bonus de carne,": "XP orbs +4% to +20%, meat bonus,",
    "Livros Antigos em baus de estrutura": "Ancient Books in structure chests",
    "Plantacao": "Farming",
    "Sobe: colher maduro (+1 a cada 4),": "Earned by harvesting (+1 per 4 crops),",
    "sementes dos plantadores (+1 a cada 10)": "seeds your planters sow (+1 per 10)",
    "Libera: Plantador de Cobre (golem),": "Unlocks: Copper Planter (golem),",
    "mais golems, raio maior,": "more golems, bigger radius,",
    "colheita extra de 5% a 25%": "extra harvest from 5% to 25%",
    "Party: parte do XP de classe ganho e dividido com quem esta na party (HardcoreRoulette).":
        "Party: part of the skill XP you earn is shared with your party (HardcoreRoulette).",
    # niveis
    "Engenharia: o que cada nivel libera": "Engineering: what each level unlocks",
    "Torres por nivel 2 a 6, alcance 12 a 20 blocos, 1.0 a 2.0 tiros por segundo":
        "2 to 6 turrets, range 12 to 20 blocks, 1.0 to 2.0 shots per second",
    "Fundicao Termica": "Thermal Foundry", "Torre Sentinela": "Sentry Turret", "Cano da Torre": "Turret Barrel",
    "Gerador a Carvao": "Coal Generator", "Painel Solar": "Solar Panel", "Inversor CC/CA": "DC/AC Inverter",
    "Cabo e Juncao": "Cable and Junction", "Postes": "Power Poles", "Gerador Geotermico": "Geothermal Generator",
    "Bateria": "Battery", "Forja de Municao": "Ammo Forge", "Prensa Hidraulica": "Hydraulic Press",
    "Torno Mecanico": "Lathe", "Sola Isolante": "Insulated Sole", "Motor Eletrico": "Electric Motor",
    "Bobinadeira": "Coil Winder", "Cadinho de Liga": "Alloy Crucible", "Gravadora": "Circuit Etcher",
    "Fornalha Eletrica": "Electric Furnace", "Triturador": "Crusher", "Plantadeira": "Seeder",
    "Flecha de fogo 30%": "Fire arrows 30%", "Torre Laser": "Laser Turret", "Montadora": "Assembler",
    "Escudo Eletrico": "Electric Shield", "Circuito Avancado": "Advanced Circuit", "Laminadora": "Rail Mill",
    "Broca de Diamante": "Diamond Drill", "Mineradora": "Quarry", "Estacao Tuneladora": "Borer Station",
    "Trem Tunelador": "Borer Train", "Modulo de Coleta": "Collection Module", "Tiro duplo": "Double shot",
    # linha industrial
    "Linha industrial": "Industrial chain",
    "Do bloco de ferro ate as maquinas avancadas. Numero entre parenteses: nivel minimo.":
        "From an iron block to advanced machines. Number in parentheses: required level.",
    "Bloco de ferro": "Iron block", "vanilla": "vanilla", "Bloco de cobre": "Copper block",
    "Carvao / ouro": "Coal / gold", "redstone, quartzo": "redstone, quartz",
    "Fundicao Termica (1)": "Thermal Foundry (1)", "carvao ou lava, 20 s": "coal or lava, 20 s",
    "Ferro Industrial": "Industrial Iron", "base de tudo": "base of everything", "Cobre Industrial": "Industrial Copper",
    "Prensa (2)": "Press (2)", "chapas ferro/cobre/aco": "iron/copper/steel plates",
    "Torno (2)": "Lathe (2)", "fio de cobre, engrenagem": "copper wire, gear",
    "Cadinho (3)": "Crucible (3)", "aco, liga condutora": "steel, conductive alloy",
    "Bobinadeira (3)": "Coil Winder (3)", "fio + chapa = bobina": "wire + plate = coil",
    "Gravadora (3/4)": "Circuit Etcher (3/4)", "circuito, circ. avancado": "circuit, adv. circuit",
    "Motor Eletrico (3)": "Electric Motor (3)", "bobina, 2 engren., chapa": "coil, 2 gears, plate",
    "2 circuitos, liga, diamante": "2 circuits, alloy, diamond",
    "Chapas e fios da Prensa e do Torno alimentam Bobinadeira e Gravadora.":
        "Plates and wires from the Press and Lathe feed the Coil Winder and Circuit Etcher.",
    "Quem consome os componentes": "Who uses the components",
    "2 Ferro Ind., motor, circuito": "2 Ind. Iron, motor, circuit",
    "4 chapa aco, 2 motores, circ. avancado": "4 steel plates, 2 motors, adv. circuit",
    "chapa, engrenagem, motor": "plate, gear, motor",
    "chapa de aco, motor, circuito": "steel plate, motor, circuit",
    "chapa de aco, bobina, circuito": "steel plate, coil, circuit",
    "chapa de aco, motor, circ. avancado": "steel plate, motor, adv. circuit",
    # rede
    "Rede eletrica": "Power network",
    "Tudo que encosta num cabo, juncao ou poste da mesma rede divide a energia.":
        "Everything touching a cable, junction or pole of the same network shares its power.",
    "4/s enquanto queima": "4/s while burning", "Geotermico": "Geothermal",
    "0.5/s por fonte de lava": "0.5/s per lava source", "dobro no Nether": "doubled in the Nether",
    "1.5/s de dia, ceu aberto": "1.5/s by day, open sky", "corrente continua:": "direct current:",
    "precisa de Inversor": "needs an Inverter", "ate 8/s, 90%": "up to 8/s, 90%",
    "Rede": "Network", "Cabo (corrente de cobre)": "Cable (copper chain)",
    "Juncao (cobre encerado)": "Junction (waxed copper)", "Poste: ate 24 blocos": "Pole: up to 24 blocks",
    "Alavanca = disjuntor": "Lever = breaker", "Cabo da choque": "Live cables shock you",
    "guarda ate 5000": "stores up to 5000", "3 modos": "3 modes", "Defesa": "Defense",
    "Torre Sentinela 2/s + tiro": "Sentry Turret 2/s + per shot", "Torre Laser +25 por tiro": "Laser Turret +25 per shot",
    "Escudo 3/s + 1 por mob": "Shield 3/s + 1 per mob", "Maquinas": "Machines",
    "Fornalha 4/s, Triturador 5/s": "Furnace 4/s, Crusher 5/s", "Prensa 6/s, Torno 4/s": "Press 6/s, Lathe 4/s",
    "Cadinho 8/s, Gravadora 6/s": "Crucible 8/s, Etcher 6/s", "Mineradora 12 por bloco": "Quarry 12 per block",
    "Tuneladora": "Tunnel Borer", "Trem 10/s escavando": "Train 10/s while digging",
    # graficos
    "O que cada nivel melhora (padrao)": "What each level improves (defaults)",
    "Torres por jogador": "Turrets per player", "Alcance das torres (blocos)": "Turret range (blocks)",
    "Bonus no XP dos orbes": "XP orb bonus", "Colheita extra": "Extra harvest",
    "XP total para chegar em cada nivel (padrao)": "Total XP to reach each level (defaults)",
    "Sobe devagar no comeco e acelera no fim. Ajuste em level-xp de cada habilidade (engineering, exploration, farming).":
        "Gentle at first, steep at the end. Tune it in each skill's level-xp (engineering, exploration, farming).",
    # livros
    "Livros Antigos (Exploracao)": "Ancient Books (Exploration)",
    "Livros Antigos": "Ancient Books",
    "Baus gerados pelo mapa (estruturas, dungeons, tavernas). Chance 4% + 3% por nivel de Exploracao.":
        "World-generated chests (structures, dungeons, taverns). Chance 4% + 3% per Exploration level.",
    "Veio  I-III": "Vein  I-III", "picareta: agachado: 6/12/20 blocos do veio": "pickaxe: sneak to mine 6/12/20 vein blocks",
    "Sabedoria  I-III": "Wisdom  I-III", "picareta: +50/100/150% XP de minerio": "pickaxe: +50/100/150% ore XP",
    "Lenhador  I": "Lumberjack  I", "machado: agachado: arvore inteira (48)": "axe: sneak to fell the whole tree (48)",
    "Decapitacao  I-III": "Beheading  I-III", "espada/machado: 4% por nivel de cabeca": "sword/axe: 4% head drop per level",
    "Replantio  I": "Replant  I", "enxada: replanta ao colher": "hoe: replants on harvest",
    "Colheita  I": "Harvest  I", "enxada: colhe 3x3 maduro": "hoe: harvests ripe crops 3x3",
    "Arado  I": "Plow  I", "enxada: ara 3x3": "hoe: tills 3x3",
    "Protecao Antiga  I-III": "Ancient Protection  I-III",
    "armadura: 4%/nivel: golpe pela metade (max 24%)": "armor: 4%/level to halve a hit (max 24%)",
    "Visao Noturna  I": "Night Vision  I", "capacete: visao noturna": "helmet: night vision",
    "Sereia  I": "Mermaid  I", "capacete: respira na agua": "helmet: water breathing",
    "Implantes  I": "Implants  I", "capacete: +1 fome a cada 6 s andando": "helmet: +1 hunger every 6 s while walking",
    "Molas  I-II": "Springs  I-II", "botas: pulo I / II": "boots: jump I / II",
    "Aplicar: pegue o livro com o cursor e clique sobre o item no inventario. Qualquer jogador usa o item encantado; so o achado depende da Exploracao.":
        "To apply: pick up the book and click it onto the item in your inventory. Anyone can use the item; only finding books depends on Exploration.",
    # arquitetura
    "Arquitetura do plugin (dev.math.classforge)": "Plugin architecture (dev.math.classforge)",
    "Linhas aproximadas de cada classe Java. Setas: quem cria ou chama quem.":
        "Approximate lines per Java class. Arrows: who creates or calls whom.",
    "main, comandos, XP e niveis (400)": "main, commands, XP and levels (400)",
    "torres, energia, rede,": "turrets, power, network,", "bancada, maquinas (2830)": "bench, machines (2830)",
    "chunks, companheiro,": "chunks, companion,", "ancora, orbes (915)": "anchor, orbs (915)",
    "Plantador de Cobre,": "Copper Planter,", "colheita extra (430)": "extra harvest (430)",
    "Livros Antigos,": "Ancient Books,", "loot de estrutura (445)": "structure loot (445)",
    "linha industrial,": "industrial chain,", "tela das maquinas (475)": "machine screens (475)",
    "Tuneladora e trem (600)": "Tunnel Borer and train (600)", "particulas nos cabos (80)": "cable particles (80)",
    "tela 3x3 de receita (100)": "3x3 recipe screen (100)", "HardcoreRoulette (opcional)": "HardcoreRoulette (optional)",
    "party, vida por mundo (reflexao)": "party, life per world (API)",
    # mapa da config
    "Mapa do config.yml": "config.yml map",
    "Cada card e uma secao de plugins/Classforge/config.yml. Use /classe reload depois de editar (modulos e receitas pedem reinicio).":
        "Each card is a section of plugins/Classforge/config.yml. Run /classe reload after editing (modules and recipes need a restart).",
    "Onde vale": "Where it applies", "Todos os mundos, por prefixo": "All worlds, by prefix",
    "ou uma lista de mundos.": "or a list of worlds.", "Progresso": "Progress",
    "Zerar niveis a cada vida;": "Reset levels every life;", "dividir XP com a party.": "share XP with the party.",
    "Modulos": "Modules", "Liga ou desliga cada": "Turn each skill", "habilidade inteira.": "on or off.",
    "Torres": "Turrets", "Quantas, alcance, cadencia,": "How many, range, fire rate,",
    "desgaste do cano, Laser.": "barrel wear, Laser.", "Energia": "Power",
    "Quanto cada gerador produz,": "What each generator makes,", "bateria e inversor.": "battery and inverter.",
    "Tempo e gasto de energia": "Time and power use", "de cada maquina.": "of each machine.",
    "Receitas": "Recipes", "Nivel, materiais e pecas;": "Level, materials and parts;",
    "desligar itens.": "disable items.", "Velocidade, energia,": "Speed, power,",
    "broca e comprimento.": "drill and length.", "XP por nivel, ancora,": "XP per level, anchor,",
    "bonus nos orbes.": "orb bonus.", "Raio, plantadores, ritmo": "Radius, planters, pace",
    "e colheita extra.": "and extra harvest.", "Chance de achar e": "Find chance and",
    "livros desligados.": "disabled books.", "Curva de XP": "XP curve", "XP para cada nivel": "XP for each level",
    "de cada habilidade.": "of each skill.",
    "Cinza: geral   Vermelho: Engenharia   Azul: Exploracao   Verde: Plantacao   Amarelo: vale para todas":
        "Gray: general   Red: Engineering   Blue: Exploration   Green: Farming   Yellow: all skills",
}

RULES = [
    (re.compile(r"^Nivel (\d)$"), r"Level \1"),
    (re.compile(r"^nv (\d)$"), r"lv \1"),
    (re.compile(r"^(\d+) XP, (\d) torres, alc\. (\d+)$"), r"\1 XP, \2 turrets, range \3"),
]

KEEP = re.compile(r"^[\d.\-+%\s−]*$|^\d+ XP$|^\d+%?$|^XP: [\d / ]+$|^[a-z*][\w.*-]*$|^(Classforge|Engineer|Explorer|Farmer|Relics|Factory|Tunnel|EnergyFx|RecipeGrid)$")


_DONE = set(EN.values())


def translate(s, missing):
    if s in _DONE:
        return s
    if s in EN:
        return EN[s]
    if re.match(r"^(Level|lv) \d$|^\d+ XP, \d turrets", s):
        return s
    for pat, rep in RULES:
        if pat.match(s):
            return pat.sub(rep, s)
    if not KEEP.match(s):
        missing.add(s)
    return s
