package com.tesla.carwith;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    public static final String VIRTUAL_PUBLIC_IP = "101.200.208.6";
    private static int port = 8080;
    private static String webroot = "/data/adb/modules/tesla_carwith_box/webroot";

    // Status
    private static volatile boolean carlifeConnected = false;
    private static volatile String activeDisplayId = "0";
    private static volatile byte[] latestJpegFrame = null;
    private static final Object frameLock = new Object();
    private static final AtomicInteger activeStreamClients = new AtomicInteger(0);

    // Audio Streaming
    private static final int UDP_AUDIO_PORT = 8089;
    private static final Set<BlockingQueue<byte[]>> activeAudioQueues = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Thread Pool
    private static final ExecutorService pool = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }
        if (args.length > 1) {
            webroot = args[1];
        }
        File wrDir = new File(webroot);
        if (!wrDir.exists() && new File("/data/local/tmp/webroot").exists()) {
            webroot = "/data/local/tmp/webroot";
        }

        System.out.println("==================================================");
        System.out.println("  Tesla CarWith Virtual HeadUnit Container Bridge ");
        System.out.println("  HTTP Service: " + port + " | Webroot: " + webroot);
        System.out.println("==================================================");

        // 1. 自动放行防火墙 (包括 wlan0 局域网与车机热点)
        unblockHotspot();

        // 2. 自动拉起 CarLife 车机端并保持后台静默就绪 (彻底解决免手动点击问题)
        ensureCarLifeVehicleStarted();

        // 3. 启动按需自适应屏幕捕获引擎 (有网页访问时即时捕获，无连接时零功耗休眠)
        pool.execute(Main::runScreenCaptureLoop);

        // 4. 启动 UDP 8089 PCM 音频截获与网页转发网关
        startAudioReceiver();
        updateBdcfAudioMode(getAudioMode());

        // 4.5 启动局域网 DNS 虚拟解析引擎 (监听 5354 端口，将 t.carwith.com 解析为车机网关)
        startDnsServer();

        // 5. 启动 HTTP 网页服务与触控网关 (监听 8080 与 80)
        startHttpServer(port);
    }

    private static void ensureCarLifeVehicleStarted() {
        pool.execute(() -> {
            try {
                execCmd("settings put system show_touches 0 2>/dev/null");
                String pid = execCmd("pgrep -f com.baidu.carlifevehicle");
                if (pid == null || pid.trim().isEmpty()) {
                    System.out.println("[AutoLaunch] Launching CarLife Vehicle background service...");
                    execCmd("am start -n com.baidu.carlifevehicle/.CarlifeActivity >/dev/null 2>&1");
                    execCmd("settings put system show_touches 0 2>/dev/null");
                    System.out.println("[AutoLaunch] CarLife Vehicle service launched.");
                } else {
                    execCmd("settings put system show_touches 0 2>/dev/null");
                    System.out.println("[AutoLaunch] CarLife Vehicle already running (PID: " + pid.trim() + ")");
                }
            } catch (Throwable t) {
                System.err.println("[AutoLaunch] Error: " + t.getMessage());
            }
        });
    }

    private static void unblockHotspot() {
        pool.execute(() -> {
            try {
                String cmd = "umount /system/etc/hosts 2>/dev/null; umount /etc/hosts 2>/dev/null; " +
                             "ip addr add " + VIRTUAL_PUBLIC_IP + "/32 dev lo 2>/dev/null; " +
                             "for dev in wlan2 swlan0 ap0 softap0 wlan1 rndis0 wlan0; do iptables -t nat -D PREROUTING -i \"$dev\" -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null; iptables -t nat -D PREROUTING -i \"$dev\" -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null; done; " +
                             "iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5354 2>/dev/null; iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null; " +
                             "iptables -C INPUT -i lo -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -i lo -j ACCEPT 2>/dev/null; " +
                             "iptables -C INPUT -d " + VIRTUAL_PUBLIC_IP + " -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -d " + VIRTUAL_PUBLIC_IP + " -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_INPUT -d " + VIRTUAL_PUBLIC_IP + " -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -d " + VIRTUAL_PUBLIC_IP + " -j ACCEPT 2>/dev/null; " +
                             "iptables -t nat -C PREROUTING -d " + VIRTUAL_PUBLIC_IP + " -p tcp -j REDIRECT --to-ports 8080 2>/dev/null || iptables -t nat -I PREROUTING 1 -d " + VIRTUAL_PUBLIC_IP + " -p tcp -j REDIRECT --to-ports 8080 2>/dev/null; " +
                             "iptables -t nat -C OUTPUT -d " + VIRTUAL_PUBLIC_IP + " -p tcp -j REDIRECT --to-ports 8080 2>/dev/null || iptables -t nat -I OUTPUT 1 -d " + VIRTUAL_PUBLIC_IP + " -p tcp -j REDIRECT --to-ports 8080 2>/dev/null; " +
                             "iptables -t nat -C PREROUTING -p udp --dport 53 -m string --string \"carwith\" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null || iptables -t nat -I PREROUTING 1 -p udp --dport 53 -m string --string \"carwith\" --algo bm -j REDIRECT --to-ports 5354 2>/dev/null; " +
                             "iptables -C INPUT -p tcp --dport " + port + " -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport " + port + " -j ACCEPT 2>/dev/null; " +
                             "iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null; " +
                             "iptables -C INPUT -p tcp --dport 7240 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 7240 -j ACCEPT 2>/dev/null; " +
                             "iptables -C INPUT -p tcp --dport 8240 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8240 -j ACCEPT 2>/dev/null; " +
                             "iptables -C INPUT -p tcp --dport 9340 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 9340 -j ACCEPT 2>/dev/null; " +
                             "iptables -C INPUT -p udp --dport 7999 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 7999 -j ACCEPT 2>/dev/null; " +
                             "iptables -C INPUT -p udp --dport " + UDP_AUDIO_PORT + " -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport " + UDP_AUDIO_PORT + " -j ACCEPT 2>/dev/null; " +
                             "iptables -C INPUT -p udp --dport 5354 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 5354 -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_INPUT -p tcp --dport " + port + " -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport " + port + " -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_INPUT -p tcp --dport 7240 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport 7240 -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_INPUT -p tcp --dport 8240 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport 8240 -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_INPUT -p tcp --dport 9340 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p tcp --dport 9340 -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_INPUT -p udp --dport " + UDP_AUDIO_PORT + " -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p udp --dport " + UDP_AUDIO_PORT + " -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_INPUT -p udp --dport 5354 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_INPUT 1 -p udp --dport 5354 -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_FORWARD -p tcp --dport " + port + " -j ACCEPT 2>/dev/null || iptables -I tetherctrl_FORWARD 1 -p tcp --dport " + port + " -j ACCEPT 2>/dev/null; " +
                             "iptables -C tetherctrl_FORWARD -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I tetherctrl_FORWARD 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null; " +
                             "ip6tables -C INPUT -p tcp --dport " + port + " -j ACCEPT 2>/dev/null || ip6tables -I INPUT 1 -p tcp --dport " + port + " -j ACCEPT 2>/dev/null; " +
                             "ip6tables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || ip6tables -I INPUT 1 -p tcp --dport 80 -j ACCEPT 2>/dev/null;";
                execCmd(cmd);
                System.out.println("[Firewall] Network ports and access rules applied.");
            } catch (Exception e) {
                System.err.println("[Firewall] Failed to apply rules: " + e.getMessage());
            }
        });
    }

    public static String getActiveIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                List<NetworkInterface> list = Collections.list(ifaces);
                // 1. 优先检索热点/AP网卡 IP (ap, softap, swlan, wlan1, rndis)
                for (NetworkInterface nif : list) {
                    String name = nif.getName().toLowerCase();
                    if (name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan") || name.equals("wlan1") || name.equals("wlan2") || name.startsWith("rndis")) {
                        for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                String host = addr.getHostAddress();
                                if (!host.startsWith("127.")) return host;
                            }
                        }
                    }
                }
                // 2. 备用检索 Wi-Fi STA 网卡 (wlan0)
                for (NetworkInterface nif : list) {
                    String name = nif.getName().toLowerCase();
                    if (name.startsWith("wlan")) {
                        for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                String host = addr.getHostAddress();
                                if (!host.startsWith("127.")) return host;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "192.168.43.1";
    }

    public static String getVehicleName() {
        try {
            File f = new File("/data/local/tmp/tesla_vehicle_name");
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "Xiaomi SU7-Tesla";
    }

    public static String getAudioMode() {
        try {
            File f = new File("/data/adb/tesla_box/audio_mode");
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "native";
    }

    public static String getResolution() {
        try {
            File f = new File("/data/adb/tesla_box/resolution");
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "1280x720";
    }

    public static boolean isCarLifeActive() {
        if (carlifeConnected) return true;
        try {
            String out = execCmd("ss -tn '( sport = :7240 or dport = :7240 )' 2>/dev/null | grep ESTAB");
            if (out != null && !out.trim().isEmpty()) return true;
            String pid = execCmd("pidof com.baidu.carlifevehicle");
            if (pid != null && !pid.trim().isEmpty()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static String findSfDisplayId() {
        try {
            File fSf = new File("/data/local/tmp/tesla_sf_display_id");
            if (fSf.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(fSf))) {
                    String line = r.readLine();
                    if (line != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.equals("0") && line.length() >= 8) {
                            return line;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            // 通过 dumpsys SurfaceFlinger --display-id 检索 com.miui.carlink 车载独立虚拟大屏
            String dump = execCmd("dumpsys SurfaceFlinger --display-id");
            if (dump != null && !dump.isEmpty()) {
                String[] lines = dump.split("\n");
                // 1. 优先精准匹配 com.miui.carlink 虚拟显示屏
                for (String l : lines) {
                    l = l.trim();
                    if (l.contains("com.miui.carlink")) {
                        // 格式: Display 11529215047229308075 (Virtual display): displayName="com.miui.carlink"
                        String[] parts = l.split("\\s+");
                        if (parts.length >= 2 && parts[0].equalsIgnoreCase("Display")) {
                            return parts[1];
                        }
                    }
                }
                // 2. 备用匹配 Virtual display (排除 minimap 小窗)
                for (String l : lines) {
                    l = l.trim();
                    if (l.contains("Virtual display") && !l.contains("minimap")) {
                        String[] parts = l.split("\\s+");
                        if (parts.length >= 2 && parts[0].equalsIgnoreCase("Display")) {
                            return parts[1];
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String findLogicalDisplayId() {
        // 1. 优先读取 Hook 注入层实时保存的逻辑 Display ID
        try {
            File f = new File("/data/local/tmp/tesla_logical_display_id");
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line = r.readLine();
                    if (line != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.equals("0")) {
                            return line;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. 从 dumpsys display 中解析 com.miui.carlink 逻辑屏幕 ID
        try {
            String dump = execCmd("dumpsys display");
            if (dump != null && !dump.isEmpty()) {
                String target = "\"com.miui.carlink\", displayId ";
                int idx = dump.indexOf(target);
                if (idx != -1) {
                    int start = idx + target.length();
                    int end = dump.indexOf(",", start);
                    if (end != -1) {
                        String dId = dump.substring(start, end).trim();
                        if (!dId.isEmpty() && !dId.equals("0")) {
                            return dId;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0";
    }

    // 安全非阻塞命令执行器 (合并 stderr，单线程同步读取，零多余线程开销)
    public static String execCmd(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            p.waitFor(3, TimeUnit.SECONDS);
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
        }
    }

    // =========================================================================
    // 屏幕帧采集 (按需串行捕获，避免进程堆叠与 Dalvik 堆耗尽)
    // =========================================================================
    private static byte[] captureRawScreen(String sfId) {
        Process p = null;
        try {
            String[] cmd = (sfId != null && !sfId.isEmpty())
                        ? new String[]{"/system/bin/screencap", "-d", sfId, "-p"}
                        : new String[]{"/system/bin/screencap", "-p"};
            p = Runtime.getRuntime().exec(cmd);

            try (InputStream is = p.getInputStream();
                 ByteArrayOutputStream rawBaos = new ByteArrayOutputStream(131072)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = is.read(buf)) != -1) {
                    rawBaos.write(buf, 0, n);
                }
                p.waitFor(3, TimeUnit.SECONDS);
                byte[] data = rawBaos.toByteArray();
                if (data.length > 2000) {
                    return data;
                }
            }
        } catch (Throwable t) {
            System.err.println("[ScreenCapture] Error in screencap: " + t.getMessage());
        } finally {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static void runScreenCaptureLoop() {
        long lastDispCheck = 0;
        String sfId = null;
        System.out.println("[ScreenCapture] Engine started (demand-driven mode).");

        while (true) {
            try {
                // 如果当前没有任何客户端在观看网页串流，则保持休眠，节省 CPU 与电池
                if (activeStreamClients.get() <= 0) {
                    Thread.sleep(250);
                    continue;
                }

                long now = System.currentTimeMillis();
                if (now - lastDispCheck > 3000 || sfId == null) {
                    sfId = findSfDisplayId();
                    activeDisplayId = findLogicalDisplayId();
                    lastDispCheck = now;
                }

                long capStart = System.currentTimeMillis();
                byte[] rawBytes = null;
                if (sfId != null) {
                    rawBytes = captureRawScreen(sfId);
                    if (rawBytes == null || rawBytes.length < 2000) {
                        sfId = findSfDisplayId();
                        if (sfId != null) {
                            rawBytes = captureRawScreen(sfId);
                        }
                    }
                }

                // 仅当彻底未检测到车载虚拟屏幕时，才临时 fallback 到手机物理屏幕
                if ((rawBytes == null || rawBytes.length < 2000) && sfId == null) {
                    rawBytes = captureRawScreen(null);
                }

                if (rawBytes != null && rawBytes.length > 2000) {
                    synchronized (frameLock) {
                        latestJpegFrame = rawBytes;
                        frameLock.notifyAll();
                    }
                }

                long capElapsed = System.currentTimeMillis() - capStart;
                long toSleep = Math.max(30, 80 - capElapsed); // 约 12-15 fps
                Thread.sleep(toSleep);
            } catch (Throwable ignored) {
                try { Thread.sleep(200); } catch (Exception ignored2) {}
            }
        }
    }

    // =========================================================================
    // HTTP 服务器与触控调度
    // =========================================================================
    private static void startHttpServer(int port) {
        if (port != 80) {
            pool.execute(() -> {
                try (ServerSocket s80 = new ServerSocket()) {
                    s80.setReuseAddress(true);
                    s80.bind(new InetSocketAddress("0.0.0.0", 80));
                    System.out.println("[HttpServer] Ready and listening on 0.0.0.0:80 (HTTP Default)");
                    while (true) {
                        Socket client = s80.accept();
                        client.setTcpNoDelay(true);
                        pool.execute(() -> handleHttpClient(client));
                    }
                } catch (Throwable t) {
                    System.out.println("[HttpServer] Port 80 handled via iptables: " + t.getMessage());
                }
            });
        }

        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
            System.out.println("[HttpServer] Ready and listening on 0.0.0.0:" + port);

            while (true) {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                pool.execute(() -> handleHttpClient(client));
            }
        } catch (Exception e) {
            System.err.println("[HttpServer] Fatal error: " + e.getMessage());
        }
    }

    private static void handleHttpClient(Socket client) {
        try (Socket cl = client) {
            cl.setSoTimeout(5000);
            try (InputStream in = cl.getInputStream();
                 OutputStream out = cl.getOutputStream()) {

                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                String reqLine = reader.readLine();
                if (reqLine == null) return;

                // 消耗剩余 HTTP 请求头
                String hLine;
                while ((hLine = reader.readLine()) != null && !hLine.isEmpty()) {}

                String[] parts = reqLine.split(" ");
                if (parts.length < 2) return;
                String path = parts[1];

                String query = "";
                int qIdx = path.indexOf('?');
                if (qIdx != -1) {
                    query = path.substring(qIdx + 1);
                    path = path.substring(0, qIdx);
                }
                Map<String, String> params = parseQuery(query);

                if (path.equals("/") || path.equals("/tesla") || path.equals("/tesla.html")) {
                    serveFile(out, new File(webroot, "tesla.html"), "text/html; charset=utf-8");
                } else if (path.equals("/config") || path.equals("/index.html")) {
                    serveFile(out, new File(webroot, "index.html"), "text/html; charset=utf-8");
                } else if (path.equals("/api/ping")) {
                    sendResponse(out, 200, "text/plain", "pong".getBytes());
                } else if (path.contains("generate_204") || path.contains("gen_204") || path.contains("connectivity-check") || path.equals("/check_network_status.txt")) {
                    String res204 = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    out.write(res204.getBytes());
                    out.flush();
                    return;
                } else if (path.contains("status.html") || path.contains("connman") || path.equals("/online")) {
                    String body = "ConnMan Online\n";
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
                    out.write(resp.getBytes());
                    out.flush();
                    return;
                } else if (path.equals("/ncsi.txt")) {
                    String body = "Microsoft NCSI";
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
                    out.write(resp.getBytes());
                    out.flush();
                    return;
                } else if (path.equals("/connecttest.txt")) {
                    String body = "Microsoft Connect Test";
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
                    out.write(resp.getBytes());
                    out.flush();
                    return;
                } else if (path.contains("hotspot-detect.html") || path.contains("library/test/success.html") || path.contains("canonical.html")) {
                    String body = "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>\n";
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
                    out.write(resp.getBytes());
                    out.flush();
                    return;
                } else if (path.equals("/api/status") || path.equals("/cgi-bin/status")) {
                    String ip = getActiveIp();
                    String vName = getVehicleName();
                    String aMode = getAudioMode();
                    String res = getResolution();
                    boolean isConn = isCarLifeActive();
                    String json = String.format(Locale.ROOT,
                            "{\"status\":\"ok\",\"connected\":%b,\"display\":\"%s\",\"ip\":\"%s\",\"virtual_ip\":\"%s\",\"domain\":\"t.carwith.com\",\"tesla_url\":\"http://t.carwith.com\",\"tesla_ip_url\":\"http://%s\",\"vehicle_name\":\"%s\",\"audio_mode\":\"%s\",\"resolution\":\"%s\"}",
                            isConn, activeDisplayId, ip, VIRTUAL_PUBLIC_IP, VIRTUAL_PUBLIC_IP, vName, aMode, res);
                    sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes("UTF-8"));
                } else if (path.equals("/api/restart")) {
                    sendResponse(out, 200, "application/json", "{\"status\":\"restarting\"}".getBytes());
                    pool.execute(() -> {
                        try {
                            Thread.sleep(300);
                            execCmd("nohup /data/adb/modules/tesla_carwith_box/scripts/server.sh start " + port + " >/dev/null 2>&1 &");
                            System.exit(0);
                        } catch (Exception ignored) {}
                    });
                } else if (path.equals("/api/unblock")) {
                    unblockHotspot();
                    sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}".getBytes());
                } else if (path.equals("/api/vehicle_name")) {
                    String name = params.getOrDefault("name", "Xiaomi SU7-Tesla");
                    execCmd("echo '" + name + "' > /data/local/tmp/tesla_vehicle_name; chmod 0666 /data/local/tmp/tesla_vehicle_name");
                    execCmd("mkdir -p /data/adb/tesla_box; echo 'VEHICLE_NAME=\"" + name + "\"' >> /data/adb/tesla_box/config.env");
                    sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}".getBytes());
                } else if (path.equals("/api/audio")) {
                    String mode = params.getOrDefault("mode", "native");
                    execCmd("mkdir -p /data/adb/tesla_box; echo '" + mode + "' > /data/adb/tesla_box/audio_mode; echo '" + mode + "' > /data/local/tmp/carwith_audio_mode; echo 'AUDIO_MODE=\"" + mode + "\"' >> /data/adb/tesla_box/config.env");
                    updateBdcfAudioMode(mode);
                    sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}".getBytes());
                } else if (path.equals("/api/res")) {
                    String res = params.getOrDefault("val", "1280x720");
                    execCmd("mkdir -p /data/adb/tesla_box; echo '" + res + "' > /data/adb/tesla_box/resolution; echo 'RES=\"" + res + "\"' >> /data/adb/tesla_box/config.env");
                    sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}".getBytes());
                } else if (path.equals("/api/start_carlife") || path.equals("/api/launch_carwith")) {
                    pool.execute(() -> {
                        try {
                            execCmd("am start -n com.miui.carlink/com.carwith.launcher.settings.phone.UCarConnectInfoActivity >/dev/null 2>&1 || am start -a miui.intent.action.UCAR_CONNECT_SETTINGS >/dev/null 2>&1");
                        } catch (Exception ignored) {}
                    });
                    sendResponse(out, 200, "application/json", "{\"status\":\"ok\",\"msg\":\"carwith_opened\"}".getBytes());
                } else if (path.equals("/api/exec")) {
                    String cmd = params.getOrDefault("cmd", "");
                    String res = "";
                    if (!cmd.isEmpty()) {
                        res = execCmd(cmd);
                    }
                    sendResponse(out, 200, "text/plain; charset=utf-8", res.getBytes("UTF-8"));
                } else if (path.equals("/api/touch") || path.equals("/cgi-bin/touch")) {
                    dispatchTouch(params);
                    sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}".getBytes());
                } else if (path.equals("/api/audio.pcm") || path.equals("/audio.pcm") || path.equals("/cgi-bin/audio.pcm")) {
                    cl.setSoTimeout(0);
                    streamPcmAudio(cl, out);
                } else if (path.equals("/api/audio.wav") || path.equals("/audio.wav") || path.equals("/cgi-bin/audio.wav")) {
                    cl.setSoTimeout(0);
                    streamWavAudio(cl, out);
                } else if (path.equals("/api/stream") || path.equals("/cgi-bin/stream")) {
                    cl.setSoTimeout(0);
                    streamMjpeg(cl, out);
                } else {
                    File target = new File(webroot, path.startsWith("/") ? path.substring(1) : path);
                    if (target.exists() && target.isFile()) {
                        String mime = "text/plain";
                        if (path.endsWith(".html")) mime = "text/html; charset=utf-8";
                        else if (path.endsWith(".js")) mime = "application/javascript";
                        else if (path.endsWith(".css")) mime = "text/css";
                        else if (path.endsWith(".png")) mime = "image/png";
                        else if (path.endsWith(".svg")) mime = "image/svg+xml";
                        serveFile(out, target, mime);
                    } else {
                        sendResponse(out, 404, "text/plain", "Not Found".getBytes());
                    }
                }
                try {
                    out.flush();
                    cl.shutdownOutput();
                } catch (Exception ignored) {}
            }
        } catch (Throwable t) {
            // connection reset or closed by client
        }
    }

    private static void dispatchTouch(Map<String, String> params) {
        String action = params.getOrDefault("action", "tap");
        int x = 0, y = 0;
        try { x = (int) Double.parseDouble(params.getOrDefault("x", "0")); } catch (Exception ignored) {}
        try { y = (int) Double.parseDouble(params.getOrDefault("y", "0")); } catch (Exception ignored) {}

        String dispOpt = "";
        String dId = activeDisplayId;
        if (dId == null || dId.isEmpty() || dId.equals("0")) {
            dId = findLogicalDisplayId();
            if (dId != null && !dId.isEmpty() && !dId.equals("0")) {
                activeDisplayId = dId;
            }
        }
        if (dId != null && !dId.isEmpty() && !dId.equals("0")) {
            dispOpt = "-d " + dId + " ";
        }

        if ("tap".equals(action)) {
            final int fx = x, fy = y;
            final String fOpt = dispOpt;
            pool.execute(() -> execCmd("input " + fOpt + "tap " + fx + " " + fy));
        } else if ("down".equals(action)) {
            final int fx = x, fy = y;
            final String fOpt = dispOpt;
            pool.execute(() -> execCmd("input " + fOpt + "motionevent DOWN " + fx + " " + fy));
        } else if ("move".equals(action)) {
            final int fx = x, fy = y;
            final String fOpt = dispOpt;
            pool.execute(() -> execCmd("input " + fOpt + "motionevent MOVE " + fx + " " + fy));
        } else if ("up".equals(action)) {
            final int fx = x, fy = y;
            final String fOpt = dispOpt;
            pool.execute(() -> execCmd("input " + fOpt + "motionevent UP " + fx + " " + fy));
        } else if ("swipe".equals(action)) {
            int x1 = x, y1 = y;
            int x2 = 0, y2 = 0;
            try { x2 = (int) Double.parseDouble(params.getOrDefault("x2", "0")); } catch (Exception ignored) {}
            try { y2 = (int) Double.parseDouble(params.getOrDefault("y2", "0")); } catch (Exception ignored) {}
            int dur = 250;
            try { dur = Integer.parseInt(params.getOrDefault("dur", "250")); } catch (Exception ignored) {}

            final int fx1 = x1, fy1 = y1, fx2 = x2, fy2 = y2, fdur = dur;
            final String fOpt = dispOpt;
            pool.execute(() -> execCmd("input " + fOpt + "swipe " + fx1 + " " + fy1 + " " + fx2 + " " + fy2 + " " + fdur));
        } else if ("back".equals(action)) {
            pool.execute(() -> execCmd("input keyevent 4"));
        } else if ("home".equals(action)) {
            pool.execute(() -> execCmd("input keyevent 3"));
        }
    }

    private static void streamMjpeg(Socket cl, OutputStream out) {
        activeStreamClients.incrementAndGet();
        try {
            out.write(("HTTP/1.1 200 OK\r\n" +
                       "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                       "Cache-Control: no-cache, private\r\n" +
                       "Pragma: no-cache\r\n" +
                       "Connection: close\r\n" +
                       "Access-Control-Allow-Origin: *\r\n\r\n").getBytes());
            out.flush();

            byte[] lastSent = null;
            while (cl != null && !cl.isClosed()) {
                byte[] frame;
                synchronized (frameLock) {
                    while (latestJpegFrame == null || latestJpegFrame == lastSent) {
                        frameLock.wait(100);
                        if (cl.isClosed()) return;
                    }
                    frame = latestJpegFrame;
                }

                if (frame != null && frame.length > 0) {
                    out.write(("--frame\r\n" +
                               "Content-Type: image/png\r\n" +
                               "Content-Length: " + frame.length + "\r\n\r\n").getBytes());
                    out.write(frame);
                    out.write("\r\n".getBytes());
                    out.flush();
                    lastSent = frame;
                }
                Thread.sleep(25);
            }
        } catch (Exception ignored) {
        } finally {
            activeStreamClients.decrementAndGet();
        }
    }

    // =========================================================================
    // Web 音频流服务与 UDP PCM 接收网关
    // =========================================================================
    public static void updateBdcfAudioMode(String mode) {
        int val = (mode != null && (mode.contains("virtual") || mode.contains("web") || mode.equals("0"))) ? 0 : 1;
        String script = "if [ -f /data/local/tmp/bdcf ]; then " +
                        "  sed -i 's/^AUDIO_TRANSMISSION_MODE=.*/AUDIO_TRANSMISSION_MODE=" + val + "/g' /data/local/tmp/bdcf; " +
                        "  sed -i 's/^AUDIO_TTS_REQUEST_FOCUS=.*/AUDIO_TTS_REQUEST_FOCUS=false/g' /data/local/tmp/bdcf; " +
                        "  sed -i 's/^VOICE_MIC=.*/VOICE_MIC=1/g' /data/local/tmp/bdcf; " +
                        "else " +
                        "  echo 'AUDIO_TRANSMISSION_MODE=" + val + "' > /data/local/tmp/bdcf; " +
                        "  echo 'AUDIO_TTS_REQUEST_FOCUS=false' >> /data/local/tmp/bdcf; " +
                        "  echo 'VOICE_MIC=1' >> /data/local/tmp/bdcf; " +
                        "fi; " +
                        "chmod 0666 /data/local/tmp/bdcf 2>/dev/null";
        execCmd(script);
        System.out.println("[AudioMode] Synced bdcf configuration (AUDIO_TRANSMISSION_MODE=" + val + ")");
    }

    private static void startAudioReceiver() {
        pool.execute(() -> {
            byte[] buf = new byte[4096];
            try (DatagramSocket socket = new DatagramSocket(UDP_AUDIO_PORT)) {
                System.out.println("[AudioReceiver] Listening for CarLife PCM audio on UDP port " + UDP_AUDIO_PORT);
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    int len = packet.getLength();
                    if (len > 0 && !activeAudioQueues.isEmpty()) {
                        byte[] pcmData = Arrays.copyOf(packet.getData(), len);
                        for (BlockingQueue<byte[]> q : activeAudioQueues) {
                            if (q.size() > 50) {
                                q.poll(); // drop oldest to avoid high latency / buffer bloat
                            }
                            q.offer(pcmData);
                        }
                    }
                }
            } catch (Throwable t) {
                System.err.println("[AudioReceiver] Error: " + t.getMessage());
            }
        });
    }

    private static void streamPcmAudio(Socket cl, OutputStream out) {
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(100);
        activeAudioQueues.add(queue);
        try {
            out.write(("HTTP/1.1 200 OK\r\n" +
                       "Content-Type: application/octet-stream\r\n" +
                       "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                       "Pragma: no-cache\r\n" +
                       "Connection: close\r\n" +
                       "Access-Control-Allow-Origin: *\r\n\r\n").getBytes());
            out.flush();

            while (cl != null && !cl.isClosed()) {
                byte[] chunk = queue.poll(500, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    out.write(chunk);
                    out.flush();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            activeAudioQueues.remove(queue);
        }
    }

    private static void streamWavAudio(Socket cl, OutputStream out) {
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(100);
        activeAudioQueues.add(queue);
        try {
            out.write(("HTTP/1.1 200 OK\r\n" +
                       "Content-Type: audio/wav\r\n" +
                       "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                       "Pragma: no-cache\r\n" +
                       "Connection: close\r\n" +
                       "Access-Control-Allow-Origin: *\r\n\r\n").getBytes());

            // 44-byte WAV header (44100Hz, 16bit, 2 channels)
            byte[] wavHeader = createWavHeader(44100, 2, 16);
            out.write(wavHeader);
            out.flush();

            // 50ms silence chunk: 44100 * 2 channels * 2 bytes * 0.05s = 8820 bytes
            byte[] silenceChunk = new byte[8820];

            while (cl != null && !cl.isClosed()) {
                byte[] chunk = queue.poll(50, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    out.write(chunk);
                    out.flush();
                } else {
                    out.write(silenceChunk);
                    out.flush();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            activeAudioQueues.remove(queue);
        }
    }

    private static byte[] createWavHeader(int sampleRate, int channels, int bitsPerSample) {
        byte[] header = new byte[44];
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        int totalDataLen = 0x7FFFF000;
        int totalLen = totalDataLen + 36;

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalLen & 0xff);
        header[5] = (byte) ((totalLen >> 8) & 0xff);
        header[6] = (byte) ((totalLen >> 16) & 0xff);
        header[7] = (byte) ((totalLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0; // PCM
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) blockAlign; header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalDataLen & 0xff);
        header[41] = (byte) ((totalDataLen >> 8) & 0xff);
        header[42] = (byte) ((totalDataLen >> 16) & 0xff);
        header[43] = (byte) ((totalDataLen >> 24) & 0xff);
        return header;
    }

    private static void serveFile(OutputStream out, File f, String mime) throws IOException {
        if (!f.exists()) {
            sendResponse(out, 404, "text/plain", "File Not Found".getBytes());
            return;
        }
        byte[] bytes = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int read = 0;
            while (read < bytes.length) {
                int n = fis.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        sendResponse(out, 200, mime, bytes);
    }

    private static void sendResponse(OutputStream out, int status, String mime, byte[] data) throws IOException {
        String res = "HTTP/1.1 " + status + " OK\r\n" +
                     "Content-Type: " + mime + "\r\n" +
                     "Content-Length: " + data.length + "\r\n" +
                     "Connection: close\r\n" +
                     "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(res.getBytes());
        out.write(data);
        out.flush();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String val = pair.substring(idx + 1);
                try {
                    val = URLDecoder.decode(val, "UTF-8");
                } catch (Exception ignored) {}
                map.put(key, val);
            }
        }
        return map;
    }

    // =========================================================================
    // DNS Virtualization for Tesla Browser (Disguise private IP as t.carwith.com)
    // =========================================================================
    private static final int DNS_PORT = 5354;

    private static void startDnsServer() {
        pool.execute(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", DNS_PORT));
                System.out.println("[DnsServer] Ready and listening on 0.0.0.0:" + DNS_PORT + " (Tesla t.carwith.com Resolver)");

                byte[] buffer = new byte[2048];
                while (!socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    final DatagramSocket currentSocket = socket;
                    final byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    final SocketAddress clientAddr = packet.getSocketAddress();
                    pool.execute(() -> handleDnsQuery(currentSocket, data, clientAddr));
                }
            } catch (Throwable t) {
                System.err.println("[DnsServer] Failed to bind/run DNS on port " + DNS_PORT + ": " + t.getMessage());
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        });
    }

    private static void handleDnsQuery(DatagramSocket socket, byte[] data, SocketAddress clientAddr) {
        try {
            if (data == null || data.length < 12) return;

            int qdCount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            if (qdCount <= 0) return;

            // Parse Question section
            int idx = 12;
            StringBuilder qnameSb = new StringBuilder();
            while (idx < data.length && data[idx] != 0) {
                int len = data[idx] & 0xFF;
                if ((len & 0xC0) == 0xC0) {
                    idx += 2;
                    break;
                }
                idx++;
                if (idx + len > data.length) break;
                if (qnameSb.length() > 0) qnameSb.append(".");
                qnameSb.append(new String(data, idx, len, java.nio.charset.StandardCharsets.US_ASCII));
                idx += len;
            }
            if (idx < data.length && data[idx] == 0) {
                idx++; // skip null terminator
            }

            int qType = 0;
            if (idx + 2 <= data.length) {
                qType = ((data[idx] & 0xFF) << 8) | (data[idx + 1] & 0xFF);
            }
            int qEnd = Math.min(idx + 4, data.length);

            String domain = qnameSb.toString().toLowerCase(Locale.ROOT);

            // Check if domain matches t.carwith.com
            boolean isCarwith = domain.equals("t.carwith.com") ||
                                domain.endsWith(".carwith.com") ||
                                domain.equals("carwith.com") ||
                                domain.contains("carwith") ||
                                domain.contains("tespush");

            if (isCarwith) {
                InetAddress addr;
                try {
                    addr = InetAddress.getByName(VIRTUAL_PUBLIC_IP);
                } catch (Exception e) {
                    addr = InetAddress.getByName("101.200.208.6");
                }
                byte[] ipBytes = addr.getAddress();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                // 1. Transaction ID
                baos.write(data[0]);
                baos.write(data[1]);
                // 2. Flags: 0x8180 (Standard response, no error)
                baos.write(0x81);
                baos.write(0x80);
                // 3. QDCOUNT: 1
                baos.write(0x00);
                baos.write(0x01);

                if (qType == 28) { // AAAA query
                    // Return 0 answers, NOERROR
                    baos.write(0x00); baos.write(0x00); // ANCOUNT: 0
                    baos.write(0x00); baos.write(0x00); // NSCOUNT: 0
                    baos.write(0x00); baos.write(0x00); // ARCOUNT: 0
                    // Question
                    baos.write(data, 12, qEnd - 12);
                } else {
                    // Type A (or others): Return 1 answer
                    baos.write(0x00); baos.write(0x01); // ANCOUNT: 1
                    baos.write(0x00); baos.write(0x00); // NSCOUNT: 0
                    baos.write(0x00); baos.write(0x00); // ARCOUNT: 0
                    // Question
                    baos.write(data, 12, qEnd - 12);
                    // Answer
                    baos.write(0xC0); baos.write(0x0C); // NAME pointer to offset 12
                    baos.write(0x00); baos.write(0x01); // TYPE = A (1)
                    baos.write(0x00); baos.write(0x01); // CLASS = IN (1)
                    baos.write(0x00); baos.write(0x00); baos.write(0x00); baos.write(0x3C); // TTL = 60s
                    baos.write(0x00); baos.write(0x04); // RDLENGTH = 4
                    baos.write(ipBytes);                // RDATA
                }

                byte[] resp = baos.toByteArray();
                DatagramPacket respPacket = new DatagramPacket(resp, resp.length, clientAddr);
                socket.send(respPacket);
                System.out.println("[DnsServer] Resolved " + domain + " -> " + addr.getHostAddress() + " for " + clientAddr);
            } else {
                // Forward other queries upstream to preserve normal internet
                forwardDnsQuery(socket, data, clientAddr);
            }
        } catch (Throwable ignored) {}
    }

    private static void forwardDnsQuery(DatagramSocket localSocket, byte[] queryData, SocketAddress clientAddr) {
        pool.execute(() -> {
            DatagramSocket forwardSocket = null;
            try {
                forwardSocket = new DatagramSocket();
                forwardSocket.setSoTimeout(1200);

                List<String> upstreams = getUpstreamDnsList();
                for (String upstreamIp : upstreams) {
                    try {
                        InetAddress upstream = InetAddress.getByName(upstreamIp);
                        DatagramPacket outPacket = new DatagramPacket(queryData, queryData.length, upstream, 53);
                        forwardSocket.send(outPacket);

                        byte[] buf = new byte[2048];
                        DatagramPacket inPacket = new DatagramPacket(buf, buf.length);
                        forwardSocket.receive(inPacket);

                        DatagramPacket replyPacket = new DatagramPacket(
                            inPacket.getData(), inPacket.getOffset(), inPacket.getLength(), clientAddr
                        );
                        localSocket.send(replyPacket);
                        return;
                    } catch (Exception ignored) {
                        // 尝试下一个上游 DNS
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (forwardSocket != null && !forwardSocket.isClosed()) {
                    forwardSocket.close();
                }
            }
        });
    }

    private static List<String> getUpstreamDnsList() {
        List<String> list = new ArrayList<>();
        try {
            String[] props = {"net.dns1", "net.dns2", "net.rmnet0.dns1", "net.wlan0.dns1"};
            for (String p : props) {
                String val = execCmd("getprop " + p);
                if (val != null && !val.trim().isEmpty() && !val.startsWith("127.") && !list.contains(val.trim())) {
                    list.add(val.trim());
                }
            }
        } catch (Throwable ignored) {}
        if (!list.contains("223.5.5.5")) list.add("223.5.5.5");
        if (!list.contains("119.29.29.29")) list.add("119.29.29.29");
        if (!list.contains("180.76.76.76")) list.add("180.76.76.76");
        if (!list.contains("114.114.114.114")) list.add("114.114.114.114");
        return list;
    }
}
