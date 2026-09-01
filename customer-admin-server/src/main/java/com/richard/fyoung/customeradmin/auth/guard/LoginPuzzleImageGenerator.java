package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaChallengeResponse;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * 登录拼图图片生成器。
 *
 * <p>背景图和拼图块都在 JVM 内生成，不依赖外部 URL。目标横坐标只出现在返回给服务层的
 * 内部结果中，图片响应仅包含缺口本身；存储层不会接触 PNG 大对象。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class LoginPuzzleImageGenerator {

    static final int CANVAS_WIDTH = 320;
    static final int CANVAS_HEIGHT = 160;
    static final int PIECE_WIDTH = 56;
    static final int PIECE_HEIGHT = 56;

    private static final int MIN_TARGET_X = 64;
    private static final int MAX_TARGET_X = 256;
    private static final int MIN_PIECE_Y = 22;
    private static final int MAX_PIECE_Y = 82;
    private static final int SERVER_TOLERANCE_PIXELS = 3;
    private static final float SHAPE_SCALE_X = PIECE_WIDTH / 64.0F;
    private static final float SHAPE_SCALE_Y = PIECE_HEIGHT / 64.0F;

    private final SecureRandom random;

    LoginPuzzleImageGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    GeneratedPuzzle generate() {
        int targetX = randomBetween(MIN_TARGET_X, MAX_TARGET_X);
        int pieceY = randomBetween(MIN_PIECE_Y, MAX_PIECE_Y);
        long sceneSeed = random.nextLong();
        int pieceVariant = random.nextInt(4);

        BufferedImage source = renderAgentTrajectoryBackground(sceneSeed);
        Shape localPieceShape = createPieceShape(pieceVariant);
        BufferedImage piece = renderPiece(source, localPieceShape, targetX, pieceY);
        BufferedImage background = renderBackgroundWithGap(source, localPieceShape, targetX, pieceY);

        int movableWidth = CANVAS_WIDTH - PIECE_WIDTH;
        int targetXNormalized = normalizePixel(targetX, movableWidth);
        int toleranceNormalized = Math.max(1,
            (int) Math.ceil(SERVER_TOLERANCE_PIXELS * 1_000.0D / movableWidth));
        return new GeneratedPuzzle(
            toPngDataUri(background),
            toPngDataUri(piece),
            CANVAS_WIDTH,
            CANVAS_HEIGHT,
            PIECE_WIDTH,
            PIECE_HEIGHT,
            pieceY,
            targetXNormalized,
            toleranceNormalized);
    }

    private BufferedImage renderAgentTrajectoryBackground(long sceneSeed) {
        SplittableRandom sceneRandom = new SplittableRandom(sceneSeed);
        BufferedImage image = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            Color start = vary(new Color(105, 79, 213), sceneRandom);
            Color middle = vary(new Color(77, 103, 218), sceneRandom);
            Color end = vary(new Color(48, 139, 215), sceneRandom);
            graphics.setPaint(new LinearGradientPaint(
                0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
                new float[]{0.0F, 0.52F, 1.0F},
                new Color[]{start, middle, end}));
            graphics.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

            drawMicroTexture(graphics, sceneRandom);
            drawGrid(graphics, sceneRandom);
            drawAgentTrajectory(graphics, sceneRandom);
            drawGlowNodes(graphics, sceneRandom);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void drawMicroTexture(Graphics2D graphics, SplittableRandom sceneRandom) {
        for (int index = 0; index < 180; index++) {
            int x = sceneRandom.nextInt(CANVAS_WIDTH);
            int y = sceneRandom.nextInt(CANVAS_HEIGHT);
            int size = 1 + sceneRandom.nextInt(3);
            int alpha = 7 + sceneRandom.nextInt(17);
            graphics.setColor(new Color(225, 235, 255, alpha));
            graphics.fillOval(x, y, size, size);
        }
    }

    private void drawGrid(Graphics2D graphics, SplittableRandom sceneRandom) {
        graphics.setStroke(new BasicStroke(1.0F));
        graphics.setColor(new Color(255, 255, 255, 30));
        int offset = sceneRandom.nextInt(48);
        int forwardSpacing = 31 + sceneRandom.nextInt(8);
        int backwardSpacing = 42 + sceneRandom.nextInt(10);
        for (int x = -CANVAS_HEIGHT + offset; x < CANVAS_WIDTH; x += forwardSpacing) {
            graphics.drawLine(x, 0, x + CANVAS_HEIGHT, CANVAS_HEIGHT);
        }
        graphics.setColor(new Color(255, 255, 255, 20));
        for (int x = offset; x < CANVAS_WIDTH + CANVAS_HEIGHT; x += backwardSpacing) {
            graphics.drawLine(x, 0, x - CANVAS_HEIGHT, CANVAS_HEIGHT);
        }
    }

    private void drawAgentTrajectory(Graphics2D graphics, SplittableRandom sceneRandom) {
        graphics.setStroke(new BasicStroke(2.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(231, 238, 255, 142));
        double shift = sceneRandom.nextDouble(-8.0D, 8.0D);
        graphics.draw(new CubicCurve2D.Double(
            23, 112 + shift,
            82 + sceneRandom.nextDouble(-12.0D, 12.0D), 17 + shift,
            145 + sceneRandom.nextDouble(-12.0D, 12.0D), 146 - shift,
            211, 53 + shift));
        graphics.draw(new CubicCurve2D.Double(
            211, 53 + shift,
            243 + sceneRandom.nextDouble(-10.0D, 10.0D), 13 - shift,
            276 + sceneRandom.nextDouble(-10.0D, 10.0D), 91 + shift,
            304, 34 - shift));

        graphics.setStroke(new BasicStroke(1.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(218, 229, 255, 82));
        graphics.draw(new CubicCurve2D.Double(
            15, 38 - shift,
            89 + sceneRandom.nextDouble(-15.0D, 15.0D), 128 + shift,
            176 + sceneRandom.nextDouble(-15.0D, 15.0D), 17 - shift,
            296, 126 + shift));
    }

    private void drawGlowNodes(Graphics2D graphics, SplittableRandom sceneRandom) {
        int[][] nodes = {
            {29, 105}, {91, 57}, {151, 103}, {211, 53}, {269, 78}, {304, 34}
        };
        for (int[] node : nodes) {
            int x = node[0] + sceneRandom.nextInt(-5, 6);
            int y = node[1] + sceneRandom.nextInt(-5, 6);
            graphics.setColor(new Color(255, 255, 255, 28));
            graphics.fillOval(x - 9, y - 9, 18, 18);
            graphics.setColor(new Color(255, 255, 255, 172));
            graphics.setStroke(new BasicStroke(1.4F));
            graphics.drawOval(x - 4, y - 4, 8, 8);
            graphics.setColor(new Color(35, 52, 133, 170));
            graphics.fillOval(x - 2, y - 2, 4, 4);
        }
    }

    private Shape createPieceShape(int variant) {
        Path2D path = new Path2D.Double();
        path.moveTo(1, 1);
        path.lineTo(23, 1);
        path.curveTo(21, 4, 21, 8, 23, 11);
        path.curveTo(26, 16, 34, 16, 37, 11);
        path.curveTo(39, 8, 39, 4, 37, 1);
        path.lineTo(63, 1);
        path.lineTo(63, 24);
        path.curveTo(60, 22, 56, 22, 53, 24);
        path.curveTo(48, 27, 48, 35, 53, 38);
        path.curveTo(56, 40, 60, 40, 63, 38);
        path.lineTo(63, 63);
        path.lineTo(39, 63);
        path.curveTo(41, 60, 41, 56, 39, 53);
        path.curveTo(36, 48, 28, 48, 25, 53);
        path.curveTo(23, 56, 23, 60, 25, 63);
        path.lineTo(1, 63);
        path.lineTo(1, 39);
        path.curveTo(4, 41, 8, 41, 11, 39);
        path.curveTo(16, 36, 16, 28, 11, 25);
        path.curveTo(8, 23, 4, 23, 1, 25);
        path.closePath();
        Shape scaled = AffineTransform.getScaleInstance(SHAPE_SCALE_X, SHAPE_SCALE_Y)
            .createTransformedShape(path);
        if (variant == 0) {
            return scaled;
        }
        return AffineTransform.getRotateInstance(
            Math.PI * variant / 2.0D, PIECE_WIDTH / 2.0D, PIECE_HEIGHT / 2.0D)
            .createTransformedShape(scaled);
    }

    private BufferedImage renderPiece(BufferedImage source, Shape localPieceShape,
                                      int targetX, int pieceY) {
        BufferedImage piece = new BufferedImage(PIECE_WIDTH, PIECE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = piece.createGraphics();
        try {
            configure(graphics);
            graphics.setComposite(AlphaComposite.Src);
            graphics.setClip(localPieceShape);
            graphics.drawImage(source, -targetX, -pieceY, null);
            graphics.setClip(null);
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(new Color(255, 255, 255, 224));
            graphics.setStroke(new BasicStroke(1.6F));
            graphics.draw(localPieceShape);
        } finally {
            graphics.dispose();
        }
        return piece;
    }

    private BufferedImage renderBackgroundWithGap(BufferedImage source, Shape localPieceShape,
                                                   int targetX, int pieceY) {
        BufferedImage background = new BufferedImage(
            CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = background.createGraphics();
        try {
            configure(graphics);
            graphics.drawImage(source, 0, 0, null);
            Shape targetShape = AffineTransform.getTranslateInstance(targetX, pieceY)
                .createTransformedShape(localPieceShape);
            graphics.setColor(new Color(18, 28, 82, 156));
            graphics.fill(targetShape);
            graphics.setColor(new Color(255, 255, 255, 184));
            graphics.setStroke(new BasicStroke(1.4F));
            graphics.draw(targetShape);
        } finally {
            graphics.dispose();
        }
        return background;
    }

    private void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private String toPngDataUri(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            return PngDataUri.PREFIX + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("login puzzle image generation failed", e);
        }
    }

    private int randomBetween(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private int normalizePixel(int pixel, int movableWidth) {
        return (int) Math.round(pixel * 1_000.0D / movableWidth);
    }

    private Color vary(Color color, SplittableRandom sceneRandom) {
        int delta = sceneRandom.nextInt(-8, 9);
        return new Color(
            clampColor(color.getRed() + delta),
            clampColor(color.getGreen() + delta),
            clampColor(color.getBlue() + delta));
    }

    private int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    record GeneratedPuzzle(String backgroundImage, String puzzlePieceImage,
                           int canvasWidth, int canvasHeight,
                           int pieceWidth, int pieceHeight, int pieceY,
                           int targetXNormalized, int toleranceNormalized) {

        LoginCaptchaChallengeResponse toResponse(String challengeId, int ttlSeconds) {
            return new LoginCaptchaChallengeResponse(
                challengeId,
                ttlSeconds,
                backgroundImage,
                puzzlePieceImage,
                canvasWidth,
                canvasHeight,
                pieceWidth,
                pieceHeight,
                pieceY);
        }
    }
}
