package com.cncd.ch04.common;

/** 简单用户资料对象。 */
public final class UserProfile {
    private final String username;
    private final String displayName;
    private final String signature;

    public UserProfile(String username, String displayName, String signature) {
        this.username = username == null ? "" : username;
        this.displayName = displayName == null ? "" : displayName;
        this.signature = signature == null ? "" : signature;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSignature() {
        return signature;
    }
}
