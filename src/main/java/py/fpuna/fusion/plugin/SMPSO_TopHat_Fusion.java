package py.fpuna.fusion.plugin;

import ij.IJ;
import ij.ImagePlus;
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
    private static final double C1 = (0.01 * L) * (0.01 * L);

    /** Constante de estabilidad para la comparacion de contraste/estructura en SSIM. */
    private static final double C2 = (0.03 * L) * (0.03 * L);

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
    private FloatProcessor toGrayFloat(ImagePlus imp) {
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
     * Nucleo del algoritmo: genera fusiones, evalua objetivos y aproxima el
     * frente de Pareto mediante una variante de SMPSO.
     */
    static class Optimizer {
        /** Imagen visible e infrarroja ya convertidas a escala de grises. */
        private final FloatProcessor vis;
        private final FloatProcessor ir;

        /** Modo de imagen base: 0 = promedio, 1 = VIS, 2 = IR. */
        private final int baseMode;
        private final int width;
        private final int height;

        /** Limites del espacio de decision (r, m). */
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

        /**
         * Crea el optimizador con las imagenes y limites seleccionados.
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
            this.vis = vis;
            this.ir = ir;
            this.baseMode = baseMode;
            this.width = vis.getWidth();
            this.height = vis.getHeight();
            this.rMin = rMin;
            this.rMax = rMax;
            this.mMin = mMin;
            this.mMax = mMax;
            this.swarmSize = swarmSize;
            this.iterations = iterations;
            this.archiveSize = archiveSize;
            this.random = new Random(seed);
        }

        /**
         * Ejecuta el ciclo completo de optimizacion multiobjetivo.
         *
         * @return copia del archivo final de soluciones no dominadas
         */
        List<Solution> run() {
            Particle[] swarm = new Particle[swarmSize];

            // Inicializa cada particula con posicion y velocidad aleatorias.
            for (int i = 0; i < swarmSize; i++) {
                double r = rMin + random.nextDouble() * (rMax - rMin);
                double m = mMin + random.nextDouble() * (mMax - mMin);
                double vr = signedRandom() * velocityLimitR();
                double vm = signedRandom() * velocityLimitM();
                Solution s = evaluate(r, m);
                swarm[i] = new Particle(r, m, vr, vm, s);
                addToArchive(s);
            }

            // En cada iteracion se mueve, muta, evalua y memoriza cada particula.
            for (int it = 0; it < iterations; it++) {
                computeCrowding(archive);
                for (Particle p : swarm) {
                    Solution leader = selectLeader();
                    updateVelocity(p, leader);

                    // x(t+1) = x(t) + v(t+1), limitada al espacio factible.
                    p.rPosition = clamp(p.rPosition + p.rVelocity, rMin, rMax);
                    p.mPosition = clamp(p.mPosition + p.mVelocity, mMin, mMax);
                    mutate(p, it);

                    Solution current = evaluate(p.rPosition, p.mPosition);

                    // Sustituye pbest si la actual domina; si son incomparables,
                    // el desempate aleatorio evita favorecer siempre la misma zona.
                    if (dominates(current, p.best) || (!dominates(p.best, current) && random.nextBoolean())) {
                        p.best = current;
                    }
                    addToArchive(current);
                }
                IJ.showProgress(it + 1, iterations);
            }

            // Limpieza final antes de devolver el frente aproximado.
            removeDuplicateParameters();
            computeCrowding(archive);
            return new ArrayList<Solution>(archive);
        }

        /**
         * Genera una imagen fusionada para una combinacion concreta de parametros.
         *
         * @param r radio del elemento estructurante
         * @param m peso aplicado a los detalles Top-Hat
         * @return nueva imagen Ifus en el rango [0, 255]
         */
        FloatProcessor fuse(int r, double m) {
            // Extrae por separado detalles brillantes (WTH) y oscuros (BTH).
            FloatProcessor wthIr = whiteTopHat(ir, r);
            FloatProcessor wthVis = whiteTopHat(vis, r);
            FloatProcessor bthIr = blackTopHat(ir, r);
            FloatProcessor bthVis = blackTopHat(vis, r);
            FloatProcessor out = new FloatProcessor(width, height);

            float[] irPix = (float[]) ir.getPixels();
            float[] visPix = (float[]) vis.getPixels();
            float[] wIr = (float[]) wthIr.getPixels();
            float[] wVis = (float[]) wthVis.getPixels();
            float[] bIr = (float[]) bthIr.getPixels();
            float[] bVis = (float[]) bthVis.getPixels();
            float[] fused = (float[]) out.getPixels();

            // Aplica la ecuacion de fusion pixel a pixel.
            for (int i = 0; i < fused.length; i++) {
                double base;
                if (baseMode == 1) base = visPix[i];
                else if (baseMode == 2) base = irPix[i];
                else base = 0.5 * (visPix[i] + irPix[i]);

                // Ifus = Ib + m*max(WTH_IR,WTH_VIS) - m*max(BTH_IR,BTH_VIS).
                double value = base + m * Math.max(wIr[i], wVis[i]) - m * Math.max(bIr[i], bVis[i]);
                // Evita intensidades invalidas y saturacion fuera de 8 bits.
                fused[i] = (float) clamp(value, 0.0, 255.0);
            }
            return out;
        }

        /**
         * Evalua una posicion del enjambre.
         *
         * @param rPosition posicion continua del radio; se redondea a entero
         * @param mPosition posicion continua del peso de contraste
         * @return solucion con parametros factibles, entropia y SSIM promedio
         */
        private Solution evaluate(double rPosition, double mPosition) {
            // La particula se mueve en continuo, pero la morfologia requiere r entero.
            int r = (int) Math.round(clamp(rPosition, rMin, rMax));
            double m = clamp(mPosition, mMin, mMax);
            FloatProcessor fused = fuse(r, m);

            // Ambos objetivos se formulan para maximizacion.
            double entropy = entropy(fused);
            double ssim = 0.5 * (ssim(fused, vis) + ssim(fused, ir));
            return new Solution(r, m, entropy, ssim);
        }

        /**
         * Actualiza la velocidad en las dimensiones r y m.
         *
         * @param p particula que sera modificada
         * @param leader solucion del archivo que orienta el movimiento social
         */
        private void updateVelocity(Particle p, Solution leader) {
            // Coeficientes cognitivo/social en [1.5, 2.5) e inercia en [0.1, 0.5).
            double c1 = 1.5 + random.nextDouble();
            double c2 = 1.5 + random.nextDouble();
            double w = 0.1 + 0.4 * random.nextDouble();
            double constriction = constrictionCoefficient(c1 + c2);

            // Inercia + atraccion a pbest + atraccion al lider.
            p.rVelocity = constriction * (w * p.rVelocity
                    + c1 * random.nextDouble() * (p.best.r - p.rPosition)
                    + c2 * random.nextDouble() * (leader.r - p.rPosition));
            p.mVelocity = constriction * (w * p.mVelocity
                    + c1 * random.nextDouble() * (p.best.m - p.mPosition)
                    + c2 * random.nextDouble() * (leader.m - p.mPosition));

            // Restriccion de velocidad caracteristica de SMPSO.
            p.rVelocity = clamp(p.rVelocity, -velocityLimitR(), velocityLimitR());
            p.mVelocity = clamp(p.mVelocity, -velocityLimitM(), velocityLimitM());
        }

        /**
         * Calcula el factor de constriccion publicado para SMPSO.
         *
         * @param phi suma de los coeficientes cognitivo y social, c1 + c2
         * @return 1 si phi <= 4; en otro caso, coeficiente de constriccion
         */
        private double constrictionCoefficient(double phi) {
            if (phi <= 4.0) return 1.0;
            return 2.0 / (2.0 - phi - Math.sqrt(phi * phi - 4.0 * phi));
        }

        /**
         * Introduce una perturbacion decreciente para recuperar diversidad.
         *
         * <p>Se ejecuta cada seis iteraciones. Modifica r o m y su intensidad
         * disminuye a medida que se acerca el final de la busqueda.</p>
         *
         * @param p particula cuya posicion puede ser mutada
         * @param iteration indice de la iteracion actual, comenzando en cero
         */
        private void mutate(Particle p, int iteration) {
            if (iteration % 6 != 0) return;
            double progress = iterations <= 1 ? 1.0 : (double) iteration / (double) (iterations - 1);
            // Potencia 5: exploracion fuerte al inicio y ajuste fino al final.
            double strength = Math.pow(1.0 - progress, 5.0);
            if (random.nextBoolean()) {
                p.rPosition = clamp(p.rPosition + signedRandom() * strength * (rMax - rMin), rMin, rMax);
            } else {
                p.mPosition = clamp(p.mPosition + signedRandom() * strength * (mMax - mMin), mMin, mMax);
            }
        }

        /**
         * Selecciona un lider entre las regiones mas dispersas del frente.
         *
         * @return solucion elegida aleatoriamente de la mitad con mayor crowding
         * @throws IllegalStateException si aun no existe ninguna solucion en el archivo
         */
        private Solution selectLeader() {
            if (archive.isEmpty()) throw new IllegalStateException("Archivo Pareto vacio");
            computeCrowding(archive);
            ArrayList<Solution> sorted = new ArrayList<Solution>(archive);
            Collections.sort(sorted, new Comparator<Solution>() {
                @Override
                public int compare(Solution a, Solution b) {
                    return Double.compare(b.crowding, a.crowding);
                }
            });
            // Favorece diversidad sin elegir siempre el mismo extremo.
            int limit = Math.max(1, sorted.size() / 2);
            return sorted.get(random.nextInt(limit));
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
            return Math.max(1.0, 0.5 * (rMax - rMin));
        }

        /**
         * @return magnitud maxima permitida para la velocidad del peso
         */
        private double velocityLimitM() {
            return Math.max(0.0001, 0.5 * (mMax - mMin));
        }

        /**
         * @return numero pseudoaleatorio uniforme dentro de [-1, 1)
         */
        private double signedRandom() {
            return 2.0 * random.nextDouble() - 1.0;
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

        // Forma simplificada de SSIM con C1 y C2 para estabilidad numerica.
        return ((2.0 * meanA * meanB + C1) * (2.0 * cov + C2))
                / ((meanA * meanA + meanB * meanB + C1) * (varA + varB + C2));
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
