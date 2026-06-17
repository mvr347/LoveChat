package me.lovelace.lovechat.managers;

import me.lovelace.lovechat.Lovechat;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DatabaseManager {
    private final Lovechat plugin;
    private Connection connection;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    public DatabaseManager(Lovechat plugin) { this.plugin = plugin; }

    public void init() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) plugin.getLogger().warning(\"Error creating folder\");
            File dbFile = new File(dataFolder, plugin.getConfig().getString(\"database.file\", \"chat.db\"));
            connection = DriverManager.getConnection(\"jdbc:sqlite:\" + dbFile.getAbsolutePath());
            createTables();
        } catch (SQLException e) { plugin.getLogger().severe(\"SQLite error: \" + e.getMessage()); }
    }
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(\"CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, player_uuid TEXT, content TEXT, timestamp LONG)\");
            stmt.execute(\"CREATE TABLE IF NOT EXISTS ignores (uuid_from TEXT, uuid_to TEXT, PRIMARY KEY(uuid_from, uuid_to))\");
            stmt.execute(\"CREATE TABLE IF NOT EXISTS player_settings (uuid TEXT PRIMARY KEY, default_channel TEXT)\");
            stmt.execute(\"CREATE TABLE IF NOT EXISTS player_tags (uuid TEXT PRIMARY KEY, disabled BOOLEAN)\");
        }
    }
    public void clearAllMessagesSync() { try (Statement stmt = connection.createStatement()) { stmt.executeUpdate(\"DELETE FROM messages\"); } catch (SQLException ignored) {} }
    public void deleteMessage(int id) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"DELETE FROM messages WHERE id = ?\")) { pstmt.setInt(1, id); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public void updateMessage(int id, String text) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"UPDATE messages SET content = ? WHERE id = ?\")) { pstmt.setString(1, text); pstmt.setInt(2, id); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public void cleanOldMessages(long age) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"DELETE FROM messages WHERE timestamp < ?\")) { pstmt.setLong(1, System.currentTimeMillis() - age); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public CompletableFuture<Set<UUID>> getIgnores(UUID who) { return CompletableFuture.supplyAsync(() -> { Set<UUID> set = new HashSet<>(); try (PreparedStatement pstmt = connection.prepareStatement(\"SELECT uuid_to FROM ignores WHERE uuid_from = ?\")) { pstmt.setString(1, who.toString()); try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) set.add(UUID.fromString(rs.getString(\"uuid_to\"))); } } catch (SQLException ignored) {} return set; }, dbExecutor); }
    public void addIgnore(UUID who, UUID target) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"INSERT OR IGNORE INTO ignores (uuid_from, uuid_to) VALUES (?, ?)\")) { pstmt.setString(1, who.toString()); pstmt.setString(2, target.toString()); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public void removeIgnore(UUID who, UUID target) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"DELETE FROM ignores WHERE uuid_from = ? AND uuid_to = ?\")) { pstmt.setString(1, who.toString()); pstmt.setString(2, target.toString()); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public void clearIgnores(UUID who) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"DELETE FROM ignores WHERE uuid_from = ?\")) { pstmt.setString(1, who.toString()); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public CompletableFuture<String> getDefaultChannel(UUID uuid) { return CompletableFuture.supplyAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"SELECT default_channel FROM player_settings WHERE uuid = ?\")) { pstmt.setString(1, uuid.toString()); try (ResultSet rs = pstmt.executeQuery()) { if (rs.next()) return rs.getString(\"default_channel\"); } } catch (SQLException ignored) {} return null; }, dbExecutor); }
    public void saveDefaultChannel(UUID uuid, String ch) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"INSERT INTO player_settings (uuid, default_channel) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET default_channel = ?\")) { pstmt.setString(1, uuid.toString()); pstmt.setString(2, ch); pstmt.setString(3, ch); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public void clearDefaultChannel(UUID uuid) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"DELETE FROM player_settings WHERE uuid = ?\")) { pstmt.setString(1, uuid.toString()); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public CompletableFuture<Boolean> getTagsDisabled(UUID uuid) { return CompletableFuture.supplyAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"SELECT disabled FROM player_tags WHERE uuid = ?\")) { pstmt.setString(1, uuid.toString()); try (ResultSet rs = pstmt.executeQuery()) { if (rs.next()) return rs.getBoolean(\"disabled\"); } } catch (SQLException ignored) {} return false; }, dbExecutor); }
    public void saveTagsDisabled(UUID uuid, boolean d) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"INSERT INTO player_tags (uuid, disabled) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET disabled = ?\")) { pstmt.setString(1, uuid.toString()); pstmt.setBoolean(2, d); pstmt.setBoolean(3, d); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public void clearTagsDisabled(UUID uuid) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"DELETE FROM player_tags WHERE uuid = ?\")) { pstmt.setString(1, uuid.toString()); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public void close() { try { if (connection != null && !connection.isClosed()) connection.close(); dbExecutor.shutdown(); } catch (SQLException ignored) {} }
    public void logMessage(int id, UUID uuid, String c) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"INSERT INTO messages (id, player_uuid, content, timestamp) VALUES (?, ?, ?, ?)\")) { pstmt.setInt(1, id); pstmt.setString(2, uuid.toString()); pstmt.setString(3, c); pstmt.setLong(4, System.currentTimeMillis()); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
    public void logSystemMessage(int id, String c) { CompletableFuture.runAsync(() -> { try (PreparedStatement pstmt = connection.prepareStatement(\"INSERT INTO messages (id, player_uuid, content, timestamp) VALUES (?, ?, ?, ?)\")) { pstmt.setInt(1, id); pstmt.setString(2, \"SYSTEM\"); pstmt.setString(3, c); pstmt.setLong(4, System.currentTimeMillis()); pstmt.executeUpdate(); } catch (SQLException ignored) {} }, dbExecutor); }
}
