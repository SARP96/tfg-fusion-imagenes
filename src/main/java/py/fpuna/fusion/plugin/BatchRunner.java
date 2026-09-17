package py.fpuna.fusion.plugin;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ejecuta en modo batch el diseno factorial descrito en un archivo de
 * configuracion de {@code experiments/configs} sobre los pares listados en un
 * manifiesto de {@code experiments/manifests}.
 *
 * <p>Por cada par de imagenes se calcula una unica {@link SMPSO_TopHat_Fusion.FusionCache}
 * (la parte cara: morfologia Top-Hat para cada radio factible) y se comparte,
 * de solo lectura, entre todas las combinaciones de particulas/generaciones y
 * las corridas independientes de ese par. Las corridas son independientes
 * entre si, por lo que se ejecutan en paralelo con un pool de hilos.</p>
 *
 * <p>No es un {@link ij.plugin.PlugIn}: se ejecuta desde linea de comandos,
 * sin abrir Fiji, para procesar el dataset completo sin intervencion manual.</p>
 *
 * <p>Uso:</p>
 * <pre>
 * java -cp target/classes:&lt;ij.jar&gt; py.fpuna.fusion.plugin.BatchRunner \
 *     --config experiments/configs/full-factorial.json \
 *     --manifest experiments/manifests/image-pairs-example.csv \
 *     --seeds experiments/seeds/seeds-example.csv \
 *     --out results/runs \
 *     --threads 8
 * </pre>
 */
public final class BatchRunner {

    private BatchRunner() {
    }

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        if (parsed.help) {
            printUsage();
            return;
        }

        ExperimentConfig config = ExperimentConfig.readFrom(parsed.configPath);
        List<Pair> pairs = readManifest(parsed.manifestPath, parsed.pairFilter);
        Map<String, Long> seedFile = parsed.seedsPath == null
                ? new HashMap<String, Long>()
                : readSeeds(parsed.seedsPath);

        if (pairs.isEmpty()) {
            System.err.println("El manifiesto no tiene pares para procesar: " + parsed.manifestPath);
            return;
        }

        String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(
                java.time.LocalDateTime.now());
        File outDir = new File(parsed.outDir, runId);
        if (!outDir.mkdirs() && !outDir.isDirectory()) {
            throw new IOException("No se pudo crear el directorio de salida: " + outDir);
        }

        String gitCommit = readGitCommit();
        List<Configuration> configurations = config.buildConfigurations();
        int threads = parsed.threads > 0 ? parsed.threads : Runtime.getRuntime().availableProcessors();

        long totalRunsPlanned = (long) pairs.size() * configurations.size() * config.runsPerPairAndConfiguration;
        System.out.println("BatchRunner: " + pairs.size() + " par(es) x " + configurations.size()
                + " configuracion(es) x " + config.runsPerPairAndConfiguration + " corrida(s) = "
                + totalRunsPlanned + " corridas planificadas, " + threads + " hilo(s).");

        writeMetadata(new File(outDir, "metadata.txt"), parsed, config, pairs, configurations, gitCommit, threads);

        File runsCsv = new File(outDir, "runs.csv");
        File frontsCsv = new File(outDir, "fronts.csv");
        RunWriter writer = new RunWriter(runsCsv, frontsCsv);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<Future<?>>();

        try {
            for (Pair pair : pairs) {
                SMPSO_TopHat_Fusion.FusionCache cache;
                try {
                    cache = buildCache(pair, config);
                } catch (RuntimeException | IOException e) {
                    System.err.println("Par " + pair.id + ": no se pudo preparar (" + e.getMessage() + "). Se omite.");
                    writer.writeFailedPair(pair, e, gitCommit);
                    continue;
                }

                for (Configuration cfg : configurations) {
                    for (int run = 1; run <= config.runsPerPairAndConfiguration; run++) {
                        long seed = resolveSeed(seedFile, pair.id, cfg, run);
                        String seedSource = seedFile.containsKey(seedKey(pair.id, cfg, run)) ? "file" : "derived";
                        RunTask task = new RunTask(pair, cfg, run, seed, seedSource, cache, gitCommit,
                                writer, completed, failed, totalRunsPlanned);
                        if (parsed.maxRuns > 0 && completed.get() + futures.size() >= parsed.maxRuns) {
                            continue;
                        }
                        futures.add(pool.submit(task));
                    }
                }
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // Cada RunTask ya registra sus propios errores; esto es una red de
                    // seguridad para fallas inesperadas del framework de hilos.
                    System.err.println("Corrida con error no controlado: " + e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
            writer.close();
        }

        System.out.println("BatchRunner finalizado: " + completed.get() + " corrida(s) completada(s), "
                + failed.get() + " fallida(s). Salida en: " + outDir.getPath());
    }

    // ------------------------------------------------------------------
    // Ejecucion de una corrida
    // ------------------------------------------------------------------

    private static final class RunTask implements Callable<Void> {
        private final Pair pair;
        private final Configuration cfg;
        private final int runIndex;
        private final long seed;
        private final String seedSource;
        private final SMPSO_TopHat_Fusion.FusionCache cache;
        private final String gitCommit;
        private final RunWriter writer;
        private final AtomicInteger completed;
        private final AtomicInteger failed;
        private final long totalRunsPlanned;

        RunTask(Pair pair, Configuration cfg, int runIndex, long seed, String seedSource,
                SMPSO_TopHat_Fusion.FusionCache cache, String gitCommit, RunWriter writer,
                AtomicInteger completed, AtomicInteger failed, long totalRunsPlanned) {
            this.pair = pair;
            this.cfg = cfg;
            this.runIndex = runIndex;
            this.seed = seed;
            this.seedSource = seedSource;
            this.cache = cache;
            this.gitCommit = gitCommit;
            this.writer = writer;
            this.completed = completed;
            this.failed = failed;
            this.totalRunsPlanned = totalRunsPlanned;
        }

        @Override
        public Void call() {
            long start = System.nanoTime();
            try {
                SMPSO_TopHat_Fusion.Optimizer optimizer = new SMPSO_TopHat_Fusion.Optimizer(
                        cache, cfg.particles, cfg.generations, cfg.archiveSize, seed);
                List<SMPSO_TopHat_Fusion.Solution> front = optimizer.run();
                double durationMs = (System.nanoTime() - start) / 1e6;

                writer.writeRun(pair, cfg, runIndex, seed, seedSource, gitCommit,
                        "OK", optimizer.getEvaluations(), durationMs, front.size(), null);
                writer.writeFront(pair, cfg, runIndex, seed, front);

                int done = completed.incrementAndGet();
                if (done % 25 == 0 || done == totalRunsPlanned) {
                    System.out.println("Progreso: " + done + "/" + totalRunsPlanned + " corridas.");
                }
            } catch (RuntimeException e) {
                double durationMs = (System.nanoTime() - start) / 1e6;
                failed.incrementAndGet();
                writer.writeRun(pair, cfg, runIndex, seed, seedSource, gitCommit,
                        "FAILED", 0, durationMs, 0, e.toString());
                System.err.println("Corrida fallida (par=" + pair.id + ", " + cfg.id + ", run="
                        + runIndex + "): " + e);
            }
            return null;
        }
    }

    private static SMPSO_TopHat_Fusion.FusionCache buildCache(Pair pair, ExperimentConfig config) throws IOException {
        FloatProcessor vis = loadGray(pair.visiblePath, "visible", pair.id);
        FloatProcessor ir = loadGray(pair.infraredPath, "infrarroja", pair.id);
        if (vis.getWidth() != ir.getWidth() || vis.getHeight() != ir.getHeight()) {
            throw new IllegalArgumentException("Las imagenes de " + pair.id
                    + " no tienen el mismo ancho y alto.");
        }
        return new SMPSO_TopHat_Fusion.FusionCache(vis, ir, config.baseMode,
                config.radiusMin, config.radiusMax, config.contrastWeightMin, config.contrastWeightMax);
    }

    private static FloatProcessor loadGray(String path, String label, String pairId) throws IOException {
        File file = new File(path);
        if (!file.isFile()) {
            throw new IOException("No se encontro la imagen " + label + " de " + pairId + ": " + path);
        }
        ImagePlus imp = IJ.openImage(file.getAbsolutePath());
        if (imp == null) {
            throw new IOException("No se pudo abrir la imagen " + label + " de " + pairId + ": " + path);
        }
        return SMPSO_TopHat_Fusion.toGrayFloat(imp);
    }

    // ------------------------------------------------------------------
    // Semillas
    // ------------------------------------------------------------------

    private static String seedKey(String pairId, Configuration cfg, int run) {
        return pairId + "|" + cfg.particles + "|" + cfg.generations + "|" + run;
    }

    private static long resolveSeed(Map<String, Long> seedFile, String pairId, Configuration cfg, int run) {
        Long fromFile = seedFile.get(seedKey(pairId, cfg, run));
        if (fromFile != null) return fromFile;
        // Semilla derivada, NO pre-registrada: solo para corridas piloto/exploratorias.
        // Las corridas formales deben declarar su semilla en experiments/seeds.
        String basis = pairId + "|" + cfg.particles + "|" + cfg.generations + "|" + run;
        long hash = 1125899906842597L; // FNV-like offset, evita 0 con entradas cortas.
        for (int i = 0; i < basis.length(); i++) {
            hash = 31 * hash + basis.charAt(i);
        }
        return hash;
    }

    private static Map<String, Long> readSeeds(String path) throws IOException {
        Map<String, Long> seeds = new HashMap<String, Long>();
        List<String[]> rows = readCsv(path);
        if (rows.isEmpty()) return seeds;
        Map<String, Integer> header = indexHeader(rows.get(0));
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String pairId = row[header.get("pair_id")];
            int particles = Integer.parseInt(row[header.get("particles")].trim());
            int generations = Integer.parseInt(row[header.get("generations")].trim());
            int run = Integer.parseInt(row[header.get("run")].trim());
            long seed = Long.parseLong(row[header.get("seed")].trim());
            seeds.put(pairId + "|" + particles + "|" + generations + "|" + run, seed);
        }
        return seeds;
    }

    // ------------------------------------------------------------------
    // Manifiesto de pares
    // ------------------------------------------------------------------

    private static final class Pair {
        final String id;
        final String visiblePath;
        final String infraredPath;

        Pair(String id, String visiblePath, String infraredPath) {
            this.id = id;
            this.visiblePath = visiblePath;
            this.infraredPath = infraredPath;
        }
    }

    private static List<Pair> readManifest(String path, String pairFilterCsv) throws IOException {
        List<Pair> pairs = new ArrayList<Pair>();
        List<String[]> rows = readCsv(path);
        if (rows.isEmpty()) return pairs;
        Map<String, Integer> header = indexHeader(rows.get(0));
        java.util.Set<String> filter = null;
        if (pairFilterCsv != null && !pairFilterCsv.isEmpty()) {
            filter = new java.util.HashSet<String>();
            for (String id : pairFilterCsv.split(",")) filter.add(id.trim());
        }
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String pairId = row[header.get("pair_id")];
            if (filter != null && !filter.contains(pairId)) continue;
            pairs.add(new Pair(pairId, row[header.get("visible_path")], row[header.get("infrared_path")]));
        }
        return pairs;
    }

    // ------------------------------------------------------------------
    // Configuracion del diseno experimental (JSON)
    // ------------------------------------------------------------------

    private static final class Configuration {
        final String id;
        final int particles;
        final int generations;
        final int archiveSize;

        Configuration(int particles, int generations, int archiveSize) {
            this.particles = particles;
            this.generations = generations;
            this.archiveSize = archiveSize;
            this.id = "P" + particles + "_G" + generations;
        }
    }

    private static final class ExperimentConfig {
        int radiusMin;
        int radiusMax;
        double contrastWeightMin;
        double contrastWeightMax;
        List<Integer> particles = new ArrayList<Integer>();
        List<Integer> generations = new ArrayList<Integer>();
        int archiveSize;
        int runsPerPairAndConfiguration;
        int baseMode;
        String baseImageRaw;

        static ExperimentConfig readFrom(String path) throws IOException {
            String content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            Object root = MiniJson.parse(content);
            @SuppressWarnings("unchecked")
            Map<String, Object> json = (Map<String, Object>) root;

            ExperimentConfig cfg = new ExperimentConfig();
            @SuppressWarnings("unchecked")
            Map<String, Object> decisionVariables = (Map<String, Object>) json.get("decisionVariables");
            @SuppressWarnings("unchecked")
            Map<String, Object> radius = (Map<String, Object>) decisionVariables.get("radius");
            @SuppressWarnings("unchecked")
            Map<String, Object> contrastWeight = (Map<String, Object>) decisionVariables.get("contrastWeight");
            cfg.radiusMin = ((Number) radius.get("minimum")).intValue();
            cfg.radiusMax = ((Number) radius.get("maximum")).intValue();
            cfg.contrastWeightMin = ((Number) contrastWeight.get("minimum")).doubleValue();
            cfg.contrastWeightMax = ((Number) contrastWeight.get("maximum")).doubleValue();

            @SuppressWarnings("unchecked")
            Map<String, Object> smpso = (Map<String, Object>) json.get("smpso");
            @SuppressWarnings("unchecked")
            List<Object> particlesRaw = (List<Object>) smpso.get("particles");
            @SuppressWarnings("unchecked")
            List<Object> generationsRaw = (List<Object>) smpso.get("generations");
            for (Object p : particlesRaw) cfg.particles.add(((Number) p).intValue());
            for (Object g : generationsRaw) cfg.generations.add(((Number) g).intValue());
            cfg.archiveSize = ((Number) smpso.get("archiveSize")).intValue();
            cfg.runsPerPairAndConfiguration = ((Number) smpso.get("runsPerPairAndConfiguration")).intValue();

            cfg.baseImageRaw = String.valueOf(json.get("baseImage"));
            cfg.baseMode = parseBaseMode(cfg.baseImageRaw);
            return cfg;
        }

        private static int parseBaseMode(String value) {
            if ("visible".equalsIgnoreCase(value)) return 1;
            if ("infrared".equalsIgnoreCase(value) || "infrarroja".equalsIgnoreCase(value)) return 2;
            if (!"average".equalsIgnoreCase(value)) {
                System.err.println("Aviso: baseImage=\"" + value + "\" no es una eleccion concreta "
                        + "(ver docs/decisions.md). Se usa el promedio VIS/IR por defecto.");
            }
            return 0;
        }

        List<Configuration> buildConfigurations() {
            List<Configuration> result = new ArrayList<Configuration>();
            for (int p : particles) {
                for (int g : generations) {
                    result.add(new Configuration(p, g, archiveSize));
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // Salida: runs.csv y fronts.csv
    // ------------------------------------------------------------------

    private static final class RunWriter {
        private final PrintWriter runs;
        private final PrintWriter fronts;

        RunWriter(File runsFile, File frontsFile) throws IOException {
            runs = new PrintWriter(runsFile, "UTF-8");
            fronts = new PrintWriter(frontsFile, "UTF-8");
            runs.println("pair_id,config_id,particles,generations,run,seed,seed_source,status,"
                    + "evaluations,duration_ms,front_size,git_commit,timestamp,error");
            fronts.println("pair_id,config_id,particles,generations,run,seed,r,m,entropy,ssim");
        }

        synchronized void writeRun(Pair pair, Configuration cfg, int runIndex, long seed, String seedSource,
                                    String gitCommit, String status, int evaluations, double durationMs,
                                    int frontSize, String error) {
            runs.println(csv(pair.id) + "," + csv(cfg.id) + "," + cfg.particles + "," + cfg.generations + ","
                    + runIndex + "," + seed + "," + seedSource + "," + status + "," + evaluations + ","
                    + String.format(java.util.Locale.US, "%.3f", durationMs) + "," + frontSize + ","
                    + csv(gitCommit) + "," + Instant.now() + "," + csv(error == null ? "" : error));
        }

        synchronized void writeFront(Pair pair, Configuration cfg, int runIndex, long seed,
                                      List<SMPSO_TopHat_Fusion.Solution> front) {
            for (SMPSO_TopHat_Fusion.Solution s : front) {
                fronts.println(csv(pair.id) + "," + csv(cfg.id) + "," + cfg.particles + "," + cfg.generations + ","
                        + runIndex + "," + seed + "," + s.r + ","
                        + String.format(java.util.Locale.US, "%.6f", s.m) + ","
                        + String.format(java.util.Locale.US, "%.6f", s.entropy) + ","
                        + String.format(java.util.Locale.US, "%.6f", s.ssim));
            }
        }

        synchronized void writeFailedPair(Pair pair, Exception e, String gitCommit) {
            String[] fields = {
                    csv(pair.id), "", "", "", "", "", "", "FAILED", "0", "0.000", "0",
                    csv(gitCommit), String.valueOf(Instant.now()), csv(e.toString())
            };
            runs.println(String.join(",", fields));
        }

        void close() {
            runs.flush();
            runs.close();
            fronts.flush();
            fronts.close();
        }

        private static String csv(String value) {
            if (value == null) return "";
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }
    }

    private static void writeMetadata(File file, Args parsed, ExperimentConfig config, List<Pair> pairs,
                                       List<Configuration> configurations, String gitCommit, int threads)
            throws IOException {
        try (PrintWriter out = new PrintWriter(file, "UTF-8")) {
            out.println("config: " + parsed.configPath);
            out.println("manifest: " + parsed.manifestPath);
            out.println("seeds: " + (parsed.seedsPath == null ? "(derivadas, no formales)" : parsed.seedsPath));
            out.println("git_commit: " + gitCommit);
            out.println("started_at: " + Instant.now());
            out.println("threads: " + threads);
            out.println("available_processors: " + Runtime.getRuntime().availableProcessors());
            out.println("pairs: " + pairs.size());
            out.println("configurations: " + configurations.size());
            out.println("runs_per_pair_and_configuration: " + config.runsPerPairAndConfiguration);
            out.println("radius_range: [" + config.radiusMin + "," + config.radiusMax + "]");
            out.println("contrast_weight_range: [" + config.contrastWeightMin + "," + config.contrastWeightMax + "]");
            out.println("base_image: " + config.baseImageRaw);
            out.println("max_runs_cap: " + (parsed.maxRuns > 0 ? parsed.maxRuns : "(sin limite)"));
            out.println("smpso_c1_range: [" + SMPSO_TopHat_Fusion.SMPSO_C1_MIN + ","
                    + SMPSO_TopHat_Fusion.SMPSO_C1_MAX + "]");
            out.println("smpso_c2_range: [" + SMPSO_TopHat_Fusion.SMPSO_C2_MIN + ","
                    + SMPSO_TopHat_Fusion.SMPSO_C2_MAX + "]");
            out.println("smpso_inertia_weight: " + SMPSO_TopHat_Fusion.SMPSO_INERTIA_WEIGHT);
            out.println("smpso_boundary_velocity_change: " + SMPSO_TopHat_Fusion.SMPSO_BOUNDARY_VELOCITY_CHANGE);
            out.println("smpso_mutation_frequency: " + SMPSO_TopHat_Fusion.SMPSO_MUTATION_FREQUENCY);
            out.println("smpso_mutation_probability: " + SMPSO_TopHat_Fusion.SMPSO_MUTATION_PROBABILITY);
            out.println("smpso_mutation_distribution_index: "
                    + SMPSO_TopHat_Fusion.SMPSO_MUTATION_DISTRIBUTION_INDEX);
            out.println("(parametros SMPSO fijos; ver docs/correcciones-smpso-referencia.md)");
        }
    }

    private static String readGitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            int code = p.waitFor();
            return (code == 0 && output != null) ? output.trim() : "unknown";
        } catch (IOException | InterruptedException e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------
    // CSV minimo (sin comillas embebidas complejas; suficiente para los
    // manifiestos y semillas versionados en experiments/).
    // ------------------------------------------------------------------

    private static List<String[]> readCsv(String path) throws IOException {
        List<String[]> rows = new ArrayList<String[]>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                rows.add(line.split(",", -1));
            }
        }
        return rows;
    }

    private static Map<String, Integer> indexHeader(String[] header) {
        Map<String, Integer> index = new HashMap<String, Integer>();
        for (int i = 0; i < header.length; i++) index.put(header[i].trim(), i);
        return index;
    }

    // ------------------------------------------------------------------
    // Argumentos de linea de comandos
    // ------------------------------------------------------------------

    private static final class Args {
        String configPath;
        String manifestPath;
        String seedsPath;
        String outDir = "results/runs";
        String pairFilter;
        int threads;
        int maxRuns;
        boolean help;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                switch (arg) {
                    case "--config": a.configPath = argv[++i]; break;
                    case "--manifest": a.manifestPath = argv[++i]; break;
                    case "--seeds": a.seedsPath = argv[++i]; break;
                    case "--out": a.outDir = argv[++i]; break;
                    case "--pairs": a.pairFilter = argv[++i]; break;
                    case "--threads": a.threads = Integer.parseInt(argv[++i]); break;
                    case "--max-runs": a.maxRuns = Integer.parseInt(argv[++i]); break;
                    case "--help": case "-h": a.help = true; break;
                    default:
                        throw new IllegalArgumentException("Argumento desconocido: " + arg);
                }
            }
            if (!a.help) {
                if (a.configPath == null) throw new IllegalArgumentException("Falta --config");
                if (a.manifestPath == null) throw new IllegalArgumentException("Falta --manifest");
            }
            return a;
        }
    }

    private static void printUsage() {
        System.out.println("Uso: BatchRunner --config <experiments/configs/*.json> "
                + "--manifest <experiments/manifests/*.csv> [--seeds <experiments/seeds/*.csv>] "
                + "[--out results/runs] [--threads N] [--pairs id1,id2] [--max-runs N]");
    }

    // ------------------------------------------------------------------
    // Parser JSON minimo (objetos, arreglos, strings, numeros, booleanos,
    // null). Alcanza para los archivos de experiments/configs; no pretende
    // ser un parser JSON general ni agrega una dependencia externa.
    // ------------------------------------------------------------------

    static final class MiniJson {
        private final String s;
        private int pos;

        private MiniJson(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            MiniJson parser = new MiniJson(text);
            parser.skipWhitespace();
            Object value = parser.parseValue();
            parser.skipWhitespace();
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                pos++; // :
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = s.charAt(pos++);
                if (c == '}') break;
                // si no es '}' se asume ',' y se continua
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<Object>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char c = s.charAt(pos++);
                if (c == ']') break;
            }
            return list;
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char next = s.charAt(pos++);
                    switch (next) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: sb.append(next);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < s.length() && "-+.eE0123456789".indexOf(s.charAt(pos)) >= 0) pos++;
            String token = s.substring(start, pos);
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }

        private void expect(String literal) {
            if (!s.regionMatches(pos, literal, 0, literal.length())) {
                throw new IllegalArgumentException("JSON invalido cerca de " + pos);
            }
            pos += literal.length();
        }

        private char peek() {
            return s.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
