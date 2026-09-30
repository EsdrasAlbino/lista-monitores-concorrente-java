# Lista de Exercícios – Monitores (Programação Concorrente)

Implementação em **Java** (monitor de objetos: `synchronized`, `wait()`,
`notifyAll()`) das três questões da lista.

> **Atenção:** o enunciado pede para escolher **apenas uma** das três
> questões para entregar (nota da Lista 1). As três estão implementadas
> aqui para você decidir qual entregar; escolha uma pasta e envie
> só o `.java` e o `README.md` dela.

- [`questao1-logging/`](questao1-logging/) — Sistema de Logging Assíncrono
  (fila limitada, produtoras/consumidora).
- [`questao2-pool-conexoes/`](questao2-pool-conexoes/) — Pool de Conexões
  (5 conexões, 25 requisições concorrentes).
- [`questao3-estoque/`](questao3-estoque/) — Controle de Estoque
  Compartilhado (condição de espera dependente da quantidade pedida por
  cada thread — é a mais rica para discutir na explicação escrita).

Cada pasta é independente e contém:
- o `.java` do programa (`AsyncLogger.java` / `ConnectionPoolDemo.java` /
  `StockControl.java`), sem dependências externas — **este é o código-fonte
  da entrega**;
- um `*Test.java` com testes automatizados de dados mockados (ver abaixo);
- um `README.md` com toda a explicação da entrega: instruções de
  compilação/execução, estratégia de sincronização, descrição das
  variáveis de condição (equivalente aos "semáforos" pedidos no checklist
  de entrega — o enunciado real desta lista exige monitor, não semáforo, e
  cada README explica essa adaptação), valor inicial de cada uma,
  explicação das operações, identificação das regiões críticas e um
  exemplo de execução real (capturado rodando o programa).

## Testes com dados mockados

Cada `*Test.java` é uma pequena bateria de testes escrita à mão (sem JUnit,
para manter cada pasta livre de dependências externas) que instancia o
monitor diretamente com dados fixos e conhecidos — sem depender do `main()`
nem de aleatoriedade — e verifica bloqueio/desbloqueio, invariantes e
encerramento correto. Rodar em qualquer pasta:

```bash
javac *.java
java <NomeDaClasse>Test
```

Ex.: `java AsyncLoggerTest`, `java ConnectionPoolDemoTest`,
`java StockControlTest`. Cada teste imprime `[PASS]`/`[FAIL]` por caso e
termina com "TODOS OS TESTES PASSARAM" ou sai com código 1 se algo falhar.

O destaque é `StockControlTest.testWakeupDoesNotImplyProgress()`: reproduz
o exemplo do próprio enunciado (uma thread esperando retirar 5 unidades,
outra esperando 2, e uma reposição parcial que só deve liberar a menor) —
prova concreta de que o `while` de reavaliação da condição é necessário, não
opcional.

Todos os três (programas e testes) já foram compilados e executados várias
vezes durante o desenvolvimento — inclusive repetidamente, para checar
ausência de flakiness — e sempre terminam corretamente respeitando os
invariantes pedidos (capacidade do buffer, limite de conexões, limites do
estoque) — não apenas que "imprimem algo razoável".
