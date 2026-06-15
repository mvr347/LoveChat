package me.lovelace.lovechat.managers;

import me.lovelace.lovechat.Lovechat;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Менеджер базы данных SQLite для Lovechat.
 * Обрабатывает все асинхронные операции с БД.
 * 
 * SQLite Database Manager for Lovechat.
 * Handles all async database operations.
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class DatabaseManager {
    private final Lovechat plugin;
    private Connection connection;

    // Выделенный поток для базы данных. Исключает Database Locked ошибки!
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    public DatabaseManager(Lovechat plugin) {
        this.plugin = plugin;
    }

    /**
     * Инициализация соединения и создание таблиц
     */
    public void init() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку плагина!");
            }
            File dbFile = new File(dataFolder, plugin.getConfig().getString("database.file", "chat.db"));
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка SQLite: " + e.getMessage());
        }
    }

    /**
     * Создание всех таблиц при старте
     */
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Основная таблица сообщений
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY, " +
                    "player_uuid TEXT, " +
                    "content TEXT, " +
                    "timestamp LONG)");
            
            // Таблица игноров
            stmt.execute("CREATE TABLE IF NOT EXISTS ignores (" +
                    "uuid_from TEXT, " +
                    "uuid_to TEXT, " +
                    "PRIMARY KEY(uuid_from, uuid_to))");
            
            // Настройки игроков
            stmt.execute("CREATE TABLE IF NOT EXISTS player_settings (" +
                    "uuid TEXT PRIMARY KEY, " +
                    "default_channel TEXT)");
            
            // Статистика игроков
            stmt.execute("CREATE TABLE IF NOT EXISTS player_stats (" +
                    "uuid TEXT PRIMARY KEY, " +
                    "messages_sent INTEGER)");
            
            // Отключенные теги
            stmt.execute("CREATE TABLE IF NOT EXISTS player_tags (" +
                    "uuid TEXT PRIMARY KEY, " +
                    "disabled BOOLEAN)");
            
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages(timestamp)");
        }
    }

    /**
     * Очистка всех сообщений (при рестарте)
     */
    public void clearAllMessagesSync() {
        if (connection == null) return;
        try (Statement stmt = connection.createStatement()) {
            int deleted = stmt.executeUpdate("DELETE FROM messages");
            if (deleted > 0) {
                plugin.getLogger().info("База данных очищена от старых сообщений (" + deleted + " шт.)");
            }
        } catch (SQLException ignored) {}
    }

    // ==========================================
    //            MESSAGE OPERATIONS
    // ==========================================

    /**
     * Логирование сообщения в БД
     */
    public void logMessage(int messageId, UUID uuid, String content) {
        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO messages (id, player_uuid, content, timestamp) VALUES (?, ?, ?, ?)";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, messageId);
                pstmt.setString(2, uuid.toString());
                pstmt.setString(3, content);
                pstmt.setLong(4, System.currentTimeMillis());
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Логирование системного сообщения
     */
    public void logSystemMessage(int messageId, String content) {
        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO messages (id, player_uuid, content, timestamp) VALUES (?, ?, ?, ?)";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, messageId);
                pstmt.setString(2, "SYSTEM");
                pstmt.setString(3, content);
                pstmt.setLong(4, System.currentTimeMillis());
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Редактирование сообщения
     */
    public void editMessage(int messageId, String newContent) {
        CompletableFuture.runAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement("UPDATE messages SET content = ? WHERE id = ?")) {
                pstmt.setString(1, newContent);
                pstmt.setInt(2, messageId);
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Удаление сообщения
     */
    public void deleteMessage(int messageId) {
        CompletableFuture.runAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement("DELETE FROM messages WHERE id = ?")) {
                pstmt.setInt(1, messageId);
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Обновление текста сообщения
     */
    public void updateMessage(int messageId, String newText) {
        CompletableFuture.runAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement("UPDATE messages SET content = ? WHERE id = ?")) {
                pstmt.setString(1, newText);
                pstmt.setInt(2, messageId);
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Очистка старых сообщений
     */
    public void cleanOldMessages(long maxAgeMillis) {
        CompletableFuture.runAsync(() -> {
            long cutoffTime = System.currentTimeMillis() - maxAgeMillis;
            try (PreparedStatement pstmt = connection.prepareStatement("DELETE FROM messages WHERE timestamp < ?")) {
                pstmt.setLong(1, cutoffTime);
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    // ==========================================
    //         PLAYER SETTINGS OPERATIONS
    // ==========================================

    /**
     * Получить статус отключенных тегов
     */
    public CompletableFuture<Boolean> getTagsDisabled(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "SELECT disabled FROM player_tags WHERE uuid = ?")) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getBoolean("disabled");
                }
            } catch (SQLException ignored) {}
            return false;
        }, dbExecutor);
    }

    /**
     * Сохранить статус отключенных тегов
     */
    public void saveTagsDisabled(UUID uuid, boolean disabled) {
        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_tags (uuid, disabled) VALUES (?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET disabled = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setBoolean(2, disabled);
                pstmt.setBoolean(3, disabled);
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Получить список игнорируемых игроков
     */
    public CompletableFuture<Set<UUID>> getIgnores(UUID who) {
        return CompletableFuture.supplyAsync(() -> {
            Set<UUID> ignores = new HashSet<>();
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "SELECT uuid_to FROM ignores WHERE uuid_from = ?")) {
                pstmt.setString(1, who.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) ignores.add(UUID.fromString(rs.getString("uuid_to")));
                }
            } catch (SQLException ignored) {}
            return ignores;
        }, dbExecutor);
    }

    /**
     * Добавить игрока в игнор
     */
    public void addIgnore(UUID who, UUID target) {
        CompletableFuture.runAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "INSERT OR IGNORE INTO ignores (uuid_from, uuid_to) VALUES (?, ?)")) {
                pstmt.setString(1, who.toString());
                pstmt.setString(2, target.toString());
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Удалить игрока из игнора
     */
    public void removeIgnore(UUID who, UUID target) {
        CompletableFuture.runAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "DELETE FROM ignores WHERE uuid_from = ? AND uuid_to = ?")) {
                pstmt.setString(1, who.toString());
                pstmt.setString(2, target.toString());
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Очистить список игнора игрока
     */
    public void clearIgnores(UUID playerUuid) {
        CompletableFuture.runAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "DELETE FROM ignores WHERE uuid_from = ?")) {
                pstmt.setString(1, playerUuid.toString());
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Получить количество сообщений игрока
     */
    public CompletableFuture<Integer> getMessageCount(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "SELECT messages_sent FROM player_stats WHERE uuid = ?")) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getInt("messages_sent");
                }
            } catch (SQLException ignored) {}
            return 0;
        }, dbExecutor);
    }

    /**
     * Очистить канал по умолчанию игрока
     */
    public void clearDefaultChannel(UUID playerUuid) {
        CompletableFuture.runAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "DELETE FROM player_settings WHERE uuid = ?")) {
                pstmt.setString(1, playerUuid.toString());
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Очистить статус отключенных тегов игрока
     */
    public void clearTagsDisabled(UUID playerUuid) {
        CompletableFuture.runAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "DELETE FROM player_tags WHERE uuid = ?")) {
                pstmt.setString(1, playerUuid.toString());
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Увеличить счетчик сообщений
     */
    public void incrementMessageCount(UUID uuid) {
        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_stats (uuid, messages_sent) VALUES (?, 1) " +
                    "ON CONFLICT(uuid) DO UPDATE SET messages_sent = messages_sent + 1";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    /**
     * Получить канал по умолчанию
     */
    public CompletableFuture<String> getDefaultChannel(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "SELECT default_channel FROM player_settings WHERE uuid = ?")) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return rs.getString("default_channel");
                }
            } catch (SQLException ignored) {}
            return null;
        }, dbExecutor);
    }

    /**
     * Сохранить канал по умолчанию
     */
    public void saveDefaultChannel(UUID uuid, String channel) {
        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_settings (uuid, default_channel) VALUES (?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET default_channel = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, channel);
                pstmt.setString(3, channel);
                pstmt.executeUpdate();
            } catch (SQLException ignored) {}
        }, dbExecutor);
    }

    // ==========================================
    // ==========================================
        try {
            if (connection != null && !connection.isClosed()) connection.close();
            dbExecutor.shutdown();
        } catch (SQLException ignored) {}
    }

    // ==========================================
    //              DATA RECORDS
    // ==========================================

