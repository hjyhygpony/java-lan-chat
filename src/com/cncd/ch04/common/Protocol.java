package com.cncd.ch04.common;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 参考“实验三：聊天工具”实现的文本消息协议。
 *
 * <p>每条消息以 0xFF 结束；命令消息以 0xFD 开头。普通聊天正文直接使用 UTF-8，
 * 命令参数采用 Base64 编码后用制表符分隔，从而同时兼容中文、换行和二进制文件。</p>
 */
public final class Protocol {
    private Protocol() {}

    public static final int MSG_END = 0xFF;
    public static final int COMMAND_PREFIX = 0xFD;

    // 客户端 -> 服务器命令名称（尽量沿用参考程序的命令式设计）
    public static final String C_LOGIN = "nick";
    public static final String C_PRIVATE_MESSAGE = "msg";
    public static final String C_ADD_FRIEND = "friend";
    public static final String C_UPDATE_PROFILE = "setinfo";
    public static final String C_QUERY_PROFILE = "info";
    public static final String C_FILE_TRANSFER = "file";
    public static final String C_USERS = "users";
    public static final String C_HEARTBEAT = "ping";
    public static final String C_LOGOUT = "exit";
    public static final String C_REGISTER = "reg";

    // 服务器 -> 客户端事件名称
    public static final String S_LOGIN_OK = "loginok";
    public static final String S_ERROR = "error";
    public static final String S_SYSTEM_MESSAGE = "system";
    public static final String S_USER_LIST = "userlist";
    public static final String S_PUBLIC_MESSAGE = "public";
    public static final String S_PRIVATE_MESSAGE = "private";
    public static final String S_FRIEND_LIST = "friendlist";
    public static final String S_FRIEND_STATUS = "friendstatus";
    public static final String S_PROFILE = "profile";
    public static final String S_FILE_TRANSFER = "filedata";
    public static final String S_HEARTBEAT = "pong";
    public static final String S_REGISTER_OK = "regok";

    public static final int MAX_MESSAGE_CHARS = 2000;
    public static final int MAX_USERNAME_CHARS = 16;
    public static final int MAX_DISPLAY_NAME_CHARS = 30;
    public static final int MAX_SIGNATURE_CHARS = 120;
    public static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    public static final int MIN_PASSWORD_LENGTH = 4;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** 构造一条命令，所有参数都按 UTF-8 文本进行 Base64 编码。 */
    public static String command(String name, String... textArguments) {
        String[] encoded = new String[textArguments == null ? 0 : textArguments.length];
        for (int i = 0; i < encoded.length; i++) {
            encoded[i] = encodeText(textArguments[i]);
        }
        return encodedCommand(name, encoded);
    }

    /** 构造一条命令，参数已经由调用者编码，可用于文件内容。 */
    public static String encodedCommand(String name, String... encodedArguments) {
        StringBuilder builder = new StringBuilder(name == null ? "" : name.trim());
        if (encodedArguments != null) {
            for (String argument : encodedArguments) {
                builder.append('\t').append(argument == null ? "" : argument);
            }
        }
        return builder.toString();
    }

    public static ParsedCommand parseCommand(String commandLine) throws IOException {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            throw new IOException("收到空命令。 ");
        }
        String[] parts = commandLine.split("\\t", -1);
        String name = parts[0].trim().toLowerCase();
        List<String> arguments = new ArrayList<String>();
        for (int i = 1; i < parts.length; i++) {
            arguments.add(parts[i]);
        }
        return new ParsedCommand(name, arguments);
    }

    public static String encodeText(String value) {
        String safe = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe.getBytes(UTF8));
    }

    public static String decodeText(String encoded) throws IOException {
        try {
            if (encoded == null || encoded.length() == 0) {
                return "";
            }
            return new String(Base64.getUrlDecoder().decode(encoded), UTF8);
        } catch (IllegalArgumentException e) {
            throw new IOException("命令参数 Base64 格式错误。", e);
        }
    }

    public static String encodeBytes(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes);
    }

    public static byte[] decodeBytes(String encoded) throws IOException {
        try {
            return Base64.getDecoder().decode(encoded == null ? "" : encoded);
        } catch (IllegalArgumentException e) {
            throw new IOException("文件数据 Base64 格式错误。", e);
        }
    }

    /** 按参考程序的 0xFD 命令前缀和 0xFF 结束符写出一帧。 */
    public static void writeFrame(OutputStream output, String payload, boolean command) throws IOException {
        if (command) {
            output.write(COMMAND_PREFIX);
        }
        byte[] bytes = (payload == null ? "" : payload).getBytes(command ? ASCII : UTF8);
        if (bytes.length > MAX_FRAME_BYTES) {
            throw new IOException("消息帧过大：" + bytes.length + " 字节。 ");
        }
        output.write(bytes);
        output.write(MSG_END);
        output.flush();
    }

    /** 读取一帧；流结束时抛出 EOFException。 */
    public static Frame readFrame(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            throw new EOFException("连接已关闭。 ");
        }
        boolean command = first == COMMAND_PREFIX;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!command && first != MSG_END) {
            buffer.write(first);
        }
        if (first != MSG_END) {
            while (true) {
                int value = input.read();
                if (value < 0) {
                    throw new EOFException("消息帧尚未结束，连接已关闭。 ");
                }
                if (value == MSG_END) {
                    break;
                }
                if (buffer.size() >= MAX_FRAME_BYTES) {
                    throw new IOException("收到的消息帧超过最大限制。 ");
                }
                buffer.write(value);
            }
        }
        String payload = new String(buffer.toByteArray(), command ? ASCII : UTF8);
        return new Frame(command, payload);
    }

    public static String hashPassword(String plainPassword) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(plainPassword.getBytes(UTF8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String generateSalt() {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static boolean verifySaltedPassword(String clientHash, String storedHash, String salt) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(Base64.getDecoder().decode(salt));
            byte[] hashed = md.digest(clientHash.getBytes(UTF8));
            String computed = Base64.getEncoder().encodeToString(hashed);
            return computed.equals(storedHash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String[] hashWithSalt(String clientHash) {
        try {
            String salt = generateSalt();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(Base64.getDecoder().decode(salt));
            byte[] hashed = md.digest(clientHash.getBytes(UTF8));
            return new String[] { Base64.getEncoder().encodeToString(hashed), salt };
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static final class Frame {
        private final boolean command;
        private final String payload;

        private Frame(boolean command, String payload) {
            this.command = command;
            this.payload = payload;
        }

        public boolean isCommand() { return command; }
        public String getPayload() { return payload; }
    }

    public static final class ParsedCommand {
        private final String name;
        private final List<String> encodedArguments;

        private ParsedCommand(String name, List<String> encodedArguments) {
            this.name = name;
            this.encodedArguments = encodedArguments;
        }

        public String getName() { return name; }
        public int size() { return encodedArguments.size(); }

        public String text(int index) throws IOException {
            checkIndex(index);
            return decodeText(encodedArguments.get(index));
        }

        public String encoded(int index) throws IOException {
            checkIndex(index);
            return encodedArguments.get(index);
        }

        private void checkIndex(int index) throws IOException {
            if (index < 0 || index >= encodedArguments.size()) {
                throw new IOException("命令“" + name + "”缺少第 " + (index + 1) + " 个参数。 ");
            }
        }
    }
}
