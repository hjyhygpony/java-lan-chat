package com.cncd.ch04.test;

import com.cncd.ch04.client.ClientKernel;
import com.cncd.ch04.common.UserProfile;
import com.cncd.ch04.server.MainServer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 不启动图形界面，验证参考程序式消息队列、命令解析和扩展功能。 */
public final class ProtocolIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path tempData = Files.createTempDirectory("chat-reference-method-");
        MainServer server = new MainServer(0, tempData, "127.0.0.1");
        server.start();

        TestListener aliceListener = new TestListener();
        TestListener bobListener = new TestListener();
        ClientKernel alice = new ClientKernel(aliceListener);
        ClientKernel bob = new ClientKernel(bobListener);
        try {
            alice.connect("127.0.0.1", server.getPort(), "Alice");
            alice.register("Alice", "pass1234");
            aliceListener.awaitLogin("Alice");

            bob.connect("127.0.0.1", server.getPort(), "Bob");
            bob.register("Bob", "pass5678");
            bobListener.awaitLogin("Bob");
            aliceListener.awaitUser("Bob");

            // --- Auth tests ---
            // Wrong password
            TestListener wrongPwListener = new TestListener();
            ClientKernel wrongPw = new ClientKernel(wrongPwListener);
            wrongPw.connect("127.0.0.1", server.getPort(), "TestUser");
            wrongPw.register("TestUser", "rightpass");
            wrongPwListener.awaitLogin("TestUser");
            wrongPw.close();
            Thread.sleep(100);

            wrongPw = new ClientKernel(wrongPwListener);
            wrongPwListener.events.clear();
            wrongPw.connect("127.0.0.1", server.getPort(), "TestUser");
            wrongPw.login("TestUser", "wrongpass");
            Event err = wrongPwListener.await("error");
            if (err.a == null || !err.a.contains("密码错误")) {
                throw new AssertionError("错误密码应返回密码错误，实际=" + (err.a == null ? "null" : err.a));
            }
            wrongPw.close();

            // Duplicate registration
            TestListener dupListener = new TestListener();
            ClientKernel dup = new ClientKernel(dupListener);
            dup.connect("127.0.0.1", server.getPort(), "Charlie");
            dup.register("Charlie", "pass9999");
            dupListener.awaitLogin("Charlie");
            dup.close();
            Thread.sleep(100);

            TestListener dup2Listener = new TestListener();
            ClientKernel dup2 = new ClientKernel(dup2Listener);
            dup2.connect("127.0.0.1", server.getPort(), "Charlie");
            dup2.register("Charlie", "pass9999");
            Event dupError = dup2Listener.await("error");
            if (dupError.a == null || !dupError.a.contains("已被注册")) {
                throw new AssertionError("重复注册应返回已被注册错误，实际=" + (dupError.a == null ? "null" : dupError.a));
            }
            dup2.close();

            // --- Main functional tests ---
            alice.sendPublicMessage("你好，计算机网络！");
            Event publicMessage = bobListener.await("public");
            assertEquals("Alice", publicMessage.a, "群聊发送者");
            assertEquals("你好，计算机网络！", publicMessage.b, "中文群聊内容");

            alice.addFriend("Bob");
            aliceListener.awaitFriend("Bob");

            bob.updateProfile("小鲍勃", "正在完成聊天工具实验");
            bobListener.awaitProfile("Bob", "小鲍勃");
            alice.queryProfile("Bob");
            Event profile = aliceListener.awaitProfile("Bob", "小鲍勃");
            assertEquals("正在完成聊天工具实验", profile.c, "资料签名");

            alice.sendPrivateMessage("Bob", "这是一条私聊消息");
            Event privateMessage = bobListener.await("private");
            assertEquals("Alice", privateMessage.a, "私聊发送者");
            assertEquals("这是一条私聊消息", privateMessage.b, "私聊内容");

            byte[] expected = "file-content-聊天工具".getBytes("UTF-8");
            Path file = Files.createTempFile("chat-file-", ".txt");
            Files.write(file, expected);
            alice.sendFile("Bob", file.toFile());
            Event receivedFile = bobListener.await("file");
            assertEquals("Alice", receivedFile.a, "文件发送者");
            if (!Arrays.equals(expected, receivedFile.bytes)) {
                throw new AssertionError("文件内容不一致");
            }

            System.out.println("[PASS] 0xFD命令、0xFF分帧、双线程收发、注册登录、密码错误测试、重复注册测试、中文群聊、私聊、好友、资料和文件传输均通过测试。");
        } finally {
            alice.close();
            bob.close();
            Thread.sleep(150);
            server.close();
        }
    }

    private static void assertEquals(String expected, String actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + "不一致，期望=" + expected + "，实际=" + actual);
        }
    }

    private static final class TestListener implements ClientKernel.Listener {
        private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<Event>();

        Event await(String type) throws Exception {
            long end = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < end) {
                Event event = events.poll(500, TimeUnit.MILLISECONDS);
                if (event != null && type.equals(event.type)) return event;
            }
            throw new AssertionError("未收到事件：" + type);
        }

        void awaitLogin(String name) throws Exception {
            while (true) {
                Event event = await("login");
                if (name.equals(event.a)) return;
            }
        }

        void awaitUser(String name) throws Exception {
            long end = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < end) {
                Event event = events.poll(500, TimeUnit.MILLISECONDS);
                if (event != null && "users".equals(event.type)
                        && event.list != null && event.list.contains(name)) return;
            }
            throw new AssertionError("在线用户列表中没有：" + name);
        }

        void awaitFriend(String name) throws Exception {
            long end = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < end) {
                Event event = events.poll(500, TimeUnit.MILLISECONDS);
                if (event != null && "friends".equals(event.type)
                        && event.map != null && event.map.containsKey(name)) return;
            }
            throw new AssertionError("好友列表中没有：" + name);
        }

        Event awaitProfile(String user, String display) throws Exception {
            long end = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < end) {
                Event event = events.poll(500, TimeUnit.MILLISECONDS);
                if (event != null && "profile".equals(event.type)
                        && user.equals(event.a) && display.equals(event.b)) return event;
            }
            throw new AssertionError("未收到指定用户资料：" + user + "/" + display);
        }

        @Override public void onLoginSuccess(String username) {
            events.add(Event.of("login", username, null, null));
        }
        @Override public void onRegisterSuccess(String username) {
            events.add(Event.of("regok", username, null, null));
        }
        @Override public void onError(String message) {
            events.add(Event.of("error", message, null, null));
        }
        @Override public void onSystemMessage(String message) {
            events.add(Event.of("system", message, null, null));
        }
        @Override public void onUserList(List<String> users) {
            Event event = Event.of("users", null, null, null);
            event.list = users;
            events.add(event);
        }
        @Override public void onPublicMessage(String from, String message, long timestamp) {
            events.add(Event.of("public", from, message, null));
        }
        @Override public void onPrivateMessage(String from, String message, long timestamp) {
            events.add(Event.of("private", from, message, null));
        }
        @Override public void onFriendList(Map<String, Boolean> friends) {
            Event event = Event.of("friends", null, null, null);
            event.map = friends;
            events.add(event);
        }
        @Override public void onFriendStatus(String friend, boolean online, String note) {
            events.add(Event.of("friendstatus", friend, online ? "1" : "0", note));
        }
        @Override public void onProfile(UserProfile profile) {
            events.add(Event.of("profile", profile.getUsername(),
                    profile.getDisplayName(), profile.getSignature()));
        }
        @Override public void onFileReceived(String from, String fileName, byte[] data) {
            Event event = Event.of("file", from, fileName, null);
            event.bytes = data;
            events.add(event);
        }
        @Override public void onDisconnected(String reason) {
            events.add(Event.of("disconnect", reason, null, null));
        }
    }

    private static final class Event {
        String type;
        String a;
        String b;
        String c;
        byte[] bytes;
        List<String> list;
        Map<String, Boolean> map;

        static Event of(String type, String a, String b, String c) {
            Event event = new Event();
            event.type = type;
            event.a = a;
            event.b = b;
            event.c = c;
            return event;
        }
    }
}
