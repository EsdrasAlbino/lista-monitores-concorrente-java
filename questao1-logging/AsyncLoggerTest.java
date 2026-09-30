import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bateria de testes com dados mockados para o monitor LogQueue.
 * Sem dependencias externas (sem JUnit) -- roda com:
 *   javac *.java && java AsyncLoggerTest
 *
 * Cada teste usa dados de entrada fixos e conhecidos (mock), nao aleatorios,
 * para que o resultado esperado seja previsivel e o teste seja reproduzivel.
 */
public class AsyncLoggerTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        testFifoOrderSingleThread();
        testPushBlocksWhenFull();
        testPopBlocksWhenEmptyAndNotClosed();
        testCloseDrainsThenReturnsNull();
        testCloseUnblocksWaitingConsumerWithNoMessages();
        testIntegrationWithFixedProducers();

        System.out.println();
        if (failures == 0) {
            System.out.println("TODOS OS TESTES PASSARAM.");
        } else {
            System.out.println(failures + " TESTE(S) FALHARAM.");
            System.exit(1);
        }
    }

    // ---------- utilidades ----------

    static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("[PASS] " + name);
        } else {
            System.out.println("[FAIL] " + name);
            failures++;
        }
    }

    static AsyncLogger.LogMessage mock(int seq, int threadId, String text) {
        return new AsyncLogger.LogMessage(threadId, 0, text, seq);
    }

    // ---------- testes ----------

    /** Sem concorrencia: pop() deve devolver na mesma ordem em que foi inserido (FIFO). */
    static void testFifoOrderSingleThread() throws InterruptedException {
        AsyncLogger.LogQueue q = new AsyncLogger.LogQueue();
        AsyncLogger.LogMessage m1 = mock(1, 1, "a");
        AsyncLogger.LogMessage m2 = mock(2, 1, "b");
        AsyncLogger.LogMessage m3 = mock(3, 1, "c");

        q.push(m1);
        q.push(m2);
        q.push(m3);

        boolean order = q.pop() == m1 && q.pop() == m2 && q.pop() == m3;
        check("FIFO: mensagens saem na ordem de insercao", order);
    }

    /** push() deve bloquear quando o buffer estiver cheio (capacidade = 10) e destravar apos um pop(). */
    static void testPushBlocksWhenFull() throws InterruptedException {
        AsyncLogger.LogQueue q = new AsyncLogger.LogQueue();
        for (int i = 1; i <= 10; i++) {
            q.push(mock(i, 1, "msg" + i));
        }

        AtomicReference<Exception> error = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                q.push(mock(11, 1, "msg11"));
            } catch (Exception e) {
                error.set(e);
            }
        });
        producer.start();
        Thread.sleep(200);
        check("push() bloqueia com buffer cheio (10/10)", producer.isAlive());

        AsyncLogger.LogMessage removed = q.pop(); // abre espaco
        producer.join(1000);
        check("push() destrava assim que ha espaco", !producer.isAlive() && error.get() == null);
        check("mensagem removida foi a mais antiga (seq=1)", removed.sequence == 1);
    }

    /** pop() deve bloquear quando vazio e nao fechado, e destravar assim que algo for inserido. */
    static void testPopBlocksWhenEmptyAndNotClosed() throws InterruptedException {
        AsyncLogger.LogQueue q = new AsyncLogger.LogQueue();
        AtomicReference<AsyncLogger.LogMessage> result = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                result.set(q.pop());
            } catch (InterruptedException ignored) {
            }
        });
        consumer.start();
        Thread.sleep(200);
        check("pop() bloqueia com fila vazia e producao ativa", consumer.isAlive());

        AsyncLogger.LogMessage sent = mock(99, 2, "acorda");
        q.push(sent);
        consumer.join(1000);
        check("pop() destrava e retorna a mensagem inserida", result.get() == sent);
    }

    /** Depois de close(), pop() deve drenar o que resta e so entao retornar null (sem bloquear). */
    static void testCloseDrainsThenReturnsNull() throws InterruptedException {
        AsyncLogger.LogQueue q = new AsyncLogger.LogQueue();
        q.push(mock(1, 1, "a"));
        q.push(mock(2, 1, "b"));
        q.push(mock(3, 1, "c"));
        q.close();

        boolean drainedInOrder = q.pop().sequence == 1 && q.pop().sequence == 2 && q.pop().sequence == 3;
        check("apos close(), mensagens restantes sao drenadas em ordem", drainedInOrder);

        AtomicReference<AsyncLogger.LogMessage> extra = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                extra.set(q.pop());
            } catch (InterruptedException ignored) {
            }
        });
        t.start();
        t.join(500);
        check("pop() apos fila vazia+fechada retorna null sem bloquear", !t.isAlive() && extra.get() == null);
    }

    /** Se a consumidora ja estava esperando (fila vazia) e close() e chamado, ela deve destravar com null. */
    static void testCloseUnblocksWaitingConsumerWithNoMessages() throws InterruptedException {
        AsyncLogger.LogQueue q = new AsyncLogger.LogQueue();
        AtomicReference<AsyncLogger.LogMessage> result = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                result.set(q.pop());
            } catch (InterruptedException ignored) {
            }
        });
        consumer.start();
        Thread.sleep(200);
        check("consumidora esperando em fila vazia (pre-close)", consumer.isAlive());

        q.close();
        consumer.join(1000);
        check("close() destrava consumidora esperando, resultado null", !consumer.isAlive() && result.get() == null);
    }

    /** Integracao com dados fixos (nao aleatorios): 2 produtoras x 5 mensagens cada, 1 consumidora. */
    static void testIntegrationWithFixedProducers() throws InterruptedException {
        AsyncLogger.LogQueue q = new AsyncLogger.LogQueue();
        int perProducer = 5;
        int totalExpected = 2 * perProducer;

        Set<AsyncLogger.LogMessage> received = new HashSet<>();
        Thread consumer = new Thread(() -> {
            try {
                AsyncLogger.LogMessage m;
                while ((m = q.pop()) != null) {
                    synchronized (received) {
                        received.add(m);
                    }
                }
            } catch (InterruptedException ignored) {
            }
        });
        consumer.start();

        Thread p1 = new Thread(() -> pushFixed(q, 1, perProducer, 1));
        Thread p2 = new Thread(() -> pushFixed(q, 2, perProducer, 1 + perProducer));
        p1.start();
        p2.start();
        p1.join();
        p2.join();
        q.close();
        consumer.join(2000);

        check("integracao: consumidora terminou (sem travar)", !consumer.isAlive());
        check("integracao: todas as " + totalExpected + " mensagens fixas foram recebidas sem perda/duplicacao",
                received.size() == totalExpected);
        check("integracao: capacidade nunca excedida", q.maxSizeObserved() <= AsyncLogger.CAPACITY);
    }

    private static void pushFixed(AsyncLogger.LogQueue q, int threadId, int count, int startSeq) {
        for (int i = 0; i < count; i++) {
            try {
                q.push(mock(startSeq + i, threadId, "fixo-" + i));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
