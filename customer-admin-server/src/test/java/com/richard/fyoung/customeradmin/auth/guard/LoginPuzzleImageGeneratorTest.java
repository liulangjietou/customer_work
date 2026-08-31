package com.richard.fyoung.customeradmin.auth.guard;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JVM 内拼图素材生成的尺寸、透明通道与服务端秘密边界。 */
class LoginPuzzleImageGeneratorTest {

    private static final String PNG_PREFIX = "data:image/png;base64,";

    @Test
    void generate_shouldCreateFixedSizePngAssetsAndKeepSecretCoordinateInternal() throws Exception {
        LoginPuzzleImageGenerator.GeneratedPuzzle puzzle =
            new LoginPuzzleImageGenerator(new SecureRandom()).generate();

        assertEquals(LoginPuzzleImageGenerator.CANVAS_WIDTH, puzzle.canvasWidth());
        assertEquals(LoginPuzzleImageGenerator.CANVAS_HEIGHT, puzzle.canvasHeight());
        assertEquals(LoginPuzzleImageGenerator.PIECE_WIDTH, puzzle.pieceWidth());
        assertEquals(LoginPuzzleImageGenerator.PIECE_HEIGHT, puzzle.pieceHeight());
        assertTrue(puzzle.pieceY() >= 0);
        assertTrue(puzzle.pieceY() + puzzle.pieceHeight() <= puzzle.canvasHeight());
        assertTrue(puzzle.targetXNormalized() > 0 && puzzle.targetXNormalized() < 1_000);
        assertEquals(12, puzzle.toleranceNormalized(), "±3px 应向上换算为 12 个归一化单位");

        BufferedImage background = decode(puzzle.backgroundImage());
        BufferedImage piece = decode(puzzle.puzzlePieceImage());
        assertEquals(puzzle.canvasWidth(), background.getWidth());
        assertEquals(puzzle.canvasHeight(), background.getHeight());
        assertEquals(puzzle.pieceWidth(), piece.getWidth());
        assertEquals(puzzle.pieceHeight(), piece.getHeight());

        int transparentPixels = 0;
        int opaquePixels = 0;
        for (int y = 0; y < piece.getHeight(); y++) {
            for (int x = 0; x < piece.getWidth(); x++) {
                int alpha = piece.getRGB(x, y) >>> 24;
                if (alpha == 0) {
                    transparentPixels++;
                }
                if (alpha > 0) {
                    opaquePixels++;
                }
            }
        }
        assertTrue(transparentPixels > 0, "拼图块外部必须保持透明");
        assertTrue(opaquePixels > 0, "拼图块本体必须包含背景像素");
    }

    @Test
    void generate_shouldCoverConfiguredTargetBoundsWithoutTouchingCanvasEdges() {
        LoginPuzzleImageGenerator.GeneratedPuzzle minimum =
            new LoginPuzzleImageGenerator(new BoundarySecureRandom(false, 11L)).generate();
        LoginPuzzleImageGenerator.GeneratedPuzzle maximum =
            new LoginPuzzleImageGenerator(new BoundarySecureRandom(true, 22L)).generate();

        assertEquals(242, minimum.targetXNormalized(),
            "64px 在 264px 可移动行程中应归一化为 242");
        assertEquals(970, maximum.targetXNormalized(),
            "256px 在 264px 可移动行程中应归一化为 970");
        assertEquals(22, minimum.pieceY());
        assertEquals(82, maximum.pieceY());
        assertEquals(12, minimum.toleranceNormalized());
        assertEquals(12, maximum.toleranceNormalized());
    }

    @Test
    void generate_shouldAddHighEntropySceneEvenForSameTargetAndShape() {
        LoginPuzzleImageGenerator.GeneratedPuzzle first =
            new LoginPuzzleImageGenerator(new BoundarySecureRandom(false, 101L)).generate();
        LoginPuzzleImageGenerator.GeneratedPuzzle second =
            new LoginPuzzleImageGenerator(new BoundarySecureRandom(false, 202L)).generate();

        assertEquals(first.targetXNormalized(), second.targetXNormalized());
        assertEquals(first.pieceY(), second.pieceY());
        assertNotEquals(first.backgroundImage(), second.backgroundImage(),
            "场景随机种子不能退化成有限模板集合");
        assertNotEquals(first.puzzlePieceImage(), second.puzzlePieceImage(),
            "拼图块纹理也必须随高熵背景变化");
    }

    @Test
    void toResponse_shouldExposeRenderingDataButNotServerTarget() {
        LoginPuzzleImageGenerator.GeneratedPuzzle puzzle =
            new LoginPuzzleImageGenerator(new SecureRandom()).generate();

        var response = puzzle.toResponse("challenge-id", 120);

        assertEquals("challenge-id", response.challengeId());
        assertEquals(120, response.ttlSeconds());
        assertEquals(puzzle.backgroundImage(), response.backgroundImage());
        assertEquals(puzzle.puzzlePieceImage(), response.puzzlePieceImage());
        assertEquals(9, response.getClass().getRecordComponents().length,
            "公开 challenge 合同不得增加 targetX 或 tolerance");
    }

    private BufferedImage decode(String dataUri) throws Exception {
        assertTrue(dataUri.startsWith(PNG_PREFIX));
        byte[] png = Base64.getDecoder().decode(dataUri.substring(PNG_PREFIX.length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        return image;
    }

    private static final class BoundarySecureRandom extends SecureRandom {
        private final boolean maximum;
        private final long sceneSeed;

        private BoundarySecureRandom(boolean maximum, long sceneSeed) {
            this.maximum = maximum;
            this.sceneSeed = sceneSeed;
        }

        @Override
        public int nextInt(int bound) {
            return maximum ? bound - 1 : 0;
        }

        @Override
        public long nextLong() {
            return sceneSeed;
        }
    }
}
