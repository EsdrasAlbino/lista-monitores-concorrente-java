import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bateria de testes com dados mockados para o monitor ConnectionPool.
 * Sem dependencias externas (sem JUnit). Roda com:
 *   javac *.java && java ConnectionPoolDemoTest
 */
public class ConnectionPoolDemoTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        testAcquireReturnsDistinctIndices();
        testAcquireBlocksWhenExhaustedThenUnblocksOnRelease();
        testReleaseOfAlreadyFreeConnectionThrows();
        testFixedConcurrentLoadNeverExceedsLimit();

        System.out.println();
        if (failures == 0) {
            System.out.println("TODOS OS TESTES PASSARAM.");
        } else {
            System.out.println(failures + " TESTE(S) FALHARAM.");
            System.exit(1);
        }
    }

    static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("[PASS] " + name);
        } else {
            System.out.println("[FAIL] " + name);
            failures++;
        }
    }

    /** As 5 primeiras aquisicoes (pool vazio no inicio) devem devolver 5 indices distintos, sem bloquear. */
    static void testAcquireReturnsDistinctIndices() throws InterruptedException {
        ConnectionPoolDemo.ConnectionPool pool = new ConnectionPoolDemo.ConnectionPool();
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < ConnectionPoolDemo.POOL_SIZE; i++) {
            ids.add(pool.acquire());
        }
        check("5 acquire() sem contencao devolvem 5 indices distintos", ids.size() == ConnectionPoolDemo.POOL_SIZE);
        check("availableCount() == 0 apos esgotar o pool", pool.availableCount() == 0);
    }

    /**
     * Mock: pool com todas as conexoes ocupadas. Uma 6a thread pedindo acquire()
     * deve bloquear ate um release() liberar espaco.
     */
    static void testAcquireBlocksWhenExhaustedThenUnblocksOnRelease() throws InterruptedException {
        ConnectionPoolDemo.ConnectionPool pool = new ConnectionPoolDemo.ConnectionPool();
        int[] held = new int[ConnectionPoolDemo.POOL_SIZE];
        for (int i = 0; i < ConnectionPoolDemo.POOL_SIZE; i++) {
            held[i] = pool.acquire();
        }

        AtomicInteger obtained = new AtomicInteger(-1);
        Thread waiter = new Thread(() -> {
            try {
                obtained.set(pool.acquire());
            } catch (InterruptedException ignored) {
            }
        });
        waiter.start();
        Thread.sleep(200);
        check("acquire() bloqueia quando as 5 conexoes estao em uso", waiter.isAlive());

        pool.release(held[0]);
        waiter.join(1000);
        check("acquire() destrava assim que uma conexao e liberada", !waiter.isAlive());
        check("thread destravada recebeu a conexao liberada", obtained.get() == held[0]);
    }

    /** Devolver uma conexao que ja esta livre deve ser tratado como erro de uso, nao silenciado. */
    static void testReleaseOfAlreadyFreeConnectionThrows() throws InterruptedException {
        ConnectionPoolDemo.ConnectionPool pool = new ConnectionPoolDemo.ConnectionPool();
        int id = pool.acquire();
        pool.release(id);

        boolean threw = false;
        try {
            pool.release(id); // double release da mesma conexao
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check("release() duplicado da mesma conexao lanca IllegalStateException", threw);
    }

    /**
     * Mock de carga fixa (nao aleatoria): 12 threads, cada uma faz acquire -> release
     * uma unica vez. Verifica que o pico de uso nunca excede POOL_SIZE e que o pool
     * volta ao estado inicial (5 livres) ao final.
     */
    static void testFixedConcurrentLoadNeverExceedsLimit() throws InterruptedException {
        ConnectionPoolDemo.ConnectionPool pool = new ConnectionPoolDemo.ConnectionPool();
        int numRequesters = 12;
        Thread[] threads = new Thread[numRequesters];
        for (int i = 0; i < numRequesters; i++) {
            final int delayMs = (i % 4) * 5; // atraso fixo e determinístico, nao aleatorio
            threads[i] = new Thread(() -> {
                try {
                    int id = pool.acquire();
                    Thread.sleep(delayMs);
                    pool.release(id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join(3000);

        boolean allFinished = true;
        for (Thread t : threads) {
            if (t.isAlive()) allFinished = false;
        }

        check("12 threads (carga fixa) finalizaram corretamente", allFinished);
        check("pico de conexoes em uso nunca excedeu " + ConnectionPoolDemo.POOL_SIZE, pool.peakInUse() <= ConnectionPoolDemo.POOL_SIZE);
        check("pool volta ao estado inicial (todas livres) ao final", pool.availableCount() == ConnectionPoolDemo.POOL_SIZE);
    }
}
