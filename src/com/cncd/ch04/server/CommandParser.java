package com.cncd.ch04.server;

import java.io.IOException;

/** 与参考程序相同，服务器通过独立命令解析器处理文本命令。 */
public interface CommandParser {
    void setDataSource(DataSource dataSource);
    void runCommand(ConnectedClient client, String commandLine) throws IOException;
    void runMessage(ConnectedClient client, String message);
    void clientDisconnected(ConnectedClient client);
}
