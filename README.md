# HardcoreRoulette Addon: Classes

Addon do HardcoreRoulette (plugin interno: BabylonClasses). Plugin Paper de **habilidades por nivel** para servidores hardcore em grupo. Ninguem escolhe classe: qualquer jogador pode fazer tudo, e o nivel de cada habilidade e o que libera mais coisas. Os niveis zeram quando a vida acaba (o XP fica preso ao mundo `hc_*` em que foi ganho).

Feito para **Paper 26.3** (Java 25). Mensagens em portugues (pt-BR). Pensado para rodar junto com o [HardcoreRoulette](https://github.com/matheus-fsc/hardcore-roulette).

## Habilidades

- **Engenharia** (pronta): torres de defesa, geracao de energia, cabos e postes.
- **Exploracao** (em breve): XP por distancia percorrida, bonus de comida.
- **Coleta** (em breve): farm e plantio automatizado.

`/classe` (ou `/engenharia`, `/habilidades`) abre o menu de habilidades.

## Engenharia

### Niveis

| Nivel | XP  | Torres | Alcance | Tiros/s | Extra |
|-------|-----|--------|---------|---------|-------|
| 1     | 0   | 2      | 12      | 1.0     |       |
| 2     | 25  | 3      | 14      | 1.2     | Geotermico e Bateria |
| 3     | 75  | 4      | 16      | 1.4     | Flecha de fogo (30%) |
| 4     | 175 | 5      | 18      | 1.7     |       |
| 5     | 350 | 6      | 20      | 2.0     | Tiro duplo |

XP: montar na bancada (+3), trocar o cano de uma torre (+5), abate de uma torre sua (+1).

### Bancada de Engenharia
Receita na mesa de trabalho: mesa de trabalho no centro, 5 ferros embaixo e dos lados, 1 redstone em cima. Clique com o direito nela para montar os dispositivos. No painel, clicar num item mostra o craft dele.

### Dispositivos

- **Torre Sentinela** (dispenser): atira flechas (normais, com efeito, espectrais) ou bolas de fogo guardadas nela em mobs hostis. Mira na frente do alvo e compensa a queda da flecha. Nunca acerta jogador, pet ou animal. Menu com municao preferida, prioridade de alvo, filtro de mobs, liga/desliga e alcance.
  - Consome energia ligada mesmo parada (2.0/s, sobe com o nivel) e mais por tiro (5 flecha, 10 bola de fogo).
  - **Cano**: aguenta 300 tiros (bola de fogo conta 2). Gasto, a torre para. Troque pelo menu da torre com um Cano da Torre. O desgaste fica salvo no item se a torre for quebrada.
- **Gerador a Carvao** (fornalha): 4 energia/s enquanto queima.
- **Gerador Geotermico** (bloco de magma): 0.5/s por fonte de lava encostada, dobro no Nether.
- **Painel Solar** (sensor de luz): 1.5/s de dia com ceu aberto. Gera corrente continua: precisa de um **Inversor CC/CA** na rede (ate 8/s, 90% de eficiencia).
- **Bateria** (magnetita): guarda ate 5000. Modos: carrega e fornece, so carrega, so fornece.

### Rede eletrica

- **Cabo** (corrente de cobre encerada): liga tudo que encosta nele. Qualquer corrente de cobre conduz.
- **Juncao** (bloco de cobre encerado): conduz como o cabo, serve para curvas e emendas.
- **Alavanca** no meio do cabo funciona como disjuntor.
- **Poste**: muro de pedregulho na base, 1 a 8 blocos de madeira e cobre talhado no topo. Liga com outros postes a ate 24 blocos sem fio (com fio visual em particulas). Para tirar energia do poste, encoste cabo ou juncao em qualquer parte dele.
- Cabo energizado da choque: dano por segundo e lentidao II em quem encostar.
- Etiquetas flutuantes mostram energia, municao e estado de cada maquina. Particulas de energia correm pelos cabos ate as maquinas (`/classe particulas` liga e desliga).

## Comandos

| Comando | Descricao |
|---------|-----------|
| `/classe` | Menu de habilidades |
| `/classe info` | Nivel e status da Engenharia |
| `/classe particulas` | Liga/desliga as particulas de energia |
| `/classe admin <jogador> <nivel>` | Define o nivel (permissao `bclasses.admin`) |
| `/classe dar <item>` | Da um item do plugin (permissao `bclasses.admin`) |

## Build

```
mvn package
```

O jar sai em `target/BabylonClasses.jar`. Copie para `plugins/`.

## Dados

- `plugins/BabylonClasses/players.yml`: XP e mundo de cada jogador.
- `plugins/BabylonClasses/devices.yml`: dispositivos, cabos e postes.

## Licenca

MIT
