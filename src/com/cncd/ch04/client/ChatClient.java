package com.cncd.ch04.client;

import com.cncd.ch04.common.Protocol;
import com.cncd.ch04.common.UserProfile;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.imageio.ImageIO;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Swing 图形聊天客户端。 */
public final class ChatClient extends JFrame implements ClientKernel.Listener {
    private static final String PUBLIC_TARGET = "所有人（群聊）";
    private static final String IMAGE_MESSAGE_ATTRIBUTE = "chat.image.message";
    private static final int THUMBNAIL_MAX_WIDTH = 280;
    private static final int THUMBNAIL_MAX_HEIGHT = 220;

    private final JButton connectButton = new JButton("连接");
    private final JLabel statusLabel = new JLabel("未连接");

    private final JTextPane historyPane = new JTextPane();
    private final JTextArea inputArea = new JTextArea(3, 30);
    private final DefaultListModel<String> onlineModel = new DefaultListModel<String>();
    private final DefaultListModel<String> friendModel = new DefaultListModel<String>();
    private final JList<String> onlineList = new JList<String>(onlineModel);
    private final JList<String> friendList = new JList<String>(friendModel);
    private final DefaultComboBoxModel<String> recipientModel = new DefaultComboBoxModel<String>();
    private final JComboBox<String> recipientCombo = new JComboBox<String>(recipientModel);
    private final JButton sendButton = new JButton("发送");
    private final JButton addFriendButton = new JButton("添加好友");
    private final JButton profileButton = new JButton("查看资料");
    private final JButton editProfileButton = new JButton("修改我的资料");
    private final JButton fileButton = new JButton("发送文件/图片");

    private ClientKernel kernel;
    private String loginHost;
    private int loginPort;
    private String loginUsername;
    private String loginPassword;
    private boolean loginModeIsRegister;
    private String currentUsername;
    private UserProfile myProfile;
    private Map<String, Boolean> friendStatus = new LinkedHashMap<String, Boolean>();
    private volatile boolean showNextProfile;

    public ChatClient() {
        super("实验四：简单聊天程序");
    }

    private boolean showLoginDialog() {
        JTextField hostField = new JTextField("127.0.0.1", 16);
        JTextField portField = new JTextField("3500", 6);
        JTextField userField = new JTextField(12);
        JPasswordField passwordField = new JPasswordField(12);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("服务器地址："), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(hostField, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        panel.add(new JLabel("端口："), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(portField, c);

        c.gridx = 0; c.gridy = 2; c.weightx = 0;
        panel.add(new JLabel("用户名："), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(userField, c);

        c.gridx = 0; c.gridy = 3; c.weightx = 0;
        panel.add(new JLabel("密码："), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(passwordField, c);

        while (true) {
            int choice = JOptionPane.showOptionDialog(
                    null, panel, "登录 / 注册 - 简单聊天程序",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null,
                    new String[]{"登录", "注册", "退出"}, "登录");

            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                return false;
            }

            String host = hostField.getText().trim();
            String portText = portField.getText().trim();
            String user = userField.getText().trim();
            String pass = new String(passwordField.getPassword());

            if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                JOptionPane.showMessageDialog(null, "服务器地址、用户名和密码不能为空。", "提示", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (pass.length() < Protocol.MIN_PASSWORD_LENGTH) {
                JOptionPane.showMessageDialog(null, "密码至少需要" + Protocol.MIN_PASSWORD_LENGTH + "个字符。", "提示", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(null, "端口必须是数字。", "提示", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            boolean isLogin = (choice == 0);

            if (!isLogin) {
                JPasswordField confirmField = new JPasswordField(12);
                JPanel confirmPanel = new JPanel(new BorderLayout(6, 6));
                confirmPanel.add(new JLabel("请再次输入密码："), BorderLayout.NORTH);
                confirmPanel.add(confirmField, BorderLayout.CENTER);
                int confirmChoice = JOptionPane.showConfirmDialog(null, confirmPanel, "确认密码", JOptionPane.OK_CANCEL_OPTION);
                if (confirmChoice != JOptionPane.OK_OPTION) {
                    continue;
                }
                String confirmPass = new String(confirmField.getPassword());
                if (!pass.equals(confirmPass)) {
                    JOptionPane.showMessageDialog(null, "两次密码不一致，请重新输入。", "提示", JOptionPane.WARNING_MESSAGE);
                    continue;
                }
            }

            loginHost = host;
            loginPort = port;
            loginUsername = user;
            loginPassword = pass;
            loginModeIsRegister = !isLogin;
            return true;
        }
    }

    private void doConnect() {
        statusLabel.setText("正在连接……");
        appendLine("正在连接 " + loginHost + ":" + loginPort + "……", Color.GRAY);
        new Thread(() -> {
            try {
                ClientKernel newKernel = new ClientKernel(ChatClient.this);
                kernel = newKernel;
                newKernel.connect(loginHost, loginPort, loginUsername);
                if (loginModeIsRegister) {
                    newKernel.register(loginUsername, loginPassword);
                } else {
                    newKernel.login(loginUsername, loginPassword);
                }
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    kernel = null;
                    statusLabel.setText("连接失败");
                    connectButton.setEnabled(true);
                    showError("连接服务器失败：" + ex.getMessage() + "\n\n跨局域网时请填写公网服务器IP或域名，并确认服务器TCP端口已在云安全组和系统防火墙中放行。");
                });
            }
        }, "connect-worker").start();
    }

    private void initializeUi() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(850, 600));
        setSize(960, 680);
        setLocationRelativeTo(null);

        JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectionPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        connectionPanel.add(new JLabel("状态："));
        connectionPanel.add(statusLabel);
        connectionPanel.add(connectButton);
        connectButton.setText("断开");
        connectButton.setEnabled(false);
        add(connectionPanel, BorderLayout.NORTH);

        historyPane.setEditable(false);
        historyPane.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        JScrollPane historyScroll = new JScrollPane(historyPane);
        historyScroll.setBorder(BorderFactory.createTitledBorder("聊天记录"));

        onlineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTabbedPane userTabs = new JTabbedPane();
        userTabs.addTab("在线用户", new JScrollPane(onlineList));
        userTabs.addTab("好友", new JScrollPane(friendList));
        userTabs.setPreferredSize(new Dimension(210, 400));

        JPanel rightButtons = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(3, 3, 3, 3);
        c.gridy = 0; rightButtons.add(addFriendButton, c);
        c.gridy = 1; rightButtons.add(profileButton, c);
        c.gridy = 2; rightButtons.add(editProfileButton, c);
        c.gridy = 3; rightButtons.add(fileButton, c);
        c.gridy = 4; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        rightButtons.add(new JPanel(), c);

        JPanel usersPanel = new JPanel(new BorderLayout());
        usersPanel.add(userTabs, BorderLayout.CENTER);
        usersPanel.add(rightButtons, BorderLayout.SOUTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, historyScroll, usersPanel);
        splitPane.setResizeWeight(0.78);
        add(splitPane, BorderLayout.CENTER);

        recipientModel.addElement(PUBLIC_TARGET);
        JPanel sendTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sendTop.add(new JLabel("发送给："));
        sendTop.add(recipientCombo);
        sendTop.add(new JLabel("提示：Ctrl+Enter发送"));

        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        JPanel inputPanel = new JPanel(new BorderLayout(6, 6));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        inputPanel.add(sendTop, BorderLayout.NORTH);
        inputPanel.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        add(inputPanel, BorderLayout.SOUTH);

        setChatControlsEnabled(false);
    }

    private void bindActions() {
        connectButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                disconnect();
                dispose();
            }
        });
        sendButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });
        inputArea.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && e.isControlDown()) {
                    e.consume();
                    sendMessage();
                }
            }
        });
        historyPane.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                ReceivedImage image = imageAt(e.getPoint());
                if (image != null) {
                    openImageViewer(image);
                }
            }
        });
        historyPane.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                boolean overImage = imageAt(e.getPoint()) != null;
                historyPane.setCursor(overImage
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
                historyPane.setToolTipText(overImage ? "点击查看大图" : null);
            }
        });
        onlineList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && onlineList.getSelectedValue() != null) {
                recipientCombo.setSelectedItem(onlineList.getSelectedValue());
            }
        });
        friendList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && friendList.getSelectedValue() != null) {
                recipientCombo.setSelectedItem(extractFriendName(friendList.getSelectedValue()));
            }
        });
        addFriendButton.addActionListener(e -> addFriend());
        profileButton.addActionListener(e -> queryProfile());
        editProfileButton.addActionListener(e -> editProfile());
        fileButton.addActionListener(e -> sendFile());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                disconnect();
                dispose();
            }
        });
    }


    private void disconnect() {
        ClientKernel old = kernel;
        kernel = null;
        if (old != null) {
            old.close();
        }
        setLoggedOutUi("未连接");
    }

    private void sendMessage() {
        if (!ensureConnected()) {
            return;
        }
        final String text = inputArea.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > Protocol.MAX_MESSAGE_CHARS) {
            showError("消息不能超过" + Protocol.MAX_MESSAGE_CHARS + "个字符。");
            return;
        }
        String target = String.valueOf(recipientCombo.getSelectedItem());
        try {
            if (PUBLIC_TARGET.equals(target)) {
                kernel.sendPublicMessage(text);
            } else {
                kernel.sendPrivateMessage(target, text);
            }
            inputArea.setText("");
            inputArea.requestFocusInWindow();
        } catch (IOException e) {
            showError("发送失败：" + e.getMessage());
        }
    }

    private void addFriend() {
        if (!ensureConnected()) return;
        String initial = selectedUser();
        String target = JOptionPane.showInputDialog(this, "请输入要添加的用户名：", initial == null ? "" : initial);
        if (target == null || target.trim().isEmpty()) return;
        try {
            kernel.addFriend(target.trim());
        } catch (IOException e) {
            showError("添加好友失败：" + e.getMessage());
        }
    }

    private void queryProfile() {
        if (!ensureConnected()) return;
        String target = selectedUser();
        if (target == null) {
            target = JOptionPane.showInputDialog(this, "请输入要查询的用户名：");
        }
        if (target == null || target.trim().isEmpty()) return;
        try {
            showNextProfile = true;
            kernel.queryProfile(target.trim());
        } catch (IOException e) {
            showNextProfile = false;
            showError("查询失败：" + e.getMessage());
        }
    }

    private void editProfile() {
        if (!ensureConnected()) return;
        String oldName = myProfile == null ? currentUsername : myProfile.getDisplayName();
        String oldSignature = myProfile == null ? "" : myProfile.getSignature();
        JTextField name = new JTextField(oldName, 24);
        JTextArea signature = new JTextArea(oldSignature, 4, 24);
        signature.setLineWrap(true);
        signature.setWrapStyleWord(true);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 4, 4, 4);
        panel.add(new JLabel("显示名称："), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        panel.add(name, c);
        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("个性签名："), c);
        c.gridx = 1; c.fill = GridBagConstraints.BOTH; c.weighty = 1;
        panel.add(new JScrollPane(signature), c);
        int result = JOptionPane.showConfirmDialog(this, panel, "修改个人资料", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;
        try {
            kernel.updateProfile(name.getText().trim(), signature.getText().trim());
        } catch (IOException e) {
            showError("保存资料失败：" + e.getMessage());
        }
    }

    private void sendFile() {
        if (!ensureConnected()) return;
        String target = selectedUser();
        if (target == null || target.equals(currentUsername)) {
            showError("请先在在线用户或好友列表中选择一名其他用户。");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        final String finalTarget = target;
        fileButton.setEnabled(false);
        new Thread(() -> {
            try {
                kernel.sendFile(finalTarget, file);
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> showError("发送文件失败：" + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> fileButton.setEnabled(true));
            }
        }, "file-sender").start();
    }

    private boolean ensureConnected() {
        if (kernel == null || !kernel.isConnected()) {
            showError("请先连接服务器。");
            return false;
        }
        return true;
    }

    private String selectedUser() {
        String online = onlineList.getSelectedValue();
        if (online != null) return online;
        String friend = friendList.getSelectedValue();
        return friend == null ? null : extractFriendName(friend);
    }

    private static String extractFriendName(String display) {
        int firstSpace = display.indexOf(' ');
        return firstSpace >= 0 ? display.substring(firstSpace + 1).trim() : display.trim();
    }

    private void setChatControlsEnabled(boolean enabled) {
        inputArea.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        recipientCombo.setEnabled(enabled);
        addFriendButton.setEnabled(enabled);
        profileButton.setEnabled(enabled);
        editProfileButton.setEnabled(enabled);
        fileButton.setEnabled(enabled);
    }

    private void setLoggedOutUi(String status) {
        SwingUtilities.invokeLater(() -> {
            currentUsername = null;
            myProfile = null;
            statusLabel.setText(status);
            connectButton.setText("断开");
            connectButton.setEnabled(false);
            onlineModel.clear();
            friendModel.clear();
            recipientModel.removeAllElements();
            recipientModel.addElement(PUBLIC_TARGET);
            setChatControlsEnabled(false);
            friendStatus.clear();
        });
    }

    private void appendLine(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument document = historyPane.getStyledDocument();
            SimpleAttributeSet attributes = new SimpleAttributeSet();
            StyleConstants.setForeground(attributes, color);
            try {
                document.insertString(document.getLength(), text + System.lineSeparator(), attributes);
                historyPane.setCaretPosition(document.getLength());
            } catch (BadLocationException ignored) {
            }
        });
    }

    /**
     * 把收到的图片作为可点击缩略图插入聊天记录。
     * 图片仅保存在当前客户端内存中，用户点击“保存图片”后才写入磁盘。
     */
    private void appendImageMessage(String from, String fileName, byte[] data, BufferedImage originalImage) {
        final ReceivedImage receivedImage = new ReceivedImage(from, fileName, data, originalImage);
        SwingUtilities.invokeLater(() -> {
            StyledDocument document = historyPane.getStyledDocument();
            SimpleAttributeSet headerAttributes = new SimpleAttributeSet();
            StyleConstants.setForeground(headerAttributes, new Color(153, 102, 0));

            SimpleAttributeSet imageAttributes = new SimpleAttributeSet();
            ImageIcon thumbnail = createScaledIcon(originalImage, THUMBNAIL_MAX_WIDTH, THUMBNAIL_MAX_HEIGHT, false);
            StyleConstants.setIcon(imageAttributes, thumbnail);
            imageAttributes.addAttribute(IMAGE_MESSAGE_ATTRIBUTE, receivedImage);

            SimpleAttributeSet hintAttributes = new SimpleAttributeSet();
            StyleConstants.setForeground(hintAttributes, Color.GRAY);
            StyleConstants.setItalic(hintAttributes, true);

            try {
                document.insertString(document.getLength(),
                        "[图片] " + from + " 发送了“" + fileName + "”\n", headerAttributes);
                document.insertString(document.getLength(), " ", imageAttributes);
                document.insertString(document.getLength(), "\n点击图片查看大图，可在预览窗口中选择保存\n", hintAttributes);
                historyPane.setCaretPosition(document.getLength());
            } catch (BadLocationException ignored) {
            }
        });
    }

    /** 获取鼠标所在位置对应的图片消息。 */
    private ReceivedImage imageAt(Point point) {
        int position = historyPane.viewToModel(point);
        ReceivedImage image = imageAtPosition(position, point);
        if (image == null && position > 0) {
            image = imageAtPosition(position - 1, point);
        }
        return image;
    }

    private ReceivedImage imageAtPosition(int position, Point point) {
        if (position < 0) {
            return null;
        }
        StyledDocument document = historyPane.getStyledDocument();
        Element element = document.getCharacterElement(position);
        Object value = element.getAttributes().getAttribute(IMAGE_MESSAGE_ATTRIBUTE);
        if (!(value instanceof ReceivedImage)) {
            return null;
        }
        Object iconValue = element.getAttributes().getAttribute(StyleConstants.IconAttribute);
        if (iconValue instanceof ImageIcon) {
            try {
                Rectangle start = historyPane.modelToView(element.getStartOffset());
                if (start != null) {
                    ImageIcon icon = (ImageIcon) iconValue;
                    Rectangle iconBounds = new Rectangle(start.x, start.y,
                            icon.getIconWidth(), icon.getIconHeight());
                    if (!iconBounds.contains(point)) {
                        return null;
                    }
                }
            } catch (BadLocationException ignored) {
            }
        }
        return (ReceivedImage) value;
    }

    /** 打开支持缩放和按需保存的图片预览窗口。 */
    private void openImageViewer(final ReceivedImage receivedImage) {
        final JDialog dialog = new JDialog(this,
                "图片预览 - " + receivedImage.fileName, true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(6, 6));

        final JLabel imageLabel = new JLabel();
        imageLabel.setHorizontalAlignment(JLabel.CENTER);
        imageLabel.setVerticalAlignment(JLabel.CENTER);
        imageLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane imageScroll = new JScrollPane(imageLabel);
        imageScroll.getVerticalScrollBar().setUnitIncrement(20);
        imageScroll.getHorizontalScrollBar().setUnitIncrement(20);
        dialog.add(imageScroll, BorderLayout.CENTER);

        final JLabel scaleLabel = new JLabel();
        final double fitScale = calculateFitScale(receivedImage.image, 850, 560);
        final double[] currentScale = new double[] { fitScale };
        final Runnable refreshImage = new Runnable() {
            @Override public void run() {
                double scale = currentScale[0];
                int width = Math.max(1, (int) Math.round(receivedImage.image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(receivedImage.image.getHeight() * scale));
                imageLabel.setIcon(createScaledIcon(receivedImage.image, width, height, true));
                scaleLabel.setText((int) Math.round(scale * 100) + "%");
                imageLabel.revalidate();
                imageLabel.repaint();
            }
        };

        JButton zoomOutButton = new JButton("缩小");
        zoomOutButton.addActionListener(e -> {
            currentScale[0] = Math.max(0.05, currentScale[0] / 1.25);
            refreshImage.run();
        });
        JButton fitButton = new JButton("适应窗口");
        fitButton.addActionListener(e -> {
            currentScale[0] = fitScale;
            refreshImage.run();
        });
        JButton originalButton = new JButton("原始大小");
        originalButton.addActionListener(e -> {
            currentScale[0] = 1.0;
            refreshImage.run();
        });
        JButton zoomInButton = new JButton("放大");
        zoomInButton.addActionListener(e -> {
            currentScale[0] = Math.min(8.0, currentScale[0] * 1.25);
            refreshImage.run();
        });
        JButton saveButton = new JButton("保存图片…");
        saveButton.addActionListener(e -> saveReceivedImage(receivedImage));
        JButton closeButton = new JButton("关闭");
        closeButton.addActionListener(e -> dialog.dispose());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        controls.add(zoomOutButton);
        controls.add(fitButton);
        controls.add(originalButton);
        controls.add(zoomInButton);
        controls.add(scaleLabel);
        controls.add(saveButton);
        controls.add(closeButton);
        dialog.add(controls, BorderLayout.SOUTH);

        dialog.setSize(960, 720);
        dialog.setMinimumSize(new Dimension(560, 420));
        dialog.setLocationRelativeTo(this);
        refreshImage.run();
        dialog.setVisible(true);
    }

    private void saveReceivedImage(ReceivedImage receivedImage) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存收到的图片");
        chooser.setSelectedFile(new File(safeFileName(receivedImage.fileName)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File selected = chooser.getSelectedFile();
        if (selected.exists()) {
            int overwrite = JOptionPane.showConfirmDialog(this,
                    "文件已存在，是否覆盖？\n" + selected.getAbsolutePath(),
                    "确认覆盖",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) {
                return;
            }
        }

        try {
            Files.write(selected.toPath(), receivedImage.data);
            JOptionPane.showMessageDialog(this,
                    "图片已保存到：\n" + selected.getAbsolutePath(),
                    "保存成功",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            showError("保存图片失败：" + e.getMessage());
        }
    }

    private static double calculateFitScale(BufferedImage image, int maxWidth, int maxHeight) {
        double widthScale = maxWidth / (double) image.getWidth();
        double heightScale = maxHeight / (double) image.getHeight();
        return Math.min(1.0, Math.min(widthScale, heightScale));
    }

    private static ImageIcon createScaledIcon(BufferedImage image, int maxWidth, int maxHeight,
                                               boolean exactSize) {
        int targetWidth;
        int targetHeight;
        if (exactSize) {
            targetWidth = Math.max(1, maxWidth);
            targetHeight = Math.max(1, maxHeight);
        } else {
            double scale = calculateFitScale(image, maxWidth, maxHeight);
            targetWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            targetHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
        }
        if (targetWidth == image.getWidth() && targetHeight == image.getHeight()) {
            return new ImageIcon(image);
        }
        Image scaled = image.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    private static String safeFileName(String fileName) {
        String safe = fileName == null ? "收到的图片" : fileName.replace('\\', '_').replace('/', '_').trim();
        return safe.isEmpty() ? "收到的图片" : safe;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.WARNING_MESSAGE);
    }

    private static String time(long timestamp) {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(timestamp));
    }

    @Override public void onLoginSuccess(String username) {
        SwingUtilities.invokeLater(() -> {
            currentUsername = username;
            statusLabel.setText("已连接：" + loginHost + ":" + loginPort + " / " + username);
            connectButton.setText("断开");
            connectButton.setEnabled(true);
            setChatControlsEnabled(true);
            inputArea.requestFocusInWindow();
            appendLine("登录成功，可以开始聊天。", new Color(0, 128, 0));
        });
    }

    @Override public void onRegisterSuccess(String username) {
        // 注册成功后服务器自动走登录流程，loginok 事件会处理 UI
    }

    @Override public void onError(String message) {
        appendLine("[错误] " + message, Color.RED);
    }

    @Override public void onSystemMessage(String message) {
        appendLine(message, Color.GRAY);
    }

    @Override public void onUserList(List<String> users) {
        SwingUtilities.invokeLater(() -> {
            String selected = String.valueOf(recipientCombo.getSelectedItem());
            onlineModel.clear();
            recipientModel.removeAllElements();
            recipientModel.addElement(PUBLIC_TARGET);
            for (String user : users) {
                onlineModel.addElement(user);
                if (!user.equals(currentUsername)) {
                    recipientModel.addElement(user);
                }
            }
            recipientCombo.setSelectedItem(selected);
            if (recipientCombo.getSelectedIndex() < 0) recipientCombo.setSelectedIndex(0);
        });
    }

    @Override public void onPublicMessage(String from, String message, long timestamp) {
        Color color = from.equals(currentUsername) ? new Color(0, 102, 153) : Color.BLACK;
        appendLine("[群聊 " + time(timestamp) + "] " + from + "：" + message, color);
    }

    @Override public void onPrivateMessage(String from, String message, long timestamp) {
        appendLine("[私聊 " + time(timestamp) + "] " + from + "：" + message, new Color(128, 0, 128));
    }

    @Override public void onFriendList(Map<String, Boolean> friends) {
        friendStatus = new LinkedHashMap<String, Boolean>(friends);
        SwingUtilities.invokeLater(() -> {
            friendModel.clear();
            for (Map.Entry<String, Boolean> entry : friendStatus.entrySet()) {
                friendModel.addElement((entry.getValue().booleanValue() ? "● " : "○ ") + entry.getKey());
            }
        });
    }

    @Override public void onFriendStatus(String friend, boolean online, String note) {
        appendLine("[好友提醒] " + note, online ? new Color(0, 128, 0) : Color.GRAY);
    }

    @Override public void onProfile(UserProfile profile) {
        if (profile.getUsername().equals(currentUsername)) {
            myProfile = profile;
        }
        if (showNextProfile) {
            showNextProfile = false;
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    ChatClient.this,
                    "用户名：" + profile.getUsername() + "\n显示名称：" + profile.getDisplayName() +
                            "\n个性签名：" + profile.getSignature(),
                    "用户资料",
                    JOptionPane.INFORMATION_MESSAGE));
        }
    }

    @Override public void onFileReceived(String from, String fileName, byte[] data) {
        new Thread(() -> {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
                if (image != null) {
                    appendImageMessage(from, fileName, data, image);
                    return;
                }
            } catch (IOException ignored) {
                // 无法按图片解析时，继续按普通文件处理。
            }

            try {
                Path directory = Paths.get("received_files");
                Files.createDirectories(directory);
                String safe = safeFileName(fileName);
                Path target = directory.resolve(System.currentTimeMillis() + "_" + safe);
                Files.copy(new ByteArrayInputStream(data), target, StandardCopyOption.REPLACE_EXISTING);
                appendLine("[文件] 收到 " + from + " 发送的“" + fileName + "”，已保存到：" + target.toAbsolutePath(),
                        new Color(153, 102, 0));
            } catch (IOException e) {
                appendLine("[文件] 保存失败：" + e.getMessage(), Color.RED);
            }
        }, "file-receiver").start();
    }

    @Override public void onDisconnected(String reason) {
        appendLine(reason, Color.RED);
        setLoggedOutUi("未连接");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            ChatClient client = new ChatClient();
            if (!client.showLoginDialog()) {
                System.exit(0);
                return;
            }
            client.initializeUi();
            client.bindActions();
            client.setVisible(true);
            client.doConnect();
        });
    }

    /** 当前会话中尚未保存到磁盘的图片消息。 */
    private static final class ReceivedImage {
        private final String from;
        private final String fileName;
        private final byte[] data;
        private final BufferedImage image;

        private ReceivedImage(String from, String fileName, byte[] data, BufferedImage image) {
            this.from = from;
            this.fileName = safeFileName(fileName);
            this.data = data.clone();
            this.image = image;
        }
    }
}
