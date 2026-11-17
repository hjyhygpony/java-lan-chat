package com.cncd.ch04.server;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 连接池实现：使用 LinkedList 保存每个 ConnectedClient，
 * 并由连接池负责广播、私聊查找和命令转发。
 */
public final class ConnectionKeeper {
    private final LinkedList<ConnectedClient> clientList = new LinkedList<ConnectedClient>();
    private final CommandParser commandParser;

    public ConnectionKeeper(CommandParser commandParser) {
        this.commandParser = commandParser;
    }

    public ConnectedClient add(Socket socket) throws IOException {
        ConnectedClient client = new ConnectedClient(socket, this);
        synchronized (clientList) {
            clientList.addLast(client);
        }
        MainServer.incrementConnects();
        client.start();
        return client;
    }

    public void remove(ConnectedClient client) {
        boolean removed;
        synchronized (clientList) {
            removed = clientList.remove(client);
        }
        if (removed) {
            commandParser.clientDisconnected(client);
        }
    }

    public boolean assignNick(ConnectedClient client, String nick) {
        synchronized (clientList) {
            for (ConnectedClient current : clientList) {
                if (current != client && nick.equalsIgnoreCase(current.getUsername())) {
                    return false;
                }
            }
            client.setUsername(nick);
            return true;
        }
    }

    public void releaseNick(ConnectedClient client) {
        client.setUsername(null);
    }

    public ConnectedClient find(String user) {
        if (user == null) return null;
        synchronized (clientList) {
            for (ConnectedClient client : clientList) {
                if (user.equalsIgnoreCase(client.getUsername())) {
                    return client;
                }
            }
        }
        return null;
    }

    public boolean isOnline(String user) {
        return find(user) != null;
    }

    public List<ConnectedClient> snapshot() {
        synchronized (clientList) {
            return new ArrayList<ConnectedClient>(clientList);
        }
    }

    public List<String> onlineUsers() {
        List<String> names = new ArrayList<String>();
        for (ConnectedClient client : snapshot()) {
            if (client.getUsername() != null) {
                names.add(client.getUsername());
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public void runCommand(ConnectedClient client, String commandLine) throws IOException {
        commandParser.runCommand(client, commandLine);
    }

    public void runMessage(ConnectedClient client, String message) {
        commandParser.runMessage(client, message);
    }

    public void sendTo(ConnectedClient sender, String user, String message) {
        ConnectedClient receiver = find(user);
        if (receiver == null) {
            sender.sendError("用户“" + user + "”当前不在线。 ");
            return;
        }
        long now = System.currentTimeMillis();
        receiver.sendPrivateMessage(sender.getUsername(), message, now);
        if (receiver != sender) {
            sender.sendPrivateMessage("我 -> " + receiver.getUsername(), message, now);
        }
    }

    public void broadcastPublic(String from, String message) {
        long now = System.currentTimeMillis();
        for (ConnectedClient client : snapshot()) {
            if (client.getUsername() != null) {
                client.sendPublicMessage(from, message, now);
            }
        }
    }

    public void broadcastSystem(String message) {
        for (ConnectedClient client : snapshot()) {
            if (client.getUsername() != null) {
                client.sendSystemMessage(message);
            }
        }
    }

    public void broadcastUserList() {
        List<String> users = onlineUsers();
        for (ConnectedClient client : snapshot()) {
            if (client.getUsername() != null) {
                client.sendUserList(users);
            }
        }
    }

    public Map<String, Boolean> friendStatus(Iterable<String> friends) {
        Map<String, Boolean> result = new LinkedHashMap<String, Boolean>();
        for (String friend : friends) {
            result.put(friend, Boolean.valueOf(isOnline(friend)));
        }
        return result;
    }
}
