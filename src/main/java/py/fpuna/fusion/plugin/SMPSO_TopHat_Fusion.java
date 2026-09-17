package py.fpuna.fusion.plugin;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Plot;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.filter.RankFilters;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Plugin de Fiji/ImageJ para fusionar una imagen visible y una infrarroja.
 *
 * <p>Entradas principales: dos imagenes registradas, limites de los parametros
 * {@code r} y {@code m}, y configuracion del enjambre. La optimizacion maximiza
 * simultaneamente la entropia y el SSIM promedio de la imagen fusionada.</p>
 *
 * <p>Salidas principales: archivo de soluciones no dominadas, tabla de
 * resultados, grafico del frente de Pareto e imagenes fusionadas opcionales.</p>
 */
public class SMPSO_TopHat_Fusion implements PlugIn {
    /** Rango dinamico de una imagen de 8 bits: valores entre 0 y 255. */
    private static final double L = 255.0;

    /** Constante de estabilidad para la comparacion de luminancia en SSIM. */
    private static final double SSIM_C1 = (0.01 * L) * (0.01 * L);

    /** Constante de estabilidad para la comparacion de contraste/estructura en SSIM. */
    private static final double SSIM_C2 = (0.03 * L) * (0.03 * L);

    /*
     * Parametros del SMPSO de referencia. PT-CLAHE informa los rangos de c1,
     * c2, r1 y r2, y limita cada velocidad a la mitad del rango de su variable.
     * La configuracion restante sigue la implementacion de referencia de jMetal.
     */
    static final double SMPSO_C1_MIN = 1.5;
    static final double SMPSO_C1_MAX = 2.5;
    static final double SMPSO_C2_MIN = 1.5;
    static final double SMPSO_C2_MAX = 2.5;
    static final double SMPSO_INERTIA_WEIGHT = 0.1;
    static final double SMPSO_BOUNDARY_VELOCITY_CHANGE = -1.0;
    static final int SMPSO_MUTATION_FREQUENCY = 6;
    static final double SMPSO_MUTATION_PROBABILITY = 0.5; // 1 / 2 variables.
    static final double SMPSO_MUTATION_DISTRIBUTION_INDEX = 20.0;

    /**
     * Punto de entrada ejecutado por Fiji al seleccionar el plugin.
     *
     * <p>Entrada: imagenes abiertas y parametros ingresados en el dialogo.
     * Salida: resultados Pareto mostrados en Fiji. Si una validacion falla,
     * muestra un mensaje y termina sin ejecutar la optimizacion.</p>
     *
     * @param arg argumento opcional enviado por ImageJ; no se usa en este plugin
     */
    @Override
    public void run(String arg) {
        // Recupera los identificadores de todas las imagenes abiertas en Fiji.
        int[] ids = WindowManager.getIDList();
        if (ids == null || ids.length < 2) {
            IJ.error("SMPSO Top-Hat Fusion", "Abra al menos dos imagenes: visible e infrarroja.");
            return;
        }

        // Convierte los identificadores en titulos para los selectores del dialogo.
        String[] titles = new String[ids.length];
        for (int i = 0; i < ids.length; i++) titles[i] = WindowManager.getImage(ids[i]).getTitle();

        // Define las entradas del usuario y sus valores iniciales sugeridos.
        GenericDialog gd = new GenericDialog("SMPSO Top-Hat Fusion");
        gd.addChoice("Imagen visible", titles, titles[0]);
        gd.addChoice("Imagen infrarroja", titles, titles[Math.min(1, titles.length - 1)]);
        gd.addChoice("Imagen base Ib", new String[]{"Promedio VIS/IR", "Visible", "Infrarroja"}, "Promedio VIS/IR");
        gd.addNumericField("Radio minimo r", 1, 0);
        gd.addNumericField("Radio maximo r", 15, 0);
        gd.addNumericField("Peso minimo m", 0.10, 2);
        gd.addNumericField("Peso maximo m", 2.00, 2);
        gd.addNumericField("Tamano del enjambre", 30, 0);
        gd.addNumericField("Iteraciones", 80, 0);
        gd.addNumericField("Tamano maximo del archivo Pareto", 30, 0);
        gd.addNumericField("Semilla aleatoria", 12345, 0);
        gd.addCheckbox("Mostrar imagenes Pareto", true);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        // Lee los campos en el mismo orden en que fueron agregados al dialogo.
        ImagePlus visImp = WindowManager.getImage(ids[gd.getNextChoiceIndex()]);
        ImagePlus irImp = WindowManager.getImage(ids[gd.getNextChoiceIndex()]);
        // baseMode: 0 = promedio, 1 = visible, 2 = infrarroja.
        int baseMode = gd.getNextChoiceIndex();
        int rMin = (int) gd.getNextNumber();
        int rMax = (int) gd.getNextNumber();
        double mMin = gd.getNextNumber();
        double mMax = gd.getNextNumber();
        int swarmSize = (int) gd.getNextNumber();
        int iterations = (int) gd.getNextNumber();
        int archiveSize = (int) gd.getNextNumber();
        long seed = (long) gd.getNextNumber();
        boolean showParetoImages = gd.getNextBoolean();

        // Detiene la ejecucion si las imagenes o los rangos no son validos.
        if (!validInputs(visImp, irImp, rMin, rMax, mMin, mMax, swarmSize, iterations, archiveSize)) return;

        // Convierte las fuentes a escala de grises flotante para la morfologia.
        FloatProcessor vis = toGrayFloat(visImp);
        FloatProcessor ir = toGrayFloat(irImp);
        Optimizer optimizer = new Optimizer(vis, ir, baseMode, rMin, rMax, mMin, mMax,
                swarmSize, iterations, archiveSize, seed);

        IJ.showStatus("Ejecutando SMPSO Top-Hat...");
        List<Solution> pareto = optimizer.run();
        // El orden por r y m facilita comparar parametros en la tabla final.
        Collections.sort(pareto, Comparator.comparingInt((Solution s) -> s.r).thenComparingDouble(s -> s.m));
        showResults(pareto, optimizer, showParetoImages);
        IJ.showStatus("SMPSO Top-Hat finalizado: " + pareto.size() + " soluciones Pareto.");
    }

    /**
     * Comprueba que las entradas permitan ejecutar la fusion.
     *
     * @param visImp imagen visible seleccionada
     * @param irImp imagen infrarroja seleccionada
     * @param rMin radio entero minimo
     * @param rMax radio entero maximo
     * @param mMin peso de contraste minimo
     * @param mMax peso de contraste maximo
     * @param swarmSize cantidad de particulas
     * @param iterations cantidad de iteraciones
     * @param archiveSize capacidad del archivo Pareto
     * @return {@code true} si todas las entradas son validas; {@code false} en caso contrario
     */
    private boolean validInputs(ImagePlus visImp, ImagePlus irImp, int rMin, int rMax, double mMin, double mMax,
                                int swarmSize, int iterations, int archiveSize) {
        if (visImp == null || irImp == null) {
            IJ.error("SMPSO Top-Hat Fusion", "No se pudieron leer las imagenes seleccionadas.");
            return false;
        }
        if (visImp.getWidth() != irImp.getWidth() || visImp.getHeight() != irImp.getHeight()) {
            IJ.error("SMPSO Top-Hat Fusion", "Las imagenes deben tener el mismo ancho y alto.");
            return false;
        }
        if (rMin < 1 || rMax < rMin) {
            IJ.error("SMPSO Top-Hat Fusion", "Use radios con 1 <= r_min <= r_max.");
            return false;
        }
        if (mMax < mMin) {
            IJ.error("SMPSO Top-Hat Fusion", "Use pesos con m_min <= m_max.");
            return false;
        }
        if (swarmSize < 4 || iterations < 1 || archiveSize < 2) {
            IJ.error("SMPSO Top-Hat Fusion", "Use enjambre >= 4, iteraciones >= 1 y archivo >= 2.");
            return false;
        }
        return true;
    }

    /**
     * Convierte una imagen de Fiji a escala de grises flotante en [0, 255].
     *
     * @param imp imagen de entrada, que puede tener otro tipo de pixel
     * @return copia independiente como {@link FloatProcessor}
     */
    static FloatProcessor toGrayFloat(ImagePlus imp) {
        // convertToByte(true) escala el rango visible a 8 bits antes de pasar a float.
        ImageProcessor ip = imp.getProcessor().convertToByte(true).convertToFloatProcessor();
        FloatProcessor fp = (FloatProcessor) ip.duplicate();
        float[] pixels = (float[]) fp.getPixels();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (float p : pixels) {
            if (p < min) min = p;
            if (p > max) max = p;
        }
        // Proteccion adicional: normaliza si algun valor quedara fuera de 8 bits.
        if (max > 255.0 || min < 0.0) {
            double range = max - min;
            if (range <= 0.0) range = 1.0;
            for (int i = 0; i < pixels.length; i++) pixels[i] = (float) ((pixels[i] - min) * 255.0 / range);
        }
        return fp;
    }

    /**
     * Presenta las soluciones del frente de Pareto.
     *
     * <p>Entrada: lista de soluciones y optimizador usado para reconstruir las
     * imagenes. Salida: tabla, grafico Entropia-SSIM e imagenes opcionales.</p>
     *
     * @param pareto soluciones no dominadas encontradas
     * @param optimizer optimizador que permite regenerar cada imagen fusionada
     * @param showParetoImages si es {@code true}, abre una imagen por solucion
     */
    private void showResults(List<Solution> pareto, Optimizer optimizer, boolean showParetoImages) {
        ResultsTable rt = new ResultsTable();
        double[] entropy = new double[pareto.size()];
        double[] ssim = new double[pareto.size()];

        for (int i = 0; i < pareto.size(); i++) {
            Solution s = pareto.get(i);
            rt.incrementCounter();
            rt.addValue("idx", i + 1);
            rt.addValue("r", s.r);
            rt.addValue("m", s.m);
            rt.addValue("Entropia_f1", s.entropy);
            rt.addValue("SSIM_promedio_f2", s.ssim);
            entropy[i] = s.entropy;
            ssim[i] = s.ssim;

            if (showParetoImages) {
                // Reconstruye la imagen usando exactamente los parametros de la fila.
                FloatProcessor fused = optimizer.fuse(s.r, s.m);
                new ImagePlus(String.format("Pareto_%02d_r%d_m%.3f", i + 1, s.r, s.m), fused).show();
            }
        }

        rt.show("Pareto SMPSO Top-Hat");
        if (!pareto.isEmpty()) {
            // Cada punto del grafico corresponde a una imagen fusionada distinta.
            Plot plot = new Plot("Frente de Pareto", "Entropia f1", "SSIM promedio f2", entropy, ssim);
            plot.setColor(java.awt.Color.BLUE);
            plot.addPoints(entropy, ssim, Plot.CIRCLE);
            plot.show();
        }
    }

    /**
     * Precalcula y conserva los mapas Top-Hat (WTH/BTH, VIS/IR) para cada
     * radio entero factible de un par de imagenes.
     *
     * <p>{@code r} solo toma {@code rMax-rMin+1} valores enteros posibles y la
     * morfologia no depende de {@code m}. Calcularla una sola vez por par y
     * reutilizarla en todas las evaluaciones (de una corrida o de muchas
     * corridas/configuraciones sobre el mismo par, como hace {@link BatchRunner})
     * evita repetir la parte mas costosa de {@code fuse} en cada evaluacion.</p>
     *
     * <p>Una vez construida, la instancia es inmutable y de solo lectura, por lo
     * que puede compartirse entre hilos sin sincronizacion adicional.</p>
     */
    static class FusionCache {
        final FloatProcessor vis;
        final FloatProcessor ir;
        final int baseMode;
        final int width;
        final int height;
        final int rMin;
        final int rMax;
        final double mMin;
        final double mMax;

        private final FloatProcessor[] wthIrByR;
        private final FloatProcessor[] wthVisByR;
        private final FloatProcessor[] bthIrByR;
        private final FloatProcessor[] bthVisByR;

        /**
         * Precalcula la morfologia para todo el rango factible de {@code r}.
         *
         * @param vis imagen visible en escala de grises
         * @param ir imagen infrarroja en escala de grises
         * @param baseMode seleccion de la imagen base
         * @param rMin radio minimo permitido
         * @param rMax radio maximo permitido
         * @param mMin peso minimo permitido
         * @param mMax peso maximo permitido
         */
        FusionCache(FloatProcessor vis, FloatProcessor ir, int baseMode,
                    int rMin, int rMax, double mMin, double mMax) {
            this.vis = vis;
            this.ir = ir;
            this.baseMode = baseMode;
            this.width = vis.getWidth();
            this.height = vis.getHeight();
            this.rMin = rMin;
            this.rMax = rMax;
            this.mMin = mMin;
            this.mMax = mMax;

            // RankFilters coordina sus propios hilos con contadores atomicos pensados
            // para ser accionados desde PlugInFilterRunner. Invocado directamente (como
            // aqui) con mas de un hilo puede bloquearse o dejar hilos activos. Se fuerza
            // un solo hilo y el paralelismo se hace afuera, entre corridas independientes.
            Prefs.setThreads(1);

            this.wthIrByR = new FloatProcessor[rMax + 1];
            this.wthVisByR = new FloatProcessor[rMax + 1];
            this.bthIrByR = new FloatProcessor[rMax + 1];
            this.bthVisByR = new FloatProcessor[rMax + 1];
            for (int r = rMin; r <= rMax; r++) {
                wthIrByR[r] = whiteTopHat(ir, r);
                wthVisByR[r] = whiteTopHat(vis, r);
                bthIrByR[r] = blackTopHat(ir, r);
                bthVisByR[r] = blackTopHat(vis, r);
            }
        }

        /**
         * Genera una imagen fusionada reutilizando la morfologia precalculada.
         *
         * @param r radio del elemento estructurante; debe estar en [rMin, rMax]
         * @param m peso aplicado a los detalles Top-Hat
         * @return nueva imagen Ifus en el rango [0, 255]
         */
        FloatProcessor fuse(int r, double m) {
            FloatProcessor out = new FloatProcessor(width, height);
            float[] irPix = (float[]) ir.getPixels();
            float[] visPix = (float[]) vis.getPixels();
            float[] wIr = (float[]) wthIrByR[r].getPixels();
            float[] wVis = (float[]) wthVisByR[r].getPixels();
            float[] bIr = (float[]) bthIrByR[r].getPixels();
            float[] bVis = (float[]) bthVisByR[r].getPixels();
            float[] fused = (float[]) out.getPixels();

            for (int i = 0; i < fused.length; i++) {
                double base;
                if (baseMode == 1) base = visPix[i];
                else if (baseMode == 2) base = irPix[i];
                else base = 0.5 * (visPix[i] + irPix[i]);

                double value = base + m * Math.max(wIr[i], wVis[i]) - m * Math.max(bIr[i], bVis[i]);
                fused[i] = (float) clamp(value, 0.0, 255.0);
            }
            return out;
        }

        /**
         * Evalua una posicion del enjambre reutilizando la cache de morfologia.
         *
         * <p>No incrementa ningun contador: es un metodo sin estado mutable,
         * seguro para invocarse concurrentemente desde varios {@link Optimizer}
         * que comparten esta misma cache.</p>
         *
         * @param rPosition posicion continua del radio; se redondea a entero
         * @param mPosition posicion continua del peso de contraste
         * @return solucion con parametros factibles, entropia y SSIM promedio
         */
        Solution evaluate(double rPosition, double mPosition) {
            int r = (int) Math.round(clamp(rPosition, rMin, rMax));
            double m = clamp(mPosition, mMin, mMax);
            FloatProcessor fused = fuse(r, m);
            double entropy = entropy(fused);
            double ssim = 0.5 * (ssim(fused, vis) + ssim(fused, ir));
            return new Solution(r, m, entropy, ssim);
        }
    }

    /**
     * Nucleo del algoritmo: genera fusiones, evalua objetivos y aproxima el
     * frente de Pareto mediante SMPSO con los operadores de referencia.
     */
    static class Optimizer {
        /** Morfologia precalculada del par de imagenes; de solo lectura. */
        private final FusionCache cache;

        /** Limites del espacio de decision (r, m), copiados de la cache. */
        private final int rMin;
        private final int rMax;
        private final double mMin;
        private final double mMax;

        /** Configuracion de la busqueda y del archivo externo. */
        private final int swarmSize;
        private final int iterations;
        private final int archiveSize;

        /** Generador pseudoaleatorio controlado por una semilla reproducible. */
        private final Random random;

        /** Soluciones no dominadas conservadas durante la busqueda. */
        private final ArrayList<Solution> archive = new ArrayList<Solution>();

        /** Cantidad de evaluaciones de la funcion multiobjetivo realizadas. */
        private int evaluations;

        /**
         * Crea el optimizador a partir de una cache de morfologia ya calculada.
         *
         * <p>Pensado para reutilizar la misma cache (un par de imagenes) en
         * muchas corridas y configuraciones distintas, como hace
         * {@link BatchRunner}: la morfologia se calcula una unica vez por par.</p>
         *
         * @param cache morfologia precalculada del par de imagenes
         * @param swarmSize cantidad de particulas
         * @param iterations cantidad de iteraciones
         * @param archiveSize capacidad maxima del archivo Pareto
         * @param seed semilla usada para reproducir la ejecucion
         */
        Optimizer(FusionCache cache, int swarmSize, int iterations, int archiveSize, long seed) {
            this.cache = cache;
            this.rMin = cache.rMin;
            this.rMax = cache.rMax;
            this.mMin = cache.mMin;
            this.mMax = cache.mMax;
            this.swarmSize = swarmSize;
            this.iterations = iterations;
            this.archiveSize = archiveSize;
            this.random = new Random(seed);
        }

        /**
         * Constructor de conveniencia: crea su propia cache de morfologia para
         * un unico par de imagenes. Preserva el comportamiento y la firma que
         * ya usaban el plugin interactivo y las pruebas existentes.
         *
         * @param vis imagen visible en escala de grises
         * @param ir imagen infrarroja en escala de grises
         * @param baseMode seleccion de la imagen base
         * @param rMin radio minimo permitido
         * @param rMax radio maximo permitido
         * @param mMin peso minimo permitido
         * @param mMax peso maximo permitido
         * @param swarmSize cantidad de particulas
         * @param iterations cantidad de iteraciones
         * @param archiveSize capacidad maxima del archivo Pareto
         * @param seed semilla usada para reproducir la ejecucion
         */
        Optimizer(FloatProcessor vis, FloatProcessor ir, int baseMode, int rMin, int rMax, double mMin, double mMax,
                  int swarmSize, int iterations, int archiveSize, long seed) {
            this(new FusionCache(vis, ir, baseMode, rMin, rMax, mMin, mMax),
                    swarmSize, iterations, archiveSize, seed);
        }

        /**
         * Ejecuta el ciclo completo de optimizacion multiobjetivo.
         *
         * @return copia del archivo final de soluciones no dominadas
         */
        List<Solution> run() {
            Particle[] swarm = new Particle[swarmSize];

            // La inicializacion de referencia usa posiciones aleatorias y velocidad cero.
            for (int i = 0; i < swarmSize; i++) {
                double r = rMin + random.nextDouble() * (rMax - rMin);
                double m = mMin + random.nextDouble() * (mMax - mMin);
                Solution s = evaluate(r, m);
                swarm[i] = new Particle(r, m, 0.0, 0.0, s);
                addToArchive(s);
            }

            /*
             * Actualizacion sincrona de referencia: todo el enjambre usa el mismo
             * archivo de lideres; despues se mueve, muta y evalua por completo.
             * Solo entonces se actualizan el archivo externo y los pbest.
             */
            for (int it = 0; it < iterations; it++) {
                computeCrowding(archive);

                for (Particle p : swarm) {
                    Solution leader = selectLeader();
                    updateVelocity(p, leader);
                }

                for (Particle p : swarm) updatePosition(p);

                // SMPSO aplica mutacion polinomial a los indices 0, 6, 12, ...
                for (int i = 0; i < swarm.length; i++) {
                    if (shouldMutateParticle(i)) polynomialMutation(swarm[i]);
                }

                Solution[] current = new Solution[swarmSize];
                for (int i = 0; i < swarmSize; i++) {
                    current[i] = evaluate(swarm[i].rPosition, swarm[i].mPosition);
                }

                // El archivo se actualiza con el enjambre completo ya evaluado.
                for (Solution solution : current) addToArchive(solution);

                for (int i = 0; i < swarmSize; i++) {
                    // Referencia: reemplaza pbest salvo que pbest domine a la actual.
                    if (!dominates(swarm[i].best, current[i])) swarm[i].best = current[i];
                }

                IJ.showProgress(it + 1, iterations);
            }

            // Limpieza final antes de devolver el frente aproximado.
            removeDuplicateParameters();
            computeCrowding(archive);
            return new ArrayList<Solution>(archive);
        }

        /**
         * Genera una imagen fusionada para una combinacion concreta de parametros,
         * reutilizando la morfologia precalculada en la cache.
         *
         * @param r radio del elemento estructurante
         * @param m peso aplicado a los detalles Top-Hat
         * @return nueva imagen Ifus en el rango [0, 255]
         */
        FloatProcessor fuse(int r, double m) {
            return cache.fuse(r, m);
        }

        /**
         * Evalua una posicion del enjambre delegando en la cache compartida y
         * llevando la cuenta de evaluaciones de esta corrida en particular.
         *
         * @param rPosition posicion continua del radio; se redondea a entero
         * @param mPosition posicion continua del peso de contraste
         * @return solucion con parametros factibles, entropia y SSIM promedio
         */
        private Solution evaluate(double rPosition, double mPosition) {
            Solution solution = cache.evaluate(rPosition, mPosition);
            evaluations++;
            return solution;
        }

        /**
         * Actualiza la velocidad en las dimensiones r y m.
         *
         * @param p particula que sera modificada
         * @param leader solucion del archivo que orienta el movimiento social
         */
        void updateVelocity(Particle p, Solution leader) {
            // PT-CLAHE: c1,c2 en [1.5,2.5] y r1,r2 uniformes en [0,1].
            double c1 = randomInRange(SMPSO_C1_MIN, SMPSO_C1_MAX);
            double c2 = randomInRange(SMPSO_C2_MIN, SMPSO_C2_MAX);
            double r1 = random.nextDouble();
            double r2 = random.nextDouble();
            double constriction = constrictionCoefficient(c1 + c2);

            // Se usan los mismos r1 y r2 para ambas dimensiones de la particula.
            p.rVelocity = constriction * (SMPSO_INERTIA_WEIGHT * p.rVelocity
                    + c1 * r1 * (p.best.r - p.rPosition)
                    + c2 * r2 * (leader.r - p.rPosition));
            p.mVelocity = constriction * (SMPSO_INERTIA_WEIGHT * p.mVelocity
                    + c1 * r1 * (p.best.m - p.mPosition)
                    + c2 * r2 * (leader.m - p.mPosition));

            // Restriccion de velocidad caracteristica de SMPSO.
            p.rVelocity = clamp(p.rVelocity, -velocityLimitR(), velocityLimitR());
            p.mVelocity = clamp(p.mVelocity, -velocityLimitM(), velocityLimitM());
        }

        /**
         * Calcula la magnitud positiva del factor de constriccion de SMPSO.
         *
         * @param phi suma de los coeficientes cognitivo y social, c1 + c2
         * @return 1 si phi <= 4; en otro caso, coeficiente positivo menor que 1
         */
        static double constrictionCoefficient(double phi) {
            if (phi <= 4.0) return 1.0;
            double denominator = Math.abs(2.0 - phi - Math.sqrt(phi * phi - 4.0 * phi));
            return 2.0 / denominator;
        }

        /**
         * Actualiza la posicion y refleja la velocidad si se alcanza un limite.
         *
         * @param p particula cuya posicion y velocidad seran modificadas
         */
        void updatePosition(Particle p) {
            p.rPosition += p.rVelocity;
            if (p.rPosition < rMin) {
                p.rPosition = rMin;
                p.rVelocity *= SMPSO_BOUNDARY_VELOCITY_CHANGE;
            } else if (p.rPosition > rMax) {
                p.rPosition = rMax;
                p.rVelocity *= SMPSO_BOUNDARY_VELOCITY_CHANGE;
            }

            p.mPosition += p.mVelocity;
            if (p.mPosition < mMin) {
                p.mPosition = mMin;
                p.mVelocity *= SMPSO_BOUNDARY_VELOCITY_CHANGE;
            } else if (p.mPosition > mMax) {
                p.mPosition = mMax;
                p.mVelocity *= SMPSO_BOUNDARY_VELOCITY_CHANGE;
            }
        }

        /**
         * Indica si una particula recibe la mutacion de referencia.
         *
         * @param particleIndex indice de la particula, comenzando en cero
         * @return {@code true} para los indices 0, 6, 12, ...
         */
        static boolean shouldMutateParticle(int particleIndex) {
            return particleIndex % SMPSO_MUTATION_FREQUENCY == 0;
        }

        /**
         * Aplica mutacion polinomial de referencia a las dos variables.
         *
         * <p>Cada variable se muta con probabilidad 1/n=0.5. El indice de
         * distribucion 20 favorece cambios pequenos y permite saltos ocasionales.</p>
         *
         * @param p particula seleccionada por la frecuencia de mutacion
         */
        void polynomialMutation(Particle p) {
            if (random.nextDouble() <= SMPSO_MUTATION_PROBABILITY) {
                p.rPosition = polynomialMutatedValue(p.rPosition, rMin, rMax,
                        SMPSO_MUTATION_DISTRIBUTION_INDEX, random.nextDouble());
            }
            if (random.nextDouble() <= SMPSO_MUTATION_PROBABILITY) {
                p.mPosition = polynomialMutatedValue(p.mPosition, mMin, mMax,
                        SMPSO_MUTATION_DISTRIBUTION_INDEX, random.nextDouble());
            }
        }

        /**
         * Calcula una coordenada mutada mediante el operador polinomial.
         *
         * @param value valor actual dentro de los limites
         * @param lowerBound limite inferior
         * @param upperBound limite superior
         * @param distributionIndex indice eta que controla la magnitud del cambio
         * @param randomValue numero uniforme en [0,1]
         * @return valor mutado y reparado dentro de los limites
         */
        static double polynomialMutatedValue(double value, double lowerBound, double upperBound,
                                             double distributionIndex, double randomValue) {
            if (upperBound <= lowerBound) return lowerBound;

            double boundedValue = clamp(value, lowerBound, upperBound);
            double delta1 = (boundedValue - lowerBound) / (upperBound - lowerBound);
            double delta2 = (upperBound - boundedValue) / (upperBound - lowerBound);
            double mutationPower = 1.0 / (distributionIndex + 1.0);
            double delta;

            if (randomValue <= 0.5) {
                double xy = 1.0 - delta1;
                double valueToPower = 2.0 * randomValue
                        + (1.0 - 2.0 * randomValue) * Math.pow(xy, distributionIndex + 1.0);
                delta = Math.pow(valueToPower, mutationPower) - 1.0;
            } else {
                double xy = 1.0 - delta2;
                double valueToPower = 2.0 * (1.0 - randomValue)
                        + 2.0 * (randomValue - 0.5) * Math.pow(xy, distributionIndex + 1.0);
                delta = 1.0 - Math.pow(valueToPower, mutationPower);
            }

            return clamp(boundedValue + delta * (upperBound - lowerBound), lowerBound, upperBound);
        }

        /**
         * Selecciona un lider mediante torneo binario por crowding.
         *
         * @return solucion con mayor crowding; si empatan, una de ellas al azar
         * @throws IllegalStateException si aun no existe ninguna solucion en el archivo
         */
        Solution selectLeader() {
            if (archive.isEmpty()) throw new IllegalStateException("Archivo Pareto vacio");
            Solution first = archive.get(random.nextInt(archive.size()));
            Solution second = archive.get(random.nextInt(archive.size()));
            return selectByCrowding(first, second, random);
        }

        /**
         * Resuelve un torneo de crowding sin introducir sesgo por orden.
         *
         * @param first primera candidata
         * @param second segunda candidata
         * @param randomGenerator generador usado solamente si existe empate
         * @return candidata con mayor crowding o una seleccion aleatoria al empatar
         */
        static Solution selectByCrowding(Solution first, Solution second, Random randomGenerator) {
            int comparison = Double.compare(first.crowding, second.crowding);
            if (comparison > 0) return first;
            if (comparison < 0) return second;
            return randomGenerator.nextBoolean() ? first : second;
        }

        /**
         * Intenta incorporar una solucion al archivo externo.
         *
         * <p>Descarta candidatos dominados o equivalentes, elimina soluciones
         * dominadas por el candidato y, si se supera la capacidad, elimina la
         * solucion ubicada en la region mas poblada.</p>
         *
         * @param candidate solucion evaluada que se desea almacenar
         */
        private void addToArchive(Solution candidate) {
            // Un candidato dominado o con los mismos objetivos no aporta al frente.
            for (Solution s : archive) {
                if (dominates(s, candidate) || sameObjectives(s, candidate)) return;
            }

            // Recorre de atras hacia adelante para eliminar sin saltar indices.
            for (int i = archive.size() - 1; i >= 0; i--) {
                if (dominates(candidate, archive.get(i))) archive.remove(i);
            }
            archive.add(candidate);

            // El archivo conserva como maximo archiveSize soluciones diversas.
            if (archive.size() > archiveSize) {
                computeCrowding(archive);
                int removeIndex = 0;
                double minCrowding = Double.POSITIVE_INFINITY;
                for (int i = 0; i < archive.size(); i++) {
                    if (archive.get(i).crowding < minCrowding) {
                        minCrowding = archive.get(i).crowding;
                        removeIndex = i;
                    }
                }
                archive.remove(removeIndex);
            }
        }

        /**
         * Elimina combinaciones repetidas de parametros del archivo final.
         *
         * <p>La clave utiliza r entero y m redondeado a seis decimales.
         * No devuelve valor: modifica {@code archive} directamente.</p>
         */
        private void removeDuplicateParameters() {
            Set<String> seen = new HashSet<String>();
            for (int i = archive.size() - 1; i >= 0; i--) {
                Solution s = archive.get(i);
                String key = s.r + ":" + String.format(java.util.Locale.US, "%.6f", s.m);
                if (seen.contains(key)) archive.remove(i);
                else seen.add(key);
            }
        }

        /**
         * @return magnitud maxima permitida para la velocidad del radio
         */
        private double velocityLimitR() {
            return 0.5 * (rMax - rMin);
        }

        /**
         * @return magnitud maxima permitida para la velocidad del peso
         */
        private double velocityLimitM() {
            return 0.5 * (mMax - mMin);
        }

        /**
         * @return cantidad de evaluaciones realizadas durante la ejecucion
         */
        int getEvaluations() {
            return evaluations;
        }

        /**
         * Genera un numero uniforme dentro de un intervalo semiabierto.
         */
        private double randomInRange(double minimum, double maximum) {
            return minimum + random.nextDouble() * (maximum - minimum);
        }
    }

    /**
     * Estado movil de una particula del enjambre.
     *
     * <p>La posicion representa una combinacion candidata (r, m), la velocidad
     * indica el desplazamiento siguiente y {@code best} guarda su memoria
     * personal.</p>
     */
    static class Particle {
        /** Posiciones actuales; r se redondea solamente durante la evaluacion. */
        double rPosition;
        double mPosition;

        /** Desplazamientos actuales en cada dimension del problema. */
        double rVelocity;
        double mVelocity;

        /** Mejor solucion personal conocida por la particula. */
        Solution best;

        /**
         * Inicializa una particula.
         *
         * @param rPosition posicion inicial del radio
         * @param mPosition posicion inicial del peso
         * @param rVelocity velocidad inicial del radio
         * @param mVelocity velocidad inicial del peso
         * @param best evaluacion inicial, usada como mejor memoria personal
         */
        Particle(double rPosition, double mPosition, double rVelocity, double mVelocity, Solution best) {
            this.rPosition = rPosition;
            this.mPosition = mPosition;
            this.rVelocity = rVelocity;
            this.mVelocity = mVelocity;
            this.best = best;
        }
    }

    /**
     * Evaluacion inmutable de una combinacion (r, m).
     *
     * <p>Entropia y SSIM son los objetivos que se maximizan. La distancia de
     * crowding es mutable porque se recalcula segun el frente actual.</p>
     */
    static class Solution {
        final int r;
        final double m;
        final double entropy;
        final double ssim;
        double crowding;

        /**
         * Crea una solucion ya evaluada.
         *
         * @param r radio entero usado en la morfologia
         * @param m peso de contraste usado en la fusion
         * @param entropy entropia de la imagen fusionada
         * @param ssim promedio del SSIM con VIS e IR
         */
        Solution(int r, double m, double entropy, double ssim) {
            this.r = r;
            this.m = m;
            this.entropy = entropy;
            this.ssim = ssim;
        }
    }

    /**
     * Comprueba dominancia de Pareto para dos objetivos de maximizacion.
     *
     * @param a primera solucion
     * @param b segunda solucion
     * @return {@code true} si a no es peor en ningun objetivo y es mejor en al menos uno
     */
    static boolean dominates(Solution a, Solution b) {
        return (a.entropy >= b.entropy && a.ssim >= b.ssim)
                && (a.entropy > b.entropy || a.ssim > b.ssim);
    }

    /**
     * Detecta soluciones con objetivos numericamente equivalentes.
     *
     * @param a primera solucion
     * @param b segunda solucion
     * @return {@code true} si entropia y SSIM difieren menos de 1e-12
     */
    static boolean sameObjectives(Solution a, Solution b) {
        return Math.abs(a.entropy - b.entropy) < 1e-12 && Math.abs(a.ssim - b.ssim) < 1e-12;
    }

    /**
     * Recalcula la distancia de crowding de todo un frente.
     *
     * <p>Entrada y salida usan la misma lista: el metodo actualiza el campo
     * {@code crowding} de cada solucion. Los extremos reciben infinito para
     * proteger los valores maximos/minimos de cada objetivo.</p>
     *
     * @param front lista de soluciones no dominadas
     */
    static void computeCrowding(List<Solution> front) {
        for (Solution s : front) s.crowding = 0.0;
        if (front.size() <= 2) {
            for (Solution s : front) s.crowding = Double.POSITIVE_INFINITY;
            return;
        }
        crowdingByObjective(front, true);
        crowdingByObjective(front, false);
    }

    /**
     * Suma a la distancia de crowding la separacion normalizada de un objetivo.
     *
     * @param front frente que se ordena y actualiza internamente
     * @param entropyObjective {@code true} para entropia; {@code false} para SSIM
     */
    static void crowdingByObjective(List<Solution> front, final boolean entropyObjective) {
        // Ordenar permite identificar los vecinos anterior y siguiente.
        Collections.sort(front, new Comparator<Solution>() {
            @Override
            public int compare(Solution a, Solution b) {
                double av = entropyObjective ? a.entropy : a.ssim;
                double bv = entropyObjective ? b.entropy : b.ssim;
                return Double.compare(av, bv);
            }
        });

        // Los extremos representan compromisos unicos y se conservan.
        front.get(0).crowding = Double.POSITIVE_INFINITY;
        front.get(front.size() - 1).crowding = Double.POSITIVE_INFINITY;
        double min = entropyObjective ? front.get(0).entropy : front.get(0).ssim;
        double max = entropyObjective ? front.get(front.size() - 1).entropy : front.get(front.size() - 1).ssim;
        double range = max - min;
        if (range <= 0.0) return;
        for (int i = 1; i < front.size() - 1; i++) {
            double previous = entropyObjective ? front.get(i - 1).entropy : front.get(i - 1).ssim;
            double next = entropyObjective ? front.get(i + 1).entropy : front.get(i + 1).ssim;
            // Distancia entre vecinos dividida por el rango total del objetivo.
            front.get(i).crowding += (next - previous) / range;
        }
    }

    /**
     * Calcula Top-Hat blanco: WTH = imagen - apertura(imagen).
     *
     * @param input imagen de entrada
     * @param radius radio del elemento estructurante
     * @return imagen nueva con los detalles brillantes extraidos
     */
    static FloatProcessor whiteTopHat(FloatProcessor input, int radius) {
        FloatProcessor opened = opening(input, radius);
        FloatProcessor out = new FloatProcessor(input.getWidth(), input.getHeight());
        float[] inPix = (float[]) input.getPixels();
        float[] opPix = (float[]) opened.getPixels();
        float[] outPix = (float[]) out.getPixels();
        for (int i = 0; i < outPix.length; i++) outPix[i] = Math.max(0.0f, inPix[i] - opPix[i]);
        return out;
    }

    /**
     * Calcula Top-Hat negro: BTH = cierre(imagen) - imagen.
     *
     * @param input imagen de entrada
     * @param radius radio del elemento estructurante
     * @return imagen nueva con los detalles oscuros extraidos
     */
    static FloatProcessor blackTopHat(FloatProcessor input, int radius) {
        FloatProcessor closed = closing(input, radius);
        FloatProcessor out = new FloatProcessor(input.getWidth(), input.getHeight());
        float[] inPix = (float[]) input.getPixels();
        float[] clPix = (float[]) closed.getPixels();
        float[] outPix = (float[]) out.getPixels();
        for (int i = 0; i < outPix.length; i++) outPix[i] = Math.max(0.0f, clPix[i] - inPix[i]);
        return out;
    }

    /**
     * Calcula la apertura morfologica: erosion seguida de dilatacion.
     *
     * @param input imagen que no sera modificada
     * @param radius radio de la vecindad usada por RankFilters
     * @return copia procesada mediante minimo y luego maximo
     */
    static FloatProcessor opening(FloatProcessor input, int radius) {
        RankFilters rf = new RankFilters();
        FloatProcessor out = (FloatProcessor) input.duplicate();
        // MIN equivale a erosion en escala de grises; MAX, a dilatacion.
        rf.rank(out, radius, RankFilters.MIN);
        rf.rank(out, radius, RankFilters.MAX);
        return out;
    }

    /**
     * Calcula el cierre morfologico: dilatacion seguida de erosion.
     *
     * @param input imagen que no sera modificada
     * @param radius radio de la vecindad usada por RankFilters
     * @return copia procesada mediante maximo y luego minimo
     */
    static FloatProcessor closing(FloatProcessor input, int radius) {
        RankFilters rf = new RankFilters();
        FloatProcessor out = (FloatProcessor) input.duplicate();
        // MAX equivale a dilatacion en escala de grises; MIN, a erosion.
        rf.rank(out, radius, RankFilters.MAX);
        rf.rank(out, radius, RankFilters.MIN);
        return out;
    }

    /**
     * Calcula la entropia de Shannon de una imagen de 8 bits.
     *
     * @param fp imagen evaluada con intensidades esperadas en [0, 255]
     * @return entropia en bits; su maximo teorico es 8 para 256 niveles
     */
    static double entropy(FloatProcessor fp) {
        // Cada posicion almacena la frecuencia de un nivel de gris.
        int[] hist = new int[256];
        float[] pixels = (float[]) fp.getPixels();
        for (float pixel : pixels) {
            int v = (int) Math.round(clamp(pixel, 0.0, 255.0));
            hist[v]++;
        }
        double n = pixels.length;
        double h = 0.0;
        for (int count : hist) {
            if (count == 0) continue;
            double p = count / n;
            // H = -sum(p * log2(p)).
            h -= p * (Math.log(p) / Math.log(2.0));
        }
        return h;
    }

    /**
     * Calcula SSIM global entre dos imagenes del mismo tamano.
     *
     * <p>Usa una sola media, varianza y covarianza para todos los pixeles.
     * Entrada: dos imagenes registradas. Salida: un escalar de similitud,
     * normalmente en [-1, 1], donde 1 indica identidad.</p>
     *
     * @param a primera imagen
     * @param b segunda imagen
     * @return indice de similitud estructural global
     */
    static double ssim(FloatProcessor a, FloatProcessor b) {
        float[] ap = (float[]) a.getPixels();
        float[] bp = (float[]) b.getPixels();
        int n = ap.length;

        // Primera pasada: medias globales de luminancia.
        double meanA = 0.0;
        double meanB = 0.0;
        for (int i = 0; i < n; i++) {
            meanA += ap[i];
            meanB += bp[i];
        }
        meanA /= n;
        meanB /= n;

        // Segunda pasada: varianzas y covarianza muestrales.
        double varA = 0.0;
        double varB = 0.0;
        double cov = 0.0;
        for (int i = 0; i < n; i++) {
            double da = ap[i] - meanA;
            double db = bp[i] - meanB;
            varA += da * da;
            varB += db * db;
            cov += da * db;
        }
        int denom = Math.max(1, n - 1);
        varA /= denom;
        varB /= denom;
        cov /= denom;

        // Forma simplificada de SSIM con sus constantes de estabilidad numerica.
        return ((2.0 * meanA * meanB + SSIM_C1) * (2.0 * cov + SSIM_C2))
                / ((meanA * meanA + meanB * meanB + SSIM_C1) * (varA + varB + SSIM_C2));
    }

    /**
     * Restringe un valor al intervalo cerrado [min, max].
     *
     * @param value valor original
     * @param min limite inferior
     * @param max limite superior
     * @return {@code min} si value es menor, {@code max} si es mayor o value si esta dentro
     */
    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
