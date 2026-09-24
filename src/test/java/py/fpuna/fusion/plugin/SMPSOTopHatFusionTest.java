package py.fpuna.fusion.plugin;

import ij.process.FloatProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SMPSOTopHatFusionTest {

    @Test
    void constantImageHasZeroEntropy() {
        FloatProcessor image = image(4, 4, 42.0f);
        assertEquals(0.0, SMPSO_TopHat_Fusion.entropy(image), 1e-12);
    }

    @Test
    void balancedBinaryImageHasOneEighthNormalizedEntropy() {
        FloatProcessor image = new FloatProcessor(2, 2, new float[]{0, 0, 255, 255});
        assertEquals(0.125, SMPSO_TopHat_Fusion.entropy(image), 1e-12);
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

    @Test
    void constrictionCoefficientIsNeverNegative() {
        assertEquals(1.0, SMPSO_TopHat_Fusion.Optimizer.constrictionCoefficient(3.5), 1e-12);
        assertEquals(1.0, SMPSO_TopHat_Fusion.Optimizer.constrictionCoefficient(4.0), 1e-12);

        double coefficient = SMPSO_TopHat_Fusion.Optimizer.constrictionCoefficient(4.1);
        assertTrue(coefficient > 0.0);
        assertTrue(coefficient < 1.0);
    }

    @Test
    void referenceMutationUsesParticleIndexFrequency() {
        assertTrue(SMPSO_TopHat_Fusion.Optimizer.shouldMutateParticle(0));
        assertFalse(SMPSO_TopHat_Fusion.Optimizer.shouldMutateParticle(1));
        assertTrue(SMPSO_TopHat_Fusion.Optimizer.shouldMutateParticle(6));
        assertTrue(SMPSO_TopHat_Fusion.Optimizer.shouldMutateParticle(12));
    }

    @Test
    void polynomialMutationMovesInBothDirectionsAndStaysInsideBounds() {
        double lowerMutation = SMPSO_TopHat_Fusion.Optimizer.polynomialMutatedValue(
                0.5, 0.0, 1.0, 20.0, 0.0);
        double upperMutation = SMPSO_TopHat_Fusion.Optimizer.polynomialMutatedValue(
                0.5, 0.0, 1.0, 20.0, 1.0);

        assertTrue(lowerMutation >= 0.0 && lowerMutation < 0.5);
        assertTrue(upperMutation <= 1.0 && upperMutation > 0.5);
        assertEquals(2.0, SMPSO_TopHat_Fusion.Optimizer.polynomialMutatedValue(
                2.0, 2.0, 2.0, 20.0, 0.75), 1e-12);
    }

    @Test
    void crowdingTournamentPrefersMoreIsolatedSolution() {
        SMPSO_TopHat_Fusion.Solution isolated = solution(7.2, 0.88);
        SMPSO_TopHat_Fusion.Solution crowded = solution(7.0, 0.90);
        isolated.crowding = 2.0;
        crowded.crowding = 0.5;

        Random random = new Random(12345L);
        for (int i = 0; i < 20; i++) {
            assertSame(isolated, SMPSO_TopHat_Fusion.Optimizer.selectByCrowding(
                    isolated, crowded, random));
        }
    }

    @Test
    void crowdingTournamentBreaksTiesRandomlyAndReproducibly() {
        SMPSO_TopHat_Fusion.Solution first = solution(7.2, 0.88);
        SMPSO_TopHat_Fusion.Solution second = solution(7.0, 0.90);
        first.crowding = 1.0;
        second.crowding = 1.0;

        Random firstRun = new Random(24680L);
        Random repeatedRun = new Random(24680L);
        boolean selectedFirst = false;
        boolean selectedSecond = false;
        for (int i = 0; i < 50; i++) {
            SMPSO_TopHat_Fusion.Solution selected =
                    SMPSO_TopHat_Fusion.Optimizer.selectByCrowding(first, second, firstRun);
            SMPSO_TopHat_Fusion.Solution repeated =
                    SMPSO_TopHat_Fusion.Optimizer.selectByCrowding(first, second, repeatedRun);
            assertSame(selected, repeated);
            selectedFirst |= selected == first;
            selectedSecond |= selected == second;
        }

        assertTrue(selectedFirst);
        assertTrue(selectedSecond);
        assertNotSame(first, second);
    }

    @Test
    void positionUpdateReflectsVelocityAtBothBounds() {
        SMPSO_TopHat_Fusion.Optimizer optimizer = optimizer(123L, 4, 1, 4);
        SMPSO_TopHat_Fusion.Particle particle = new SMPSO_TopHat_Fusion.Particle(
                2.9, 0.40, 1.95, 1.0, -0.20, 0.20, solution(0.8, 0.9));

        optimizer.updatePosition(particle);

        assertEquals(3.0, particle.rPosition, 1e-12);
        assertEquals(-1.0, particle.rVelocity, 1e-12);
        assertEquals(0.35, particle.mWthPosition, 1e-12);
        assertEquals(0.20, particle.mWthVelocity, 1e-12);
        assertEquals(2.0, particle.mBthPosition, 1e-12);
        assertEquals(-0.20, particle.mBthVelocity, 1e-12);
    }

    @Test
    void velocityRemainsInsideHalfRangeLimits() {
        SMPSO_TopHat_Fusion.Optimizer optimizer = optimizer(321L, 4, 1, 4);
        SMPSO_TopHat_Fusion.Particle particle = new SMPSO_TopHat_Fusion.Particle(
                1.0, 0.35, 0.35, 100.0, 100.0, 100.0,
                new SMPSO_TopHat_Fusion.Solution(3, 1.0, 1.0, 0.8, 0.9));
        SMPSO_TopHat_Fusion.Solution leader =
                new SMPSO_TopHat_Fusion.Solution(3, 1.0, 1.0, 0.81, 0.91);

        optimizer.updateVelocity(particle, leader);

        assertTrue(Math.abs(particle.rVelocity) <= 1.0);
        assertTrue(Math.abs(particle.mWthVelocity) <= 0.825);
        assertTrue(Math.abs(particle.mBthVelocity) <= 0.825);
    }

    @Test
    void completeRunIsReproducibleAndReturnsFeasibleNondominatedFront() {
        int swarmSize = 6;
        int iterations = 3;
        int archiveSize = 8;
        SMPSO_TopHat_Fusion.Optimizer first = optimizer(987654321L, swarmSize, iterations, archiveSize);
        SMPSO_TopHat_Fusion.Optimizer second = optimizer(987654321L, swarmSize, iterations, archiveSize);

        List<SMPSO_TopHat_Fusion.Solution> firstFront = sorted(first.run());
        List<SMPSO_TopHat_Fusion.Solution> secondFront = sorted(second.run());

        assertFalse(firstFront.isEmpty());
        assertTrue(firstFront.size() <= archiveSize);
        assertEquals(swarmSize * (iterations + 1), first.getEvaluations());
        assertEquals(firstFront.size(), secondFront.size());

        for (int i = 0; i < firstFront.size(); i++) {
            SMPSO_TopHat_Fusion.Solution a = firstFront.get(i);
            SMPSO_TopHat_Fusion.Solution b = secondFront.get(i);
            assertTrue(a.r >= 1 && a.r <= 3);
            assertTrue(a.mWth >= 0.35 && a.mWth <= 2.0);
            assertTrue(a.mBth >= 0.35 && a.mBth <= 2.0);
            assertTrue(Double.isFinite(a.entropy));
            assertTrue(a.entropy >= 0.0 && a.entropy <= 1.0);
            assertTrue(Double.isFinite(a.ssim));
            assertEquals(a.r, b.r);
            assertEquals(a.mWth, b.mWth, 1e-12);
            assertEquals(a.mBth, b.mBth, 1e-12);
            assertEquals(a.entropy, b.entropy, 1e-12);
            assertEquals(a.ssim, b.ssim, 1e-12);

            for (int j = 0; j < firstFront.size(); j++) {
                if (i != j) assertFalse(SMPSO_TopHat_Fusion.dominates(firstFront.get(j), a));
            }
        }
    }

    @Test
    void sharedFusionCacheMatchesIndependentCachePerRun() {
        int swarmSize = 5;
        int iterations = 4;
        int archiveSize = 6;
        long seedA = 111L;
        long seedB = 222L;

        // Cache creada una sola vez y reutilizada por dos corridas distintas,
        // tal como hace BatchRunner al procesar varias configuraciones del mismo par.
        SMPSO_TopHat_Fusion.FusionCache sharedCache = new SMPSO_TopHat_Fusion.FusionCache(
                ctFixture(), mriFixture(), 1, 3, 0.35, 2.0, 0.35, 2.0);
        SMPSO_TopHat_Fusion.Optimizer sharedRunA = new SMPSO_TopHat_Fusion.Optimizer(
                sharedCache, swarmSize, iterations, archiveSize, seedA);
        SMPSO_TopHat_Fusion.Optimizer sharedRunB = new SMPSO_TopHat_Fusion.Optimizer(
                sharedCache, swarmSize, iterations, archiveSize, seedB);

        // Cache independiente por corrida (constructor de conveniencia, como antes del refactor).
        SMPSO_TopHat_Fusion.Optimizer independentRunA = new SMPSO_TopHat_Fusion.Optimizer(
                ctFixture(), mriFixture(), 1, 3, 0.35, 2.0, 0.35, 2.0,
                swarmSize, iterations, archiveSize, seedA);
        SMPSO_TopHat_Fusion.Optimizer independentRunB = new SMPSO_TopHat_Fusion.Optimizer(
                ctFixture(), mriFixture(), 1, 3, 0.35, 2.0, 0.35, 2.0,
                swarmSize, iterations, archiveSize, seedB);

        List<SMPSO_TopHat_Fusion.Solution> sharedFrontA = sorted(sharedRunA.run());
        List<SMPSO_TopHat_Fusion.Solution> independentFrontA = sorted(independentRunA.run());
        List<SMPSO_TopHat_Fusion.Solution> sharedFrontB = sorted(sharedRunB.run());
        List<SMPSO_TopHat_Fusion.Solution> independentFrontB = sorted(independentRunB.run());

        assertFrontsEqual(independentFrontA, sharedFrontA);
        assertFrontsEqual(independentFrontB, sharedFrontB);

        // Cada corrida cuenta sus propias evaluaciones, sin importar que comparta la cache.
        assertEquals(swarmSize * (iterations + 1), sharedRunA.getEvaluations());
        assertEquals(swarmSize * (iterations + 1), sharedRunB.getEvaluations());
    }

    @Test
    void fusionCacheFuseMatchesUncachedComputation() {
        SMPSO_TopHat_Fusion.FusionCache cache = new SMPSO_TopHat_Fusion.FusionCache(
                ctFixture(), mriFixture(), 1, 3, 0.35, 2.0, 0.35, 2.0);

        for (int r = 1; r <= 3; r++) {
            FloatProcessor cached = cache.fuse(r, 0.75, 1.25);
            FloatProcessor direct = referenceFuse(ctFixture(), mriFixture(), r, 0.75, 1.25);
            float[] cachedPixels = (float[]) cached.getPixels();
            float[] directPixels = (float[]) direct.getPixels();
            assertEquals(directPixels.length, cachedPixels.length);
            for (int i = 0; i < cachedPixels.length; i++) {
                assertEquals(directPixels[i], cachedPixels[i], 1e-6f);
            }
        }
    }

    private static void assertFrontsEqual(List<SMPSO_TopHat_Fusion.Solution> expected,
                                           List<SMPSO_TopHat_Fusion.Solution> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            SMPSO_TopHat_Fusion.Solution a = expected.get(i);
            SMPSO_TopHat_Fusion.Solution b = actual.get(i);
            assertEquals(a.r, b.r);
            assertEquals(a.mWth, b.mWth, 1e-12);
            assertEquals(a.mBth, b.mBth, 1e-12);
            assertEquals(a.entropy, b.entropy, 1e-12);
            assertEquals(a.ssim, b.ssim, 1e-12);
        }
    }

    private static FloatProcessor referenceFuse(FloatProcessor ct, FloatProcessor mri, int r,
                                                 double mWth, double mBth) {
        FloatProcessor wthCt = SMPSO_TopHat_Fusion.whiteTopHat(ct, r);
        FloatProcessor wthMri = SMPSO_TopHat_Fusion.whiteTopHat(mri, r);
        FloatProcessor bthCt = SMPSO_TopHat_Fusion.blackTopHat(ct, r);
        FloatProcessor bthMri = SMPSO_TopHat_Fusion.blackTopHat(mri, r);
        int w = ct.getWidth(), h = ct.getHeight();
        FloatProcessor out = new FloatProcessor(w, h);
        float[] ctPixels = (float[]) ct.getPixels();
        float[] mriPixels = (float[]) mri.getPixels();
        float[] wCt = (float[]) wthCt.getPixels();
        float[] wMri = (float[]) wthMri.getPixels();
        float[] bCt = (float[]) bthCt.getPixels();
        float[] bMri = (float[]) bthMri.getPixels();
        float[] fused = (float[]) out.getPixels();
        for (int i = 0; i < fused.length; i++) {
            double base = 0.5 * (ctPixels[i] + mriPixels[i]);
            double value = base + mWth * Math.max(wCt[i], wMri[i])
                    - mBth * Math.max(bCt[i], bMri[i]);
            fused[i] = (float) SMPSO_TopHat_Fusion.clamp(value, 0.0, 255.0);
        }
        return out;
    }

    private static FloatProcessor ctFixture() {
        return new FloatProcessor(5, 5, new float[]{
                0, 10, 20, 30, 40,
                15, 25, 35, 45, 55,
                30, 40, 50, 60, 70,
                45, 55, 65, 75, 85,
                60, 70, 80, 90, 100
        });
    }

    private static FloatProcessor mriFixture() {
        return new FloatProcessor(5, 5, new float[]{
                100, 90, 80, 70, 60,
                85, 75, 65, 55, 45,
                70, 60, 50, 40, 30,
                55, 45, 35, 25, 15,
                40, 30, 20, 10, 0
        });
    }

    private static FloatProcessor image(int width, int height, float value) {
        float[] pixels = new float[width * height];
        java.util.Arrays.fill(pixels, value);
        return new FloatProcessor(width, height, pixels);
    }

    private static SMPSO_TopHat_Fusion.Solution solution(double entropy, double ssim) {
        return new SMPSO_TopHat_Fusion.Solution(1, 1.0, 1.0, entropy, ssim);
    }

    private static SMPSO_TopHat_Fusion.Optimizer optimizer(long seed, int swarmSize,
                                                            int iterations, int archiveSize) {
        FloatProcessor ct = new FloatProcessor(5, 5, new float[]{
                0, 10, 20, 30, 40,
                15, 25, 35, 45, 55,
                30, 40, 50, 60, 70,
                45, 55, 65, 75, 85,
                60, 70, 80, 90, 100
        });
        FloatProcessor mri = new FloatProcessor(5, 5, new float[]{
                100, 90, 80, 70, 60,
                85, 75, 65, 55, 45,
                70, 60, 50, 40, 30,
                55, 45, 35, 25, 15,
                40, 30, 20, 10, 0
        });
        return new SMPSO_TopHat_Fusion.Optimizer(ct, mri,
                1, 3, 0.35, 2.0, 0.35, 2.0, swarmSize, iterations, archiveSize, seed);
    }

    private static List<SMPSO_TopHat_Fusion.Solution> sorted(
            List<SMPSO_TopHat_Fusion.Solution> front) {
        List<SMPSO_TopHat_Fusion.Solution> copy =
                new ArrayList<SMPSO_TopHat_Fusion.Solution>(front);
        Collections.sort(copy, Comparator
                .comparingInt((SMPSO_TopHat_Fusion.Solution s) -> s.r)
                .thenComparingDouble(s -> s.mWth)
                .thenComparingDouble(s -> s.mBth));
        return copy;
    }
}
