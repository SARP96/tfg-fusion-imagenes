package py.fpuna.fusion.plugin;

import ij.process.FloatProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SMPSOTopHatFusionTest {

    @Test
    void constantImageHasZeroEntropy() {
        FloatProcessor image = image(4, 4, 42.0f);
        assertEquals(0.0, SMPSO_TopHat_Fusion.entropy(image), 1e-12);
    }

    @Test
    void balancedBinaryImageHasOneBitOfEntropy() {
        FloatProcessor image = new FloatProcessor(2, 2, new float[]{0, 0, 255, 255});
        assertEquals(1.0, SMPSO_TopHat_Fusion.entropy(image), 1e-12);
    }

    @Test
    void identicalImagesHavePerfectGlobalSsim() {
        FloatProcessor image = new FloatProcessor(2, 2, new float[]{0, 64, 128, 255});
        assertEquals(1.0, SMPSO_TopHat_Fusion.ssim(image, image), 1e-12);
    }

    @Test
    void dominanceRequiresNoWorseAndAtLeastOneBetterObjective() {
        SMPSO_TopHat_Fusion.Solution better = solution(7.1, 0.91);
        SMPSO_TopHat_Fusion.Solution worse = solution(6.9, 0.89);
        SMPSO_TopHat_Fusion.Solution tradeoff = solution(7.2, 0.88);

        assertTrue(SMPSO_TopHat_Fusion.dominates(better, worse));
        assertFalse(SMPSO_TopHat_Fusion.dominates(better, tradeoff));
    }

    @Test
    void crowdingProtectsExtremeSolutions() {
        List<SMPSO_TopHat_Fusion.Solution> front = new ArrayList<SMPSO_TopHat_Fusion.Solution>();
        front.add(solution(6.8, 0.95));
        front.add(solution(7.0, 0.92));
        front.add(solution(7.2, 0.89));

        SMPSO_TopHat_Fusion.computeCrowding(front);

        long extremes = front.stream().filter(s -> Double.isInfinite(s.crowding)).count();
        assertEquals(2L, extremes);
    }

    private static FloatProcessor image(int width, int height, float value) {
        float[] pixels = new float[width * height];
        java.util.Arrays.fill(pixels, value);
        return new FloatProcessor(width, height, pixels);
    }

    private static SMPSO_TopHat_Fusion.Solution solution(double entropy, double ssim) {
        return new SMPSO_TopHat_Fusion.Solution(1, 1.0, entropy, ssim);
    }
}
