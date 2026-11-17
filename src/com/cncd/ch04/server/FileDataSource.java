package com.cncd.ch04.server;

import com.cncd.ch04.common.UserProfile;
import com.cncd.ch04.common.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * 使用 properties 文件保存资料和好友关系，无需安装数据库。
 * profiles.properties 保存昵称与签名，friends.properties 保存好友列表。
 */
public final class FileDataSource implements DataSource {
    private final Path dataDir;
    private final Path profilesFile;
    private final Path friendsFile;
    private final Properties profiles = new Properties();
    private final Properties friends = new Properties();

    public FileDataSource(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        this.profilesFile = dataDir.resolve("profiles.properties");
        this.friendsFile = dataDir.resolve("friends.properties");
        Files.createDirectories(dataDir);
        load(profilesFile, profiles);
        load(friendsFile, friends);
    }

    private static void load(Path file, Properties props) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
    }

    @Override
    public synchronized void ensureUser(String username) {
        if (!userExists(username)) {
            profiles.setProperty(key(username, "displayName"), username);
            profiles.setProperty(key(username, "signature"), "这个人很神秘，还没有填写个性签名。");
            friends.setProperty(username, "");
            saveQuietly();
        }
    }

    @Override
    public synchronized boolean userExists(String username) {
        return username != null && profiles.containsKey(key(username, "displayName"));
    }

    @Override
    public synchronized UserProfile getProfile(String username) {
        if (!userExists(username)) {
            return null;
        }
        String displayName = profiles.getProperty(key(username, "displayName"), username);
        String signature = profiles.getProperty(key(username, "signature"), "");
        return new UserProfile(username, displayName, signature);
    }

    @Override
    public synchronized void updateProfile(String username, String displayName, String signature) {
        ensureUser(username);
        profiles.setProperty(key(username, "displayName"), displayName);
        profiles.setProperty(key(username, "signature"), signature);
        saveQuietly();
    }

    @Override
    public synchronized boolean addFriend(String username, String friend) {
        ensureUser(username);
        Set<String> set = parseFriends(friends.getProperty(username, ""));
        boolean changed = set.add(friend);
        friends.setProperty(username, join(set));
        if (changed) {
            saveQuietly();
        }
        return changed;
    }

    @Override
    public synchronized Set<String> getFriends(String username) {
        if (username == null) {
            return Collections.emptySet();
        }
        return new TreeSet<String>(parseFriends(friends.getProperty(username, "")));
    }

    @Override
    public synchronized Set<String> getAllUsers() {
        Set<String> users = new TreeSet<String>();
        for (Object keyObject : profiles.keySet()) {
            String key = String.valueOf(keyObject);
            if (key.endsWith(".displayName")) {
                users.add(key.substring(0, key.length() - ".displayName".length()));
            }
        }
        return users;
    }

    @Override
    public synchronized boolean registerUser(String username, String passwordHash) {
        if (userExists(username)) {
            return false;
        }
        // 先创建基础用户资料
        ensureUser(username);
        // 存入密码
        String[] hashAndSalt = Protocol.hashWithSalt(passwordHash);
        profiles.setProperty(key(username, "passwordHash"), hashAndSalt[0]);
        profiles.setProperty(key(username, "passwordSalt"), hashAndSalt[1]);
        saveQuietly();
        return true;
    }

    @Override
    public synchronized boolean verifyPassword(String username, String passwordHash) {
        if (!userExists(username)) {
            return false;
        }
        String storedHash = profiles.getProperty(key(username, "passwordHash"));
        String salt = profiles.getProperty(key(username, "passwordSalt"));
        if (storedHash == null || salt == null) {
            return false;
        }
        return Protocol.verifySaltedPassword(passwordHash, storedHash, salt);
    }

    private static String key(String username, String field) {
        return username + "." + field;
    }

    private static Set<String> parseFriends(String value) {
        Set<String> set = new LinkedHashSet<String>();
        if (value == null || value.trim().isEmpty()) {
            return set;
        }
        String[] parts = value.split(",");
        for (String part : parts) {
            String name = part.trim();
            if (!name.isEmpty()) {
                set.add(name);
            }
        }
        return set;
    }

    private static String join(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private void saveQuietly() {
        try {
            saveAtomic(profilesFile, profiles, "Chat user profiles");
            saveAtomic(friendsFile, friends, "Chat friend relations");
        } catch (IOException e) {
            System.err.println("保存用户数据失败：" + e.getMessage());
        }
    }

    private static void saveAtomic(Path target, Properties props, String comment) throws IOException {
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            props.store(out, comment);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveNotSupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
