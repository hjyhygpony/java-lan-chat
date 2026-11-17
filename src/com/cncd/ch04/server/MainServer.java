package com.cncd.ch04.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 聊天服务器入口。服务器只负责 accept，新连接交给 ConnectionKeeper；
 * 每个连接内部再创建独立的发送线程和接收线程。
 */
public final class MainServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 3500;
    public static volatile long uptime;
    private static final AtomicLong CONNECTS = new AtomicLong();

    private final int requestedPort;
    private final Path dataDirectory;
    private final String bindAddress;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    public ConnectionKeeper ck;
    public static DataSource ds;
    public static CommandParser cp;

    public MainServer(int port) {
        this(port, Paths.get("data"), "0.0.0.0");
    }

    public MainServer(int port, Path dataDirectory) {
        this(port, dataDirectory, "0.0.0.0");
    }

    public MainServer(int port, Path dataDirectory, String bindAddress) {
        this.requestedPort = port;
        this.dataDirectory = dataDirectory == null ? Paths.get("data") : dataDirectory;
        this.bindAddress = bindAddress == null || bindAddress.trim().isEmpty()
                ? "0.0.0.0" : bindAddress.trim();
    }

    public synchronized void start() throws IOException {
        if (running) return;

        ds = new FileDataSource(dataDirectory);
        cp = new BroadcastCommandParser();
        cp.setDataSource(ds);
        ck = new ConnectionKeeper(cp);

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, requestedPort), 100);
        uptime = System.currentTimeMillis();
        running = true;

        acceptThread = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "main-server-accept");
        acceptThread.start();
        printStartupInfo();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                ck.add(socket);
            } catch (IOException e) {
                if (running) {
                    System.err.println("接收客户端连接失败：" + e.getMessage());
                }
            }
        }
    }

    private void printStartupInfo() {
        System.out.println("============================================");
        System.out.println("聊天服务器已启动（参考程序实现方式版）");
        System.out.println("实现：LinkedList连接池 + 每客户端收/发双线程");
        System.out.println("协议：0xFD命令前缀 + 0xFF消息结束符");
        System.out.println("绑定地址：" + bindAddress);
        System.out.println("监听端口：" + getPort());
        try {
            System.out.println("本机地址：" + InetAddress.getLocalHost().getHostAddress());
        } catch (Exception ignored) {
            System.out.println("本机地址：请执行 ipconfig 查看");
        }
        System.out.println("数据目录：" + dataDirectory.toAbsolutePath());
        System.out.println("按 Ctrl+C 停止服务器");
        System.out.println("============================================");
    }

    public int getPort() {
        return serverSocket == null ? requestedPort : serverSocket.getLocalPort();
    }

    public static void incrementConnects() {
        CONNECTS.incrementAndGet();
    }

    public static long getConnects() {
        return CONNECTS.get();
    }

    @Override
    public synchronized void close() {
        running = false;
        if (ck != null) {
            for (ConnectedClient client : ck.snapshot()) {
                client.forceClose();
            }
        }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        if (acceptThread != null && acceptThread != Thread.currentThread()) {
            try { acceptThread.join(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 参数：端口、数据目录、绑定地址。 */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        Path dataDir = Paths.get("data");
        String bind = "0.0.0.0";
        if (args.length >= 1) {
            try { port = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) {
                System.err.println("端口格式错误，使用默认端口3500。 ");
            }
        }
        if (args.length >= 2) dataDir = Paths.get(args[1]);
        if (args.length >= 3) bind = args[2];

        final MainServer server = new MainServer(port, dataDir, bind);
        try {
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override public void run() { server.close(); }
            }));
        } catch (IOException e) {
            System.err.println("服务器启动失败：" + e.getMessage());
            System.exit(1);
        }
    }
}
