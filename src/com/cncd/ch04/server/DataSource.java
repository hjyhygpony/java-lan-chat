package com.cncd.ch04.server;

import com.cncd.ch04.common.UserProfile;
import java.util.Set;

/** 用户资料和好友关系的持久化接口。 */
public interface DataSource {
    void ensureUser(String username);
    boolean userExists(String username);
    UserProfile getProfile(String username);
    void updateProfile(String username, String displayName, String signature);
    boolean addFriend(String username, String friend);
    Set<String> getFriends(String username);
    Set<String> getAllUsers();
    boolean registerUser(String username, String passwordHash);
    boolean verifyPassword(String username, String passwordHash);
}
