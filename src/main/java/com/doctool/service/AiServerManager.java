package com.doctool.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Service
public class AiServerManager {

    private static final Logger log = LoggerFactory.getLogger(AiServerManager.class);

    @Value("${ai-server.url:http://localhost:5001}")
    private String aiServerUrl;

    @Value("${ai-server.script:ai-server/server.py}")
    private String scriptPath;

    @Value("${ai-server.auto-start:true}")
    private boolean autoStart;

    private Process pythonProcess;
    private volatile boolean started = false;

    @PostConstruct
    public void init() {
        if (!autoStart) {
            log.info("AI Server 自动启动已禁用");
            return;
        }
        // 异步启动，不阻塞主应用
        CompletableFuture.runAsync(this::startServer);
    }

    /**
     * 启动 Python AI Server
     */
    public synchronized boolean startServer() {
        if (isRunning()) return true;

        File script = new File(scriptPath);
        if (!script.exists()) {
            // 尝试从 classpath 或 jar 同级目录查找
            String altPath = findScript();
            if (altPath != null) {
                script = new File(altPath);
            } else {
                log.warn("未找到 AI Server 脚本: {}", scriptPath);
                return false;
            }
        }

        try {
            String pythonCmd = findPython();
            if (pythonCmd == null) {
                log.warn("未找到 Python 可执行文件，AI Server 无法启动");
                return false;
            }

            ProcessBuilder pb = new ProcessBuilder(pythonCmd, script.getAbsolutePath());
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);
            // 合并输出到 Java 进程的 stdout
            pb.inheritIO();

            pythonProcess = pb.start();
            started = true;
            log.info("AI Server 进程已启动 (PID: {}), 等待模型加载...", pythonProcess.pid());
            return true;
        } catch (Exception e) {
            log.error("启动 AI Server 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查 Python 进程是否在运行
     */
    public boolean isRunning() {
        if (pythonProcess == null) return false;
        return pythonProcess.isAlive();
    }

    /**
     * 检查 AI Server HTTP 服务是否就绪
     */
    public boolean isReady() {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiServerUrl + "/api/health"))
                .GET()
                .timeout(Duration.ofSeconds(3))
                .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 AI Server 当前模型的短名称（用于显示）
     */
    public String getModelName() {
        String full = getFullModelName();
        int slash = full.lastIndexOf('/');
        return slash >= 0 ? full.substring(slash + 1) : full;
    }

    /**
     * 获取 AI Server 当前使用的完整模型名
     */
    public String getFullModelName() {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiServerUrl + "/api/health"))
                .GET()
                .timeout(Duration.ofSeconds(3))
                .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                int idx = body.indexOf("\"model\":");
                if (idx >= 0) {
                    int start = body.indexOf("\"", idx + 8);
                    if (start >= 0) {
                        int end = body.indexOf("\"", start + 1);
                        if (end >= 0) {
                            return body.substring(start + 1, end);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "llava-hf/llava-1.5-7b-hf";
    }

    /**
     * 获取 AI Server 可用的模型列表
     */
    public java.util.List<String> getAvailableModels() {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiServerUrl + "/api/models"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                // 解析 {"models":[...],"current":"..."}
                String body = resp.body();
                int idx = body.indexOf("\"models\":[");
                if (idx >= 0) {
                    int start = idx + 10;
                    int end = body.indexOf("]", start);
                    if (end >= 0) {
                        String arr = body.substring(start, end);
                        java.util.List<String> models = new java.util.ArrayList<>();
                        for (String m : arr.split(",")) {
                            m = m.trim().replace("\"", "");
                            if (!m.isEmpty()) models.add(m);
                        }
                        return models;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return java.util.List.of();
    }

    /**
     * 等待服务就绪
     */
    private boolean waitForReady(int maxSeconds) {
        for (int i = 0; i < maxSeconds; i++) {
            if (!isRunning()) {
                log.warn("AI Server 进程已退出");
                return false;
            }
            if (isReady()) {
                log.info("AI Server HTTP 服务就绪");
                return true;
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        log.warn("AI Server 启动超时 ({}s)", maxSeconds);
        return false;
    }

    @PreDestroy
    public void destroy() {
        if (pythonProcess != null && pythonProcess.isAlive()) {
            log.info("正在关闭 AI Server 进程...");
            pythonProcess.destroy();
            try {
                if (pythonProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.info("AI Server 进程已关闭");
                } else {
                    pythonProcess.destroyForcibly();
                    log.info("AI Server 进程已强制关闭");
                }
            } catch (InterruptedException e) {
                pythonProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    private String findPython() {
        // 优先 python，其次 python3
        for (String cmd : new String[]{"python", "python3"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start();
                if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return cmd;
                }
            } catch (Exception e) {
                // try next
            }
        }
        return null;
    }

    private String findScript() {
        // 尝试常见位置
        String[] candidates = {
            "ai-server/server.py",
            "../ai-server/server.py",
            "server.py"
        };
        // 相对于工作目录
        String userDir = System.getProperty("user.dir");
        for (String c : candidates) {
            File f = new File(userDir, c);
            if (f.exists()) return f.getAbsolutePath();
        }
        // 相对于 jar 所在目录
        try {
            String jarDir = new File(AiServerManager.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getParent();
            for (String c : candidates) {
                File f = new File(jarDir, c);
                if (f.exists()) return f.getAbsolutePath();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
