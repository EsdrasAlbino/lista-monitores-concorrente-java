import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Questao 3 - Controle de Estoque Compartilhado.
 *
 * Estado compartilhado: o inteiro "stock" (e os contadores min/maxObserved
 * usados apenas para instrumentacao/verificacao). Tudo dentro de Stock,
 * so acessado por metodos synchronized -- esse e o monitor.
 *
 * Ponto central do enunciado: a condicao de espera depende do valor que a
 * PROPRIA thread pediu (quantidade a retirar/repor). Por isso o wait() esta
 * dentro de um while que reavalia exatamente essa condicao, e usamos
 * notifyAll() (nao notify()) sempre que o estoque muda -- uma thread que
 * acorda pode descobrir que ainda nao pode prosseguir e volta a esperar.
 *
 * Para garantir que TODAS as threads terminam independentemente da ordem de
 * escalonamento (requisito da lista), a demanda total dos clientes e a
 * oferta total das reposicoes sao geradas de forma que a soma seja igual:
 * assim nenhuma thread fica esperando por unidades que nunca vao chegar.
 */
public class StockControl {

    static final int CAPACITY = 20;
    static final int INITIAL_STOCK = 8;
    static final int NUM_CLIENTS = 10;
    static final int NUM_RESTOCKERS = 4;
    static final int WITHDRAWALS_PER_CLIENT = 5;

    /** Monitor que encapsula o estoque compartilhado. */
    static final class Stock {
        private int stock;
        private int minObserved;
        private int maxObserved;

        Stock(int initial) {
            this.stock = initial;
            this.minObserved = initial;
            this.maxObserved = initial;
        }

        /**
         * Regiao critica: retira "quantity" unidades ou aguarda.
         * Condicao de espera: stock < quantity (depende do argumento da propria
         * thread, por isso NAO pode ser generalizada para "estoque vazio").
         */
        synchronized void withdraw(int quantity) throws InterruptedException {
            while (stock < quantity) {
                wait();
            }
            stock -= quantity;
            updateBounds();
            notifyAll(); // pode ter liberado espaco para uma reposicao aguardando
        }

        /**
         * Regiao critica: adiciona "quantity" unidades ou aguarda.
         * Condicao de espera: stock + quantity > CAPACITY.
         */
        synchronized void restock(int quantity) throws InterruptedException {
            while (stock + quantity > CAPACITY) {
                wait();
            }
            stock += quantity;
            updateBounds();
            notifyAll(); // pode ter liberado unidades para um cliente aguardando
        }

        synchronized int getStock() {
            return stock;
        }

        synchronized int minObserved() {
            return minObserved;
        }

        synchronized int maxObserved() {
            return maxObserved;
        }

        private void updateBounds() {
            if (stock < 0 || stock > CAPACITY) {
                throw new IllegalStateException("Invariante violado: estoque = " + stock);
            }
            if (stock < minObserved) minObserved = stock;
            if (stock > maxObserved) maxObserved = stock;
        }
    }

    static class ClientThread extends Thread {
        private final Stock stock;
        private final int[] amounts;
        private final Random rnd = new Random();

        ClientThread(Stock stock, int id, int[] amounts) {
            super("Client-" + id);
            this.stock = stock;
            this.amounts = amounts;
        }

        @Override
        public void run() {
            for (int qty : amounts) {
                try {
                    stock.withdraw(qty);
                    System.out.printf("%-10s comprou %d unidade(s) -> estoque=%d%n",
                            getName(), qty, stock.getStock());
                    Thread.sleep(rnd.nextInt(8)); // simula processamento do pedido
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    static class RestockerThread extends Thread {
        private final Stock stock;
        private final List<Integer> chunks;
        private final Random rnd = new Random();

        RestockerThread(Stock stock, int id, List<Integer> chunks) {
            super("Restocker-" + id);
            this.stock = stock;
            this.chunks = chunks;
        }

        @Override
        public void run() {
            for (int qty : chunks) {
                try {
                    stock.restock(qty);
                    System.out.printf("%-10s repos    %d unidade(s) -> estoque=%d%n",
                            getName(), qty, stock.getStock());
                    Thread.sleep(rnd.nextInt(8)); // simula chegada de mercadoria
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Random rnd = new Random(42);
        Stock stock = new Stock(INITIAL_STOCK);

        // 1) gera as quantidades de compra de cada cliente (1 a 6 unidades)
        //    e acumula a demanda total.
        int[][] clientAmounts = new int[NUM_CLIENTS][WITHDRAWALS_PER_CLIENT];
        int totalDemand = 0;
        for (int c = 0; c < NUM_CLIENTS; c++) {
            for (int i = 0; i < WITHDRAWALS_PER_CLIENT; i++) {
                int qty = 1 + rnd.nextInt(6);
                clientAmounts[c][i] = qty;
                totalDemand += qty;
            }
        }

        // 2) fragmenta a demanda total em pedacos de 1 a 3 unidades para as
        //    reposicoes, garantindo oferta total == demanda total. Assim o
        //    sistema sempre termina, nao importa a ordem de escalonamento.
        List<Integer> allChunks = new ArrayList<>();
        int remaining = totalDemand;
        while (remaining > 0) {
            int chunk = Math.min(remaining, 1 + rnd.nextInt(3));
            allChunks.add(chunk);
            remaining -= chunk;
        }
        List<List<Integer>> restockerChunks = new ArrayList<>();
        for (int r = 0; r < NUM_RESTOCKERS; r++) restockerChunks.add(new ArrayList<>());
        for (int i = 0; i < allChunks.size(); i++) {
            restockerChunks.get(i % NUM_RESTOCKERS).add(allChunks.get(i));
        }

        System.out.println("Demanda total dos clientes: " + totalDemand);
        System.out.println("Oferta total das reposicoes: " + allChunks.stream().mapToInt(Integer::intValue).sum());
        System.out.println("Estoque inicial: " + INITIAL_STOCK + " | capacidade: " + CAPACITY);
        System.out.println();

        List<Thread> clients = new ArrayList<>();
        for (int c = 0; c < NUM_CLIENTS; c++) {
            clients.add(new ClientThread(stock, c + 1, clientAmounts[c]));
        }
        List<Thread> restockers = new ArrayList<>();
        for (int r = 0; r < NUM_RESTOCKERS; r++) {
            restockers.add(new RestockerThread(stock, r + 1, restockerChunks.get(r)));
        }

        for (Thread t : clients) t.start();
        for (Thread t : restockers) t.start();

        for (Thread t : clients) t.join();
        for (Thread t : restockers) t.join();

        System.out.println();
        System.out.println("===== Verificacao de invariantes =====");
        System.out.println("Estoque final: " + stock.getStock() + " (esperado = " + INITIAL_STOCK + ", pois oferta == demanda)");
        System.out.println("Valor minimo observado: " + stock.minObserved() + " (>= 0)");
        System.out.println("Valor maximo observado: " + stock.maxObserved() + " (<= " + CAPACITY + ")");
        System.out.println("Nunca ficou negativo nem excedeu a capacidade: "
                + (stock.minObserved() >= 0 && stock.maxObserved() <= CAPACITY));
        System.out.println("Todas as " + (NUM_CLIENTS + NUM_RESTOCKERS) + " threads finalizadas corretamente.");
    }
}
