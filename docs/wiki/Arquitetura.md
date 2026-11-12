# Arquitetura

[Inicio](Home.md) | [Engenharia](Engenharia.md) | [Plantacao](Plantacao.md) | [Exploracao](Exploracao.md) | [Arquitetura](Arquitetura.md)

![Arquitetura](../img/arquitetura.png)

## Classes

| Classe | Papel |
|--------|-------|
| `BabylonClasses` | Classe principal: comandos, XP e niveis das tres habilidades, divisao de XP com a party |
| `Engineer` | Bancada, torres (Sentinela e Laser), geradores, bateria, cabos, postes, etiquetas, maquinas simples |
| `Factory` | Linha industrial (Prensa, Torno, Bobinadeira, Cadinho, Gravadora, Laminadora) e a tela das maquinas |
| `Tunnel` | Estacao, Trem Tunelador, brocas e Modulo de Coleta |
| `EnergyFx` | Particulas de energia correndo pelos cabos |
| `Explorer` | Chunks, companheiro, Ancora de Perola, bonus de orbes e de carne |
| `Farmer` | Plantador de Cobre (golem) e colheita extra |
| `Relics` | Livros Antigos: loot de estrutura, aplicacao no item e efeitos |
| `RecipeGrid` | Tela generica de receita 3x3 usada pelos paineis |

## Integracao com o HardcoreRoulette

O plugin nao depende do HardcoreRoulette para compilar. A divisao de XP com a party usa reflexao
(`xpShareTargets` e `xpShareRatio`); se o HardcoreRoulette nao estiver carregado, nada e dividido.

## Dados

| Arquivo | Conteudo |
|---------|----------|
| `players.yml` | XP e mundo de cada jogador |
| `devices.yml` | Dispositivos, cabos e postes |
| `explorer.yml` | Companheiros e ancoras |
| `farm.yml` | Plantadores |

Todos em `plugins/HardcoreRouletteClasses/`.

## Diagramas

As imagens em `docs/img/` saem de `docs/diagrams.py` (matplotlib):

```
pip install matplotlib
python docs/diagrams.py
```

Os numeros do script copiam as constantes do codigo. Ao mudar um balanceamento, ajuste o script e gere de novo.
