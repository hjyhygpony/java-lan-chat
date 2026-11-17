package com.cncd.ch04.server;

import com.cncd.ch04.common.Protocol;
import com.cncd.ch04.common.UserProfile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每个客户端连接由两个线程维护：ServerMsgListener 负责接收，
 * ServerMsgSender 负责从消息队列取出数据并发送。
 */
public final class ConnectedClient {
    private final ConnectionKeeper connectionKeeper;
    private final Socket socket;
    private final ServerMsgSender messageSender;
    private final ServerMsgListener messageListener;
    private final AtomicBoolean removed = new AtomicBoolean(false);

    private volatile String username;
    public final Date connectedTime = new Date();
    public final String ipNumber;
    public final int portNumber;

    ConnectedClient(Socket socket, ConnectionKeeper connectionKeeper) throws IOException {
        this.socket = socket;
        this.connectionKeeper = connectionKeeper;
        this.ipNumber = socket.getInetAddress().getHostAddress();
        this.portNumber = socket.getPort();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        this.messageSender = new ServerMsgSender(socket, this);
        this.messageListener = new ServerMsgListener(socket, this);
    }

    void start() {
        messageSender.start();
        messageListener.start();
    }

    public ConnectionKeeper getConnectionKeeper() {
        return connectionKeeper;
    }

    public String getUsername() {
        return username;
    }

    public String getNick() {
        return username == null ? String.valueOf(portNumber) : username;
    }

    void setUsername(String username) {
        this.username = username;
    }

    public String getRemoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    public void runCommand(String commandLine) throws IOException {
        connectionKeeper.runCommand(this, commandLine);
    }

    public void broadcastMessage(String message) {
        connectionKeeper.runMessage(this, message);
    }

    public void sendTo(String user, String message) {
        connectionKeeper.sendTo(this, user, message);
    }

    private void sendCommand(String commandLine) {
        try {
            messageSender.addCommand(commandLine);
        } catch (IOException e) {
            dropClient();
        }
    }

    public void sendLoginOk(String user) {
        sendCommand(Protocol.command(Protocol.S_LOGIN_OK, user));
    }

    public void sendError(String message) {
        sendCommand(Protocol.command(Protocol.S_ERROR, message));
    }

    public void sendSystemMessage(String message) {
        sendCommand(Protocol.command(Protocol.S_SYSTEM_MESSAGE, message));
    }

    public void sendUserList(List<String> users) {
        sendCommand(Protocol.command(Protocol.S_USER_LIST, users.toArray(new String[users.size()])));
    }

    public void sendPublicMessage(String from, String message, long timestamp) {
        sendCommand(Protocol.command(Protocol.S_PUBLIC_MESSAGE,
                from, message, String.valueOf(timestamp)));
    }

    public void sendPrivateMessage(String from, String message, long timestamp) {
        sendCommand(Protocol.command(Protocol.S_PRIVATE_MESSAGE,
                from, message, String.valueOf(timestamp)));
    }

    public void sendFriendList(Map<String, Boolean> friends) {
        LinkedList<String> args = new LinkedList<String>();
        for (Map.Entry<String, Boolean> entry : friends.entrySet()) {
            args.add(entry.getKey());
            args.add(entry.getValue().booleanValue() ? "1" : "0");
        }
        sendCommand(Protocol.command(Protocol.S_FRIEND_LIST,
                args.toArray(new String[args.size()])));
    }

    public void sendFriendStatus(String friend, boolean online, String note) {
        sendCommand(Protocol.command(Protocol.S_FRIEND_STATUS,
                friend, online ? "1" : "0", note == null ? "" : note));
    }

    public void sendProfile(UserProfile profile) {
        if (profile == null) return;
        sendCommand(Protocol.command(Protocol.S_PROFILE,
                profile.getUsername(), profile.getDisplayName(), profile.getSignature()));
    }

    public void sendFile(String from, String fileName, byte[] bytes) {
        String command = Protocol.encodedCommand(Protocol.S_FILE_TRANSFER,
                Protocol.encodeText(from),
                Protocol.encodeText(fileName),
                Protocol.encodeBytes(bytes));
        sendCommand(command);
    }

    public void sendHeartbeat() {
        sendCommand(Protocol.command(Protocol.S_HEARTBEAT,
                String.valueOf(System.currentTimeMillis())));
    }

    /** 先发送队列中剩余消息，再关闭连接，避免错误提示尚未送达就断开。 */
    public void closeConnection() {
        messageListener.requestStop();
        messageSender.closeAfterDrain();
    }

    /** 从连接池移除，仅执行一次。 */
    public void dropClient() {
        if (!removed.compareAndSet(false, true)) {
            return;
        }
        messageListener.requestStop();
        messageSender.closeAfterDrain();
        connectionKeeper.remove(this);
    }

    void forceClose() {
        if (removed.compareAndSet(false, true)) {
            connectionKeeper.remove(this);
        }
        messageListener.requestStop();
        messageSender.forceClose();
        try { socket.close(); } catch (IOException ignored) {}
    }
}

/** 服务器端发送线程：LinkedList 消息队列 + 独立线程。 */
final class ServerMsgSender extends Thread {
    private final Socket socket;
    private final ConnectedClient client;
    private final LinkedList<OutboundMessage> messages = new LinkedList<OutboundMessage>();
    private volatile boolean accepting = true;
    private volatile boolean forceClosed;

    ServerMsgSender(Socket socket, ConnectedClient client) {
        super("server-sender-" + socket.getRemoteSocketAddress());
        this.socket = socket;
        this.client = client;
        setDaemon(true);
    }

    synchronized void addCommand(String command) throws IOException {
        if (!accepting || forceClosed) {
            throw new IOException("连接已经关闭。 ");
        }
        messages.addLast(new OutboundMessage(command, true));
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
        try {
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            while (!forceClosed) {
                OutboundMessage message;
                synchronized (this) {
                    while (messages.isEmpty() && accepting && !forceClosed) {
                        wait();
                    }
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
            if (!forceClosed) {
                System.out.println("向客户端发送消息失败 " + client.getRemoteAddress() + "：" + e.getMessage());
            }
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            client.dropClient();
        }
    }
}

/** 服务器端接收线程：逐字节读取，0xFF 表示一条消息结束。 */
final class ServerMsgListener extends Thread {
    private final Socket socket;
    private final ConnectedClient client;
    private volatile boolean running = true;

    ServerMsgListener(Socket socket, ConnectedClient client) {
        super("server-listener-" + socket.getRemoteSocketAddress());
        this.socket = socket;
        this.client = client;
        setDaemon(true);
    }

    void requestStop() {
        running = false;
    }

    @Override
    public void run() {
        try {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            while (running && !socket.isClosed()) {
                Protocol.Frame frame = Protocol.readFrame(input);
                if (frame.isCommand()) {
                    client.runCommand(frame.getPayload());
                } else if (!frame.getPayload().trim().isEmpty()) {
                    client.broadcastMessage(frame.getPayload());
                }
            }
        } catch (SocketException e) {
            if (running && !socket.isClosed()) {
                System.out.println("客户端连接重置 " + client.getRemoteAddress() + "：" + e.getMessage());
            }
        } catch (IOException e) {
            if (running && !socket.isClosed()) {
                System.out.println("读取客户端消息失败 " + client.getRemoteAddress() + "：" + e.getMessage());
            }
        } finally {
            client.dropClient();
        }
    }
}

final class OutboundMessage {
    final String payload;
    final boolean command;

    OutboundMessage(String payload, boolean command) {
        this.payload = payload;
        this.command = command;
    }
}
