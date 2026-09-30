# Questão 2 – Pool de Conexões

**Disciplina:** Programação Concorrente — Lista de Exercícios: Monitores
**Linguagem:** Java (monitor de objetos — `synchronized`, `wait()`, `notifyAll()`)
**Código-fonte:** [`ConnectionPoolDemo.java`](ConnectionPoolDemo.java) (solução) e [`ConnectionPoolDemoTest.java`](ConnectionPoolDemoTest.java) (testes)

> Os requisitos específicos desta questão e os critérios de avaliação estão
> detalhados na lista disponibilizada em anexo pelo professor (não
> reproduzidos aqui). Este documento cobre a explicação da solução.

---

## Nota sobre terminologia (semáforos × monitores)

O enunciado desta lista **exige explicitamente o uso de monitores**, e proíbe
semáforos como mecanismo de sincronização — mais ainda, para esta questão em
particular o enunciado é explícito: *"O número máximo de operações
simultâneas não deve ser controlado por um semáforo. O objetivo é praticar a
representação do próprio pool como um monitor cuja condição de progresso é a
existência de uma conexão livre."* Por isso, nos itens "Descrição dos
semáforos" e "Valor inicial de cada semáforo" abaixo, descrevo o mecanismo
realmente exigido e usado: a **variável de condição do monitor** e o
**estado inicial das variáveis que controlam o pool**.

---

## Instruções para compilação e execução

```bash
javac ConnectionPoolDemo.java
java ConnectionPoolDemo
```

Testes automatizados (dados mockados, sem dependências externas):

```bash
javac *.java
java ConnectionPoolDemoTest
```

## Explicação da estratégia de sincronização utilizada

O estado do pool (quais conexões estão livres/ocupadas) fica encapsulado
inteiramente na classe interna `ConnectionPool`, acessado somente por
métodos `synchronized` — essa classe é o monitor. O limite de 5 conexões
simultâneas **não** é imposto por um contador de semáforo externo: é o
próprio estado do monitor (`freeCount`, atualizado dentro da região crítica)
que garante o limite, combinado com a condição de espera em `acquire()`.

## Descrição dos semáforos utilizados

Não há semáforos nesta solução (vedados pelo enunciado, inclusive
nominalmente para esta questão). O papel que um semáforo contador teria é
desempenhado pela variável de condição do monitor `ConnectionPool`:

| "Semáforo" equivalente | Papel | Onde é testado (`wait`) | Onde é sinalizado (`notifyAll`) |
|---|---|---|---|
| Condição de conexão livre | "existe conexão livre" (`freeCount > 0`) | `while (freeCount == 0) wait();` em `acquire()` | ao final de `release()` |

Usamos `notifyAll()` (não `notify()`) porque podem existir várias threads
esperando simultaneamente por uma conexão, e não há garantia de que a única
thread acordada por `notify()` seria capaz de prosseguir em todos os
cenários — `notifyAll()` deixa cada uma reavaliar sua própria condição.

## Valor inicial de cada semáforo

O contador equivalente ao "valor inicial do semáforo" em um desenho
clássico de pool é `freeCount`:

- `inUse[]` (vetor de 5 posições): todas **`false`** no início (nenhuma
  conexão em uso).
- `freeCount`: **`5`** — todas as conexões livres.
- `POOL_SIZE` (constante, capacidade do pool): **`5`**.
- `currentInUse` / `peakInUse` (apenas instrumentação): **`0`**.

## Explicação das operações

- **`acquire()`** — retorna o índice de uma conexão livre. Bloqueia (via
  `wait()`) enquanto `freeCount == 0`. Ao encontrar uma conexão livre, marca
  `inUse[i] = true`, decrementa `freeCount`, atualiza o pico de uso
  (instrumentação) e retorna `i`.
- **`release(int connectionId)`** — devolve a conexão ao pool: marca
  `inUse[connectionId] = false`, incrementa `freeCount` e chama
  `notifyAll()`. Lança `IllegalStateException` se a conexão já estava livre
  (uso incorreto da API é detectado, não silenciado).
- **`availableCount()`** — leitura auxiliar (quantas conexões livres agora).
- **`peakInUse()`** — leitura auxiliar, usada só para comprovar que o limite
  de 5 nunca foi ultrapassado.

## Identificação das regiões críticas

Os corpos de `acquire`, `release`, `availableCount` e `peakInUse` — todos
`synchronized` sobre a instância de `ConnectionPool`. Fora dessas quatro
regiões, nenhum outro código lê ou escreve `inUse[]`, `freeCount`,
`currentInUse` ou `peakInUse`.

## Estratégia de encerramento das threads

1. `main` cria e inicia as 25 `RequesterThread`.
2. Cada thread faz `acquire()` → simula uso da conexão com `Thread.sleep()`
   (apenas para simular tempo de operação no banco, não é sincronização) →
   `release()`.
3. `main` dá `join()` em todas as 25 threads antes de imprimir o relatório
   final.

Toda conexão adquirida é devolvida no mesmo `run()` da thread que a pegou,
então o sistema sempre progride e nenhuma thread fica presa esperando algo
que não vai acontecer.

## Exemplo de execução do programa

```
Requisicao  1 -> obteve conexao 0 (livres=3)
Requisicao  2 -> obteve conexao 1 (livres=3)
Requisicao  3 -> obteve conexao 2 (livres=2)
Requisicao  4 -> obteve conexao 3 (livres=1)
Requisicao  5 -> obteve conexao 4 (livres=0)
Requisicao  1 <- devolveu conexao 0 (livres=0)
Requisicao  6 -> obteve conexao 0 (livres=0)
Requisicao  2 <- devolveu conexao 1 (livres=1)
...
Requisicao 15 <- devolveu conexao 1 (livres=4)
Requisicao 17 <- devolveu conexao 0 (livres=5)

===== Verificacao de invariantes =====
Threads finalizadas: 25/25
Conexoes livres ao final: 5 (esperado = 5)
Pico de conexoes simultaneamente em uso: 5 (limite = 5)
Nunca excedeu o limite de 5 conexoes: true
```

## Testes automatizados (evidência adicional, dados mockados)

`ConnectionPoolDemoTest.java` (9 casos, sem dependências externas) testa o
monitor isoladamente com dados fixos: aquisição de índices distintos,
bloqueio no esgotamento do pool e desbloqueio no `release()`, detecção de
double-release, e carga fixa de 12 threads confirmando o limite nunca
excedido.

```
[PASS] 5 acquire() sem contencao devolvem 5 indices distintos
[PASS] availableCount() == 0 apos esgotar o pool
[PASS] acquire() bloqueia quando as 5 conexoes estao em uso
[PASS] acquire() destrava assim que uma conexao e liberada
[PASS] thread destravada recebeu a conexao liberada
[PASS] release() duplicado da mesma conexao lanca IllegalStateException
[PASS] 12 threads (carga fixa) finalizaram corretamente
[PASS] pico de conexoes em uso nunca excedeu 5
[PASS] pool volta ao estado inicial (todas livres) ao final

TODOS OS TESTES PASSARAM.
```
