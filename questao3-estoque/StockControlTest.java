import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bateria de testes com dados mockados para o monitor Stock.
 * Sem dependencias externas (sem JUnit). Roda com:
 *   javac *.java && java StockControlTest
 *
 * O teste mais importante aqui e testWakeupDoesNotImplyProgress(), que
 * reproduz literalmente o cenario descrito no enunciado: uma thread espera
 * retirar 5 unidades enquanto outra espera retirar 2, e uma reposicao
 * parcial so deve liberar quem pediu a quantidade menor.
 */
public class StockControlTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        testBasicWithdrawAndRestockSingleThread();
        testWithdrawBlocksUntilEnoughStockAccumulates();
        testWakeupDoesNotImplyProgress();
        testRestockBlocksWhenExceedingCapacity();
        testFixedBalancedLoadNeverViolatesBounds();

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

    /** Sem concorrencia: operacoes basicas devem apenas somar/subtrair corretamente. */
    static void testBasicWithdrawAndRestockSingleThread() throws InterruptedException {
        StockControl.Stock s = new StockControl.Stock(8);
        s.withdraw(3); // 8 -> 5
        s.restock(5);  // 5 -> 10
        check("operacoes basicas sem concorrencia: estoque final = 10", s.getStock() == 10);
    }

    /**
     * Mock: estoque comeca em 2. Uma thread pede withdraw(5) e deve continuar
     * bloqueada apos uma reposicao insuficiente (+2 = 4, ainda < 5), e so
     * destravar quando o estoque alcancar exatamente 5.
     */
    static void testWithdrawBlocksUntilEnoughStockAccumulates() throws InterruptedException {
        StockControl.Stock s = new StockControl.Stock(2);
        AtomicBoolean finished = new AtomicBoolean(false);
        Thread client = new Thread(() -> {
            try {
                s.withdraw(5);
                finished.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        client.start();
        Thread.sleep(150);
        check("withdraw(5) bloqueia com estoque=2", client.isAlive());

        s.restock(2); // estoque = 4, ainda insuficiente
        Thread.sleep(150);
        check("apos restock insuficiente (estoque=4), cliente continua bloqueado", client.isAlive());

        s.restock(1); // estoque = 5, agora suficiente
        client.join(1000);
        check("cliente destrava assim que o estoque alcanca o valor pedido", !client.isAlive() && finished.get());
        check("estoque final = 0 apos withdraw(5) ser concluido", s.getStock() == 0);
    }

    /**
     * Reproducao do exemplo do enunciado: estoque baixo, uma thread espera
     * retirar uma quantidade GRANDE (5) e outra espera retirar uma quantidade
     * PEQUENA (2). Uma reposicao parcial (+2) deve liberar apenas quem pediu
     * a quantidade pequena -- prova de que "acordar" nao significa "pode
     * prosseguir", e que o while (nao um if) e essencial.
     */
    static void testWakeupDoesNotImplyProgress() throws InterruptedException {
        StockControl.Stock s = new StockControl.Stock(0);
        AtomicBoolean bigFinished = new AtomicBoolean(false);
        AtomicBoolean smallFinished = new AtomicBoolean(false);

        Thread big = new Thread(() -> {
            try {
                s.withdraw(5);
                bigFinished.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        big.start();
        Thread.sleep(100); // garante que 'big' ja esta esperando antes de 'small' comecar

        Thread small = new Thread(() -> {
            try {
                s.withdraw(2);
                smallFinished.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        small.start();
        Thread.sleep(100);
        check("ambas as threads (pedindo 5 e 2) comecam bloqueadas com estoque=0", big.isAlive() && small.isAlive());

        s.restock(2); // estoque = 2: suficiente so para quem pediu 2
        small.join(1000);
        check("apenas a thread que pediu a quantidade pequena (2) e destravada", !small.isAlive() && smallFinished.get());
        check("a thread que pediu a quantidade grande (5) permanece esperando", big.isAlive() && !bigFinished.get());
        check("estoque volta a 0 (foi consumido pela thread pequena, nao pela grande)", s.getStock() == 0);

        s.restock(5); // agora sim da para atender quem pediu 5
        big.join(1000);
        check("thread grande destrava quando o estoque finalmente alcanca 5", !big.isAlive() && bigFinished.get());
        check("estoque final = 0", s.getStock() == 0);
    }

    /**
     * Mock: estoque proximo da capacidade (18/20). Uma reposicao de 5
     * excederia a capacidade e deve bloquear ate um withdraw abrir espaco.
     */
    static void testRestockBlocksWhenExceedingCapacity() throws InterruptedException {
        StockControl.Stock s = new StockControl.Stock(18);
        AtomicBoolean finished = new AtomicBoolean(false);
        Thread restocker = new Thread(() -> {
            try {
                s.restock(5); // 18 + 5 = 23 > 20
                finished.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        restocker.start();
        Thread.sleep(150);
        check("restock(5) bloqueia quando excederia a capacidade (18+5=23>20)", restocker.isAlive());

        s.withdraw(4); // estoque = 14; 14 + 5 = 19 <= 20, agora cabe
        restocker.join(1000);
        check("restock() destrava assim que ha espaco suficiente", !restocker.isAlive() && finished.get());
        check("estoque final = 19 (14 + 5)", s.getStock() == 19);
    }

    /**
     * Mock de carga fixa e balanceada (nao aleatoria): 3 clientes com listas
     * de retirada conhecidas e 2 repositores com listas de reposicao
     * conhecidas, cuja soma total e identica (18 == 18). Verifica que o
     * estoque nunca sai de [0,20] e retorna ao valor inicial ao final.
     */
    static void testFixedBalancedLoadNeverViolatesBounds() throws InterruptedException {
        StockControl.Stock s = new StockControl.Stock(8);

        int[][] clientPlans = {
                {3, 2, 1}, // cliente 1: total 6
                {2, 4},    // cliente 2: total 6
                {1, 3, 2}, // cliente 3: total 6
        };
        int[][] restockerPlans = {
                {5, 4}, // repositor 1: total 9
                {4, 5}, // repositor 2: total 9
        };

        Thread[] clients = new Thread[clientPlans.length];
        for (int i = 0; i < clientPlans.length; i++) {
            int[] plan = clientPlans[i];
            clients[i] = new Thread(() -> runWithdrawPlan(s, plan));
        }
        Thread[] restockers = new Thread[restockerPlans.length];
        for (int i = 0; i < restockerPlans.length; i++) {
            int[] plan = restockerPlans[i];
            restockers[i] = new Thread(() -> runRestockPlan(s, plan));
        }

        for (Thread t : clients) t.start();
        for (Thread t : restockers) t.start();
        for (Thread t : clients) t.join(3000);
        for (Thread t : restockers) t.join(3000);

        boolean allFinished = true;
        for (Thread t : clients) if (t.isAlive()) allFinished = false;
        for (Thread t : restockers) if (t.isAlive()) allFinished = false;

        check("carga fixa e balanceada (18 retirados == 18 repostos): todas as threads terminaram", allFinished);
        check("estoque final volta ao valor inicial (8)", s.getStock() == 8);
        check("estoque nunca ficou negativo (min observado >= 0)", s.minObserved() >= 0);
        check("estoque nunca excedeu a capacidade (max observado <= 20)", s.maxObserved() <= StockControl.CAPACITY);
    }

    private static void runWithdrawPlan(StockControl.Stock s, int[] plan) {
        for (int qty : plan) {
            try {
                s.withdraw(qty);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void runRestockPlan(StockControl.Stock s, int[] plan) {
        for (int qty : plan) {
            try {
                s.restock(qty);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
