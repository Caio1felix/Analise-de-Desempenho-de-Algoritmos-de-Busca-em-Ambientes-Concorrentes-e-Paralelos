import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;
import javax.swing.SwingWorker;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Aplicacao autocontida para comparar algoritmos seriais e paralelos de ordenacao.
 * Requer apenas Java 17+; usa Swing e ForkJoinPool, ambos incluidos no JDK.
 */
public class BenchmarkOrdenacaoApp {
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    enum Algorithm {
        BUBBLE("Bubble Sort"),
        MERGE("Merge Sort"),
        SELECTION("Selection Sort"),
        INSERTION("Insertion Sort");
        final String label;
        Algorithm(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    enum Distribution {
        RANDOM("Aleatório"),
        SORTED("Ordenado"),
        REVERSED("Inverso"),
        NEARLY_SORTED("Quase ordenado"),
        DUPLICATES("Muitos repetidos");
        final String label;
        Distribution(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    enum Mode { SERIAL("Serial"), PARALLEL("Paralelo");
        final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    static final class SampleResult {
        final String dateTime;
        final Algorithm algorithm;
        final Distribution distribution;
        final Mode mode;
        final int size;
        final int threads;
        final int sample;
        final long nanos;
        SampleResult(Algorithm algorithm, Distribution distribution, Mode mode, int size,
                     int threads, int sample, long nanos) {
            this.dateTime = LocalDateTime.now().format(DATE_TIME);
            this.algorithm = algorithm;
            this.distribution = distribution;
            this.mode = mode;
            this.size = size;
            this.threads = threads;
            this.sample = sample;
            this.nanos = nanos;
        }
        double millis() { return nanos / 1_000_000.0; }
    }

    static final class GroupKey {
        final Algorithm algorithm;
        final Distribution distribution;
        final Mode mode;
        final int size;
        final int threads;
        GroupKey(SampleResult r) {
            algorithm = r.algorithm; distribution = r.distribution; mode = r.mode;
            size = r.size; threads = r.threads;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof GroupKey)) return false;
            GroupKey k = (GroupKey) other;
            return algorithm == k.algorithm && distribution == k.distribution && mode == k.mode
                    && size == k.size && threads == k.threads;
        }
        @Override public int hashCode() {
            int h = algorithm.hashCode(); h = 31*h + distribution.hashCode(); h = 31*h + mode.hashCode();
            h = 31*h + size; h = 31*h + threads; return h;
        }
    }

    static final class Stats {
        final int count;
        final double mean;
        final double median;
        final double stddev;
        final double min;
        final double max;
        Stats(List<Double> values) {
            count = values.size();
            double sum = 0;
            for (double v : values) sum += v;
            mean = count == 0 ? 0 : sum / count;
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            if (count == 0) median = 0;
            else if ((count & 1) == 1) median = sorted.get(count / 2);
            else median = (sorted.get(count / 2 - 1) + sorted.get(count / 2)) / 2.0;
            double squared = 0;
            for (double v : values) squared += (v - mean) * (v - mean);
            stddev = count == 0 ? 0 : Math.sqrt(squared / count);
            min = count == 0 ? 0 : sorted.get(0);
            max = count == 0 ? 0 : sorted.get(count - 1);
        }
    }

    static final class SummaryRow {
        final GroupKey key;
        final Stats stats;
        final double speedup;
        SummaryRow(GroupKey key, Stats stats, double speedup) {
            this.key = key; this.stats = stats; this.speedup = speedup;
        }
    }

    static class Sorts {
        static void serial(Algorithm algorithm, int[] a) {
            switch (algorithm) {
                case BUBBLE: bubble(a); break;
                case MERGE: mergeSort(a); break;
                case SELECTION: selection(a); break;
                case INSERTION: insertion(a, 0, a.length); break;
            }
        }

        static void parallel(Algorithm algorithm, int[] a, ForkJoinPool pool) {
            if (a.length < 2) return;
            int p = Math.max(1, pool.getParallelism());
            switch (algorithm) {
                case BUBBLE: oddEvenParallel(a, pool); break;
                case MERGE: pool.invoke(new ParallelMergeTask(a, new int[a.length], 0, a.length,
                        Math.max(128, a.length / (p * 4)))); break;
                case SELECTION: selectionParallel(a, pool); break;
                case INSERTION: insertionParallel(a, pool); break;
            }
        }

        private static void bubble(int[] a) {
            for (int end = a.length - 1; end > 0; end--) {
                boolean swapped = false;
                for (int i = 0; i < end; i++) {
                    if (a[i] > a[i + 1]) { int t = a[i]; a[i] = a[i + 1]; a[i + 1] = t; swapped = true; }
                }
                if (!swapped) break;
            }
        }

        // Variante paralela correta do Bubble Sort: ordenacao odd-even/transposition.
        // Em cada fase, os pares processados nao se sobrepoem; a fase seguinte comeca apos a barreira do pool.
        private static void oddEvenParallel(int[] a, ForkJoinPool pool) {
            int n = a.length;
            int consecutiveNoSwap = 0;
            for (int phase = 0; phase < n && consecutiveNoSwap < 2; phase++) {
                int parity = phase & 1;
                int pairCount = (n - parity) / 2;
                AtomicBoolean swapped = new AtomicBoolean(false);
                if (pairCount > 0) pool.invoke(new OddEvenPhaseTask(a, parity, 0, pairCount, swapped));
                if (swapped.get()) consecutiveNoSwap = 0;
                else consecutiveNoSwap++;
            }
        }

        private static void selection(int[] a) {
            for (int i = 0; i < a.length - 1; i++) {
                int min = i;
                for (int j = i + 1; j < a.length; j++) if (a[j] < a[min]) min = j;
                if (min != i) { int t = a[i]; a[i] = a[min]; a[min] = t; }
            }
        }

        // O minimo da parte nao ordenada e reduzido em paralelo em cada iteracao.
        private static void selectionParallel(int[] a, ForkJoinPool pool) {
            int n = a.length;
            int cutoff = Math.max(48, n / (Math.max(1, pool.getParallelism()) * 4));
            for (int i = 0; i < n - 1; i++) {
                int min;
                if (n - i > cutoff * 2) min = pool.invoke(new MinIndexTask(a, i, n, cutoff));
                else {
                    min = i;
                    for (int j = i + 1; j < n; j++) if (a[j] < a[min]) min = j;
                }
                if (min != i) { int t = a[i]; a[i] = a[min]; a[min] = t; }
            }
        }

        private static void insertion(int[] a, int lo, int hi) {
            for (int i = lo + 1; i < hi; i++) {
                int value = a[i];
                int j = i - 1;
                while (j >= lo && a[j] > value) { a[j + 1] = a[j]; j--; }
                a[j + 1] = value;
            }
        }

        // Insercao por blocos: insertion sort em blocos independentes, em paralelo, seguido de merge paralelo.
        private static void insertionParallel(int[] a, ForkJoinPool pool) {
            int n = a.length;
            int p = Math.max(1, pool.getParallelism());
            int chunkSize = Math.max(32, (int)Math.ceil(n / (double)(p * 4)));
            int chunkCount = (n + chunkSize - 1) / chunkSize;
            pool.invoke(new InsertionChunksTask(a, chunkSize, n, 0, chunkCount));
            int[] aux = new int[n];
            for (int width = chunkSize; width < n; width = width > n / 2 ? n : width * 2) {
                int pairCount = (n + 2 * width - 1) / (2 * width);
                pool.invoke(new MergeLevelTask(a, aux, n, width, 0, pairCount));
                if (width == n) break;
            }
        }

        private static void mergeSort(int[] a) {
            if (a.length < 2) return;
            mergeSortRange(a, new int[a.length], 0, a.length);
        }

        private static void mergeSortRange(int[] a, int[] aux, int lo, int hi) {
            if (hi - lo < 2) return;
            int mid = lo + (hi - lo) / 2;
            mergeSortRange(a, aux, lo, mid);
            mergeSortRange(a, aux, mid, hi);
            merge(a, aux, lo, mid, hi);
        }

        private static void merge(int[] a, int[] aux, int lo, int mid, int hi) {
            int i = lo, j = mid, k = lo;
            while (i < mid && j < hi) aux[k++] = a[i] <= a[j] ? a[i++] : a[j++];
            while (i < mid) aux[k++] = a[i++];
            while (j < hi) aux[k++] = a[j++];
            System.arraycopy(aux, lo, a, lo, hi - lo);
        }

        static boolean isSorted(int[] a) {
            for (int i = 1; i < a.length; i++) if (a[i - 1] > a[i]) return false;
            return true;
        }

        private static final class OddEvenPhaseTask extends RecursiveAction {
            private final int[] a; private final int parity, from, to; private final AtomicBoolean swapped;
            OddEvenPhaseTask(int[] a, int parity, int from, int to, AtomicBoolean swapped) {
                this.a=a; this.parity=parity; this.from=from; this.to=to; this.swapped=swapped;
            }
            @Override protected void compute() {
                if (to - from <= 64) {
                    for (int pair = from; pair < to; pair++) {
                        int i = pair * 2 + parity;
                        if (a[i] > a[i + 1]) {
                            int t=a[i]; a[i]=a[i+1]; a[i+1]=t; swapped.set(true);
                        }
                    }
                } else {
                    int mid = from + (to - from) / 2;
                    invokeAll(new OddEvenPhaseTask(a, parity, from, mid, swapped),
                            new OddEvenPhaseTask(a, parity, mid, to, swapped));
                }
            }
        }

        private static final class MinIndexTask extends RecursiveTask<Integer> {
            private final int[] a; private final int from, to, cutoff;
            MinIndexTask(int[] a, int from, int to, int cutoff) { this.a=a; this.from=from; this.to=to; this.cutoff=cutoff; }
            @Override protected Integer compute() {
                if (to - from <= cutoff) {
                    int min=from;
                    for (int i=from+1; i<to; i++) if (a[i] < a[min]) min=i;
                    return min;
                }
                int mid = from + (to-from)/2;
                MinIndexTask left = new MinIndexTask(a, from, mid, cutoff);
                MinIndexTask right = new MinIndexTask(a, mid, to, cutoff);
                left.fork();
                int ri = right.compute();
                int li = left.join();
                if (a[li] < a[ri]) return li;
                if (a[ri] < a[li]) return ri;
                return Math.min(li, ri);
            }
        }

        private static final class ParallelMergeTask extends RecursiveAction {
            private final int[] a, aux; private final int lo, hi, cutoff;
            ParallelMergeTask(int[] a, int[] aux, int lo, int hi, int cutoff) {
                this.a=a; this.aux=aux; this.lo=lo; this.hi=hi; this.cutoff=cutoff;
            }
            @Override protected void compute() {
                if (hi-lo <= cutoff) { mergeSortRange(a, aux, lo, hi); return; }
                int mid=lo+(hi-lo)/2;
                invokeAll(new ParallelMergeTask(a,aux,lo,mid,cutoff), new ParallelMergeTask(a,aux,mid,hi,cutoff));
                merge(a,aux,lo,mid,hi);
            }
        }

        private static final class InsertionChunksTask extends RecursiveAction {
            private final int[] a; private final int chunkSize, n, firstChunk, lastChunk;
            InsertionChunksTask(int[] a, int chunkSize, int n, int firstChunk, int lastChunk) {
                this.a=a; this.chunkSize=chunkSize; this.n=n; this.firstChunk=firstChunk; this.lastChunk=lastChunk;
            }
            @Override protected void compute() {
                if (lastChunk-firstChunk <= 1) {
                    if (firstChunk < lastChunk) {
                        int lo=firstChunk*chunkSize, hi=Math.min(n,lo+chunkSize);
                        insertion(a,lo,hi);
                    }
                    return;
                }
                int mid=firstChunk+(lastChunk-firstChunk)/2;
                invokeAll(new InsertionChunksTask(a,chunkSize,n,firstChunk,mid),
                        new InsertionChunksTask(a,chunkSize,n,mid,lastChunk));
            }
        }

        private static final class MergeLevelTask extends RecursiveAction {
            private final int[] a, aux; private final int n, width, firstPair, lastPair;
            MergeLevelTask(int[] a, int[] aux, int n, int width, int firstPair, int lastPair) {
                this.a=a; this.aux=aux; this.n=n; this.width=width; this.firstPair=firstPair; this.lastPair=lastPair;
            }
            @Override protected void compute() {
                if (lastPair-firstPair <= 1) {
                    if (firstPair < lastPair) {
                        int lo=firstPair*2*width;
                        int mid=Math.min(n,lo+width);
                        int hi=Math.min(n,lo+2*width);
                        if (mid < hi) merge(a,aux,lo,mid,hi);
                    }
                    return;
                }
                int mid=firstPair+(lastPair-firstPair)/2;
                invokeAll(new MergeLevelTask(a,aux,n,width,firstPair,mid),
                        new MergeLevelTask(a,aux,n,width,mid,lastPair));
            }
        }
    }

    static final class Config {
        final List<Integer> sizes;
        final int samples;
        final List<Integer> threads;
        final List<Algorithm> algorithms;
        final List<Distribution> distributions;
        final boolean serial;
        final boolean parallel;
        Config(List<Integer> sizes, int samples, List<Integer> threads, List<Algorithm> algorithms,
               List<Distribution> distributions, boolean serial, boolean parallel) {
            this.sizes=sizes; this.samples=samples; this.threads=threads; this.algorithms=algorithms;
            this.distributions=distributions; this.serial=serial; this.parallel=parallel;
        }
        long totalRuns() {
            long modes = (serial ? 1 : 0) + (parallel ? threads.size() : 0);
            return (long)sizes.size()*samples*algorithms.size()*distributions.size()*modes;
        }
    }

    static List<SummaryRow> summarize(List<SampleResult> results) {
        Map<GroupKey, List<Double>> grouped = new LinkedHashMap<>();
        for (SampleResult r : results) grouped.computeIfAbsent(new GroupKey(r), k -> new ArrayList<>()).add(r.millis());
        Map<String, Double> serialMeans = new LinkedHashMap<>();
        for (Map.Entry<GroupKey,List<Double>> e : grouped.entrySet()) {
            GroupKey k=e.getKey();
            if (k.mode == Mode.SERIAL) serialMeans.put(baselineKey(k.algorithm,k.distribution,k.size), new Stats(e.getValue()).mean);
        }
        List<SummaryRow> summary = new ArrayList<>();
        for (Map.Entry<GroupKey,List<Double>> e : grouped.entrySet()) {
            GroupKey k=e.getKey(); Stats s=new Stats(e.getValue()); double speedup=Double.NaN;
            Double serial = serialMeans.get(baselineKey(k.algorithm,k.distribution,k.size));
            if (serial != null && s.mean > 0) speedup = serial / s.mean;
            summary.add(new SummaryRow(k,s,speedup));
        }
        summary.sort(Comparator.comparing((SummaryRow r)->r.key.algorithm.ordinal())
                .thenComparing(r->r.key.distribution.ordinal()).thenComparingInt(r->r.key.size)
                .thenComparing(r->r.key.mode.ordinal()).thenComparingInt(r->r.key.threads));
        return summary;
    }

    static String baselineKey(Algorithm a, Distribution d, int size) { return a.name()+"|"+d.name()+"|"+size; }

    static int[] createData(int n, Distribution distribution, long seed) {
        int[] a = new int[n]; Random random = new Random(seed);
        if (n == 0) return a;
        switch (distribution) {
            case RANDOM:
                for (int i=0; i<n; i++) a[i]=random.nextInt(Math.max(100, n*10));
                break;
            case SORTED:
                for (int i=0; i<n; i++) a[i]=i;
                break;
            case REVERSED:
                for (int i=0; i<n; i++) a[i]=n-i;
                break;
            case NEARLY_SORTED:
                for (int i=0; i<n; i++) a[i]=random.nextInt(Math.max(100, n*10));
                Arrays.sort(a);
                int swaps=Math.max(1,n/100);
                for (int s=0; s<swaps; s++) {
                    int i=random.nextInt(Math.max(1,n)), j=random.nextInt(Math.max(1,n));
                    int t=a[i]; a[i]=a[j]; a[j]=t;
                }
                break;
            case DUPLICATES:
                int range=Math.max(2, Math.min(20, n/10+1));
                for (int i=0; i<n; i++) a[i]=random.nextInt(range);
                break;
        }
        return a;
    }

    static void saveCsv(Path rawPath, Path summaryPath, List<SampleResult> results) throws IOException {
        Path parent=rawPath.toAbsolutePath().getParent(); if (parent!=null) Files.createDirectories(parent);
        try (BufferedWriter w=Files.newBufferedWriter(rawPath, StandardCharsets.UTF_8)) {
            w.write('\ufeff');
            w.write("data_hora;algoritmo;distribuicao;modo;tamanho;threads;amostra;tempo_ns;tempo_ms\n");
            for (SampleResult r : results) {
                w.write(csv(r.dateTime)+";"+csv(r.algorithm.label)+";"+csv(r.distribution.label)+";"+csv(r.mode.label)+";"
                        +r.size+";"+r.threads+";"+r.sample+";"+r.nanos+";"+String.format(Locale.ROOT,"%.6f",r.millis())+"\n");
            }
        }
        if (summaryPath != null) {
            Path summaryParent=summaryPath.toAbsolutePath().getParent(); if(summaryParent!=null) Files.createDirectories(summaryParent);
            try (BufferedWriter w=Files.newBufferedWriter(summaryPath, StandardCharsets.UTF_8)) {
                w.write('\ufeff');
                w.write("algoritmo;distribuicao;modo;tamanho;threads;amostras;media_ms;mediana_ms;desvio_padrao_ms;minimo_ms;maximo_ms;speedup\n");
                for (SummaryRow r : summarize(results)) {
                    GroupKey k=r.key; Stats s=r.stats;
                    w.write(csv(k.algorithm.label)+";"+csv(k.distribution.label)+";"+csv(k.mode.label)+";"+k.size+";"+k.threads+";"+s.count+";"
                            +fmt(s.mean)+";"+fmt(s.median)+";"+fmt(s.stddev)+";"+fmt(s.min)+";"+fmt(s.max)+";"
                            +(Double.isFinite(r.speedup)?fmt(r.speedup):"")+"\n");
                }
            }
        }
    }

    static String fmt(double v) { return String.format(Locale.ROOT,"%.6f",v); }
    static String csv(String s) {
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) return "\""+s.replace("\"","\"\"")+"\"";
        return s;
    }

    static final class BenchmarkFrame extends JFrame {
        private final JTextField sizesField = new JTextField("250, 500, 1000", 18);
        private final JTextField threadsField = new JTextField(defaultThreads(), 12);
        private final JSpinner samplesSpinner = new JSpinner(new SpinnerNumberModel(5, 5, 100, 1));
        private final JCheckBox serialCheck = new JCheckBox("Executar versão serial", true);
        private final JCheckBox parallelCheck = new JCheckBox("Executar versão paralela", true);
        private final Map<Algorithm,JCheckBox> algorithmChecks = new EnumMap<>(Algorithm.class);
        private final Map<Distribution,JCheckBox> distributionChecks = new EnumMap<>(Distribution.class);
        private final JButton runButton = new JButton("Executar benchmark");
        private final JButton exportButton = new JButton("Exportar CSV...");
        private final JLabel statusLabel = new JLabel("Configure o teste e clique em Executar benchmark.");
        private final JProgressBar progressBar = new JProgressBar(0,100);
        private final DefaultTableModel tableModel = new DefaultTableModel(new Object[]{
                "Algoritmo","Distribuição","Tamanho","Modo","Threads","Amostras","Média (ms)","Mediana (ms)","Desvio (ms)","Mín. (ms)","Máx. (ms)","Speedup"
        },0) { @Override public boolean isCellEditable(int r,int c){return false;} };
        private final JTable resultsTable = new JTable(tableModel);
        private final JComboBox<Algorithm> chartAlgorithm = new JComboBox<>(Algorithm.values());
        private final JComboBox<Distribution> chartDistribution = new JComboBox<>(Distribution.values());
        private final JComboBox<String> chartMetric = new JComboBox<>(new String[]{"Tempo médio (ms)","Speedup"});
        private final ChartPanel chartPanel = new ChartPanel();
        private List<SampleResult> lastResults = new ArrayList<>();
        private SwingWorker<List<SampleResult>,String> worker;

        BenchmarkFrame() {
            super("Benchmark de Ordenação Serial e Paralela — Java");
            setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            setMinimumSize(new Dimension(1000,680)); setSize(1220,820); setLocationRelativeTo(null);
            setLayout(new BorderLayout(8,8));
            add(buildTopPanel(),BorderLayout.NORTH);
            JTabbedPane tabs=new JTabbedPane();
            tabs.addTab("Resultados",buildResultsPanel());
            tabs.addTab("Gráfico dinâmico",buildChartPanel());
            add(tabs,BorderLayout.CENTER);
            JPanel bottom=new JPanel(new BorderLayout(8,4)); bottom.setBorder(new EmptyBorder(4,10,10,10));
            bottom.add(statusLabel,BorderLayout.CENTER); bottom.add(progressBar,BorderLayout.SOUTH); add(bottom,BorderLayout.SOUTH);
            exportButton.setEnabled(false);
            runButton.addActionListener(this::startBenchmark);
            exportButton.addActionListener(e->exportCsv());
            chartAlgorithm.addActionListener(e->refreshChart());
            chartDistribution.addActionListener(e->refreshChart());
            chartMetric.addActionListener(e->refreshChart());
        }

        private JPanel buildTopPanel() {
            JPanel outer=new JPanel(new BorderLayout(8,8)); outer.setBorder(new EmptyBorder(12,12,0,12));
            JPanel form=new JPanel(new GridBagLayout()); form.setBorder(BorderFactory.createTitledBorder("Configuração dos testes"));
            GridBagConstraints c=new GridBagConstraints(); c.insets=new Insets(4,6,4,6); c.anchor=GridBagConstraints.WEST;
            c.gridx=0;c.gridy=0; form.add(new JLabel("Tamanhos dos vetores:"),c);
            c.gridx=1;c.fill=GridBagConstraints.HORIZONTAL;c.weightx=1;form.add(sizesField,c);
            c.gridx=2;c.fill=GridBagConstraints.NONE;c.weightx=0;form.add(new JLabel("Amostras (mín. 5):"),c);
            c.gridx=3;form.add(samplesSpinner,c);
            c.gridx=0;c.gridy=1;form.add(new JLabel("Threads paralelas (vírgulas):"),c);
            c.gridx=1;c.fill=GridBagConstraints.HORIZONTAL;c.weightx=1;form.add(threadsField,c);
            c.gridx=2;c.gridwidth=2;c.fill=GridBagConstraints.NONE;c.weightx=0;
            form.add(new JLabel("Processadores disponíveis: "+Runtime.getRuntime().availableProcessors()),c);
            JPanel selections=new JPanel(new GridLayout(1,3,8,4));
            JPanel algPanel=new JPanel(new GridLayout(0,1));algPanel.setBorder(BorderFactory.createTitledBorder("Algoritmos"));
            for(Algorithm a:Algorithm.values()){JCheckBox cb=new JCheckBox(a.label,true);algorithmChecks.put(a,cb);algPanel.add(cb);} selections.add(algPanel);
            JPanel distPanel=new JPanel(new GridLayout(0,1));distPanel.setBorder(BorderFactory.createTitledBorder("Tipos de entrada"));
            for(Distribution d:Distribution.values()){JCheckBox cb=new JCheckBox(d.label,d==Distribution.RANDOM);distributionChecks.put(d,cb);distPanel.add(cb);} selections.add(distPanel);
            JPanel modePanel=new JPanel(new GridLayout(0,1));modePanel.setBorder(BorderFactory.createTitledBorder("Execução"));
            modePanel.add(serialCheck);modePanel.add(parallelCheck);
            JLabel note=new JLabel("Bubble, Selection e Insertion podem ter custo O(n²); aumente o tamanho gradualmente.");
            note.setFont(note.getFont().deriveFont(Font.ITALIC,11f));modePanel.add(note);selections.add(modePanel);
            JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0)); actions.add(runButton);actions.add(exportButton);
            outer.add(form,BorderLayout.NORTH);outer.add(selections,BorderLayout.CENTER);outer.add(actions,BorderLayout.SOUTH);return outer;
        }

        private JPanel buildResultsPanel() {
            JPanel panel=new JPanel(new BorderLayout(8,8)); panel.setBorder(new EmptyBorder(10,10,10,10));
            resultsTable.setAutoCreateRowSorter(true);resultsTable.setFillsViewportHeight(true);resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            int[] widths={145,135,85,85,65,70,95,95,95,90,90,75};
            for(int i=0;i<widths.length;i++)resultsTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            panel.add(new JScrollPane(resultsTable),BorderLayout.CENTER);
            JLabel info=new JLabel("A tabela apresenta estatísticas por configuração. Speedup = tempo médio serial / tempo médio paralelo; acima de 1 indica ganho.");
            info.setBorder(new EmptyBorder(0,0,2,0));panel.add(info,BorderLayout.SOUTH);return panel;
        }

        private JPanel buildChartPanel() {
            JPanel panel=new JPanel(new BorderLayout(8,8));panel.setBorder(new EmptyBorder(10,10,10,10));
            JPanel options=new JPanel(new FlowLayout(FlowLayout.LEFT));
            options.add(new JLabel("Algoritmo:"));options.add(chartAlgorithm);options.add(new JLabel("Entrada:"));options.add(chartDistribution);
            options.add(new JLabel("Métrica:"));options.add(chartMetric);panel.add(options,BorderLayout.NORTH);
            chartPanel.setBorder(BorderFactory.createLineBorder(new Color(220,220,220)));panel.add(chartPanel,BorderLayout.CENTER);
            return panel;
        }

        private void startBenchmark(ActionEvent event) {
            if(worker!=null&&!worker.isDone())return;
            Config config;
            try { config=readConfig(); }
            catch(IllegalArgumentException ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Configuração inválida",JOptionPane.WARNING_MESSAGE);return;}
            int max= config.sizes.get(config.sizes.size()-1);
            if(max>3000){int answer=JOptionPane.showConfirmDialog(this,
                    "O maior vetor tem "+max+" elementos. Bubble Sort, Selection Sort e algumas versões paralelas podem demorar bastante, especialmente com várias amostras.\nDeseja continuar?",
                    "Aviso de desempenho",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
                if(answer!=JOptionPane.YES_OPTION)return;}
            runButton.setEnabled(false);exportButton.setEnabled(false);progressBar.setValue(0);statusLabel.setText("Preparando execução e aquecimento da JVM...");
            worker=new SwingWorker<List<SampleResult>,String>() {
                @Override protected List<SampleResult> doInBackground() throws Exception {
                    List<SampleResult> result=new ArrayList<>();
                    Map<Integer,ForkJoinPool> pools=new LinkedHashMap<>();
                    try {
                        if(config.parallel)for(int threads:config.threads)pools.put(threads,new ForkJoinPool(threads));
                        warmUp(config,pools);
                        long total=Math.max(1,config.totalRuns()), done=0;
                        for(int size:config.sizes) for(Distribution distribution:config.distributions) for(int sample=1;sample<=config.samples;sample++) {
                            if(isCancelled()||Thread.currentThread().isInterrupted())throw new InterruptedException("Execução cancelada.");
                            long seed=0x5EEDL+size*31L+distribution.ordinal()*1000003L+sample*97L;
                            int[] base=createData(size,distribution,seed);
                            for(Algorithm algorithm:config.algorithms) {
                                if(config.serial) {
                                    int[] data=base.clone(); long start=System.nanoTime();Sorts.serial(algorithm,data);long elapsed=System.nanoTime()-start;
                                    if(!Sorts.isSorted(data))throw new IllegalStateException(algorithm.label+" serial produziu um vetor fora de ordem.");
                                    result.add(new SampleResult(algorithm,distribution,Mode.SERIAL,size,1,sample,elapsed));done++;
                                    updateProgress(done,total,"Serial | "+algorithm.label+" | n="+size+" | amostra "+sample+"/"+config.samples);
                                }
                                if(config.parallel) for(int threads:config.threads) {
                                    int[] data=base.clone(); long start=System.nanoTime();Sorts.parallel(algorithm,data,pools.get(threads));long elapsed=System.nanoTime()-start;
                                    if(!Sorts.isSorted(data))throw new IllegalStateException(algorithm.label+" paralelo produziu um vetor fora de ordem ("+threads+" threads).");
                                    result.add(new SampleResult(algorithm,distribution,Mode.PARALLEL,size,threads,sample,elapsed));done++;
                                    updateProgress(done,total,"Paralelo ("+threads+" threads) | "+algorithm.label+" | n="+size+" | amostra "+sample+"/"+config.samples);
                                }
                            }
                        }
                    } finally { for(ForkJoinPool p:pools.values())p.shutdown(); }
                    return result;
                }
                private void updateProgress(long done,long total,String message) {
                    setProgress((int)Math.min(100,done*100/total));publish(message);
                }
                @Override protected void process(List<String> messages) { if(!messages.isEmpty())statusLabel.setText(messages.get(messages.size()-1)); }
                @Override protected void done() {
                    runButton.setEnabled(true);
                    try {
                        lastResults=get(); populateTable(lastResults); refreshChart(); exportButton.setEnabled(!lastResults.isEmpty());
                        Path folder=Paths.get("resultados");String stamp=LocalDateTime.now().format(FILE_DATE);
                        Path raw=folder.resolve("resultados_ordenacao_"+stamp+".csv");
                        Path sum=folder.resolve("resumo_ordenacao_"+stamp+".csv");
                        saveCsv(raw,sum,lastResults);
                        progressBar.setValue(100);
                        statusLabel.setText("Concluído: "+lastResults.size()+" medições. CSVs salvos em: "+raw.toAbsolutePath());
                        JOptionPane.showMessageDialog(BenchmarkFrame.this,"Benchmark concluído.\n\nMedições: "+lastResults.size()+"\nCSV detalhado: "+raw.toAbsolutePath()+"\nCSV resumido: "+sum.toAbsolutePath(),"Concluído",JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        Throwable cause=ex.getCause()==null?ex:ex.getCause();
                        statusLabel.setText("Falha: "+cause.getMessage());
                        JOptionPane.showMessageDialog(BenchmarkFrame.this,"Não foi possível concluir os testes:\n"+cause.getMessage(),"Erro no benchmark",JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            worker.addPropertyChangeListener(e->{if("progress".equals(e.getPropertyName()))progressBar.setValue((Integer)e.getNewValue());});
            worker.execute();
        }

        private void warmUp(Config config, Map<Integer,ForkJoinPool> pools) throws InterruptedException {
            int n=Math.max(32,Math.min(300,config.sizes.get(0)));
            int[] base=createData(n,Distribution.RANDOM,1234567L);
            for(Algorithm algorithm:config.algorithms) {
                if(config.serial){int[] a=base.clone();Sorts.serial(algorithm,a);}
                if(config.parallel)for(ForkJoinPool pool:pools.values()){int[] a=base.clone();Sorts.parallel(algorithm,a,pool);}
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Aquecimento interrompido.");
            }
        }

        private Config readConfig() {
            List<Integer> sizes=parsePositiveList(sizesField.getText(),"tamanhos");
            if(sizes.isEmpty())throw new IllegalArgumentException("Informe pelo menos um tamanho de vetor.");
            for(int n:sizes)if(n>100000)throw new IllegalArgumentException("Cada tamanho deve ser menor ou igual a 100000. Vetores maiores podem consumir tempo e memória excessivos.");
            int samples=(Integer)samplesSpinner.getValue();if(samples<5)throw new IllegalArgumentException("Use pelo menos 5 amostras por configuração.");
            List<Algorithm> algorithms=new ArrayList<>();for(Algorithm a:Algorithm.values())if(algorithmChecks.get(a).isSelected())algorithms.add(a);
            List<Distribution> distributions=new ArrayList<>();for(Distribution d:Distribution.values())if(distributionChecks.get(d).isSelected())distributions.add(d);
            if(algorithms.isEmpty())throw new IllegalArgumentException("Selecione pelo menos um algoritmo.");
            if(distributions.isEmpty())throw new IllegalArgumentException("Selecione pelo menos um tipo de entrada.");
            boolean serial=serialCheck.isSelected(),parallel=parallelCheck.isSelected();
            if(!serial&&!parallel)throw new IllegalArgumentException("Selecione execução serial, paralela ou ambas.");
            List<Integer> threads=parsePositiveList(threadsField.getText(),"threads");
            if(parallel&&threads.isEmpty())throw new IllegalArgumentException("Informe pelo menos uma quantidade de threads para a versão paralela.");
            int cpus=Runtime.getRuntime().availableProcessors();
            for(int t:threads)if(t>cpus)throw new IllegalArgumentException("A quantidade de threads ("+t+") excede os processadores disponíveis informados pela JVM ("+cpus+"). Ajuste a lista de threads.");
            return new Config(sizes,samples,threads,algorithms,distributions,serial,parallel);
        }

        private List<Integer> parsePositiveList(String input,String label) {
            Set<Integer> values=new LinkedHashSet<>();
            try {
                for(String part:input.split("[,;\\s]+"))if(!part.trim().isEmpty()){
                    int value=Integer.parseInt(part.trim());if(value<=0)throw new IllegalArgumentException("Os valores de "+label+" devem ser inteiros positivos.");values.add(value);
                }
            } catch(NumberFormatException ex){throw new IllegalArgumentException("Formato inválido em "+label+". Use números separados por vírgula, por exemplo: 250, 500, 1000.");}
            List<Integer> result=new ArrayList<>(values);Collections.sort(result);return result;
        }

        private void populateTable(List<SampleResult> data) {
            tableModel.setRowCount(0);
            for(SummaryRow row:summarize(data)) {
                GroupKey k=row.key;Stats s=row.stats;
                tableModel.addRow(new Object[]{k.algorithm.label,k.distribution.label,k.size,k.mode.label,k.threads,s.count,
                        fmt(s.mean),fmt(s.median),fmt(s.stddev),fmt(s.min),fmt(s.max),Double.isFinite(row.speedup)?fmt(row.speedup):"—"});
            }
        }

        private void refreshChart() {
            chartPanel.setData(lastResults,(Algorithm)chartAlgorithm.getSelectedItem(),(Distribution)chartDistribution.getSelectedItem(),chartMetric.getSelectedIndex()==1);
        }

        private void exportCsv() {
            if(lastResults.isEmpty())return;
            JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Salvar resultados detalhados CSV");
            chooser.setSelectedFile(new java.io.File("resultados_ordenacao.csv"));
            if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;
            Path raw=chooser.getSelectedFile().toPath();String filename=raw.getFileName().toString();
            int dot=filename.lastIndexOf('.');String base=dot>0?filename.substring(0,dot):filename;
            Path summary=raw.resolveSibling(base+"_resumo.csv");
            try {saveCsv(raw,summary,lastResults);JOptionPane.showMessageDialog(this,"Arquivos salvos:\n"+raw.toAbsolutePath()+"\n"+summary.toAbsolutePath(),"Exportação concluída",JOptionPane.INFORMATION_MESSAGE);}
            catch(IOException ex){JOptionPane.showMessageDialog(this,"Erro ao salvar CSV:\n"+ex.getMessage(),"Erro",JOptionPane.ERROR_MESSAGE);}
        }
    }

    static final class ChartPanel extends JPanel {
        private List<SampleResult> data=new ArrayList<>();
        private Algorithm algorithm=Algorithm.BUBBLE;
        private Distribution distribution=Distribution.RANDOM;
        private boolean speedup=false;
        private final Color[] colors={new Color(45,105,180),new Color(220,90,50),new Color(45,145,90),new Color(145,80,175),new Color(190,135,25),new Color(40,150,160),new Color(120,120,120)};
        ChartPanel(){setBackground(Color.WHITE);setPreferredSize(new Dimension(850,480));}
        void setData(List<SampleResult> data,Algorithm algorithm,Distribution distribution,boolean speedup){
            this.data=new ArrayList<>(data);this.algorithm=algorithm;this.distribution=distribution;this.speedup=speedup;repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(),h=getHeight();int left=88,right=36,top=48,bottom=115;int pw=Math.max(1,w-left-right),ph=Math.max(1,h-top-bottom);
            g.setColor(Color.DARK_GRAY);g.setFont(getFont().deriveFont(Font.BOLD,14f));
            String title=(algorithm==null?"":algorithm.label)+" — "+(distribution==null?"":distribution.label);
            g.drawString(title, left, 24);
            if(data.isEmpty()){g.setFont(getFont().deriveFont(13f));g.drawString("Execute um benchmark para visualizar o gráfico.",left,top+30);g.dispose();return;}
            Map<Integer,Map<String,List<Double>>> values=new TreeMap<>();
            Map<String,Double> serialBySize=new TreeMap<>();
            for(SampleResult r:data)if(r.algorithm==algorithm&&r.distribution==distribution&&r.mode==Mode.SERIAL){
                String key="S|"+r.size;serialBySize.computeIfAbsent(key,k->0.0);serialBySize.put(key,serialBySize.get(key)+r.millis());
            }
            Map<String,Integer> serialCounts=new TreeMap<>();for(SampleResult r:data)if(r.algorithm==algorithm&&r.distribution==distribution&&r.mode==Mode.SERIAL)serialCounts.merge("S|"+r.size,1,Integer::sum);
            for(String key:new ArrayList<>(serialBySize.keySet()))serialBySize.put(key,serialBySize.get(key)/serialCounts.get(key));
            for(SampleResult r:data) {
                if(r.algorithm!=algorithm||r.distribution!=distribution)continue;
                if(speedup&&r.mode==Mode.SERIAL)continue;
                String series=r.mode==Mode.SERIAL?"Serial":"Paralelo - "+r.threads+" th";
                values.computeIfAbsent(r.size,k->new LinkedHashMap<>()).computeIfAbsent(series,k->new ArrayList<>()).add(r.millis());
            }
            Map<String,Map<Integer,Double>> seriesValues=new LinkedHashMap<>();
            for(Map.Entry<Integer,Map<String,List<Double>>> sizeEntry:values.entrySet())for(Map.Entry<String,List<Double>> seriesEntry:sizeEntry.getValue().entrySet()){
                double mean=seriesEntry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                if(speedup){String base="S|"+sizeEntry.getKey();Double serial=serialBySize.get(base);if(serial==null||mean<=0)continue;mean=serial/mean;}
                seriesValues.computeIfAbsent(seriesEntry.getKey(),k->new TreeMap<>()).put(sizeEntry.getKey(),mean);
            }
            if(seriesValues.isEmpty()){g.setColor(Color.GRAY);g.drawString(speedup?"Speedup indisponível: execute também a versão serial.":"Sem dados para esta seleção.",left,top+30);g.dispose();return;}
            List<Integer> xs=new ArrayList<>(values.keySet());double maxY=0;
            for(Map<Integer,Double> series:seriesValues.values())for(double v:series.values())if(Double.isFinite(v))maxY=Math.max(maxY,v);
            if(maxY<=0)maxY=1;maxY*=1.12;
            g.setFont(getFont().deriveFont(10f));
            for(int i=0;i<=5;i++){
                int y=top+ph-(int)Math.round(ph*i/5.0);double val=maxY*i/5.0;
                g.setColor(new Color(225,225,225));g.drawLine(left,y,left+pw,y);g.setColor(Color.DARK_GRAY);
                g.drawString(String.format(Locale.ROOT,"%.3g",val),8,y+4);
            }
            g.setColor(Color.GRAY);g.drawLine(left,top,left,top+ph);g.drawLine(left,top+ph,left+pw,top+ph);
            int nX=xs.size();
            for(int i=0;i<nX;i++){
                int x=nX==1?left+pw/2:left+(int)Math.round(pw*i/(double)(nX-1));
                g.setColor(Color.DARK_GRAY);g.drawLine(x,top+ph,x,top+ph+4);g.drawString(Integer.toString(xs.get(i)),x-15,top+ph+18);
            }
            g.setFont(getFont().deriveFont(Font.PLAIN,11f));g.drawString("Tamanho do vetor (n)",left+pw/2-45,h-17);
            Graphics2D gy=(Graphics2D)g.create();gy.rotate(-Math.PI/2);gy.drawString(speedup?"Speedup (serial / paralelo)":"Tempo médio (ms)",-top-ph/2-55,20);gy.dispose();
            int si=0;int legendY=top+ph+39;int legendX=left;
            for(Map.Entry<String,Map<Integer,Double>> se:seriesValues.entrySet()){
                Color color=colors[si%colors.length];g.setColor(color);int previousX=-1,previousY=-1;
                for(int xIndex=0;xIndex<xs.size();xIndex++){
                    int size=xs.get(xIndex);Double value=se.getValue().get(size);if(value==null||!Double.isFinite(value))continue;
                    int x=nX==1?left+pw/2:left+(int)Math.round(pw*xIndex/(double)(nX-1));int y=top+ph-(int)Math.round(ph*value/maxY);
                    if(previousX>=0)g.drawLine(previousX,previousY,x,y);g.fillOval(x-3,y-3,6,6);previousX=x;previousY=y;
                }
                int labelW=g.getFontMetrics().stringWidth(se.getKey())+36;
                if(legendX+labelW>w-right){legendX=left;legendY+=19;}
                g.drawLine(legendX,legendY-4,legendX+18,legendY-4);g.fillOval(legendX+7,legendY-7,6,6);g.setColor(Color.DARK_GRAY);g.drawString(se.getKey(),legendX+23,legendY);
                legendX+=labelW;si++;
            }
            g.dispose();
        }
    }

    static String defaultThreads() {
        int p=Math.max(1,Runtime.getRuntime().availableProcessors());List<Integer> values=new ArrayList<>();
        if(p>=2)values.add(2);if(p>=4)values.add(4);if(p>4)values.add(p);if(values.isEmpty())values.add(1);
        List<String> text=new ArrayList<>();for(int v:values)text.add(Integer.toString(v));return String.join(", ",text);
    }

    static void selfTest() {
        int[] sizes={0,1,2,3,4,5,16,31,32,63,64,127,128,255,256,513};
        int[] threadCounts={1,2,4};
        int count=0;
        for(int n:sizes)for(Distribution d:Distribution.values())for(int sample=0;sample<3;sample++) {
            int[] original=createData(n,d,777L+n*13L+d.ordinal()*31L+sample);
            int[] expected=original.clone();Arrays.sort(expected);
            for(Algorithm a:Algorithm.values()) {
                int[] serial=original.clone();Sorts.serial(a,serial);
                if(!Arrays.equals(expected,serial))throw new AssertionError("Falha serial: "+a+", n="+n+", tipo="+d);
                count++;
                for(int threads:threadCounts) {
                    ForkJoinPool pool=new ForkJoinPool(threads);
                    try {int[] parallel=original.clone();Sorts.parallel(a,parallel,pool);
                        if(!Arrays.equals(expected,parallel))throw new AssertionError("Falha paralela: "+a+", n="+n+", tipo="+d+", threads="+threads);
                    } finally {pool.shutdown();}
                    count++;
                }
            }
        }
        System.out.println("AUTOTESTE OK: "+count+" execucoes validadas em comparacao com Arrays.sort.");
    }

    public static void main(String[] args) {
        if(args.length>0&&"--self-test".equals(args[0])) {selfTest();return;}
        SwingUtilities.invokeLater(()->{
            try {UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception ignored){}
            new BenchmarkFrame().setVisible(true);
        });
    }
}
