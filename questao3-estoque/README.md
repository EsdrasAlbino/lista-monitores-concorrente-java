# Questão 3 – Controle de Estoque Compartilhado

**Disciplina:** Programação Concorrente — Lista de Exercícios: Monitores
**Linguagem:** Java (monitor de objetos — `synchronized`, `wait()`, `notifyAll()`)
**Código-fonte:** [`StockControl.java`](StockControl.java) (solução) e [`StockControlTest.java`](StockControlTest.java) (testes)

> Os requisitos específicos desta questão e os critérios de avaliação estão
> detalhados na lista disponibilizada em anexo pelo professor (não
> reproduzidos aqui). Este documento cobre a explicação da solução.

---

## Nota sobre terminologia (semáforos × monitores)

O enunciado desta lista **exige explicitamente o uso de monitores** (mutex +
variável de condição) e proíbe que semáforos substituam esse mecanismo:
*"Semáforos não deverão substituir o mecanismo pedido nesta lista."* Por
isso, nos itens "Descrição dos semáforos" e "Valor inicial de cada
semáforo" abaixo, descrevo o mecanismo realmente exigido e usado: a
**variável de condição do monitor** e o **valor inicial das variáveis de
controle**. Esta questão é, de propósito, a que mais foge do modelo clássico
de semáforo contador — a condição de espera depende de um parâmetro passado
pela própria thread (a quantidade solicitada), algo que um semáforo simples
não expressa diretamente.

---

## Instruções para compilação e execução

```bash
javac StockControl.java
java StockControl
```

Testes automatizados (dados mockados, sem dependências externas):

```bash
javac *.java
java StockControlTest
```

## Explicação da estratégia de sincronização utilizada

O estado (`stock` e os limites min/max observados) fica encapsulado
inteiramente na classe interna `Stock`, acessado somente por métodos
`synchronized` — essa classe é o monitor.

O ponto central desta questão: diferente das Questões 1 e 2 (onde a condição
é só "cheio"/"vazio"), aqui a condição de espera depende do **valor que cada
thread pediu**:

```
cliente pode comprar     ⟺  stock >= quantity
reposição pode ocorrer   ⟺  stock + quantity <= CAPACITY
```

Isso significa que acordar uma thread **não garante** que ela pode
prosseguir: um cliente esperando comprar 6 unidades pode acordar com o
estoque em 3 (suficiente para outro cliente que queria 2, mas não para ele) e
deve voltar a dormir. Por isso todo `wait()` está dentro de um `while` que
reavalia exatamente a condição daquela chamada, com o `quantity` daquela
thread — nunca assumimos que a primeira thread acordada pode prosseguir.

## Descrição dos semáforos utilizados

Não há semáforos nesta solução (vedados pelo enunciado). O papel que teriam
é desempenhado por duas variáveis de condição sobre o mesmo monitor `Stock`:

| "Semáforo" equivalente | Papel | Onde é testado (`wait`) | Onde é sinalizado (`notifyAll`) |
|---|---|---|---|
| Condição de estoque suficiente | "estoque >= quantidade pedida" | `while (stock < quantity) wait();` em `withdraw()` | ao final de `withdraw()` **e** de `restock()` |
| Condição de espaço suficiente | "estoque + quantidade <= capacidade" | `while (stock + quantity > CAPACITY) wait();` em `restock()` | ao final de `withdraw()` **e** de `restock()` |

Como diferentes threads podem estar esperando por quantidades totalmente
diferentes, usamos sempre `notifyAll()` — nunca `notify()` — após qualquer
mudança no estoque, para que todas as threads bloqueadas tenham a chance de
reavaliar sua própria condição.

## Valor inicial de cada semáforo

O valor equivalente ao "valor inicial do semáforo" em um desenho clássico
produtor/consumidor com contagem é o próprio `stock`:

- `stock` (estoque atual): **`8`**.
- `CAPACITY` (constante, limite superior): **`20`**.
- `minObserved` / `maxObserved` (apenas instrumentação): ambos inicializados
  em **`8`** (o valor inicial do próprio estoque).

## Explicação das operações

- **`withdraw(int quantity)`** — retira `quantity` unidades. Bloqueia
  enquanto `stock < quantity`. Ao prosseguir, subtrai do estoque, atualiza a
  instrumentação de min/max e chama `notifyAll()` (pode ter liberado espaço
  para uma reposição que estava esperando).
- **`restock(int quantity)`** — adiciona `quantity` unidades. Bloqueia
  enquanto `stock + quantity > CAPACITY`. Ao prosseguir, soma ao estoque,
  atualiza a instrumentação e chama `notifyAll()` (pode ter liberado
  unidades para um cliente que estava esperando).
- **`getStock()`** — leitura auxiliar, usada para instrumentação e testes.

## Identificação das regiões críticas

Os corpos de `withdraw`, `restock` e `getStock` — todos `synchronized` sobre
a instância de `Stock`. O método privado `updateBounds()` (chamado de dentro
dessas regiões, portanto também protegido) lança `IllegalStateException` se
o invariante `0 <= stock <= 20` for violado — funciona como uma verificação
de invariante em tempo de execução, não apenas uma expectativa de projeto.

## Estratégia de encerramento das threads (e por que o sistema nunca trava)

Um risco real neste problema: se a demanda total dos clientes for maior que
a oferta total das reposições, alguma thread ficaria esperando para sempre
por unidades que nunca chegariam — o que violaria o requisito de que todas
as threads terminem, independentemente do escalonamento.

Para evitar isso, `main`:

1. Gera as quantidades de compra dos 10 clientes (1–6 cada, 5 compras por
   cliente) e soma a **demanda total**.
2. Fragmenta essa mesma soma em pedaços de 1–3 unidades e distribui entre as
   4 threads de reposição — garantindo que **oferta total == demanda
   total**.
3. Inicia as 14 threads e dá `join()` em cada uma.

Como a soma repõe exatamente o que é retirado, toda espera é temporária:
não importa a ordem de escalonamento, sempre existe uma sequência de
operações que libera cada thread bloqueada, e o estoque final volta ao valor
inicial (8) — o que o programa confirma na verificação final.

## Exemplo de execução do programa

```
Demanda total dos clientes: 158
Oferta total das reposicoes: 158
Estoque inicial: 8 | capacidade: 20

Client-1   comprou 3 unidade(s) -> estoque=5
Client-2   comprou 2 unidade(s) -> estoque=3
Client-3   comprou 3 unidade(s) -> estoque=0
Restocker-1 repos    2 unidade(s) -> estoque=2
...
Client-8   comprou 1 unidade(s) -> estoque=8

===== Verificacao de invariantes =====
Estoque final: 8 (esperado = 8, pois oferta == demanda)
Valor minimo observado: 0 (>= 0)
Valor maximo observado: 9 (<= 20)
Nunca ficou negativo nem excedeu a capacidade: true
Todas as 14 threads finalizadas corretamente.
```

## Testes automatizados (evidência adicional, dados mockados)

`StockControlTest.java` (18 casos, sem dependências externas) testa o
monitor isoladamente com dados fixos. O caso mais importante,
`testWakeupDoesNotImplyProgress()`, reproduz **literalmente** o exemplo do
próprio enunciado: uma thread esperando retirar 5 unidades e outra esperando
retirar 2, estoque em 0. Uma reposição parcial de +2 deve liberar **só**
quem pediu 2 — prova concreta de que o `while` de reavaliação da condição é
necessário (um `if` no lugar do `while` deixaria a thread errada prosseguir
e o estoque ficaria negativo).

```
[PASS] operacoes basicas sem concorrencia: estoque final = 10
[PASS] withdraw(5) bloqueia com estoque=2
[PASS] apos restock insuficiente (estoque=4), cliente continua bloqueado
[PASS] cliente destrava assim que o estoque alcanca o valor pedido
[PASS] estoque final = 0 apos withdraw(5) ser concluido
[PASS] ambas as threads (pedindo 5 e 2) comecam bloqueadas com estoque=0
[PASS] apenas a thread que pediu a quantidade pequena (2) e destravada
[PASS] a thread que pediu a quantidade grande (5) permanece esperando
[PASS] estoque volta a 0 (foi consumido pela thread pequena, nao pela grande)
[PASS] thread grande destrava quando o estoque finalmente alcanca 5
[PASS] estoque final = 0
[PASS] restock(5) bloqueia quando excederia a capacidade (18+5=23>20)
[PASS] restock() destrava assim que ha espaco suficiente
[PASS] estoque final = 19 (14 + 5)
[PASS] carga fixa e balanceada (18 retirados == 18 repostos): todas as threads terminaram
[PASS] estoque final volta ao valor inicial (8)
[PASS] estoque nunca ficou negativo (min observado >= 0)
[PASS] estoque nunca excedeu a capacidade (max observado <= 20)

TODOS OS TESTES PASSARAM.
```
