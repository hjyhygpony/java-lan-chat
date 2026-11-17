# Java LAN Chat

一个使用原生 Java Socket 实现的局域网聊天工具，支持注册登录、群聊、私聊、好友、个人资料、文件传输和图片消息。

项目重点演示 TCP 长连接、消息分帧、双线程收发、异步发送队列、心跳保活和简单的命令协议设计。

## 功能

- 多客户端并发连接
- 用户注册与登录
- 在线用户列表
- 中文群聊
- 两用户私聊
- 好友添加与上下线提醒
- 个人资料修改和查询
- 普通文件传输
- 图片发送、预览和保存
- TCP KeepAlive + 应用层心跳
- 服务端可绑定局域网、公网、IPv6 或域名

## 技术实现

- Java 8+
- 原生 `Socket` / `ServerSocket`
- 客户端使用 `ClientMsgSender` 和 `ClientMsgListener` 双线程
- 服务端为每个连接使用 `ServerMsgSender` 和 `ServerMsgListener`
- 发送端使用 `LinkedList` 消息队列异步发送
- 消息以 `0xFF` 结束，命令以 `0xFD` 开头
- 正文使用 UTF-8，命令参数使用 Base64 编码
- 用户资料和好友关系保存在本地 properties 文件

## 快速开始

### Windows

```text
1. 双击 compile.bat
2. 双击 run_server.bat
3. 再启动两个 run_client.bat
4. 同机测试时服务器填写 127.0.0.1
5. 局域网测试时填写服务器电脑的局域网 IPv4 地址
```

### Linux / macOS

```bash
./compile.sh
./run_server.sh
./run_client.sh
```

需要安装 JDK 8 或更高版本，并确保 `javac`、`java` 已加入 `PATH`。

## 自动测试

Windows：

```text
run_test.bat
```

Linux / macOS：

```bash
./run_test.sh
```

看到以下结果表示核心协议与主要功能测试通过：

```text
[PASS] 0xFD命令、0xFF分帧、双线程收发、中文群聊、私聊、好友、资料和文件传输均通过测试。
```

## 项目结构

```text
java-lan-chat/
├─ src/com/cncd/ch04/
│  ├─ client/      # 客户端 GUI 与收发线程
│  ├─ common/      # 协议和用户资料模型
│  ├─ server/      # 服务端、连接池、命令解析和文件存储
│  └─ test/        # 协议集成测试
├─ data/           # 运行时用户资料与好友关系
├─ received_files/ # 运行时接收文件
├─ deploy/         # systemd 部署示例
├─ docs/           # 协议、跨局域网和修改说明
└─ run_*.sh/.bat
```

## 网络说明

不同局域网之间要通信，仍然需要可达网络路径，例如：

- 公网服务器；
- IPv6 直连；
- 路由器端口映射；
- 可访问的中转服务器。

仅修改 Java 协议不能绕过运营商 NAT。

## 安全说明

这是课程实践项目，使用明文 TCP，未实现 TLS。密码以加盐哈希保存，但不建议直接部署到不可信公网环境。若用于真实场景，应补充 TLS、防重放、权限控制、限流和审计。
