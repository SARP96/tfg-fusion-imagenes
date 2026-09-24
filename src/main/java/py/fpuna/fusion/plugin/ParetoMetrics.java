package py.fpuna.fusion.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Indicadores posteriores a la optimizacion para frentes biobjetivo. */
final class ParetoMetrics {
    static final double REFERENCE_POINT = 1.1;
    private static final double EPSILON = 1e-12;

    private ParetoMetrics() {
    }

    /**
     * Obtiene ideal/nadir experimentales y P* desde todos los frentes de un par.
     * Los objetivos originales se maximizan; las coordenadas normalizadas se
     * transforman a minimizacion para HV e IGD.
     */
    static Analysis analyze(List<List<SMPSO_TopHat_Fusion.Solution>> fronts) {
        List<SMPSO_TopHat_Fusion.Solution> union = flatten(fronts);
        if (union.isEmpty()) throw new IllegalArgumentException("No hay soluciones para analizar");

        double idealEntropy = Double.NEGATIVE_INFINITY;
        double nadirEntropy = Double.POSITIVE_INFINITY;
        double idealSsim = Double.NEGATIVE_INFINITY;
        double nadirSsim = Double.POSITIVE_INFINITY;
        for (SMPSO_TopHat_Fusion.Solution solution : union) {
            requireFinite(solution);
            idealEntropy = Math.max(idealEntropy, solution.entropy);
            nadirEntropy = Math.min(nadirEntropy, solution.entropy);
            idealSsim = Math.max(idealSsim, solution.ssim);
            nadirSsim = Math.min(nadirSsim, solution.ssim);
        }

        return new Analysis(idealEntropy, nadirEntropy, idealSsim, nadirSsim,
                nondominated(union), union.size());
    }

    /** Devuelve la union no dominada y sin objetivos duplicados. */
    static List<SMPSO_TopHat_Fusion.Solution> consolidate(
            List<List<SMPSO_TopHat_Fusion.Solution>> fronts) {
        return nondominated(flatten(fronts));
    }

    /** HV exacto en dos dimensiones, con referencia fija (1.1,1.1). */
    static double hypervolume(List<SMPSO_TopHat_Fusion.Solution> front, Analysis analysis) {
        List<NormalizedPoint> points = normalizedNondominated(front, analysis);
        Collections.sort(points, Comparator
                .comparingDouble((NormalizedPoint p) -> p.entropy)
                .thenComparingDouble(p -> p.ssim));

        double hv = 0.0;
        double currentY = REFERENCE_POINT;
        for (NormalizedPoint point : points) {
            if (point.entropy >= REFERENCE_POINT || point.ssim >= REFERENCE_POINT) continue;
            if (point.ssim < currentY) {
                hv += (REFERENCE_POINT - point.entropy) * (currentY - point.ssim);
                currentY = point.ssim;
            }
        }
        return hv;
    }

    /** IGD convencional: promedio de distancias desde P* al frente evaluado. */
    static double igd(List<SMPSO_TopHat_Fusion.Solution> front, Analysis analysis) {
        if (front.isEmpty()) return Double.POSITIVE_INFINITY;
        List<NormalizedPoint> candidate = normalize(front, analysis);
        List<NormalizedPoint> reference = normalize(analysis.referenceFront, analysis);
        double sum = 0.0;
        for (NormalizedPoint target : reference) {
            double bestSquared = Double.POSITIVE_INFINITY;
            for (NormalizedPoint point : candidate) {
                double dEntropy = target.entropy - point.entropy;
                double dSsim = target.ssim - point.ssim;
                bestSquared = Math.min(bestSquared, dEntropy * dEntropy + dSsim * dSsim);
            }
            sum += Math.sqrt(bestSquared);
        }
        return sum / reference.size();
    }

    static NormalizedPoint normalize(SMPSO_TopHat_Fusion.Solution solution, Analysis analysis) {
        return new NormalizedPoint(
                normalizeForMinimization(solution.entropy, analysis.idealEntropy, analysis.nadirEntropy),
                normalizeForMinimization(solution.ssim, analysis.idealSsim, analysis.nadirSsim));
    }

    private static double normalizeForMinimization(double value, double ideal, double nadir) {
        double range = ideal - nadir;
        if (range <= EPSILON) return 0.0;
        return SMPSO_TopHat_Fusion.clamp((ideal - value) / range, 0.0, 1.0);
    }

    private static List<NormalizedPoint> normalize(
            List<SMPSO_TopHat_Fusion.Solution> front, Analysis analysis) {
        List<NormalizedPoint> result = new ArrayList<NormalizedPoint>(front.size());
        for (SMPSO_TopHat_Fusion.Solution solution : front) result.add(normalize(solution, analysis));
        return result;
    }

    private static List<NormalizedPoint> normalizedNondominated(
            List<SMPSO_TopHat_Fusion.Solution> front, Analysis analysis) {
        return normalize(nondominated(new ArrayList<SMPSO_TopHat_Fusion.Solution>(front)), analysis);
    }

    private static List<SMPSO_TopHat_Fusion.Solution> flatten(
            List<List<SMPSO_TopHat_Fusion.Solution>> fronts) {
        List<SMPSO_TopHat_Fusion.Solution> union = new ArrayList<SMPSO_TopHat_Fusion.Solution>();
        for (List<SMPSO_TopHat_Fusion.Solution> front : fronts) union.addAll(front);
        return union;
    }

    /** Algoritmo O(n log n) para maximizar dos objetivos. */
    private static List<SMPSO_TopHat_Fusion.Solution> nondominated(
            List<SMPSO_TopHat_Fusion.Solution> solutions) {
        Collections.sort(solutions, Comparator
                .comparingDouble((SMPSO_TopHat_Fusion.Solution s) -> s.entropy).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (SMPSO_TopHat_Fusion.Solution s) -> s.ssim).reversed())
                .thenComparingInt(s -> s.r)
                .thenComparingDouble(s -> s.mWth)
                .thenComparingDouble(s -> s.mBth));

        List<SMPSO_TopHat_Fusion.Solution> result = new ArrayList<SMPSO_TopHat_Fusion.Solution>();
        double bestSsim = Double.NEGATIVE_INFINITY;
        for (SMPSO_TopHat_Fusion.Solution solution : solutions) {
            requireFinite(solution);
            if (solution.ssim > bestSsim + EPSILON) {
                result.add(solution);
                bestSsim = solution.ssim;
            }
        }
        return result;
    }

    private static void requireFinite(SMPSO_TopHat_Fusion.Solution solution) {
        if (!Double.isFinite(solution.entropy) || !Double.isFinite(solution.ssim)) {
            throw new IllegalArgumentException("El frente contiene objetivos no finitos");
        }
    }

    static final class Analysis {
        final double idealEntropy;
        final double nadirEntropy;
        final double idealSsim;
        final double nadirSsim;
        final List<SMPSO_TopHat_Fusion.Solution> referenceFront;
        final int unionSize;

        Analysis(double idealEntropy, double nadirEntropy, double idealSsim, double nadirSsim,
                 List<SMPSO_TopHat_Fusion.Solution> referenceFront, int unionSize) {
            this.idealEntropy = idealEntropy;
            this.nadirEntropy = nadirEntropy;
            this.idealSsim = idealSsim;
            this.nadirSsim = nadirSsim;
            this.referenceFront = referenceFront;
            this.unionSize = unionSize;
        }
    }

    static final class NormalizedPoint {
        final double entropy;
        final double ssim;

        NormalizedPoint(double entropy, double ssim) {
            this.entropy = entropy;
            this.ssim = ssim;
        }
    }
}
