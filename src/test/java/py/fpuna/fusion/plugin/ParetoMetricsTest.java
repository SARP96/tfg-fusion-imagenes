package py.fpuna.fusion.plugin;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParetoMetricsTest {

    @Test
    void analysisBuildsEmpiricalBoundsAndReferenceFront() {
        SMPSO_TopHat_Fusion.Solution highEntropy = solution(1.0, 0.0);
        SMPSO_TopHat_Fusion.Solution balanced = solution(0.5, 0.5);
        SMPSO_TopHat_Fusion.Solution highSsim = solution(0.0, 1.0);
        SMPSO_TopHat_Fusion.Solution dominated = solution(0.2, 0.2);

        ParetoMetrics.Analysis analysis = ParetoMetrics.analyze(Arrays.asList(
                Arrays.asList(highEntropy, balanced),
                Arrays.asList(highSsim, dominated)));

        assertEquals(1.0, analysis.idealEntropy, 1e-12);
        assertEquals(0.0, analysis.nadirEntropy, 1e-12);
        assertEquals(1.0, analysis.idealSsim, 1e-12);
        assertEquals(0.0, analysis.nadirSsim, 1e-12);
        assertEquals(4, analysis.unionSize);
        assertEquals(3, analysis.referenceFront.size());
    }

    @Test
    void normalizationChangesMaximizationIntoMinimization() {
        SMPSO_TopHat_Fusion.Solution ideal = solution(0.9, 0.8);
        SMPSO_TopHat_Fusion.Solution nadir = solution(0.5, 0.4);
        ParetoMetrics.Analysis analysis = ParetoMetrics.analyze(
                Collections.singletonList(Arrays.asList(ideal, nadir)));

        ParetoMetrics.NormalizedPoint idealPoint = ParetoMetrics.normalize(ideal, analysis);
        ParetoMetrics.NormalizedPoint nadirPoint = ParetoMetrics.normalize(nadir, analysis);

        assertEquals(0.0, idealPoint.entropy, 1e-12);
        assertEquals(0.0, idealPoint.ssim, 1e-12);
        assertEquals(1.0, nadirPoint.entropy, 1e-12);
        assertEquals(1.0, nadirPoint.ssim, 1e-12);
    }

    @Test
    void twoDimensionalHypervolumeUsesFixedSafetyMargin() {
        List<SMPSO_TopHat_Fusion.Solution> reference = Arrays.asList(
                solution(1.0, 0.0),
                solution(0.5, 0.5),
                solution(0.0, 1.0));
        ParetoMetrics.Analysis analysis = ParetoMetrics.analyze(
                Collections.singletonList(reference));

        assertEquals(0.46, ParetoMetrics.hypervolume(reference, analysis), 1e-12);
    }

    @Test
    void referenceFrontHasZeroIgdAndIncompleteFrontHasPositiveIgd() {
        List<SMPSO_TopHat_Fusion.Solution> reference = Arrays.asList(
                solution(1.0, 0.0),
                solution(0.5, 0.5),
                solution(0.0, 1.0));
        ParetoMetrics.Analysis analysis = ParetoMetrics.analyze(
                Collections.singletonList(reference));

        assertEquals(0.0, ParetoMetrics.igd(reference, analysis), 1e-12);
        assertTrue(ParetoMetrics.igd(Collections.singletonList(reference.get(1)), analysis) > 0.0);
    }

    @Test
    void degenerateObjectiveRangeIsNormalizedToZero() {
        SMPSO_TopHat_Fusion.Solution first = solution(0.7, 0.4);
        SMPSO_TopHat_Fusion.Solution second = solution(0.7, 0.8);
        ParetoMetrics.Analysis analysis = ParetoMetrics.analyze(
                Collections.singletonList(Arrays.asList(first, second)));

        assertEquals(0.0, ParetoMetrics.normalize(first, analysis).entropy, 1e-12);
        assertEquals(0.0, ParetoMetrics.normalize(second, analysis).entropy, 1e-12);
    }

    private static SMPSO_TopHat_Fusion.Solution solution(double entropy, double ssim) {
        return new SMPSO_TopHat_Fusion.Solution(1, 1.0, 1.0, entropy, ssim);
    }
}
