package com.doctool.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class AnimToVideoService {

    private static final Set<String> SUPPORTED_EXTS = Set.of("gif", "webp");

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    /**
     * 批量转换：接收多个文件，逐个转为 MP4，最终打包为 ZIP 返回。
     * @return ZIP 字节数组
     */
    public byte[] convertAll(List<MultipartFile> files, String outputFormat) throws Exception {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个文件");
        }

        Path workDir = Files.createTempDirectory("anim2video_");
        try {
            List<Path> mp4Files = new ArrayList<>();

            for (MultipartFile file : files) {
                String originalName = file.getOriginalFilename();
                if (originalName == null || !isSupported(originalName)) {
                    log.warn("跳过不支持的文件: {}", originalName);
                    continue;
                }

                // 保存源文件到临时目录
                String ext = getExtension(originalName).toLowerCase();
                Path srcFile = workDir.resolve(UUID.randomUUID() + "." + ext);
                file.transferTo(srcFile.toFile());

                // 转换
                String baseName = originalName.contains(".")
                    ? originalName.substring(0, originalName.lastIndexOf('.'))
                    : originalName;
                String targetExt = "webm".equals(outputFormat) ? "webm"
                    : "avi".equals(outputFormat) ? "avi" : "mp4";
                Path destFile = workDir.resolve(baseName + "." + targetExt);

                try {
                    convertOne(srcFile, destFile, targetExt);
                    mp4Files.add(destFile);
                    log.info("转换成功: {} → {}", originalName, destFile.getFileName());
                } catch (Exception e) {
                    log.error("转换失败: {} - {}", originalName, e.getMessage());
                    throw new RuntimeException("转换 " + originalName + " 失败: " + e.getMessage(), e);
                } finally {
                    try { Files.deleteIfExists(srcFile); } catch (IOException ignored) {}
                }
            }

            if (mp4Files.isEmpty()) {
                throw new RuntimeException("没有成功转换的文件");
            }

            // 单个文件直接返回，多个文件打包 ZIP
            if (mp4Files.size() == 1) {
                return Files.readAllBytes(mp4Files.get(0));
            }

            return createZip(mp4Files, workDir);
        } finally {
            // 清理临时目录
            deleteDir(workDir);
        }
    }

    private void convertOne(Path src, Path dest, String format) throws Exception {
        // 优先使用 FFmpeg
        if (isFfmpegAvailable()) {
            convertWithFfmpeg(src, dest, format);
            return;
        }
        throw new RuntimeException("未检测到 FFmpeg，请先安装 FFmpeg 并确保其在系统 PATH 中");
    }

    private void convertWithFfmpeg(Path src, Path dest, String format) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");                    // 覆盖输出
        cmd.add("-i");                    // 输入文件
        cmd.add(src.toAbsolutePath().toString());

        // 保证宽高为偶数（视频编码要求）
        cmd.add("-vf");
        cmd.add("scale=trunc(iw/2)*2:trunc(ih/2)*2");

        if ("webm".equals(format)) {
            cmd.add("-c:v");
            cmd.add("libvpx-vp9");
            cmd.add("-b:v");
            cmd.add("1M");
            cmd.add("-auto-alt-ref");
            cmd.add("0");
            cmd.add("-pix_fmt");
            cmd.add("yuva420p");
        } else if ("avi".equals(format)) {
            cmd.add("-c:v");
            cmd.add("mpeg4");
            cmd.add("-q:v");
            cmd.add("4");
        } else {
            // MP4: H.264
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-preset");
            cmd.add("medium");
            cmd.add("-crf");
            cmd.add("23");
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
        }

        if ("mp4".equals(format)) {
            cmd.add("-movflags");
            cmd.add("+faststart");
        }
        cmd.add(dest.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        // 读取输出流，捕获最后几行作为错误信息
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // 只保留最后 20 行
                int count = 0;
                for (int i = 0; i < output.length(); i++) {
                    if (output.charAt(i) == '\n') count++;
                }
                if (count > 20) {
                    output.delete(0, output.indexOf("\n") + 1);
                }
            }
        }

        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg 转换失败，退出码: " + exitCode + "\n" + output);
        }
    }

    private boolean isFfmpegAvailable() {
        try {
            Process proc = new ProcessBuilder(ffmpegPath, "-version")
                .redirectErrorStream(true)
                .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String firstLine = reader.readLine();
                // 读完剩余输出
                while (reader.readLine() != null) {}
                proc.waitFor();
                return firstLine != null && firstLine.contains("ffmpeg");
            }
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] createZip(List<Path> files, Path baseDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Path file : files) {
                zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private boolean isSupported(String filename) {
        String ext = getExtension(filename).toLowerCase();
        return SUPPORTED_EXTS.contains(ext);
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }
}
