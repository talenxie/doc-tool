import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven 构建时自动为 JS/CSS 文件名添加内容哈希，并更新 HTML 模板引用。
 * 用法: java HashAssets <target/classes目录>
 */
public class HashAssets {

    // 要处理的静态资源（相对于 static/ 目录）
    private static final String[] ASSETS = {
        "js/app.js",
        "js/file-upload.js",
        "css/style.css"
    };

    public static void main(String[] args) throws Exception {
        String classesDir = args.length > 0 ? args[0] : "target/classes";
        Path staticDir = Path.of(classesDir, "static");
        Path templatesDir = Path.of(classesDir, "templates");

        MessageDigest md = MessageDigest.getInstance("MD5");
        Map<String, String> renameMap = new LinkedHashMap<>();

        // 1. 计算哈希并重命名文件
        for (String asset : ASSETS) {
            Path srcFile = staticDir.resolve(asset);
            if (!Files.exists(srcFile)) {
                System.out.println("[HashAssets] 跳过: " + asset);
                continue;
            }

            byte[] content = Files.readAllBytes(srcFile);
            md.reset();
            String hash = HexFormat.of().formatHex(md.digest(content)).substring(0, 8);

            String fileName = srcFile.getFileName().toString();
            int dotIdx = fileName.lastIndexOf('.');
            String base = fileName.substring(0, dotIdx);
            String ext = fileName.substring(dotIdx);
            String hashedName = base + "." + hash + ext;

            Path destFile = srcFile.resolveSibling(hashedName);
            Files.move(srcFile, destFile, StandardCopyOption.REPLACE_EXISTING);

            // 记录映射: /js/app.js → /js/app.a3f8b2c1.js
            String dir = asset.startsWith("js/") ? "/js/" : "/css/";
            renameMap.put(dir + fileName, dir + hashedName);
            System.out.println("[HashAssets] " + fileName + " → " + hashedName);
        }

        if (renameMap.isEmpty()) return;

        // 2. 更新 HTML 模板
        if (!Files.exists(templatesDir)) return;
        try (var stream = Files.walk(templatesDir)) {
            stream.filter(p -> p.toString().endsWith(".html"))
                  .forEach(html -> updateHtml(html, renameMap));
        }
    }

    private static void updateHtml(Path htmlFile, Map<String, String> renameMap) {
        try {
            String content = Files.readString(htmlFile);
            String original = content;

            for (var entry : renameMap.entrySet()) {
                // 匹配 /js/app.js 或 /css/style.css，后面可能跟 ?v=xxx
                String pattern = Pattern.quote(entry.getKey()) + "(\\?[^\"']*)?";
                content = content.replaceAll(pattern, Matcher.quoteReplacement(entry.getValue()));
            }

            if (!content.equals(original)) {
                Files.writeString(htmlFile, content);
                System.out.println("[HashAssets] 更新模板: " + htmlFile.getFileName());
            }
        } catch (IOException e) {
            System.err.println("[HashAssets] 更新失败: " + htmlFile);
        }
    }
}
