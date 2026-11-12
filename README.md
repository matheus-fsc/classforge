# HardcoreRoulette Addon: Classes

Addon do [HardcoreRoulette](https://github.com/matheus-fsc/hardcore-roulette) (plugin HardcoreRouletteClasses) feito para o servidor hardcore BABYLON FLAMES. Plugin Paper de **habilidades por nivel** para grupos: ninguem escolhe classe, todo jogador sobe Engenharia, Exploracao e Plantacao, e o nivel de cada uma libera conteudo novo. Os niveis zeram quando a vida acaba.

Feito para **Paper 26.3** (Java 25). Mensagens em portugues (pt-BR).

![Visao geral](docs/img/visao-geral.png)

## Mini wiki

| Pagina | O que tem |
|--------|-----------|
| [Inicio](docs/wiki/Home.md) | Visao geral, curvas por nivel, regras gerais |
| [Engenharia](docs/wiki/Engenharia.md) | Torres, Torre Laser, energia, maquinas, linha industrial, rede, Tuneladora |
| [Plantacao](docs/wiki/Plantacao.md) | Plantador de Cobre e colheita extra |
| [Exploracao](docs/wiki/Exploracao.md) | Companheiro, Ancora de Perola, orbes, Livros Antigos |
| [Arquitetura](docs/wiki/Arquitetura.md) | Classes Java, dados, geracao dos diagramas |

## Estado atual (versao 0.11.0)

| Area | Estado | Observacao |
|------|--------|------------|
| Engenharia: torres, energia, rede | Pronto | Torre Sentinela agora pede motor e circuito |
| Engenharia: linha industrial e maquinas | Pronto | Prensa, Torno, Bobinadeira, Cadinho, Gravadora |
| Engenharia: Tuneladora | Pronto | Nivel 5, limite de 1 estacao |
| Engenharia: Torre Laser | Novo, sem teste em jogo | Lanca TNT que nao explode; dano em area so em mobs hostis |
| Exploracao: companheiro, ancora, orbes | Pronto | |
| Exploracao: Livros Antigos (12) | Novo, sem teste em jogo | Loot de baus de estrutura |
| Plantacao: Plantador de Cobre | Pronto | Receita visivel no painel |
| Divisao de XP com a party | Pronto | Depende do HardcoreRoulette carregado |

Ideias em aberto: habilidade de Coleta, revisao de balanceamento depois de uma run completa.

## Progressao

![Curvas por nivel](docs/img/curvas-nivel.png)

| Nivel | Engenharia (XP) | Exploracao (XP) | Plantacao (XP) |
|-------|-----------------|-----------------|----------------|
| 1 | 0 | 0 | 0 |
| 2 | 25 | 150 | 60 |
| 3 | 75 | 500 | 200 |
| 4 | 175 | 1200 | 500 |
| 5 | 350 | 2500 | 1000 |

![Niveis da Engenharia](docs/img/engenharia-niveis.png)

## Engenharia em uma imagem

![Linha industrial](docs/img/linha-industrial.png)

![Rede eletrica](docs/img/rede-eletrica.png)

Detalhes de cada maquina, receita e consumo na pagina [Engenharia](docs/wiki/Engenharia.md).

## Livros Antigos

![Livros Antigos](docs/img/livros-antigos.png)

## Comandos

| Comando | Descricao |
|---------|-----------|
| `/classe` (ou `/engenharia`, `/habilidades`) | Menu de habilidades |
| `/classe info` | Nivel e status da Engenharia |
| `/classe particulas` | Liga/desliga as particulas de energia |
| `/classe admin <jogador> <nivel>` | Define o nivel (permissao `bclasses.admin`) |
| `/classe dar <item>` | Da um item do plugin (permissao `bclasses.admin`) |
| `/classe give <engenheiro\|explorador\|plantador\|livros> [jogador]` | Kit de teste; `livros` da um de cada Livro Antigo (permissao `bclasses.admin`) |

## Build

```
mvn package
```

O jar sai em `target/HardcoreRouletteClasses.jar`. Copie para `plugins/`.

Para gerar os diagramas de novo: `python docs/diagrams.py` (precisa de matplotlib).

## Arquitetura

![Arquitetura](docs/img/arquitetura.png)

Dados em `plugins/HardcoreRouletteClasses/`: `players.yml`, `devices.yml`, `explorer.yml` e `farm.yml`. Mais em [Arquitetura](docs/wiki/Arquitetura.md).

## Licenca

MIT
