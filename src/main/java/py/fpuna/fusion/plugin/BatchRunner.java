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
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 *     --manifest experiments/manifests/ct-mri-184-pairs.csv \
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
        SecureRandom seedGenerator = parsed.seedsPath == null ? new SecureRandom() : null;
        Set<Long> generatedSeeds = parsed.seedsPath == null ? new HashSet<Long>() : null;

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
        long totalRunsTarget = parsed.maxRuns > 0
                ? Math.min(totalRunsPlanned, parsed.maxRuns) : totalRunsPlanned;
        System.out.println("BatchRunner: " + pairs.size() + " par(es) x " + configurations.size()
                + " configuracion(es) x " + config.runsPerPairAndConfiguration + " corrida(s) = "
                + totalRunsPlanned + " corridas planificadas, " + threads + " hilo(s).");

        writeMetadata(new File(outDir, "metadata.txt"), parsed, config, pairs, configurations, gitCommit, threads);

        RunWriter writer = new RunWriter(outDir);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int completed = 0;
        int failed = 0;
        int scheduled = 0;

        try {
            for (Pair pair : pairs) {
                if (scheduled >= totalRunsTarget) break;
                SMPSO_TopHat_Fusion.FusionCache cache;
                try {
                    cache = buildCache(pair, config);
                } catch (RuntimeException | IOException e) {
                    System.err.println("Par " + pair.id + ": no se pudo preparar (" + e.getMessage() + "). Se omite.");
                    writer.writeFailedPair(pair, e, gitCommit);
                    continue;
                }

                List<Future<RunResult>> futures = new ArrayList<Future<RunResult>>();
                for (Configuration cfg : configurations) {
                    for (int run = 1; run <= config.runsPerPairAndConfiguration; run++) {
                        if (scheduled >= totalRunsTarget) break;
                        long seed = resolveSeed(seedFile, seedGenerator, generatedSeeds,
                                pair.id, cfg, run);
                        String seedSource = parsed.seedsPath == null ? "generated" : "file";
                        writer.writeSeed(pair, cfg, run, seed);
                        RunTask task = new RunTask(pair, cfg, run, seed, seedSource, cache, gitCommit);
                        futures.add(pool.submit(task));
                        scheduled++;
                    }
                }

                List<RunResult> successfulRuns = new ArrayList<RunResult>();
                for (Future<RunResult> future : futures) {
                    RunResult result;
                    try {
                        result = future.get();
                    } catch (Exception e) {
                        failed++;
                        System.err.println("Corrida con error no controlado: " + e.getMessage());
                        continue;
                    }
                    writer.write(result);
                    if (result.isSuccessful()) {
                        successfulRuns.add(result);
                        completed++;
                    } else {
                        failed++;
                    }
                    int done = completed + failed;
                    if (done % 25 == 0 || done == totalRunsTarget) {
                        System.out.println("Progreso: " + done + "/" + totalRunsTarget + " corridas.");
                    }
                }

                if (!successfulRuns.isEmpty()) writer.writePairAnalysis(pair, successfulRuns);
                writer.flush();
            }
        } finally {
            pool.shutdown();
            writer.close();
        }

        System.out.println("BatchRunner finalizado: " + completed + " corrida(s) completada(s), "
                + failed + " fallida(s). Salida en: " + outDir.getPath());
    }

    // ------------------------------------------------------------------
    // Ejecucion de una corrida
    // ------------------------------------------------------------------

    private static final class RunTask implements Callable<RunResult> {
        private final Pair pair;
        private final Configuration cfg;
        private final int runIndex;
        private final long seed;
        private final String seedSource;
        private final SMPSO_TopHat_Fusion.FusionCache cache;
        private final String gitCommit;

        RunTask(Pair pair, Configuration cfg, int runIndex, long seed, String seedSource,
                SMPSO_TopHat_Fusion.FusionCache cache, String gitCommit) {
            this.pair = pair;
            this.cfg = cfg;
            this.runIndex = runIndex;
            this.seed = seed;
            this.seedSource = seedSource;
            this.cache = cache;
            this.gitCommit = gitCommit;
        }

        @Override
        public RunResult call() {
            long start = System.nanoTime();
            try {
                SMPSO_TopHat_Fusion.Optimizer optimizer = new SMPSO_TopHat_Fusion.Optimizer(
                        cache, cfg.particles, cfg.generations, cfg.archiveSize, seed);
                List<SMPSO_TopHat_Fusion.Solution> front = optimizer.run();
                double durationMs = (System.nanoTime() - start) / 1e6;

                return RunResult.success(pair, cfg, runIndex, seed, seedSource, gitCommit,
                        optimizer.getEvaluations(), durationMs, front);
            } catch (RuntimeException e) {
                double durationMs = (System.nanoTime() - start) / 1e6;
                System.err.println("Corrida fallida (par=" + pair.id + ", " + cfg.id + ", run="
                        + runIndex + "): " + e);
                return RunResult.failure(pair, cfg, runIndex, seed, seedSource, gitCommit,
                        durationMs, e.toString());
            }
        }
    }

    private static final class RunResult {
        final Pair pair;
        final Configuration configuration;
        final int runIndex;
        final long seed;
        final String seedSource;
        final String gitCommit;
        final int evaluations;
        final double durationMs;
        final List<SMPSO_TopHat_Fusion.Solution> front;
        final String error;

        private RunResult(Pair pair, Configuration configuration, int runIndex, long seed,
                          String seedSource, String gitCommit, int evaluations, double durationMs,
                          List<SMPSO_TopHat_Fusion.Solution> front, String error) {
            this.pair = pair;
            this.configuration = configuration;
            this.runIndex = runIndex;
            this.seed = seed;
            this.seedSource = seedSource;
            this.gitCommit = gitCommit;
            this.evaluations = evaluations;
            this.durationMs = durationMs;
            this.front = front;
            this.error = error;
        }

        static RunResult success(Pair pair, Configuration cfg, int runIndex, long seed,
                                 String seedSource, String gitCommit, int evaluations,
                                 double durationMs, List<SMPSO_TopHat_Fusion.Solution> front) {
            return new RunResult(pair, cfg, runIndex, seed, seedSource, gitCommit,
                    evaluations, durationMs, front, null);
        }

        static RunResult failure(Pair pair, Configuration cfg, int runIndex, long seed,
                                 String seedSource, String gitCommit, double durationMs, String error) {
            return new RunResult(pair, cfg, runIndex, seed, seedSource, gitCommit,
                    0, durationMs, new ArrayList<SMPSO_TopHat_Fusion.Solution>(), error);
        }

        boolean isSuccessful() {
            return error == null;
        }
    }

    private static SMPSO_TopHat_Fusion.FusionCache buildCache(Pair pair, ExperimentConfig config) throws IOException {
        FloatProcessor ct = loadGray(pair.ctPath, "CT", pair.id);
        FloatProcessor mri = loadGray(pair.mriPath, "MRI", pair.id);
        if (ct.getWidth() != mri.getWidth() || ct.getHeight() != mri.getHeight()) {
            throw new IllegalArgumentException("Las imagenes de " + pair.id
                    + " no tienen el mismo ancho y alto.");
        }
        return new SMPSO_TopHat_Fusion.FusionCache(ct, mri, config.radiusMin, config.radiusMax,
                config.whiteTopHatWeightMin, config.whiteTopHatWeightMax,
                config.blackTopHatWeightMin, config.blackTopHatWeightMax);
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

    private static long resolveSeed(Map<String, Long> seedFile, SecureRandom generator,
                                    Set<Long> generatedSeeds, String pairId, Configuration cfg, int run) {
        if (generator == null) {
            Long seed = seedFile.get(seedKey(pairId, cfg, run));
            if (seed == null) {
                throw new IllegalArgumentException("Falta semilla para par=" + pairId
                        + ", " + cfg.id + ", corrida=" + run);
            }
            return seed;
        }
        long seed;
        do {
            seed = generator.nextLong();
        } while (!generatedSeeds.add(seed));
        return seed;
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
            String key = pairId + "|" + particles + "|" + generations + "|" + run;
            if (seeds.put(key, seed) != null) {
                throw new IllegalArgumentException("Semilla duplicada para " + key);
            }
        }
        return seeds;
    }

    // ------------------------------------------------------------------
    // Manifiesto de pares
    // ------------------------------------------------------------------

    private static final class Pair {
        final String id;
        final String ctPath;
        final String mriPath;

        Pair(String id, String ctPath, String mriPath) {
            this.id = id;
            this.ctPath = ctPath;
            this.mriPath = mriPath;
        }
    }

    private static List<Pair> readManifest(String path, String pairFilterCsv) throws IOException {
        List<Pair> pairs = new ArrayList<Pair>();
        List<String[]> rows = readCsv(path);
        if (rows.isEmpty()) return pairs;
        Map<String, Integer> header = indexHeader(rows.get(0));
        Integer ctColumn = header.get("ct_path");
        Integer mriColumn = header.get("mri_path");
        // Compatibilidad con manifiestos creados antes de adoptar CT-MRI como dataset principal.
        if (ctColumn == null) ctColumn = header.get("visible_path");
        if (mriColumn == null) mriColumn = header.get("infrared_path");
        if (!header.containsKey("pair_id") || ctColumn == null || mriColumn == null) {
            throw new IllegalArgumentException(
                    "El manifiesto debe incluir pair_id, ct_path y mri_path");
        }
        java.util.Set<String> filter = null;
        if (pairFilterCsv != null && !pairFilterCsv.isEmpty()) {
            filter = new java.util.HashSet<String>();
            for (String id : pairFilterCsv.split(",")) filter.add(id.trim());
        }
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String pairId = row[header.get("pair_id")];
            if (filter != null && !filter.contains(pairId)) continue;
            pairs.add(new Pair(pairId, row[ctColumn], row[mriColumn]));
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
        double whiteTopHatWeightMin;
        double whiteTopHatWeightMax;
        double blackTopHatWeightMin;
        double blackTopHatWeightMax;
        List<Integer> particles = new ArrayList<Integer>();
        List<Integer> generations = new ArrayList<Integer>();
        int archiveSize;
        int runsPerPairAndConfiguration;
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
            Map<String, Object> whiteTopHatWeight =
                    (Map<String, Object>) decisionVariables.get("whiteTopHatWeight");
            @SuppressWarnings("unchecked")
            Map<String, Object> blackTopHatWeight =
                    (Map<String, Object>) decisionVariables.get("blackTopHatWeight");
            cfg.radiusMin = ((Number) radius.get("minimum")).intValue();
            cfg.radiusMax = ((Number) radius.get("maximum")).intValue();
            cfg.whiteTopHatWeightMin = ((Number) whiteTopHatWeight.get("minimum")).doubleValue();
            cfg.whiteTopHatWeightMax = ((Number) whiteTopHatWeight.get("maximum")).doubleValue();
            cfg.blackTopHatWeightMin = ((Number) blackTopHatWeight.get("minimum")).doubleValue();
            cfg.blackTopHatWeightMax = ((Number) blackTopHatWeight.get("maximum")).doubleValue();

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
            if (!"average".equalsIgnoreCase(cfg.baseImageRaw)) {
                throw new IllegalArgumentException("baseImage debe ser average para el experimento CT-MRI");
            }
            cfg.validate();
            return cfg;
        }

        private void validate() {
            if (radiusMin < 1 || radiusMax < radiusMin) {
                throw new IllegalArgumentException("Rango de radius invalido");
            }
            if (Math.abs(whiteTopHatWeightMin - SMPSO_TopHat_Fusion.CONTRAST_WEIGHT_MIN) > 1e-12
                    || Math.abs(whiteTopHatWeightMax - SMPSO_TopHat_Fusion.CONTRAST_WEIGHT_MAX) > 1e-12
                    || Math.abs(blackTopHatWeightMin - SMPSO_TopHat_Fusion.CONTRAST_WEIGHT_MIN) > 1e-12
                    || Math.abs(blackTopHatWeightMax - SMPSO_TopHat_Fusion.CONTRAST_WEIGHT_MAX) > 1e-12) {
                throw new IllegalArgumentException("Los pesos mWTH y mBTH deben usar el rango [0.35,2]");
            }
            if (particles.isEmpty() || generations.isEmpty()) {
                throw new IllegalArgumentException("Debe existir al menos una configuracion experimental");
            }
            for (int value : particles) {
                if (value < 4) throw new IllegalArgumentException("Cada enjambre debe tener al menos 4 particulas");
            }
            for (int value : generations) {
                if (value < 1) throw new IllegalArgumentException("Las generaciones deben ser positivas");
            }
            if (archiveSize < 2 || runsPerPairAndConfiguration < 1) {
                throw new IllegalArgumentException("archiveSize y runsPerPairAndConfiguration invalidos");
            }
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
    // Salida de corridas y postprocesamiento Pareto por par
    // ------------------------------------------------------------------

    private static final class RunWriter {
        private final PrintWriter runs;
        private final PrintWriter seeds;
        private final PrintWriter fronts;
        private final PrintWriter metrics;
        private final PrintWriter normalization;
        private final PrintWriter referenceFronts;
        private final PrintWriter consolidatedFronts;

        RunWriter(File outDir) throws IOException {
            runs = new PrintWriter(new File(outDir, "runs.csv"), "UTF-8");
            seeds = new PrintWriter(new File(outDir, "seeds.csv"), "UTF-8");
            fronts = new PrintWriter(new File(outDir, "fronts.csv"), "UTF-8");
            metrics = new PrintWriter(new File(outDir, "metrics.csv"), "UTF-8");
            normalization = new PrintWriter(new File(outDir, "normalization.csv"), "UTF-8");
            referenceFronts = new PrintWriter(new File(outDir, "reference_fronts.csv"), "UTF-8");
            consolidatedFronts = new PrintWriter(new File(outDir, "consolidated_fronts.csv"), "UTF-8");
            runs.println("pair_id,config_id,particles,generations,run,seed,seed_source,status,"
                    + "evaluations,duration_ms,front_size,git_commit,timestamp,error");
            seeds.println("pair_id,particles,generations,run,seed");
            fronts.println("pair_id,config_id,particles,generations,run,seed,r,m_wth,m_bth,"
                    + "entropy_normalized,ssim_global_average");
            metrics.println("pair_id,config_id,particles,generations,run,seed,hypervolume,igd");
            normalization.println("pair_id,ideal_entropy,nadir_entropy,ideal_ssim,nadir_ssim,"
                    + "hv_reference_z_entropy,hv_reference_z_ssim,union_size,reference_front_size");
            referenceFronts.println("pair_id,r,m_wth,m_bth,entropy_normalized,ssim_global_average,"
                    + "z_entropy,z_ssim");
            consolidatedFronts.println("pair_id,config_id,particles,generations,r,m_wth,m_bth,"
                    + "entropy_normalized,ssim_global_average");
        }

        void writeSeed(Pair pair, Configuration cfg, int run, long seed) {
            seeds.println(csv(pair.id) + "," + cfg.particles + "," + cfg.generations
                    + "," + run + "," + seed);
        }

        void write(RunResult result) {
            Pair pair = result.pair;
            Configuration cfg = result.configuration;
            String status = result.isSuccessful() ? "OK" : "FAILED";
            runs.println(csv(pair.id) + "," + csv(cfg.id) + "," + cfg.particles + "," + cfg.generations + ","
                    + result.runIndex + "," + result.seed + "," + result.seedSource + "," + status + ","
                    + result.evaluations + "," + format(result.durationMs, 3) + "," + result.front.size() + ","
                    + csv(result.gitCommit) + "," + Instant.now() + ","
                    + csv(result.error == null ? "" : result.error));
            if (!result.isSuccessful()) return;
            for (SMPSO_TopHat_Fusion.Solution s : result.front) {
                fronts.println(csv(pair.id) + "," + csv(cfg.id) + "," + cfg.particles + "," + cfg.generations + ","
                        + result.runIndex + "," + result.seed + "," + s.r + ","
                        + format(s.mWth, 9) + "," + format(s.mBth, 9) + ","
                        + format(s.entropy, 12) + "," + format(s.ssim, 12));
            }
        }

        void writePairAnalysis(Pair pair, List<RunResult> results) {
            List<List<SMPSO_TopHat_Fusion.Solution>> allFronts = new ArrayList<List<SMPSO_TopHat_Fusion.Solution>>();
            for (RunResult result : results) allFronts.add(result.front);
            ParetoMetrics.Analysis analysis = ParetoMetrics.analyze(allFronts);

            normalization.println(csv(pair.id) + "," + format(analysis.idealEntropy, 12) + ","
                    + format(analysis.nadirEntropy, 12) + "," + format(analysis.idealSsim, 12) + ","
                    + format(analysis.nadirSsim, 12) + "," + ParetoMetrics.REFERENCE_POINT + ","
                    + ParetoMetrics.REFERENCE_POINT + "," + analysis.unionSize + ","
                    + analysis.referenceFront.size());

            for (SMPSO_TopHat_Fusion.Solution solution : analysis.referenceFront) {
                ParetoMetrics.NormalizedPoint point = ParetoMetrics.normalize(solution, analysis);
                referenceFronts.println(csv(pair.id) + "," + solution.r + ","
                        + format(solution.mWth, 9) + "," + format(solution.mBth, 9) + ","
                        + format(solution.entropy, 12) + "," + format(solution.ssim, 12) + ","
                        + format(point.entropy, 12) + "," + format(point.ssim, 12));
            }

            Map<String, List<List<SMPSO_TopHat_Fusion.Solution>>> byConfiguration =
                    new LinkedHashMap<String, List<List<SMPSO_TopHat_Fusion.Solution>>>();
            Map<String, Configuration> configurationById = new LinkedHashMap<String, Configuration>();
            for (RunResult result : results) {
                double hv = ParetoMetrics.hypervolume(result.front, analysis);
                double igd = ParetoMetrics.igd(result.front, analysis);
                Configuration cfg = result.configuration;
                metrics.println(csv(pair.id) + "," + csv(cfg.id) + "," + cfg.particles + ","
                        + cfg.generations + "," + result.runIndex + "," + result.seed + ","
                        + format(hv, 12) + "," + format(igd, 12));

                if (!byConfiguration.containsKey(cfg.id)) {
                    byConfiguration.put(cfg.id, new ArrayList<List<SMPSO_TopHat_Fusion.Solution>>());
                    configurationById.put(cfg.id, cfg);
                }
                byConfiguration.get(cfg.id).add(result.front);
            }

            for (Map.Entry<String, List<List<SMPSO_TopHat_Fusion.Solution>>> entry
                    : byConfiguration.entrySet()) {
                Configuration cfg = configurationById.get(entry.getKey());
                for (SMPSO_TopHat_Fusion.Solution solution : ParetoMetrics.consolidate(entry.getValue())) {
                    consolidatedFronts.println(csv(pair.id) + "," + csv(cfg.id) + "," + cfg.particles + ","
                            + cfg.generations + "," + solution.r + "," + format(solution.mWth, 9) + ","
                            + format(solution.mBth, 9) + "," + format(solution.entropy, 12) + ","
                            + format(solution.ssim, 12));
                }
            }
        }

        void writeFailedPair(Pair pair, Exception e, String gitCommit) {
            String[] fields = {
                    csv(pair.id), "", "", "", "", "", "", "FAILED", "0", "0.000", "0",
                    csv(gitCommit), String.valueOf(Instant.now()), csv(e.toString())
            };
            runs.println(String.join(",", fields));
        }

        void flush() {
            runs.flush();
            seeds.flush();
            fronts.flush();
            metrics.flush();
            normalization.flush();
            referenceFronts.flush();
            consolidatedFronts.flush();
            if (seeds.checkError()) {
                throw new IllegalStateException("No se pudo guardar seeds.csv");
            }
        }

        void close() {
            flush();
            runs.close();
            seeds.close();
            fronts.close();
            metrics.close();
            normalization.close();
            referenceFronts.close();
            consolidatedFronts.close();
        }

        private static String format(double value, int decimals) {
            return String.format(java.util.Locale.US, "%." + decimals + "f", value);
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
            out.println("seeds: " + (parsed.seedsPath == null ? "generated" : parsed.seedsPath));
            out.println("seeds_output: seeds.csv");
            out.println("git_commit: " + gitCommit);
            out.println("started_at: " + Instant.now());
            out.println("threads: " + threads);
            out.println("available_processors: " + Runtime.getRuntime().availableProcessors());
            out.println("pairs: " + pairs.size());
            out.println("configurations: " + configurations.size());
            out.println("runs_per_pair_and_configuration: " + config.runsPerPairAndConfiguration);
            out.println("radius_range: [" + config.radiusMin + "," + config.radiusMax + "]");
            out.println("white_top_hat_weight_range: [" + config.whiteTopHatWeightMin + ","
                    + config.whiteTopHatWeightMax + "]");
            out.println("black_top_hat_weight_range: [" + config.blackTopHatWeightMin + ","
                    + config.blackTopHatWeightMax + "]");
            out.println("base_image: " + config.baseImageRaw);
            out.println("entropy_objective: shannon_normalized_by_8");
            out.println("ssim_objective: global_average_ct_mri");
            out.println("metric_normalization: empirical_ideal_nadir_per_pair_all_runs_all_configurations");
            out.println("metric_direction: minimization_after_normalization");
            out.println("hypervolume_reference_point: [" + ParetoMetrics.REFERENCE_POINT + ","
                    + ParetoMetrics.REFERENCE_POINT + "]");
            out.println("igd_reference_front: nondominated_union_per_pair_all_runs_all_configurations");
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
    // manifiestos y archivos de semillas generados por BatchRunner).
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
