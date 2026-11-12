# HardcoreRouletteClasses

![Paper](https://img.shields.io/badge/Paper-26.3-blue) ![Java](https://img.shields.io/badge/Java-25-orange) ![Versao](https://img.shields.io/badge/versao-0.12.0-green) ![Licenca](https://img.shields.io/badge/licenca-MIT-lightgrey)

Plugin Paper de **habilidades por nivel** para servidores hardcore em grupo. Ninguem escolhe classe: todo jogador sobe Engenharia, Exploracao e Plantacao, e o nivel de cada uma libera conteudo novo. Os niveis zeram quando a vida acaba.

Addon do [HardcoreRoulette](https://github.com/matheus-fsc/hardcore-roulette), mas funciona sozinho (sem ele, so a divisao de XP com a party fica desligada). Mensagens em portugues (pt-BR).

## Recursos

- **Engenharia**: torres de defesa (Sentinela e Laser), energia, cabos e postes, maquinas, linha industrial e Tuneladora.
- **Exploracao**: companheiro, Ancora de Perola, bonus de XP e Livros Antigos em baus de estrutura.
- **Plantacao**: Plantador de Cobre (golem que planta a partir de um bau) e colheita extra.
- Menus proprios com receita 3x3 de cada item, etiquetas flutuantes e particulas de energia.

## Instalacao

1. Servidor **Paper 26.3** com **Java 25**.
2. Coloque `HardcoreRouletteClasses.jar` em `plugins/` e reinicie.
3. Opcional: [HardcoreRoulette](https://github.com/matheus-fsc/hardcore-roulette) para a divisao de XP com a party.

![Visao geral](docs/img/visao-geral.png)

## Mini wiki

| Pagina | O que tem |
|--------|-----------|
| [Inicio](docs/wiki/Home.md) | Visao geral, curvas por nivel, regras gerais |
| [Engenharia](docs/wiki/Engenharia.md) | Torres, Torre Laser, energia, maquinas, linha industrial, rede, Tuneladora |
| [Plantacao](docs/wiki/Plantacao.md) | Plantador de Cobre e colheita extra |
| [Exploracao](docs/wiki/Exploracao.md) | Companheiro, Ancora de Perola, orbes, Livros Antigos |
| [Arquitetura](docs/wiki/Arquitetura.md) | Classes Java, dados, geracao dos diagramas |

## Estado atual (versao 0.12.0)

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

## Configuracao

Tudo que era fixo no codigo agora fica em `plugins/HardcoreRouletteClasses/config.yml`, escrito comentado na primeira vez que o servidor liga ([modelo](src/main/resources/config.yml)). Os padroes sao os valores de antes, entao nada muda ate voce editar.

| Secao | O que controla |
|-------|----------------|
| `worlds` | Em quais mundos as habilidades valem: por prefixo (`hc_`), todos ou uma lista |
| `progress` | Zerar os niveis em mundo novo (vida nova) e dividir XP com a party |
| `modules` | Liga ou desliga Engenharia, Exploracao, Plantacao e Livros Antigos |
| `engineering` | XP por nivel, torres, balas, Torre Laser, geracao de energia, rede, maquinas, Tuneladora, XP de cada acao, etiquetas |
| `engineering.recipes` | Cada receita da Bancada: ligar/desligar, nivel, materiais e componentes (o plugin escreve todas na primeira vez) |
| `exploration` | XP por nivel, bonus nos orbes, Ancora de Perola |
| `farming` | XP por nivel, raio, plantadores, ritmo e colheita extra |
| `ancient-books` | Chance de achar, blocos do Veio e livros desligados |

`/classe reload` aplica os numeros sem reiniciar. Ligar ou desligar modulos e mudar receitas pede reinicio.

Para usar fora do HardcoreRoulette: `worlds.mode: all` e `progress.reset-on-new-world: false`.

## Comandos

| Comando | Descricao |
|---------|-----------|
| `/classe` (ou `/engenharia`, `/habilidades`) | Menu de habilidades |
| `/classe info` | Nivel e status da Engenharia |
| `/classe particulas` | Liga/desliga as particulas de energia |
| `/classe admin <jogador> <nivel>` | Define o nivel (permissao `hrclasses.admin`) |
| `/classe dar <item>` | Da um item do plugin (permissao `hrclasses.admin`) |
| `/classe reload` | Recarrega o config.yml (permissao `hrclasses.admin`) |
| `/classe give <engenheiro\|explorador\|plantador\|livros> [jogador]` | Kit de teste; `livros` da um de cada Livro Antigo (permissao `hrclasses.admin`) |

## Permissoes

| Permissao | Padrao | Descricao |
|-----------|--------|-----------|
| `hrclasses.admin` | op | `/classe admin`, `/classe dar` e `/classe give` |

## Build

```
mvn package
```

O jar sai em `target/HardcoreRouletteClasses.jar`. Copie para `plugins/`.

Para gerar os diagramas de novo: `python docs/diagrams.py` (precisa de matplotlib e do Minecraft instalado para os icones; detalhes em [Arquitetura](docs/wiki/Arquitetura.md)).

## Arquitetura

![Arquitetura](docs/img/arquitetura.png)

Dados em `plugins/HardcoreRouletteClasses/`: `config.yml`, `players.yml`, `devices.yml`, `explorer.yml` e `farm.yml`. Mais em [Arquitetura](docs/wiki/Arquitetura.md).

## Licenca

MIT
