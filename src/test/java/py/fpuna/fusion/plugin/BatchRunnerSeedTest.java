package py.fpuna.fusion.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchRunnerSeedTest {
    @TempDir
    Path tempDir;

    @Test
    void generatedSeedsChangeAndSavedSeedsReproduceFronts() throws Exception {
        Path ct = tempDir.resolve("ct.png");
        Path mri = tempDir.resolve("mri.png");
        saveImage(ct, false);
        saveImage(mri, true);

        Path manifest = tempDir.resolve("manifest.csv");
        Files.write(manifest, ("pair_id,ct_path,mri_path,dataset,split\n"
                + "test," + ct + "," + mri + ",synthetic,test\n").getBytes(StandardCharsets.UTF_8));

        Path config = tempDir.resolve("config.json");
        Files.write(config, ("{\"baseImage\":\"average\",\"decisionVariables\":{"
                + "\"radius\":{\"minimum\":1,\"maximum\":2},"
                + "\"whiteTopHatWeight\":{\"minimum\":0.35,\"maximum\":2.0},"
                + "\"blackTopHatWeight\":{\"minimum\":0.35,\"maximum\":2.0}},"
                + "\"smpso\":{\"particles\":[4],\"generations\":[1],"
                + "\"archiveSize\":5,\"runsPerPairAndConfiguration\":2}}")
                .getBytes(StandardCharsets.UTF_8));

        Path first = execute(config, manifest, "first", null);
        Path second = execute(config, manifest, "second", null);
        Path replay = execute(config, manifest, "replay", first.resolve("seeds.csv"));

        List<String> firstSeeds = Files.readAllLines(first.resolve("seeds.csv"), StandardCharsets.UTF_8);
        List<String> secondSeeds = Files.readAllLines(second.resolve("seeds.csv"), StandardCharsets.UTF_8);
        List<String> replaySeeds = Files.readAllLines(replay.resolve("seeds.csv"), StandardCharsets.UTF_8);
        assertEquals(3, firstSeeds.size());
        assertNotEquals(firstSeeds, secondSeeds);
        assertNotEquals(firstSeeds.get(1), firstSeeds.get(2));
        assertEquals(firstSeeds, replaySeeds);
        assertEquals(Files.readAllLines(first.resolve("fronts.csv"), StandardCharsets.UTF_8),
                Files.readAllLines(replay.resolve("fronts.csv"), StandardCharsets.UTF_8));
        assertEquals(Files.readAllLines(first.resolve("metrics.csv"), StandardCharsets.UTF_8),
                Files.readAllLines(replay.resolve("metrics.csv"), StandardCharsets.UTF_8));

        String firstRuns = new String(Files.readAllBytes(first.resolve("runs.csv")), StandardCharsets.UTF_8);
        String replayRuns = new String(Files.readAllBytes(replay.resolve("runs.csv")), StandardCharsets.UTF_8);
        assertTrue(firstRuns.contains(",generated,OK,"));
        assertTrue(replayRuns.contains(",file,OK,"));
        assertFalse(replayRuns.contains(",generated,"));

        Path incomplete = tempDir.resolve("incomplete-seeds.csv");
        Files.write(incomplete, (firstSeeds.get(0) + "\n").getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> execute(config, manifest, "incomplete", incomplete));
        assertTrue(missing.getMessage().contains("Falta semilla"));
    }

    private Path execute(Path config, Path manifest, String name, Path seeds) throws Exception {
        Path output = tempDir.resolve(name);
        if (seeds == null) {
            BatchRunner.main(new String[]{"--config", config.toString(), "--manifest", manifest.toString(),
                    "--out", output.toString(), "--threads", "2"});
        } else {
            BatchRunner.main(new String[]{"--config", config.toString(), "--manifest", manifest.toString(),
                    "--seeds", seeds.toString(), "--out", output.toString(), "--threads", "2"});
        }
        try (Stream<Path> directories = Files.list(output)) {
            return directories.filter(Files::isDirectory).findFirst().get();
        }
    }

    private static void saveImage(Path path, boolean inverted) throws Exception {
        BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                int value = (x * 17 + y * 9) & 255;
                image.getRaster().setSample(x, y, 0, inverted ? 255 - value : value);
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }
}
