# HardcoreRoulette Addon: Classes

Addon do HardcoreRoulette (plugin HardcoreRouletteClasses). Plugin Paper de **habilidades por nivel** para servidores hardcore em grupo. Ninguem escolhe classe: qualquer jogador pode fazer tudo, e o nivel de cada habilidade e o que libera mais coisas. Os niveis zeram quando a vida acaba (o XP fica preso ao mundo `hc_*` em que foi ganho).

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
| 2     | 25  | 3      | 14      | 1.2     | Geotermico, Bateria e Forja |
| 3     | 75  | 4      | 16      | 1.4     | Flecha de fogo (30%) |
| 4     | 175 | 5      | 18      | 1.7     |       |
| 5     | 350 | 6      | 20      | 2.0     | Tiro duplo |

XP: montar na bancada (+3), trocar o cano de uma torre (+5), abate de uma torre sua (+1).

### Bancada de Engenharia
Receita na mesa de trabalho: mesa de trabalho no centro, 5 ferros embaixo e dos lados, 1 redstone em cima. Clique com o direito nela para montar os dispositivos. No painel, clicar num item mostra o craft dele.

### Dispositivos

- **Torre Sentinela** (dispenser): atira flechas (normais, com efeito, espectrais), bolas de fogo ou balas guardadas nela em mobs hostis. Mira na frente do alvo e compensa a queda da flecha. Nunca acerta jogador, pet ou animal. Menu com municao preferida, prioridade de alvo, filtro de mobs, liga/desliga e alcance.
  - Consome energia ligada mesmo parada (2.0/s, sobe com o nivel) e mais por tiro (5 flecha, 10 bola de fogo, 2 por bala de cobre, 3 por bala de ferro).
  - **Cano**: aguenta 300 tiros (bola de fogo e rajada de ferro contam 2, rajada de cobre conta 1). Gasto, a torre para. Troque pelo menu da torre com um Cano da Torre. O desgaste fica salvo no item se a torre for quebrada.
- **Gerador a Carvao** (fornalha): 4 energia/s enquanto queima.
- **Gerador Geotermico** (bloco de magma): 0.5/s por fonte de lava encostada, dobro no Nether.
- **Painel Solar** (sensor de luz): 1.5/s de dia com ceu aberto. Gera corrente continua: precisa de um **Inversor CC/CA** na rede (ate 8/s, 90% de eficiencia).
- **Bateria** (magnetita): guarda ate 5000. Modos: carrega e fornece, so carrega, so fornece.
- **Forja de Municao** (alto-forno, nivel 2): 9 pepitas de ferro ou cobre viram 16 balas em 6 segundos, gastando 4 energia/s da rede. Funil em cima abastece, funil embaixo retira. Nao derrete minerio.
  - Balas saem em rajada de 3 tiros retos (3x a cadencia da flecha), com pouco dano cada. Uma flecha da cerca de 6.
  - **Bala de Cobre**: 2 de dano por bala (+0.25 por nivel), 2 de energia por bala, 1 de cano por rajada.
  - **Bala de Ferro**: 3 de dano por bala (+0.25 por nivel), 3 de energia por bala, 2 de cano por rajada.

### Maquinas

Todas gastam energia da rede enquanto trabalham e so processam o que voce coloca nelas. Clique numa maquina para ver o menu (liga/desliga, inventario, etiqueta, rede).

- **Forja** (nivel 2): alem das balas, prensa 1 bloco de ferro em 1 **Ferro Industrial** (20 s, 6 energia/s). O Ferro Industrial e peca obrigatoria das maquinas abaixo.
- **Fornalha Eletrica** (defumador, nivel 3): derrete tudo que a fornalha comum derrete, 2x mais rapido, sem combustivel. 4 energia/s (20 por item).
- **Triturador** (liberador, nivel 3): minerio bruto (ou bloco de minerio) vira minerio triturado, com 35% de chance de render 1 a mais. Triturado derrete como o bruto. Pedregulho vira cascalho e cascalho vira areia. 5 energia/s, 4 s por item. Saida no bau embaixo ou no proprio inventario; funil so puxa o que ja foi triturado.
- **Plantadeira** (barril, nivel 3): planta as sementes guardadas nela na terra arada em volta (raio 4, nivel 4: 5, nivel 5: 6). Fungo do Nether vai na areia das almas. 1 energia por semente.
- **Montadora** (crafter, nivel 4): monta sozinha um item da bancada (escolha no menu). Materiais dentro dela ou no bau em cima, resultado no bau ou funil embaixo. 5 energia/s, 10 s por item. Nao da XP.
- **Escudo Eletrico** (para-raios, nivel 4): empurra mobs hostis num raio de 5 (nivel 5: 6) com descargas de 1 de dano. 3 energia/s ligado e 1 por mob. Limite de 1 (nivel 5: 2).
- **Mineradora** (funil, nivel 5): escava uma area 5x5 embaixo dela, camada por camada, ate o fundo do mundo. 1 bloco a cada 3 s, 12 energia por bloco. Os itens caem no funil (aponte para um bau). Nao quebra baus, maquinas, cabos, liquidos nem bedrock. Limite de 1.

A bancada e o painel separam as receitas em abas: Defesa, Energia e Maquinas. Na aba, clique para ver o craft ou shift-clique para montar direto.

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

O jar sai em `target/HardcoreRouletteClasses.jar`. Copie para `plugins/`.

## Dados

- `plugins/HardcoreRouletteClasses/players.yml`: XP e mundo de cada jogador.
- `plugins/HardcoreRouletteClasses/devices.yml`: dispositivos, cabos e postes.

## Licenca

MIT
