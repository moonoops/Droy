import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** Exports the approved square illustrations without changing their source artwork. */
class GenerateLauncherIcons {
    record Art(String file, String name, int contentDp) {}
    static final Art[] ART = {
        new Art("01-closeup.png", "closeup", 72),
        new Art("02-sunglasses.png", "sunglasses", 72),
        new Art("03-headphones.png", "headphones", 72),
        new Art("04-programmer.png", "programmer", 72),
        new Art("05-hat-scarf.png", "hat_scarf", 72),
        new Art("06-sleep-curled.png", "sleep_curled", 60),
        new Art("07-sleep-side.png", "sleep_side", 60),
        new Art("08-sleep-belly-up.png", "sleep_belly_up", 60),
        new Art("09-sleep-slumped.png", "sleep_slumped", 60)
    };
    static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    static final int[] SIZES = {48, 72, 96, 144, 192};
    static final int LAYER_SIZE = 432;
    static Path root = Path.of("").toAbsolutePath();
    static boolean check;
    static int exports;

    public static void main(String[] args) throws Exception {
        Path preview = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--check")) check = true;
            else if (args[i].equals("--preview") && i + 1 < args.length) preview = Path.of(args[++i]);
            else throw new IllegalArgumentException("Usage: GenerateLauncherIcons.java [--check] [--preview OUTPUT.png]");
        }
        Path sources = root.resolve("app/launcher-icons");
        Path res = root.resolve("app/src/main/res");
        BufferedImage paper = paper(read(sources.resolve(ART[0].file)));
        output(res.resolve("drawable-nodpi/droy_launcher_paper.png"), png(paper));
        BufferedImage[] layers = new BufferedImage[ART.length];
        StringBuilder catalog = new StringBuilder("{\n  \"origin\": \"AI-generated original Droy artwork\",\n  \"background\": \"warm-white cotton card stock, approximately #EEE6D9\",\n  \"adaptiveViewportDp\": 108,\n  \"icons\": [\n");
        for (int i = 0; i < ART.length; i++) {
            Art art = ART[i];
            Path source = sources.resolve(art.file);
            BufferedImage original = read(source);
            BufferedImage foreground = foreground(original, art.contentDp);
            layers[i] = foreground;
            String resource = "ic_launcher_" + art.name;
            output(res.resolve("drawable-nodpi/" + resource + "_foreground.png"), png(foreground));
            for (int d = 0; d < DENSITIES.length; d++)
                output(res.resolve("mipmap-" + DENSITIES[d] + "/" + resource + ".png"), png(resize(original, SIZES[d], SIZES[d])));
            String xml = "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                + "    <background android:drawable=\"@drawable/droy_launcher_paper\" />\n"
                + "    <foreground android:drawable=\"@drawable/" + resource + "_foreground\" />\n"
                + "</adaptive-icon>\n";
            output(res.resolve("mipmap-anydpi-v26/" + resource + ".xml"), xml.getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            catalog.append("    {\"source\": \"").append(art.file).append("\", \"resource\": \"mipmap/")
                .append(resource).append("\", \"contentDp\": ").append(art.contentDp)
                .append(", \"sha256\": \"").append(hash).append("\"}")
                .append(i == ART.length - 1 ? "\n" : ",\n");
        }
        catalog.append("  ]\n}\n");
        output(sources.resolve("catalog.json"), catalog.toString().getBytes(StandardCharsets.UTF_8));
        if (preview != null) {
            Files.createDirectories(preview.toAbsolutePath().getParent());
            ImageIO.write(preview(paper, layers), "png", preview.toFile());
        }
        System.out.println((check ? "Verified " : "Exported ") + exports + " launcher files for " + ART.length + " icons.");
    }

    static BufferedImage read(Path path) throws Exception {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null || image.getWidth() != image.getHeight() || image.getWidth() < 512)
            throw new IllegalArgumentException("Expected square PNG artwork of at least 512px: " + path);
        return image;
    }

    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(out);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    static BufferedImage paper(BufferedImage image) {
        // The approved closeup has uninterrupted paper across its top 160px.
        BufferedImage out = new BufferedImage(LAYER_SIZE, LAYER_SIZE, BufferedImage.TYPE_INT_ARGB);
        BufferedImage strip = image.getSubimage(0, 0, image.getWidth(), 160);
        BufferedImage tile = resize(strip, LAYER_SIZE, 56);
        Graphics2D g = graphics(out);
        for (int y = 0, row = 0; y < LAYER_SIZE; y += 56, row++) {
            if (row % 2 == 0) g.drawImage(tile, 0, y, null);
            else g.drawImage(tile, 0, y + 56, LAYER_SIZE, y, 0, 0, LAYER_SIZE, 56, null);
        }
        g.dispose();
        return out;
    }

    static boolean exteriorPaper(int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        // Flood-fill only exterior pale warm paper; enclosed eyes and the sage pillow stay opaque.
        return r >= 222 && g >= 212 && b >= 192 && r >= g && g >= b && r - b >= 6;
    }

    static BufferedImage foreground(BufferedImage image, int contentDp) {
        int width = image.getWidth(), height = image.getHeight();
        boolean[] paper = new boolean[width * height];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < width; x++) { seed(image, paper, queue, x, 0); seed(image, paper, queue, x, height - 1); }
        for (int y = 0; y < height; y++) { seed(image, paper, queue, 0, y); seed(image, paper, queue, width - 1, y); }
        while (!queue.isEmpty()) {
            int index = queue.removeFirst(), x = index % width, y = index / width;
            if (x > 0) seed(image, paper, queue, x - 1, y);
            if (x + 1 < width) seed(image, paper, queue, x + 1, y);
            if (y > 0) seed(image, paper, queue, x, y - 1);
            if (y + 1 < height) seed(image, paper, queue, x, y + 1);
        }
        BufferedImage cutout = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            cutout.setRGB(x, y, paper[y * width + x] ? 0 : image.getRGB(x, y));
        BufferedImage out = new BufferedImage(LAYER_SIZE, LAYER_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(out);
        int size = contentDp * 4, inset = (LAYER_SIZE - size) / 2;
        g.drawImage(cutout, inset, inset, size, size, null);
        g.dispose();
        return out;
    }

    static void seed(BufferedImage image, boolean[] seen, ArrayDeque<Integer> queue, int x, int y) {
        int index = y * image.getWidth() + x;
        if (!seen[index] && exteriorPaper(image.getRGB(x, y))) { seen[index] = true; queue.addLast(index); }
    }

    static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IllegalStateException("PNG encoder unavailable");
        return bytes.toByteArray();
    }

    static void output(Path path, byte[] bytes) throws Exception {
        if (check) {
            if (!Files.isRegularFile(path) || !Arrays.equals(Files.readAllBytes(path), bytes))
                throw new IllegalStateException("Launcher export differs; run the generator: " + root.relativize(path));
        } else {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        }
        exports++;
    }

    static BufferedImage preview(BufferedImage paper, BufferedImage[] layers) {
        BufferedImage board = new BufferedImage(1110, 702, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(board);
        g.setColor(new Color(0xDCDCDC)); g.fillRect(0, 0, board.getWidth(), board.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        for (int i = 0; i < ART.length; i++) {
            int x = (i % 3) * 370, y = (i / 3) * 234;
            for (int mask = 0; mask < 2; mask++) {
                BufferedImage tile = new BufferedImage(156, 156, BufferedImage.TYPE_INT_ARGB);
                Graphics2D icon = graphics(tile);
                Shape shape = mask == 0 ? new Ellipse2D.Double(0, 0, 156, 156)
                    : new RoundRectangle2D.Double(0, 0, 156, 156, 40, 40);
                icon.setClip(shape);
                icon.drawImage(paper, 0, 0, 156, 156, 72, 72, 360, 360, null);
                icon.drawImage(layers[i], 0, 0, 156, 156, 72, 72, 360, 360, null);
                icon.dispose();
                g.drawImage(tile, x + 20 + mask * 174, y + 12, null);
            }
            g.setColor(new Color(0x242424));
            g.drawString(ART[i].file.replace(".png", ""), x + 24, y + 196);
        }
        g.dispose();
        return board;
    }
}
