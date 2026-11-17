package com.cncd.ch04.server;

import com.cncd.ch04.common.Protocol;
import com.cncd.ch04.common.UserProfile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 参考程序的命令解析器扩展版。普通文本负责群聊，0xFD 开头的命令负责
 * 昵称、私聊、好友、资料、图片/文件和退出等功能。
 */
public final class BroadcastCommandParser implements CommandParser {
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("[\\p{L}\\p{N}_-]{1," + Protocol.MAX_USERNAME_CHARS + "}");

    private DataSource dataSource;

    public BroadcastCommandParser() {
        System.out.println("BroadcastCommandParser：使用0xFD命令前缀和0xFF消息结束符");
    }

    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void runCommand(ConnectedClient client, String commandLine) throws IOException {
        if (dataSource == null) {
            client.sendError("服务器数据源尚未初始化。 ");
            return;
        }

        Protocol.ParsedCommand command;
        try {
            command = Protocol.parseCommand(commandLine);
        } catch (IOException e) {
            client.sendError(e.getMessage());
            return;
        }

        String name = command.getName();
        try {
            if (Protocol.C_LOGIN.equals(name)) {
                login(client, command.text(0), command.text(1));
                return;
            }
            if (Protocol.C_REGISTER.equals(name)) {
                register(client, command.text(0), command.text(1));
                return;
            }
            if (client.getUsername() == null) {
                client.sendError("请先登录或注册。");
                return;
            }

            if (Protocol.C_PRIVATE_MESSAGE.equals(name)) {
                privateMessage(client, command.text(0), command.text(1));
            } else if (Protocol.C_ADD_FRIEND.equals(name)) {
                addFriend(client, command.text(0));
            } else if (Protocol.C_UPDATE_PROFILE.equals(name)) {
                updateProfile(client, command.text(0), command.text(1));
            } else if (Protocol.C_QUERY_PROFILE.equals(name)) {
                queryProfile(client, command.text(0));
            } else if (Protocol.C_FILE_TRANSFER.equals(name)) {
                receiveFile(client, command);
            } else if (Protocol.C_USERS.equals(name)) {
                client.sendUserList(client.getConnectionKeeper().onlineUsers());
            } else if (Protocol.C_HEARTBEAT.equals(name)) {
                client.sendHeartbeat();
            } else if (Protocol.C_LOGOUT.equals(name)) {
                client.closeConnection();
            } else {
                client.sendError("未知命令：" + name);
            }
        } catch (IOException e) {
            client.sendError("命令格式错误：" + e.getMessage());
        }
    }

    /** 普通消息不带0xFD前缀，直接作为群聊正文处理。 */
    @Override
    public void runMessage(ConnectedClient client, String rawMessage) {
        if (client.getUsername() == null) {
            client.sendError("请先登录后再发送消息。 ");
            return;
        }
        String message = validateMessage(client, rawMessage);
        if (message != null) {
            client.getConnectionKeeper().broadcastPublic(client.getUsername(), message);
        }
    }

    private void login(ConnectedClient client, String rawUsername, String passwordHash) {
        if (client.getUsername() != null) {
            client.sendError("当前连接已经登录。");
            return;
        }
        String username = clean(rawUsername);
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            client.sendError("用户名应为1-" + Protocol.MAX_USERNAME_CHARS
                    + "个中文、字母、数字、下划线或短横线。");
            client.closeConnection();
            return;
        }

        if (!dataSource.userExists(username)) {
            client.sendError("用户\"" + username + "\"不存在，请先注册。");
            return;
        }

        if (!dataSource.verifyPassword(username, passwordHash)) {
            client.sendError("密码错误。");
            return;
        }

        ConnectionKeeper keeper = client.getConnectionKeeper();
        if (!keeper.assignNick(client, username)) {
            client.sendError("用户名\"" + username + "\"已经在线，请更换用户名。");
            client.closeConnection();
            return;
        }

        client.sendLoginOk(username);
        client.sendProfile(dataSource.getProfile(username));
        sendFriendList(client);
        keeper.broadcastSystem("【系统】" + username + " 已上线。");
        keeper.broadcastUserList();
        notifyFriendWatchers(client, username, true);
        System.out.println("用户登录：" + username + "，来自 " + client.getRemoteAddress());
    }

    private void register(ConnectedClient client, String rawUsername, String passwordHash) {
        if (client.getUsername() != null) {
            client.sendError("当前连接已经登录。");
            return;
        }
        String username = clean(rawUsername);
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            client.sendError("用户名应为1-" + Protocol.MAX_USERNAME_CHARS
                    + "个中文、字母、数字、下划线或短横线。");
            client.closeConnection();
            return;
        }

        ConnectionKeeper keeper = client.getConnectionKeeper();
        if (!keeper.assignNick(client, username)) {
            client.sendError("用户名\"" + username + "\"已经在线，请更换用户名。");
            client.closeConnection();
            return;
        }

        if (!dataSource.registerUser(username, passwordHash)) {
            client.sendError("用户名\"" + username + "\"已被注册。");
            keeper.releaseNick(client);
            return;
        }

        // 注册成功即登录
        client.sendSystemMessage("注册成功，欢迎 " + username + "！");
        client.sendLoginOk(username);
        client.sendProfile(dataSource.getProfile(username));
        sendFriendList(client);
        keeper.broadcastSystem("【系统】" + username + " 已上线。");
        keeper.broadcastUserList();
        notifyFriendWatchers(client, username, true);
        System.out.println("用户注册并登录：" + username + "，来自 " + client.getRemoteAddress());
    }

    private void privateMessage(ConnectedClient client, String target, String rawMessage) {
        String message = validateMessage(client, rawMessage);
        if (message == null) return;
        target = clean(target);
        if (target.length() == 0) {
            client.sendError("私聊对象不能为空。 ");
            return;
        }
        client.sendTo(target, message);
    }

    private void addFriend(ConnectedClient client, String target) {
        target = clean(target);
        String username = client.getUsername();
        if (target.equalsIgnoreCase(username)) {
            client.sendError("不能把自己添加为好友。 ");
            return;
        }
        if (!dataSource.userExists(target)) {
            client.sendError("用户“" + target + "”尚未登录过。 ");
            return;
        }
        boolean changed = dataSource.addFriend(username, target);
        sendFriendList(client);
        if (changed) {
            client.sendSystemMessage("已添加好友：" + target);
            ConnectedClient friend = client.getConnectionKeeper().find(target);
            if (friend != null) {
                friend.sendSystemMessage(username + " 将你添加为好友。 ");
            }
        } else {
            client.sendSystemMessage(target + " 已经在好友列表中。 ");
        }
    }

    private void updateProfile(ConnectedClient client, String displayName, String signature) {
        displayName = clean(displayName);
        signature = clean(signature);
        if (displayName.length() == 0 || displayName.length() > Protocol.MAX_DISPLAY_NAME_CHARS) {
            client.sendError("显示名称长度应为1-" + Protocol.MAX_DISPLAY_NAME_CHARS + "个字符。 ");
            return;
        }
        if (signature.length() > Protocol.MAX_SIGNATURE_CHARS) {
            client.sendError("个性签名不能超过" + Protocol.MAX_SIGNATURE_CHARS + "个字符。 ");
            return;
        }
        dataSource.updateProfile(client.getUsername(), displayName, signature);
        client.sendProfile(dataSource.getProfile(client.getUsername()));
        client.sendSystemMessage("个人资料已保存到服务器。 ");
    }

    private void queryProfile(ConnectedClient client, String target) {
        target = clean(target);
        UserProfile profile = dataSource.getProfile(target);
        if (profile == null) {
            client.sendError("未找到用户“" + target + "”的资料。 ");
            return;
        }
        client.sendProfile(profile);
    }

    private void receiveFile(ConnectedClient client, Protocol.ParsedCommand command) throws IOException {
        String target = clean(command.text(0));
        String fileName = cleanFileName(command.text(1));
        byte[] data = Protocol.decodeBytes(command.encoded(2));
        if (data.length > Protocol.MAX_FILE_BYTES) {
            client.sendError("文件不能超过" + (Protocol.MAX_FILE_BYTES / 1024 / 1024) + "MB。 ");
            return;
        }
        ConnectedClient receiver = client.getConnectionKeeper().find(target);
        if (receiver == null) {
            client.sendError("文件发送失败：用户“" + target + "”当前不在线。 ");
            return;
        }
        receiver.sendFile(client.getUsername(), fileName, data);
        client.sendSystemMessage("文件“" + fileName + "”已发送给 " + target + "。 ");
    }

    private void sendFriendList(ConnectedClient client) {
        Map<String, Boolean> status = new LinkedHashMap<String, Boolean>();
        for (String friend : dataSource.getFriends(client.getUsername())) {
            status.put(friend, Boolean.valueOf(client.getConnectionKeeper().isOnline(friend)));
        }
        client.sendFriendList(status);
    }

    private void notifyFriendWatchers(ConnectedClient source, String changedUser, boolean online) {
        for (ConnectedClient watcherClient : source.getConnectionKeeper().snapshot()) {
            String watcher = watcherClient.getUsername();
            if (watcher == null || watcher.equalsIgnoreCase(changedUser)) continue;
            Set<String> friends = dataSource.getFriends(watcher);
            if (friends.contains(changedUser)) {
                String note = changedUser + (online ? " 已上线。 " : " 已下线。 ");
                watcherClient.sendFriendStatus(changedUser, online, note);
                sendFriendList(watcherClient);
            }
        }
    }

    @Override
    public void clientDisconnected(ConnectedClient client) {
        String username = client.getUsername();
        if (username == null) return;
        ConnectionKeeper keeper = client.getConnectionKeeper();
        keeper.broadcastSystem("【系统】" + username + " 已下线。 ");
        keeper.broadcastUserList();
        notifyFriendWatchers(client, username, false);
        System.out.println("用户离线：" + username);
    }

    private static String validateMessage(ConnectedClient client, String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.length() == 0) {
            client.sendError("消息不能为空。 ");
            return null;
        }
        if (message.length() > Protocol.MAX_MESSAGE_CHARS) {
            client.sendError("消息不能超过" + Protocol.MAX_MESSAGE_CHARS + "个字符。 ");
            return null;
        }
        return message;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanFileName(String value) {
        String cleaned = clean(value).replace('\\', '_').replace('/', '_');
        return cleaned.length() == 0 ? "未命名文件" : cleaned;
    }
}
