import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Questao 2 - Pool de Conexoes.
 *
 * Estado compartilhado: o vetor inUse[] (quais conexoes estao livres/ocupadas)
 * e o contador freeCount. Ambos vivem exclusivamente dentro de ConnectionPool
 * e so sao tocados dentro de metodos synchronized -- esse e o monitor.
 *
 * Importante (conforme enunciado): o limite de concorrencia NAO e imposto por
 * um semaforo. E o proprio estado do monitor (inUse/freeCount) mais a
 * condicao de espera "freeCount == 0" que garante o limite de 5 conexoes.
 */
public class ConnectionPoolDemo {

    static final int POOL_SIZE = 5;
    static final int NUM_REQUESTERS = 25;

    /** Monitor que encapsula o pool de conexoes. */
    static final class ConnectionPool {
        private final boolean[] inUse = new boolean[POOL_SIZE];
        private int freeCount = POOL_SIZE;

        // instrumentacao para comprovar o invariante "nunca mais de 5 em uso"
        private int currentInUse = 0;
        private int peakInUse = 0;

        /**
         * Regiao critica: obtem uma conexao livre ou aguarda.
         * Condicao de espera: freeCount == 0 (nenhuma conexao livre).
         * Retorna o indice da conexao concedida.
         */
        synchronized int acquire() throws InterruptedException {
            while (freeCount == 0) {
                wait();
            }
            for (int i = 0; i < POOL_SIZE; i++) {
                if (!inUse[i]) {
                    inUse[i] = true;
                    freeCount--;
                    currentInUse++;
                    if (currentInUse > peakInUse) {
                        peakInUse = currentInUse;
                    }
                    return i;
                }
            }
            throw new IllegalStateException("freeCount inconsistente com inUse[]");
        }

        /**
         * Regiao critica: devolve a conexao ao pool.
         * Sinaliza (notifyAll) qualquer thread esperando por uma conexao livre.
         */
        synchronized void release(int connectionId) {
            if (!inUse[connectionId]) {
                throw new IllegalStateException("release() de conexao que ja estava livre: " + connectionId);
            }
            inUse[connectionId] = false;
            freeCount++;
            currentInUse--;
            notifyAll();
        }

        synchronized int availableCount() {
            return freeCount;
        }

        synchronized int peakInUse() {
            return peakInUse;
        }
    }

    static class RequesterThread extends Thread {
        private final ConnectionPool pool;
        private final int requestId;
        private final Random rnd = new Random();
        volatile boolean sawMoreThanLimit = false;

        RequesterThread(ConnectionPool pool, int requestId) {
            super("Request-" + requestId);
            this.pool = pool;
            this.requestId = requestId;
        }

        @Override
        public void run() {
            try {
                int connectionId = pool.acquire();
                System.out.printf("Requisicao %2d -> obteve conexao %d (livres=%d)%n",
                        requestId, connectionId, pool.availableCount());

                // simula uso da conexao (operacao de banco); NAO e sincronizacao
                Thread.sleep(5 + rnd.nextInt(15));

                pool.release(connectionId);
                System.out.printf("Requisicao %2d <- devolveu conexao %d (livres=%d)%n",
                        requestId, connectionId, pool.availableCount());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        ConnectionPool pool = new ConnectionPool();

        List<RequesterThread> requesters = new ArrayList<>();
        for (int i = 1; i <= NUM_REQUESTERS; i++) {
            requesters.add(new RequesterThread(pool, i));
        }
        for (RequesterThread t : requesters) {
            t.start();
        }
        // encerramento correto: main aguarda todas as 25 threads terminarem
        for (RequesterThread t : requesters) {
            t.join();
        }

        System.out.println();
        System.out.println("===== Verificacao de invariantes =====");
        System.out.println("Threads finalizadas: " + requesters.size() + "/" + NUM_REQUESTERS);
        System.out.println("Conexoes livres ao final: " + pool.availableCount() + " (esperado = " + POOL_SIZE + ")");
        System.out.println("Pico de conexoes simultaneamente em uso: " + pool.peakInUse() + " (limite = " + POOL_SIZE + ")");
        System.out.println("Nunca excedeu o limite de " + POOL_SIZE + " conexoes: " + (pool.peakInUse() <= POOL_SIZE));
    }
}
