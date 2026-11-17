package com.cncd.ch04.client;

import com.cncd.ch04.common.Protocol;
import com.cncd.ch04.common.UserProfile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端网络核心，改为给定聊天工具的实现方式：
 * 一个 ClientMsgSender 线程维护发送队列，一个 ClientMsgListener 线程持续接收；
 * 普通消息以 0xFF 结束，命令在消息前增加 0xFD。
 */
public final class ClientKernel implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int HEARTBEAT_INITIAL_DELAY_SECONDS = 20;
    private static final int HEARTBEAT_PERIOD_SECONDS = 25;

    public interface Listener {
        void onLoginSuccess(String username);
        void onRegisterSuccess(String username);
        void onError(String message);
        void onSystemMessage(String message);
        void onUserList(List<String> users);
        void onPublicMessage(String from, String message, long timestamp);
        void onPrivateMessage(String from, String message, long timestamp);
        void onFriendList(Map<String, Boolean> friends);
        void onFriendStatus(String friend, boolean online, String note);
        void onProfile(UserProfile profile);
        void onFileReceived(String from, String fileName, byte[] data);
        void onDisconnected(String reason);
    }

    private final Listener listener;
    private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile boolean userClosing;
    private Socket socket;
    private ClientMsgSender messageSender;
    private ClientMsgListener messageListener;
    private ScheduledExecutorService heartbeatExecutor;

    public ClientKernel(Listener listener) {
        this.listener = listener;
    }

    public void connect(String host, int port, String username) throws IOException {
        if (running) throw new IOException("客户端已经连接。 ");

        Socket newSocket = new Socket();
        try {
            newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            newSocket.setTcpNoDelay(true);
            newSocket.setKeepAlive(true);

            socket = newSocket;
            running = true;
            userClosing = false;
            disconnectNotified.set(false);
            messageSender = new ClientMsgSender(this, newSocket);
            messageListener = new ClientMsgListener(this, newSocket);
            messageSender.start();
            messageListener.start();

            // 连接已建立，登录或注册由调用者通过 login()/register() 触发
            startHeartbeat();
        } catch (IOException e) {
            running = false;
            try { newSocket.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /** 发送登录命令（连接建立后调用）。 */
    public void login(String username, String password) throws IOException {
        ensureConnected();
        String passwordHash = Protocol.hashPassword(password);
        sendCommand(Protocol.encodedCommand(Protocol.C_LOGIN,
                Protocol.encodeText(username), Protocol.encodeText(passwordHash)));
    }

    /** 发送注册命令（连接建立后调用）。 */
    public void register(String username, String password) throws IOException {
        ensureConnected();
        String passwordHash = Protocol.hashPassword(password);
        sendCommand(Protocol.encodedCommand(Protocol.C_REGISTER,
                Protocol.encodeText(username), Protocol.encodeText(passwordHash)));
    }

    public boolean isConnected() {
        return running && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** 群聊使用普通文本帧，与参考程序的广播消息处理方式一致。 */
    public void sendPublicMessage(String message) throws IOException {
        ensureConnected();
        messageSender.addMessage(message);
    }

    public void sendPrivateMessage(String target, String message) throws IOException {
        sendCommand(Protocol.command(Protocol.C_PRIVATE_MESSAGE, target, message));
    }

    public void addFriend(String target) throws IOException {
        sendCommand(Protocol.command(Protocol.C_ADD_FRIEND, target));
    }

    public void updateProfile(String displayName, String signature) throws IOException {
        sendCommand(Protocol.command(Protocol.C_UPDATE_PROFILE, displayName, signature));
    }

    public void queryProfile(String target) throws IOException {
        sendCommand(Protocol.command(Protocol.C_QUERY_PROFILE, target));
    }

    public void sendFile(String target, File file) throws IOException {
        ensureConnected();
        long length = Files.size(file.toPath());
        if (length > Protocol.MAX_FILE_BYTES) {
            throw new IOException("文件不能超过" + (Protocol.MAX_FILE_BYTES / 1024 / 1024) + "MB。 ");
        }
        byte[] data = Files.readAllBytes(file.toPath());
        String command = Protocol.encodedCommand(Protocol.C_FILE_TRANSFER,
                Protocol.encodeText(target),
                Protocol.encodeText(file.getName()),
                Protocol.encodeBytes(data));
        sendCommand(command);
    }

    private void sendCommand(String commandLine) throws IOException {
        ensureConnected();
        messageSender.addCommand(commandLine);
    }

    private void ensureConnected() throws IOException {
        if (!isConnected() || messageSender == null) {
            throw new IOException("尚未连接服务器。 ");
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "client-heartbeat");
                thread.setDaemon(true);
                return thread;
            }
        });
        heartbeatExecutor.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                if (!running) return;
                try {
                    sendCommand(Protocol.command(Protocol.C_HEARTBEAT));
                } catch (IOException e) {
                    forceTransportClose("心跳发送失败：" + e.getMessage());
                }
            }
        }, HEARTBEAT_INITIAL_DELAY_SECONDS, HEARTBEAT_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    void handleFrame(Protocol.Frame frame) throws IOException {
        if (!frame.isCommand()) {
            listener.onSystemMessage(frame.getPayload());
            return;
        }
        Protocol.ParsedCommand command = Protocol.parseCommand(frame.getPayload());
        String name = command.getName();

        if (Protocol.S_LOGIN_OK.equals(name)) {
            listener.onLoginSuccess(command.text(0));
        } else if (Protocol.S_REGISTER_OK.equals(name)) {
            listener.onRegisterSuccess(command.text(0));
        } else if (Protocol.S_ERROR.equals(name)) {
            listener.onError(command.text(0));
        } else if (Protocol.S_SYSTEM_MESSAGE.equals(name)) {
            listener.onSystemMessage(command.text(0));
        } else if (Protocol.S_USER_LIST.equals(name)) {
            List<String> users = new ArrayList<String>();
            for (int i = 0; i < command.size(); i++) users.add(command.text(i));
            listener.onUserList(users);
        } else if (Protocol.S_PUBLIC_MESSAGE.equals(name)) {
            listener.onPublicMessage(command.text(0), command.text(1), parseLong(command.text(2)));
        } else if (Protocol.S_PRIVATE_MESSAGE.equals(name)) {
            listener.onPrivateMessage(command.text(0), command.text(1), parseLong(command.text(2)));
        } else if (Protocol.S_FRIEND_LIST.equals(name)) {
            Map<String, Boolean> friends = new LinkedHashMap<String, Boolean>();
            for (int i = 0; i + 1 < command.size(); i += 2) {
                friends.put(command.text(i), Boolean.valueOf("1".equals(command.text(i + 1))));
            }
            listener.onFriendList(friends);
        } else if (Protocol.S_FRIEND_STATUS.equals(name)) {
            listener.onFriendStatus(command.text(0), "1".equals(command.text(1)), command.text(2));
        } else if (Protocol.S_PROFILE.equals(name)) {
            listener.onProfile(new UserProfile(command.text(0), command.text(1), command.text(2)));
        } else if (Protocol.S_FILE_TRANSFER.equals(name)) {
            listener.onFileReceived(command.text(0), command.text(1),
                    Protocol.decodeBytes(command.encoded(2)));
        } else if (Protocol.S_HEARTBEAT.equals(name)) {
            // 收到服务器心跳响应即可，不需要显示。
        } else {
            listener.onError("收到未知服务器命令：" + name);
        }
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { return System.currentTimeMillis(); }
    }

    void transportClosed(String reason) {
        if (!disconnectNotified.compareAndSet(false, true)) return;
        running = false;
        stopHeartbeat();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        listener.onDisconnected(userClosing ? "已断开服务器连接。 " : reason);
    }

    void forceTransportClose(String reason) {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        transportClosed(reason);
    }

    @Override
    public void close() {
        if (!running) return;
        userClosing = true;
        stopHeartbeat();
        try {
            messageSender.addCommand(Protocol.command(Protocol.C_LOGOUT));
            messageSender.closeAfterDrain();
        } catch (IOException e) {
            forceTransportClose("连接已关闭。 ");
        }
    }
}

/** 客户端发送线程，采用参考程序的 LinkedList 消息队列。 */
final class ClientMsgSender extends Thread {
    private final ClientKernel kernel;
    private final Socket socket;
    private final LinkedList<ClientOutboundMessage> messages = new LinkedList<ClientOutboundMessage>();
    private volatile boolean accepting = true;
    private volatile boolean forceClosed;

    ClientMsgSender(ClientKernel kernel, Socket socket) {
        super("client-message-sender");
        this.kernel = kernel;
        this.socket = socket;
        setDaemon(true);
    }

    synchronized void addMessage(String message) throws IOException {
        add(new ClientOutboundMessage(message, false));
    }

    synchronized void addCommand(String command) throws IOException {
        add(new ClientOutboundMessage(command, true));
    }

    private void add(ClientOutboundMessage message) throws IOException {
        if (!accepting || forceClosed) throw new IOException("发送线程已经关闭。 ");
        messages.addLast(message);
        notifyAll();
    }

    synchronized void closeAfterDrain() {
        accepting = false;
        notifyAll();
    }

    synchronized void forceClose() {
        forceClosed = true;
        accepting = false;
        messages.clear();
        notifyAll();
        interrupt();
    }

    @Override
    public void run() {
        String reason = "与服务器的连接已关闭。 ";
        try {
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            while (!forceClosed) {
                ClientOutboundMessage message;
                synchronized (this) {
                    while (messages.isEmpty() && accepting && !forceClosed) wait();
                    if (forceClosed) break;
                    if (messages.isEmpty() && !accepting) break;
                    message = messages.removeFirst();
                }
                Protocol.writeFrame(output, message.payload, message.command);
            }
            output.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            reason = "发送数据失败：" + e.getMessage();
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            kernel.transportClosed(reason);
        }
    }
}

/** 客户端接收线程，持续读取以 0xFF 结束的消息。 */
final class ClientMsgListener extends Thread {
    private final ClientKernel kernel;
    private final Socket socket;
    private volatile boolean running = true;

    ClientMsgListener(ClientKernel kernel, Socket socket) {
        super("client-message-listener");
        this.kernel = kernel;
        this.socket = socket;
        setDaemon(true);
    }

    void requestStop() {
        running = false;
    }

    @Override
    public void run() {
        String reason = "服务器已关闭连接。 ";
        try {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            while (running && !socket.isClosed()) {
                kernel.handleFrame(Protocol.readFrame(input));
            }
        } catch (SocketException e) {
            reason = "网络连接中断：" + e.getMessage();
        } catch (IOException e) {
            reason = "接收数据失败：" + e.getMessage();
        } catch (RuntimeException e) {
            reason = "处理服务器消息失败：" + e.getMessage();
        } finally {
            kernel.transportClosed(reason);
        }
    }
}

final class ClientOutboundMessage {
    final String payload;
    final boolean command;

    ClientOutboundMessage(String payload, boolean command) {
        this.payload = payload;
        this.command = command;
    }
}
