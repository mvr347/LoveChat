package me.lovelace.lovechat.managers;

import me.lovelace.lovechat.Lovechat;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class DatabaseManager {
    private final Lovechat plugin;
    private Connection connection;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean isShuttingDown = false;

    public DatabaseManager(Lovechat plugin) { this.plugin = plugin; }

    public void init() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) plugin.getLogger().warning("Error creating folder");
            File dbFile = new File(dataFolder, plugin.getConfig().getString("database.file", "chat.db"));
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
        } catch (SQLException e) {
            plugin.getLogger().severe("SQLite error: " + e.getMessage());
            connection = null;
        }
    }

    /** All DB methods below run on {@code dbExecutor} (single-threaded, so no explicit
     *  synchronization is needed between them) — but a caller can still race against
     *  {@link #close()}, or against a connection that never got initialized in the first
     *  place if {@link #init()} failed. Guards every query. */
    private boolean notReady() {
        return connection == null || isShuttingDown;
    }
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, player_uuid TEXT, content TEXT, timestamp LONG)");
            stmt.execute("CREATE TABLE IF NOT EXISTS ignores (uuid_from TEXT, uuid_to TEXT, PRIMARY KEY(uuid_from, uuid_to))");
            stmt.execute("CREATE TABLE IF NOT EXISTS player_settings (uuid TEXT PRIMARY KEY, default_channel TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS player_tags (uuid TEXT PRIMARY KEY, disabled BOOLEAN)");
            stmt.execute("CREATE TABLE IF NOT EXISTS player_stats (uuid TEXT PRIMARY KEY, message_count INTEGER DEFAULT 0)");
        }
    }

    private void logSqlWarning(String action, SQLException e) {
        plugin.getLogger().log(Level.WARNING, "SQLite error while " + action + ": " + e.getMessage(), e);
    }

    public CompletableFuture<Integer> getMessageCount(UUID uuid) { return CompletableFuture.supplyAsync(() -> { if (notReady()) return 0; try (PreparedStatement pstmt = connection.prepareStatement("SELECT message_count FROM player_stats WHERE uuid = ?")) { pstmt.setString(1, uuid.toString()); try (ResultSet rs = pstmt.executeQuery()) { if (rs.next()) return rs.getInt("message_count"); } } catch (SQLException e) { logSqlWarning("reading message count", e); } return 0; }, dbExecutor); }
    public void incrementMessageCount(UUID uuid) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("INSERT INTO player_stats (uuid, message_count) VALUES (?, 1) ON CONFLICT(uuid) DO UPDATE SET message_count = message_count + 1")) { pstmt.setString(1, uuid.toString()); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("incrementing message count", e); } }, dbExecutor); }
    /** Named "Sync" for its external API contract (caller expects the delete to be done when
     *  this returns), but actually routed through dbExecutor and blocked on — running it
     *  directly on the caller's thread would race the same Connection against dbExecutor's
     *  other queries, since a JDBC Connection isn't safe for concurrent use across threads. */
    public void clearAllMessagesSync() {
        if (notReady()) return;
        try {
            dbExecutor.submit(() -> {
                try (Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("DELETE FROM messages");
                } catch (SQLException e) {
                    logSqlWarning("clearing messages", e);
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to clear messages synchronously: " + e.getMessage(), e);
        }
    }
    public void deleteMessage(int id) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("DELETE FROM messages WHERE id = ?")) { pstmt.setInt(1, id); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("deleting message " + id, e); } }, dbExecutor); }
    public void updateMessage(int id, String text) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("UPDATE messages SET content = ? WHERE id = ?")) { pstmt.setString(1, text); pstmt.setInt(2, id); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("updating message " + id, e); } }, dbExecutor); }
    public void cleanOldMessages(long age) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("DELETE FROM messages WHERE timestamp < ?")) { pstmt.setLong(1, System.currentTimeMillis() - age); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("cleaning old messages", e); } }, dbExecutor); }
    public CompletableFuture<Set<UUID>> getIgnores(UUID who) { return CompletableFuture.supplyAsync(() -> { Set<UUID> set = new HashSet<>(); if (notReady()) return set; try (PreparedStatement pstmt = connection.prepareStatement("SELECT uuid_to FROM ignores WHERE uuid_from = ?")) { pstmt.setString(1, who.toString()); try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) set.add(UUID.fromString(rs.getString("uuid_to"))); } } catch (SQLException e) { logSqlWarning("reading ignores for " + who, e); } return set; }, dbExecutor); }
    public void addIgnore(UUID who, UUID target) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("INSERT OR IGNORE INTO ignores (uuid_from, uuid_to) VALUES (?, ?)")) { pstmt.setString(1, who.toString()); pstmt.setString(2, target.toString()); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("adding ignore " + who + " -> " + target, e); } }, dbExecutor); }
    public void removeIgnore(UUID who, UUID target) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("DELETE FROM ignores WHERE uuid_from = ? AND uuid_to = ?")) { pstmt.setString(1, who.toString()); pstmt.setString(2, target.toString()); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("removing ignore " + who + " -> " + target, e); } }, dbExecutor); }
    public void clearIgnores(UUID who) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("DELETE FROM ignores WHERE uuid_from = ?")) { pstmt.setString(1, who.toString()); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("clearing ignores for " + who, e); } }, dbExecutor); }
    public CompletableFuture<String> getDefaultChannel(UUID uuid) { return CompletableFuture.supplyAsync(() -> { if (notReady()) return null; try (PreparedStatement pstmt = connection.prepareStatement("SELECT default_channel FROM player_settings WHERE uuid = ?")) { pstmt.setString(1, uuid.toString()); try (ResultSet rs = pstmt.executeQuery()) { if (rs.next()) return rs.getString("default_channel"); } } catch (SQLException e) { logSqlWarning("reading default channel for " + uuid, e); } return null; }, dbExecutor); }
    public void saveDefaultChannel(UUID uuid, String ch) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("INSERT INTO player_settings (uuid, default_channel) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET default_channel = ?")) { pstmt.setString(1, uuid.toString()); pstmt.setString(2, ch); pstmt.setString(3, ch); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("saving default channel for " + uuid, e); } }, dbExecutor); }
    public void clearDefaultChannel(UUID uuid) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("DELETE FROM player_settings WHERE uuid = ?")) { pstmt.setString(1, uuid.toString()); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("clearing default channel for " + uuid, e); } }, dbExecutor); }
    public CompletableFuture<Boolean> getTagsDisabled(UUID uuid) { return CompletableFuture.supplyAsync(() -> { if (notReady()) return false; try (PreparedStatement pstmt = connection.prepareStatement("SELECT disabled FROM player_tags WHERE uuid = ?")) { pstmt.setString(1, uuid.toString()); try (ResultSet rs = pstmt.executeQuery()) { if (rs.next()) return rs.getBoolean("disabled"); } } catch (SQLException e) { logSqlWarning("reading tags-disabled for " + uuid, e); } return false; }, dbExecutor); }
    public void saveTagsDisabled(UUID uuid, boolean d) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("INSERT INTO player_tags (uuid, disabled) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET disabled = ?")) { pstmt.setString(1, uuid.toString()); pstmt.setBoolean(2, d); pstmt.setBoolean(3, d); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("saving tags-disabled for " + uuid, e); } }, dbExecutor); }
    public void clearTagsDisabled(UUID uuid) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("DELETE FROM player_tags WHERE uuid = ?")) { pstmt.setString(1, uuid.toString()); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("clearing tags-disabled for " + uuid, e); } }, dbExecutor); }
    public void close() {
        isShuttingDown = true;
        try {
            dbExecutor.shutdown();
            if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Database tasks did not finish in time during shutdown, forcing stop");
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dbExecutor.shutdownNow();
        }
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            logSqlWarning("closing database connection", e);
        }
    }
    public void logMessage(int id, UUID uuid, String c) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("INSERT INTO messages (id, player_uuid, content, timestamp) VALUES (?, ?, ?, ?)")) { pstmt.setInt(1, id); pstmt.setString(2, uuid.toString()); pstmt.setString(3, c); pstmt.setLong(4, System.currentTimeMillis()); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("logging message " + id, e); } }, dbExecutor); }
    public void logSystemMessage(int id, String c) { CompletableFuture.runAsync(() -> { if (notReady()) return; try (PreparedStatement pstmt = connection.prepareStatement("INSERT INTO messages (id, player_uuid, content, timestamp) VALUES (?, ?, ?, ?)")) { pstmt.setInt(1, id); pstmt.setString(2, "SYSTEM"); pstmt.setString(3, c); pstmt.setLong(4, System.currentTimeMillis()); pstmt.executeUpdate(); } catch (SQLException e) { logSqlWarning("logging system message " + id, e); } }, dbExecutor); }
}
