# Questão 1 – Sistema de Logging Assíncrono

**Disciplina:** Programação Concorrente — Lista de Exercícios: Monitores
**Linguagem:** Java (monitor de objetos — `synchronized`, `wait()`, `notifyAll()`)
**Código-fonte:** [`AsyncLogger.java`](AsyncLogger.java) (solução) e [`AsyncLoggerTest.java`](AsyncLoggerTest.java) (testes)

> Os requisitos específicos desta questão e os critérios de avaliação estão
> detalhados na lista disponibilizada em anexo pelo professor (não
> reproduzidos aqui). Este documento cobre a explicação da solução.

---

## Nota sobre terminologia (semáforos × monitores)

O enunciado desta lista **exige explicitamente o uso de monitores** (mutex +
variável de condição) e proíbe que semáforos substituam esse mecanismo:
*"Semáforos não deverão substituir o mecanismo pedido nesta lista."* Em Java,
o monitor é construído com `synchronized`, `wait()` e `notifyAll()`. Por
isso, nos itens "Descrição dos semáforos" e "Valor inicial de cada
semáforo" abaixo, descrevo o equivalente real desta solução: as **variáveis
de condição do monitor** e o **estado inicial das variáveis de controle**
que cumprem o mesmo papel.

---

## Instruções para compilação e execução

```bash
javac AsyncLogger.java
java AsyncLogger
```

Requer apenas JDK padrão (testado com `javac`/`java`), sem dependências
externas.

Testes automatizados (dados mockados, sem dependências externas):

```bash
javac *.java
java AsyncLoggerTest
```

## Explicação da estratégia de sincronização utilizada

Todo o estado compartilhado (a fila de mensagens e a flag de encerramento)
fica encapsulado dentro da classe interna `LogQueue`, e **só** é acessado
através de métodos `synchronized` — essa classe é o monitor. Nenhum código
fora dela toca a fila diretamente, garantindo exclusão mútua automática:
apenas uma thread por vez executa dentro de `push()`, `pop()` ou `close()`.

A coordenação entre produtoras e consumidora usa o monitor de objeto do
próprio Java (`wait()`/`notifyAll()`), com **dois papéis de espera
diferentes** compartilhando o mesmo monitor:

1. produtoras esperam quando o buffer está cheio (`push()`);
2. a consumidora espera quando o buffer está vazio e a produção ainda está
   ativa (`pop()`).

Como um único monitor de objeto em Java só tem uma fila de espera lógica,
usamos **`notifyAll()`** em vez de `notify()` em toda mudança de estado —
assim nunca corremos o risco de acordar uma thread do papel errado (por
exemplo, acordar outra produtora quando quem precisa prosseguir é a
consumidora). Todo `wait()` está dentro de um laço `while` que reavalia a
condição depois de acordar, conforme exigido pelo enunciado.

## Descrição dos semáforos utilizados

Não há semáforos nesta solução (vedados pelo enunciado). O papel que um
semáforo teria é desempenhado pela variável de condição do monitor
`LogQueue`, com dois usos distintos:

| "Semáforo" equivalente | Papel | Onde é testado (`wait`) | Onde é sinalizado (`notifyAll`) |
|---|---|---|---|
| Condição de espaço livre | "buffer não está cheio" | `while (queue.size() == CAPACITY) wait();` em `push()` | ao final de `pop()` (removeu um item, abriu espaço) |
| Condição de item disponível | "buffer não está vazio OU produção encerrada" | `while (queue.isEmpty() && !closed) wait();` em `pop()` | ao final de `push()` (inseriu um item) e em `close()` (encerrou a produção) |

## Valor inicial de cada semáforo

Não existe um contador numérico de permissões como em um semáforo — o
espaço livre é sempre recomputado a partir de `CAPACITY - queue.size()`
dentro da região crítica, o que evita qualquer risco de o "contador" ficar
dessincronizado do estado real da fila. Ainda assim, o estado inicial
equivalente é:

- `queue` (fila): **vazia** no início.
- `closed` (flag de encerramento): **`false`** — produção ativa.
- `CAPACITY` (capacidade máxima do buffer, constante): **`10`**.
- `maxSizeObserved` (apenas instrumentação, não afeta a lógica): **`0`**.

## Explicação das operações

- **`push(LogMessage message)`** — insere uma mensagem no fim da fila.
  Bloqueia (via `wait()`) enquanto `queue.size() == CAPACITY`. Ao inserir,
  atualiza a instrumentação de tamanho máximo e chama `notifyAll()`.
- **`pop()`** — remove e retorna a mensagem mais antiga da fila. Bloqueia
  enquanto a fila estiver vazia **e** a produção ainda estiver ativa
  (`!closed`). Se a fila estiver vazia **e** já fechada, retorna `null` —
  sinal para a consumidora encerrar. Caso contrário, remove o item e chama
  `notifyAll()`.
- **`close()`** — marca `closed = true` e chama `notifyAll()`, para acordar
  uma consumidora que porventura já esteja esperando por mensagens que
  nunca vão chegar.
- **`maxSizeObserved()`** — leitura auxiliar, usada apenas para comprovar
  (fora da saída impressa) que a capacidade nunca foi ultrapassada.

## Identificação das regiões críticas

Os corpos inteiros de `push`, `pop`, `close` e `maxSizeObserved` — todos
marcados `synchronized` sobre a instância de `LogQueue`. Fora dessas quatro
regiões, nenhuma outra parte do programa lê ou escreve `queue`, `closed` ou
`maxSizeObserved`.

## Estratégia de encerramento das threads

1. `main` inicia a thread consumidora e as 5 threads produtoras.
2. `main` dá `join()` em todas as produtoras (espera cada uma gerar suas 20
   mensagens).
3. Só então `main` chama `queue.close()`.
4. A consumidora continua drenando a fila; quando `pop()` retorna `null`
   (vazia + fechada), o `run()` da consumidora termina naturalmente.
5. `main` dá `join()` na consumidora antes de imprimir o relatório final.

Nenhuma thread é interrompida à força — todas terminam porque sua própria
condição de parada foi satisfeita.

## Exemplo de execução do programa

```
LOG [seq=003][thread=3][level=2] evento 0
LOG [seq=005][thread=5][level=0] evento 0
LOG [seq=001][thread=1][level=1] evento 0
LOG [seq=002][thread=2][level=0] evento 0
LOG [seq=004][thread=4][level=1] evento 0
LOG [seq=006][thread=5][level=1] evento 1
LOG [seq=007][thread=4][level=2] evento 1
LOG [seq=008][thread=3][level=2] evento 1
...
LOG [seq=100][thread=1][level=2] evento 19

===== Verificacao de invariantes =====
Mensagens esperadas: 100
Mensagens processadas: 100
Nenhuma mensagem perdida ou duplicada: true
Tamanho maximo da fila observado: 10 (capacidade = 10)
Buffer nunca excedeu a capacidade: true
```

(A ordem exata das mensagens varia entre execuções, como previsto no
enunciado — o que não varia são os invariantes verificados ao final.)

## Testes automatizados (evidência adicional, dados mockados)

`AsyncLoggerTest.java` (13 casos, sem dependências externas) testa o monitor
`LogQueue` isoladamente com dados fixos (não aleatórios): ordem FIFO,
bloqueio de `push()` no limite de 10 e destravamento após um `pop()`,
bloqueio de `pop()` na fila vazia e destravamento ao inserir, drenagem
completa após `close()`, `close()` destravando uma consumidora já em espera,
e uma integração menor com produtoras/mensagens fixas confirmando ausência
de perda/duplicação e respeito à capacidade.

```
[PASS] FIFO: mensagens saem na ordem de insercao
[PASS] push() bloqueia com buffer cheio (10/10)
[PASS] push() destrava assim que ha espaco
[PASS] mensagem removida foi a mais antiga (seq=1)
[PASS] pop() bloqueia com fila vazia e producao ativa
[PASS] pop() destrava e retorna a mensagem inserida
[PASS] apos close(), mensagens restantes sao drenadas em ordem
[PASS] pop() apos fila vazia+fechada retorna null sem bloquear
[PASS] consumidora esperando em fila vazia (pre-close)
[PASS] close() destrava consumidora esperando, resultado null
[PASS] integracao: consumidora terminou (sem travar)
[PASS] integracao: todas as 10 mensagens fixas foram recebidas sem perda/duplicacao
[PASS] integracao: capacidade nunca excedida

TODOS OS TESTES PASSARAM.
```
