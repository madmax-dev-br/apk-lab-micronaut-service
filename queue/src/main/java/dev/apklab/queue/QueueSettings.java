package dev.apklab.queue;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Queue settings (application.yml maps each one to a QUEUE_* environment variable; see the documentation, "Configuração"). */
@ConfigurationProperties("queue")
public class QueueSettings {
    /** A client of the queue: id, bearer token, optional own concurrency limit. */
    public record Client(String id, String token, Integer maxConcurrent) {}
    /** A runner of the registry: id, base URL, bearer token. */
    public record RunnerRef(String id, String url, String token) {}

    private String clients = "";
    private String auth = "on";
    private String corsOrigins = "";
    private String runners = "";
    private String runnerToken = "";
    private int maxPerClient = 2;
    private int maxQueuedPerClient = 50;
    private int maxAttempts = 3;
    private int maxApkMb = 200;
    private String apkUrlHosts = "";
    private Duration jobTimeout = Duration.ofMinutes(15);
    private Duration leaseMax = Duration.ofMinutes(30);
    private Duration leaseIdle = Duration.ofMinutes(5);
    private Duration resultTtl = Duration.ofDays(1);
    private Duration healthInterval = Duration.ofSeconds(5);
    private Duration retryBase = Duration.ofSeconds(1);
    private String dataDir = "data/queue";
    private String store = "memory";
    private String storeFile = "";
    private boolean allowPlugins = false;

    static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[\\s,]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** "alice:token-of-8+chars:3, bob:other-token" -> token -> client. */
    public Map<String, Client> clientMap() {
        Map<String, Client> out = new LinkedHashMap<>();
        for (String item : split(clients)) {
            String[] parts = item.split(":");
            if (parts.length < 2 || !parts[0].matches("[\\w.-]{1,64}") || parts[1].length() < 8) {
                throw new IllegalStateException("QUEUE_CLIENTS entry must be \"<client-id>:<token of 8+ chars>[:<max concurrent>]\": " + parts[0]);
            }
            Integer max = parts.length > 2 ? Math.max(1, Integer.parseInt(parts[2])) : null;
            out.put(parts[1], new Client(parts[0], parts[1], max));
        }
        return out;
    }

    /** "http://a:8081, http://b:8081|token-b" -> runners (a runner without its own token uses QUEUE_RUNNER_TOKEN). */
    public List<RunnerRef> runnerList() {
        List<String> items = split(runners);
        java.util.ArrayList<RunnerRef> out = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String[] parts = items.get(i).split("\\|", 2);
            java.net.URI uri = java.net.URI.create(parts[0]);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw new IllegalStateException("Runner URL must be http(s): " + parts[0]);
            out.add(new RunnerRef("runner-" + (i + 1), parts[0].replaceAll("/+$", ""), parts.length > 1 ? parts[1] : runnerToken));
        }
        return out;
    }

    public boolean authDisabled() { return "off".equalsIgnoreCase(auth); }
    public List<String> corsOriginList() { return split(corsOrigins).stream().map(o -> o.replaceAll("/+$", "")).toList(); }
    public List<String> apkUrlHostList() { return split(apkUrlHosts).stream().map(String::toLowerCase).toList(); }
    public long maxApkBytes() { return (long) maxApkMb * 1024 * 1024; }
    public Path dataPath() { return Path.of(dataDir).toAbsolutePath(); }

    public String getClients() { return clients; }
    public void setClients(String clients) { this.clients = clients == null ? "" : clients; }
    public String getAuth() { return auth; }
    public void setAuth(String auth) { this.auth = auth; }
    public String getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(String corsOrigins) { this.corsOrigins = corsOrigins == null ? "" : corsOrigins; }
    public String getRunners() { return runners; }
    public void setRunners(String runners) { this.runners = runners == null ? "" : runners; }
    public String getRunnerToken() { return runnerToken; }
    public void setRunnerToken(String runnerToken) { this.runnerToken = runnerToken == null ? "" : runnerToken; }
    public int getMaxPerClient() { return maxPerClient; }
    public void setMaxPerClient(int maxPerClient) { this.maxPerClient = Math.max(1, maxPerClient); }
    public int getMaxQueuedPerClient() { return maxQueuedPerClient; }
    public void setMaxQueuedPerClient(int maxQueuedPerClient) { this.maxQueuedPerClient = Math.max(1, maxQueuedPerClient); }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = Math.max(1, Math.min(10, maxAttempts)); }
    public int getMaxApkMb() { return maxApkMb; }
    public void setMaxApkMb(int maxApkMb) { this.maxApkMb = Math.max(1, maxApkMb); }
    public String getApkUrlHosts() { return apkUrlHosts; }
    public void setApkUrlHosts(String apkUrlHosts) { this.apkUrlHosts = apkUrlHosts == null ? "" : apkUrlHosts; }
    public Duration getJobTimeout() { return jobTimeout; }
    public void setJobTimeout(Duration jobTimeout) { this.jobTimeout = jobTimeout; }
    public Duration getLeaseMax() { return leaseMax; }
    public void setLeaseMax(Duration leaseMax) { this.leaseMax = leaseMax; }
    public Duration getLeaseIdle() { return leaseIdle; }
    public void setLeaseIdle(Duration leaseIdle) { this.leaseIdle = leaseIdle; }
    public Duration getResultTtl() { return resultTtl; }
    public void setResultTtl(Duration resultTtl) { this.resultTtl = resultTtl; }
    public Duration getHealthInterval() { return healthInterval; }
    public void setHealthInterval(Duration healthInterval) { this.healthInterval = healthInterval; }
    public Duration getRetryBase() { return retryBase; }
    public void setRetryBase(Duration retryBase) { this.retryBase = retryBase; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir == null || dataDir.isBlank() ? "data/queue" : dataDir; }
    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public String getStoreFile() { return storeFile; }
    public void setStoreFile(String storeFile) { this.storeFile = storeFile == null ? "" : storeFile; }
    public boolean isAllowPlugins() { return allowPlugins; }
    public void setAllowPlugins(boolean allowPlugins) { this.allowPlugins = allowPlugins; }
}
