package dev.apklab.runner;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Runner settings (application.yml maps each one to a RUNNER_* environment variable; see the documentation, "Configuração").
 */
@ConfigurationProperties("runner")
public class RunnerSettings {
    private String token = "";
    private String auth = "on";
    private String corsOrigins = "";
    private int maxSessions = 2;
    private int maxApkMb = 200;
    private int idleTimeoutSeconds = 600;
    private int sessionMaxSeconds = 3600;
    private int vmHeapMb = 2048;
    private int vmHeapMaxMb = 6144;
    private int maxMemoryMib = 2048;
    private int sidecarHeapMb = 512;
    private int loadTimeoutSeconds = 120;
    private int operationTimeoutSeconds = 45;
    private int startTimeoutSeconds = 30;
    private int callMarginSeconds = 15;
    private int maxLineMb = 64;
    private String allowedHosts = "*";
    private String allowPrivateNetwork = "false";
    private boolean allowPlugins = false;
    private int pluginMax = 4;
    private int pluginHeapMb = 64;
    private int pluginBusySeconds = 5;
    private int pluginMaxRunSeconds = 0;
    private int pluginMaxCodeKb = 256;
    private String appDataDir = DEFAULT_APP_DATA_DIR;
    private String runtimeDir = "";
    private String node = "node";
    private String sidecarScript = "sidecar/vm-host.mjs";
    private String workDir = "";

    /** RUNNER_APP_DATA_DIR default: a data/app-data folder next to the service (created on demand, gitignored). "off" disables persistence. */
    public static final String DEFAULT_APP_DATA_DIR = "data/app-data";

    public static List<String> splitList(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[\\s,]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public boolean authDisabled() { return "off".equalsIgnoreCase(auth); }
    public List<String> corsOriginList() { return splitList(corsOrigins).stream().map(o -> o.replaceAll("/+$", "")).toList(); }
    /** RUNNER_ALLOWED_HOSTS: the operator's cap on the hosts a session may allow. Default "*" (every public host, like the site); "none" = endpoint mocks only. */
    public List<String> allowedHostList() {
        if (allowedHosts.isBlank()) return List.of("*");
        if (allowedHosts.trim().equalsIgnoreCase("none")) return List.of();
        return splitList(allowedHosts).stream().map(String::toLowerCase).toList();
    }
    /** RUNNER_ALLOW_PRIVATE_NETWORK: 1/true lets apps reach loopback, private (RFC 1918, fc00::/7) and link-local addresses; refused by default. */
    public boolean allowPrivateNetworkEnabled() { return allowPrivateNetwork != null && allowPrivateNetwork.trim().matches("(?i)1|true|yes|on"); }
    /** Where app data persists: RUNNER_APP_DATA_DIR (relative paths against the repository / install root), null when "off". */
    public Path appDataPath() {
        String value = appDataDir == null || appDataDir.isBlank() ? DEFAULT_APP_DATA_DIR : appDataDir.trim();
        if (value.matches("(?i)off|none|false|0")) return null;
        Path path = Path.of(value);
        if (path.isAbsolute()) return path;
        String root = System.getProperty("apklab.repoRoot");
        return (root != null ? Path.of(root).resolve(path) : path.toAbsolutePath()).normalize();
    }
    public long maxApkBytes() { return (long) maxApkMb * 1024 * 1024; }
    public long maxLineBytes() { return (long) maxLineMb * 1024 * 1024; }
    public Path workPath() {
        return workDir == null || workDir.isBlank() ? Path.of(System.getProperty("java.io.tmpdir"), "apk-lab-runner") : Path.of(workDir).toAbsolutePath();
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token == null ? "" : token.trim(); }
    public String getAuth() { return auth; }
    public void setAuth(String auth) { this.auth = auth; }
    public String getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(String corsOrigins) { this.corsOrigins = corsOrigins == null ? "" : corsOrigins; }
    public int getMaxSessions() { return maxSessions; }
    public void setMaxSessions(int maxSessions) { this.maxSessions = Math.max(1, maxSessions); }
    public int getMaxApkMb() { return maxApkMb; }
    public void setMaxApkMb(int maxApkMb) { this.maxApkMb = Math.max(1, maxApkMb); }
    public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = Math.max(5, idleTimeoutSeconds); }
    public int getSessionMaxSeconds() { return sessionMaxSeconds; }
    public void setSessionMaxSeconds(int sessionMaxSeconds) { this.sessionMaxSeconds = Math.max(30, sessionMaxSeconds); }
    public int getVmHeapMb() { return vmHeapMb; }
    public void setVmHeapMb(int vmHeapMb) { this.vmHeapMb = vmHeapMb; }
    public int getVmHeapMaxMb() { return vmHeapMaxMb; }
    public void setVmHeapMaxMb(int vmHeapMaxMb) { this.vmHeapMaxMb = vmHeapMaxMb; }
    public int getMaxMemoryMib() { return maxMemoryMib; }
    public void setMaxMemoryMib(int maxMemoryMib) { this.maxMemoryMib = maxMemoryMib; }
    public int getSidecarHeapMb() { return sidecarHeapMb; }
    public void setSidecarHeapMb(int sidecarHeapMb) { this.sidecarHeapMb = sidecarHeapMb; }
    public int getLoadTimeoutSeconds() { return loadTimeoutSeconds; }
    public void setLoadTimeoutSeconds(int loadTimeoutSeconds) { this.loadTimeoutSeconds = Math.max(5, loadTimeoutSeconds); }
    public int getOperationTimeoutSeconds() { return operationTimeoutSeconds; }
    public void setOperationTimeoutSeconds(int operationTimeoutSeconds) { this.operationTimeoutSeconds = Math.max(1, operationTimeoutSeconds); }
    public int getStartTimeoutSeconds() { return startTimeoutSeconds; }
    public void setStartTimeoutSeconds(int startTimeoutSeconds) { this.startTimeoutSeconds = Math.max(1, startTimeoutSeconds); }
    public int getCallMarginSeconds() { return callMarginSeconds; }
    public void setCallMarginSeconds(int callMarginSeconds) { this.callMarginSeconds = Math.max(0, callMarginSeconds); }
    public int getMaxLineMb() { return maxLineMb; }
    public void setMaxLineMb(int maxLineMb) { this.maxLineMb = Math.max(1, maxLineMb); }
    public String getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(String allowedHosts) { this.allowedHosts = allowedHosts == null ? "" : allowedHosts; }
    public String getAllowPrivateNetwork() { return allowPrivateNetwork; }
    public void setAllowPrivateNetwork(String allowPrivateNetwork) { this.allowPrivateNetwork = allowPrivateNetwork == null ? "false" : allowPrivateNetwork; }
    public boolean isAllowPlugins() { return allowPlugins; }
    public void setAllowPlugins(boolean allowPlugins) { this.allowPlugins = allowPlugins; }
    public int getPluginMax() { return pluginMax; }
    public void setPluginMax(int pluginMax) { this.pluginMax = pluginMax; }
    public int getPluginHeapMb() { return pluginHeapMb; }
    public void setPluginHeapMb(int pluginHeapMb) { this.pluginHeapMb = pluginHeapMb; }
    public int getPluginBusySeconds() { return pluginBusySeconds; }
    public void setPluginBusySeconds(int pluginBusySeconds) { this.pluginBusySeconds = pluginBusySeconds; }
    public int getPluginMaxRunSeconds() { return pluginMaxRunSeconds; }
    public void setPluginMaxRunSeconds(int pluginMaxRunSeconds) { this.pluginMaxRunSeconds = pluginMaxRunSeconds; }
    public int getPluginMaxCodeKb() { return pluginMaxCodeKb; }
    public void setPluginMaxCodeKb(int pluginMaxCodeKb) { this.pluginMaxCodeKb = pluginMaxCodeKb; }
    public String getAppDataDir() { return appDataDir; }
    public void setAppDataDir(String appDataDir) { this.appDataDir = appDataDir == null ? "" : appDataDir; }
    public String getRuntimeDir() { return runtimeDir; }
    public void setRuntimeDir(String runtimeDir) { this.runtimeDir = runtimeDir == null ? "" : runtimeDir; }
    public String getNode() { return node; }
    public void setNode(String node) { this.node = node == null || node.isBlank() ? "node" : node; }
    public String getSidecarScript() { return sidecarScript; }
    public void setSidecarScript(String sidecarScript) { this.sidecarScript = sidecarScript; }
    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir == null ? "" : workDir; }
}
