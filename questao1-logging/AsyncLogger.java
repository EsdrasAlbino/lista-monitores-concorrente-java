import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Questao 1 - Sistema de Logging Assincrono.
 *
 * Estado compartilhado: a fila de mensagens (ArrayDeque<LogMessage>),
 * a flag "closed" e o tamanho maximo observado (para instrumentacao).
 * Todo esse estado vive dentro da classe LogQueue e so e acessado a
 * partir de metodos synchronized -- esse e o monitor.
 */
public class AsyncLogger {

    static final int CAPACITY = 10;
    static final int NUM_PRODUCERS = 5;
    static final int MESSAGES_PER_PRODUCER = 20;

    /** Mensagem de log. Imutavel depois de criada, portanto segura para
     *  passar entre threads sem sincronizacao adicional. */
    static final class LogMessage {
        final int threadId;
        final int level;
        final String text;
        final int sequence;

        LogMessage(int threadId, int level, String text, int sequence) {
            this.threadId = threadId;
            this.level = level;
            this.text = text;
            this.sequence = sequence;
        }

        @Override
        public String toString() {
            return String.format("[seq=%03d][thread=%d][level=%d] %s", sequence, threadId, level, text);
        }
    }

    /**
     * Monitor: encapsula a fila compartilhada. Todas as operacoes publicas
     * sao synchronized (region critica = corpo inteiro do metodo).
     *
     * Variavel de condicao usada: a propria instancia do monitor (wait/notifyAll
     * no monitor implicito do objeto), com dois papeis distintos:
     *   - "nao esta cheio"  -> acorda produtoras bloqueadas em push()
     *   - "nao esta vazio OU fechado" -> acorda a consumidora bloqueada em pop()
     * Como um unico monitor de objeto so tem uma "fila de espera" logica em Java,
     * usamos notifyAll() em vez de notify() para nao perder acordares destinados
     * ao outro papel (uma produtora nao pode acordar so outra produtora).
     */
    static final class LogQueue {
        private final ArrayDeque<LogMessage> queue = new ArrayDeque<>();
        private boolean closed = false;
        private int maxSizeObserved = 0;

        /** Regiao critica: insercao. Espera enquanto o buffer estiver cheio. */
        synchronized void push(LogMessage message) throws InterruptedException {
            while (queue.size() == CAPACITY) {
                wait();
            }
            queue.addLast(message);
            if (queue.size() > maxSizeObserved) {
                maxSizeObserved = queue.size();
            }
            notifyAll();
        }

        /**
         * Regiao critica: remocao. Espera enquanto o buffer estiver vazio E a
         * producao ainda estiver ativa. Retorna null apenas quando vazio E
         * fechado -- sinal de que a consumidora pode encerrar.
         */
        synchronized LogMessage pop() throws InterruptedException {
            while (queue.isEmpty() && !closed) {
                wait();
            }
            if (queue.isEmpty() && closed) {
                return null;
            }
            LogMessage m = queue.removeFirst();
            notifyAll();
            return m;
        }

        /** Regiao critica: encerra a producao e acorda a consumidora se ela
         *  estiver esperando por mensagens que nunca chegarao. */
        synchronized void close() {
            closed = true;
            notifyAll();
        }

        synchronized int maxSizeObserved() {
            return maxSizeObserved;
        }
    }

    static class Producer extends Thread {
        private final LogQueue queue;
        private final int id;
        private final AtomicInteger sequenceGenerator;
        private final Random rnd = new Random();

        Producer(LogQueue queue, int id, AtomicInteger sequenceGenerator) {
            super("Producer-" + id);
            this.queue = queue;
            this.id = id;
            this.sequenceGenerator = sequenceGenerator;
        }

        @Override
        public void run() {
            for (int i = 0; i < MESSAGES_PER_PRODUCER; i++) {
                int seq = sequenceGenerator.incrementAndGet();
                int level = rnd.nextInt(3); // 0=INFO,1=WARN,2=ERROR
                LogMessage msg = new LogMessage(id, level, "evento " + i, seq);
                try {
                    queue.push(msg);
                    // simula tempo de processamento entre eventos; NAO e sincronizacao
                    Thread.sleep(rnd.nextInt(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    static class Consumer extends Thread {
        private final LogQueue queue;
        final List<LogMessage> processed = new ArrayList<>();

        Consumer(LogQueue queue) {
            super("Consumer");
            this.queue = queue;
        }

        @Override
        public void run() {
            while (true) {
                LogMessage msg;
                try {
                    msg = queue.pop();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (msg == null) {
                    break; // fila vazia e fechada -> nada mais vira
                }
                processed.add(msg);
                System.out.println("LOG " + msg);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        LogQueue queue = new LogQueue();
        AtomicInteger sequenceGenerator = new AtomicInteger(0);

        Consumer consumer = new Consumer(queue);
        consumer.start();

        List<Producer> producers = new ArrayList<>();
        for (int i = 1; i <= NUM_PRODUCERS; i++) {
            Producer p = new Producer(queue, i, sequenceGenerator);
            producers.add(p);
            p.start();
        }

        // encerramento correto: espera todas as produtoras terminarem de gerar
        // mensagens antes de fechar a fila.
        for (Producer p : producers) {
            p.join();
        }
        queue.close();

        // a consumidora so termina depois de drenar tudo o que restou.
        consumer.join();

        int expected = NUM_PRODUCERS * MESSAGES_PER_PRODUCER;
        int actual = consumer.processed.size();

        boolean[] seen = new boolean[expected + 1];
        boolean noDuplicates = true;
        for (LogMessage m : consumer.processed) {
            if (m.sequence < 1 || m.sequence > expected || seen[m.sequence]) {
                noDuplicates = false;
            } else {
                seen[m.sequence] = true;
            }
        }

        System.out.println();
        System.out.println("===== Verificacao de invariantes =====");
        System.out.println("Mensagens esperadas: " + expected);
        System.out.println("Mensagens processadas: " + actual);
        System.out.println("Nenhuma mensagem perdida ou duplicada: " + (actual == expected && noDuplicates));
        System.out.println("Tamanho maximo da fila observado: " + queue.maxSizeObserved() + " (capacidade = " + CAPACITY + ")");
        System.out.println("Buffer nunca excedeu a capacidade: " + (queue.maxSizeObserved() <= CAPACITY));
    }
}
