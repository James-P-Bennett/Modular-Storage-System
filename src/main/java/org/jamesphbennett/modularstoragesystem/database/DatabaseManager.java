package org.jamesphbennett.modularstoragesystem.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public class DatabaseManager {

    private final ModularStorageSystem plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(ModularStorageSystem plugin) throws SQLException {
        this.plugin = plugin;
        initializeDatabase();
        createTables();

        // Run migration to fix storage constraints and update cell counts
        migrateDatabaseSchema();
    }

    private void initializeDatabase() throws SQLException {
        try {
            boolean useMysql = plugin.getConfigManager().isMySql();
            String databaseType = useMysql ? "MySQL" : "SQLite";

            plugin.getLogger().info("Connecting to " + databaseType + " database...");

            HikariConfig config;

            if (useMysql) {
                config = getMySQLHikariConfig();
            } else {
                // Create plugin data folder if it doesn't exist (SQLite only)
                if (!plugin.getDataFolder().exists()) {
                    if (!plugin.getDataFolder().mkdirs()) {
                        throw new RuntimeException("Failed to create plugin data directory: " + plugin.getDataFolder().getAbsolutePath());
                    }
                }

                String databasePath = plugin.getDataFolder().getAbsolutePath() + "/storage.db";
                config = getSQLiteHikariConfig(databasePath);
            }

            dataSource = new HikariDataSource(config);

            plugin.getLogger().info("Successfully connected to " + databaseType + " database");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to database!", e);

            // If MySQL failed, optionally fall back to SQLite
            if (plugin.getConfigManager().isMySql()) {
                plugin.getLogger().warning("MySQL connection failed. Falling back to SQLite...");
                try {
                    if (!plugin.getDataFolder().exists()) {
                        if (!plugin.getDataFolder().mkdirs()) {
                            throw new RuntimeException("Failed to create plugin data directory");
                        }
                    }
                    String databasePath = plugin.getDataFolder().getAbsolutePath() + "/storage.db";
                    HikariConfig config = getSQLiteHikariConfig(databasePath);
                    dataSource = new HikariDataSource(config);
                    plugin.getLogger().info("Successfully fell back to SQLite database");
                } catch (Exception fallbackException) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to fall back to SQLite!", fallbackException);
                    throw new SQLException("Database initialization failed completely", fallbackException);
                }
            } else {
                throw new SQLException("Database initialization failed", e);
            }
        }
    }

    private @NotNull HikariConfig getSQLiteHikariConfig(String databasePath) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + databasePath);
        config.setDriverClassName("org.sqlite.JDBC");

        // Connection pool settings from config
        config.setMaximumPoolSize(plugin.getConfig().getInt("database.connection_pool.maximum_pool_size", 10));
        config.setMinimumIdle(plugin.getConfig().getInt("database.connection_pool.minimum_idle", 2));
        config.setConnectionTimeout(plugin.getConfig().getLong("database.connection_pool.connection_timeout", 30000));
        config.setIdleTimeout(plugin.getConfig().getLong("database.connection_pool.idle_timeout", 600000));
        config.setMaxLifetime(plugin.getConfig().getLong("database.connection_pool.max_lifetime", 1800000));

        // SQLite specific settings from config
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("journal_mode", plugin.getConfig().getString("database.sqlite.journal_mode", "WAL"));
        config.addDataSourceProperty("synchronous", plugin.getConfig().getString("database.sqlite.synchronous", "NORMAL"));
        config.addDataSourceProperty("busy_timeout", plugin.getConfig().getString("database.sqlite.busy_timeout", "30000"));

        return config;
    }

    private @NotNull HikariConfig getMySQLHikariConfig() {
        HikariConfig config = new HikariConfig();

        // Build JDBC URL
        String host = plugin.getConfigManager().getMysqlHost();
        int port = plugin.getConfigManager().getMysqlPort();
        String database = plugin.getConfigManager().getMysqlDatabase();
        boolean useSsl = plugin.getConfigManager().isMysqlUseSsl();

        String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=%s", host, port, database, useSsl);
        config.setJdbcUrl(jdbcUrl);

        // MySQL credentials
        config.setUsername(plugin.getConfigManager().getMysqlUsername());
        config.setPassword(plugin.getConfigManager().getMysqlPassword());

        // Driver class
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // Connection pool settings from config
        config.setMaximumPoolSize(plugin.getConfig().getInt("database.connection_pool.maximum_pool_size", 10));
        config.setMinimumIdle(plugin.getConfig().getInt("database.connection_pool.minimum_idle", 2));
        config.setConnectionTimeout(plugin.getConfig().getLong("database.connection_pool.connection_timeout", 30000));
        config.setIdleTimeout(plugin.getConfig().getLong("database.connection_pool.idle_timeout", 600000));
        config.setMaxLifetime(plugin.getConfig().getLong("database.connection_pool.max_lifetime", 1800000));

        // MySQL specific settings from config
        String useUnicode = plugin.getConfig().getString("database.mysql.properties.useUnicode", "true");
        String characterEncoding = plugin.getConfig().getString("database.mysql.properties.characterEncoding", "utf8");

        config.addDataSourceProperty("useUnicode", useUnicode);
        config.addDataSourceProperty("characterEncoding", characterEncoding);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        return config;
    }

    /**
     * Migrate database to add tier support if needed
     */
    private void migrateTierSupport() throws SQLException {
        try (Connection conn = getConnection()) {
            // Check if tier column exists
            boolean needsTierMigration = false;

            if (plugin.getConfigManager().isMySql()) {
                // MySQL: Use INFORMATION_SCHEMA
                String checkQuery = """
                    SELECT COUNT(*) as count FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'storage_disks' AND COLUMN_NAME = 'tier'
                    """;
                try (var stmt = conn.prepareStatement(checkQuery)) {
                    stmt.setString(1, plugin.getConfigManager().getMysqlDatabase());
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next() && rs.getInt("count") == 0) {
                            needsTierMigration = true;
                            plugin.getLogger().info("Database migration needed - adding tier support to storage_disks");
                        }
                    }
                }
            } else {
                // SQLite: Use PRAGMA
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery("PRAGMA table_info(storage_disks)")) {
                    boolean hasTierColumn = false;
                    while (rs.next()) {
                        String columnName = rs.getString("name");
                        if ("tier".equals(columnName)) {
                            hasTierColumn = true;
                            break;
                        }
                    }

                    if (!hasTierColumn) {
                        needsTierMigration = true;
                        plugin.getLogger().info("Database migration needed - adding tier support to storage_disks");
                    }
                }
            }

            if (needsTierMigration) {
                conn.setAutoCommit(false);

                try {
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE storage_disks ADD COLUMN tier TEXT DEFAULT '1k'");
                    }

                    // Update existing disks to 1k tier (safe default)
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("UPDATE storage_disks SET tier = '1k' WHERE tier IS NULL");
                    }

                    conn.commit();
                    plugin.getLogger().info("Successfully added tier support to storage_disks table");

                } catch (Exception e) {
                    conn.rollback();
                    throw new SQLException("Failed to migrate tier support", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * CRITICAL FIX: Update all storage disks to have 64 max_cells instead of old values
     */
    private void migrateCellCounts() throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Update all storage disks to have 64 max_cells (new standard)
                try (var stmt = conn.prepareStatement(
                        "UPDATE storage_disks SET max_cells = ? WHERE max_cells != ?")) {
                    stmt.setInt(1, 64); // New standard
                    stmt.setInt(2, 64); // Only update if not already 64

                }

                conn.commit();
                plugin.getLogger().info("Cell count migration completed successfully");

            } catch (Exception e) {
                conn.rollback();
                throw new SQLException("Failed to migrate cell counts", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }


    /**
     * Migrate exporter_filters table to include slot_target column for furnace slot routing
     */
    private void migrateSlotTargeting() throws SQLException {
        try (Connection conn = getConnection()) {
            // Check if slot_target column exists in exporter_filters
            boolean needsMigration = false;

            if (plugin.getConfigManager().isMySql()) {
                // MySQL: Use INFORMATION_SCHEMA
                String checkQuery = """
                    SELECT COUNT(*) as count FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'exporter_filters' AND COLUMN_NAME = 'slot_target'
                    """;
                try (var stmt = conn.prepareStatement(checkQuery)) {
                    stmt.setString(1, plugin.getConfigManager().getMysqlDatabase());
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next() && rs.getInt("count") == 0) {
                            needsMigration = true;
                            plugin.getLogger().info("Database migration needed - adding slot_target column to exporter_filters");
                        }
                    }
                }
            } else {
                // SQLite: Use PRAGMA
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery("PRAGMA table_info(exporter_filters)")) {
                    boolean hasSlotTargetColumn = false;
                    while (rs.next()) {
                        String columnName = rs.getString("name");
                        if ("slot_target".equals(columnName)) {
                            hasSlotTargetColumn = true;
                            break;
                        }
                    }

                    if (!hasSlotTargetColumn) {
                        needsMigration = true;
                        plugin.getLogger().info("Database migration needed - adding slot_target column to exporter_filters");
                    }
                }
            }

            if (needsMigration) {
                conn.setAutoCommit(false);

                try {
                    // Add slot_target column with default value 'generic'
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE exporter_filters ADD COLUMN slot_target TEXT DEFAULT 'generic'");
                    }

                    // Update existing filters to 'generic' (safe default for backward compatibility)
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("UPDATE exporter_filters SET slot_target = 'generic' WHERE slot_target IS NULL");
                    }

                    conn.commit();
                    plugin.getLogger().info("Successfully added slot_target column to exporter_filters table");

                } catch (Exception e) {
                    conn.rollback();
                    throw new SQLException("Failed to migrate slot targeting support", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * Migrate importers table to include bottle_xp column for XP bottling feature
     */
    private void migrateBottleXpSupport() throws SQLException {
        try (Connection conn = getConnection()) {
            // Check if bottle_xp column exists in importers
            boolean needsMigration = false;

            if (plugin.getConfigManager().isMySql()) {
                // MySQL: Use INFORMATION_SCHEMA
                String checkQuery = """
                    SELECT COUNT(*) as count FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'importers' AND COLUMN_NAME = 'bottle_xp'
                    """;
                try (var stmt = conn.prepareStatement(checkQuery)) {
                    stmt.setString(1, plugin.getConfigManager().getMysqlDatabase());
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next() && rs.getInt("count") == 0) {
                            needsMigration = true;
                            plugin.getLogger().info("Database migration needed - adding bottle_xp column to importers");
                        }
                    }
                }
            } else {
                // SQLite: Use PRAGMA
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery("PRAGMA table_info(importers)")) {
                    boolean hasBottleXpColumn = false;
                    while (rs.next()) {
                        String columnName = rs.getString("name");
                        if ("bottle_xp".equals(columnName)) {
                            hasBottleXpColumn = true;
                            break;
                        }
                    }

                    if (!hasBottleXpColumn) {
                        needsMigration = true;
                        plugin.getLogger().info("Database migration needed - adding bottle_xp column to importers");
                    }
                }
            }

            if (needsMigration) {
                conn.setAutoCommit(false);

                try {
                    // Add bottle_xp column with default value false
                    String boolType = plugin.getConfigManager().isMySql() ? "BOOLEAN" : "INTEGER";
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE importers ADD COLUMN bottle_xp " + boolType + " DEFAULT " + (plugin.getConfigManager().isMySql() ? "false" : "0"));
                    }

                    // Update existing importers to have bottle_xp = false (safe default)
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("UPDATE importers SET bottle_xp = " + (plugin.getConfigManager().isMySql() ? "false" : "0") + " WHERE bottle_xp IS NULL");
                    }

                    conn.commit();
                    plugin.getLogger().info("Successfully added bottle_xp column to importers table");

                } catch (Exception e) {
                    conn.rollback();
                    throw new SQLException("Failed to migrate bottle XP support", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * Migrate exporter_filters table to include item_data column
     */
    private void migrateExporterFilters() throws SQLException {
        try (Connection conn = getConnection()) {
            // Check if item_data column exists in exporter_filters
            boolean needsMigration = false;

            if (plugin.getConfigManager().isMySql()) {
                // MySQL: Use INFORMATION_SCHEMA
                String checkQuery = """
                    SELECT COUNT(*) as count FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'exporter_filters' AND COLUMN_NAME = 'item_data'
                    """;
                try (var stmt = conn.prepareStatement(checkQuery)) {
                    stmt.setString(1, plugin.getConfigManager().getMysqlDatabase());
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next() && rs.getInt("count") == 0) {
                            needsMigration = true;
                            plugin.getLogger().info("Database migration needed - adding item_data column to exporter_filters");
                        }
                    }
                }
            } else {
                // SQLite: Use PRAGMA
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery("PRAGMA table_info(exporter_filters)")) {
                    boolean hasItemDataColumn = false;
                    while (rs.next()) {
                        String columnName = rs.getString("name");
                        if ("item_data".equals(columnName)) {
                            hasItemDataColumn = true;
                            break;
                        }
                    }

                    if (!hasItemDataColumn) {
                        needsMigration = true;
                        plugin.getLogger().info("Database migration needed - adding item_data column to exporter_filters");
                    }
                }
            }

            if (needsMigration) {
                conn.setAutoCommit(false);

                try {
                    // Add item_data column
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE exporter_filters ADD COLUMN item_data TEXT");
                    }

                    conn.commit();
                    plugin.getLogger().info("Successfully added item_data column to exporter_filters table");

                } catch (Exception e) {
                    conn.rollback();
                    throw new SQLException("Failed to migrate exporter_filters table", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * Get table creation queries based on database type
     */
    private String[] getTableCreationQueries() {
        boolean isMySQL = plugin.getConfigManager().isMySql();

        // Data type mappings
        String textType = isMySQL ? "VARCHAR(255)" : "TEXT";
        String longTextType = "TEXT"; // TEXT works in both
        String intType = isMySQL ? "INT" : "INTEGER";
        String autoIncrement = isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";
        String booleanType = "BOOLEAN"; // Both databases support BOOLEAN
        String timestampDefault = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP"; // Same for both
        String timestampUpdate = isMySQL ? "TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" : "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";

        return new String[] {
                // Networks table
                String.format("""
            CREATE TABLE IF NOT EXISTS networks (
                network_id %s PRIMARY KEY,
                owner_uuid %s NOT NULL,
                created_at %s,
                last_accessed %s
            )
            """, textType, textType, timestampDefault, timestampUpdate),

                // Network blocks table
                String.format("""
            CREATE TABLE IF NOT EXISTS network_blocks (
                id %s PRIMARY KEY %s,
                network_id %s NOT NULL,
                world_name %s NOT NULL,
                x %s NOT NULL,
                y %s NOT NULL,
                z %s NOT NULL,
                block_type %s NOT NULL,
                created_at %s,
                FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE CASCADE,
                UNIQUE(world_name, x, y, z)
            )
            """, intType, autoIncrement, textType, textType, intType, intType, intType, textType, timestampDefault),

                // Custom block markers table - tracks which blocks are our custom items
                String.format("""
            CREATE TABLE IF NOT EXISTS custom_block_markers (
                id %s PRIMARY KEY %s,
                world_name %s NOT NULL,
                x %s NOT NULL,
                y %s NOT NULL,
                z %s NOT NULL,
                block_type %s NOT NULL,
                created_at %s,
                UNIQUE(world_name, x, y, z)
            )
            """, intType, autoIncrement, textType, intType, intType, intType, textType, timestampDefault),

                // Storage disks table
                String.format("""
            CREATE TABLE IF NOT EXISTS storage_disks (
                disk_id %s PRIMARY KEY,
                crafter_uuid %s NOT NULL,
                crafter_name %s NOT NULL,
                network_id %s,
                tier %s DEFAULT '1k',
                max_cells %s NOT NULL DEFAULT 64,
                used_cells %s NOT NULL DEFAULT 0,
                created_at %s,
                updated_at %s,
                FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE SET NULL
            )
            """, textType, textType, textType, textType, textType, intType, intType, timestampDefault, timestampUpdate),

                // Drive bay slots table
                String.format("""
            CREATE TABLE IF NOT EXISTS drive_bay_slots (
                id %s PRIMARY KEY %s,
                network_id %s NOT NULL,
                world_name %s NOT NULL,
                x %s NOT NULL,
                y %s NOT NULL,
                z %s NOT NULL,
                slot_number %s NOT NULL,
                disk_id %s,
                created_at %s,
                FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE CASCADE,
                FOREIGN KEY (disk_id) REFERENCES storage_disks(disk_id) ON DELETE SET NULL,
                UNIQUE(world_name, x, y, z, slot_number),
                CHECK (slot_number >= 0 AND slot_number < 7)
            )
            """, intType, autoIncrement, textType, textType, intType, intType, intType, intType, textType, timestampDefault),

                // Storage items table
                String.format("""
                CREATE TABLE IF NOT EXISTS storage_items (
                    id %s PRIMARY KEY %s,
                    disk_id %s NOT NULL,
                    item_hash %s NOT NULL,
                    item_data %s NOT NULL,
                    quantity %s NOT NULL DEFAULT 0,
                    max_stack_size %s NOT NULL DEFAULT 64,
                    created_at %s,
                    updated_at %s,
                    FOREIGN KEY (disk_id) REFERENCES storage_disks(disk_id) ON DELETE CASCADE,
                    CHECK (quantity >= 0 AND quantity <= 8128)
                )
                """, intType, autoIncrement, textType, textType, longTextType, intType, intType, timestampDefault, timestampUpdate),

                // Exporters table
                String.format("""
                CREATE TABLE IF NOT EXISTS exporters (
                    exporter_id %s PRIMARY KEY,
                    network_id %s NOT NULL,
                    world_name %s NOT NULL,
                    x %s NOT NULL,
                    y %s NOT NULL,
                    z %s NOT NULL,
                    enabled %s NOT NULL DEFAULT true,
                    last_export TIMESTAMP,
                    created_at %s,
                    updated_at %s,
                    FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE CASCADE,
                    UNIQUE(world_name, x, y, z)
                )
                """, textType, textType, textType, intType, intType, intType, booleanType, timestampDefault, timestampUpdate),

                // Exporter filters table
                String.format("""
                CREATE TABLE IF NOT EXISTS exporter_filters (
                    id %s PRIMARY KEY %s,
                    exporter_id %s NOT NULL,
                    item_hash %s NOT NULL,
                    item_data %s,
                    filter_type %s NOT NULL DEFAULT 'whitelist',
                    created_at %s,
                    FOREIGN KEY (exporter_id) REFERENCES exporters(exporter_id) ON DELETE CASCADE,
                    UNIQUE(exporter_id, item_hash, filter_type)
                )
                """, intType, autoIncrement, textType, textType, longTextType, textType, timestampDefault),

                // Importers table
                String.format("""
                CREATE TABLE IF NOT EXISTS importers (
                    importer_id %s PRIMARY KEY,
                    network_id %s NOT NULL,
                    world_name %s NOT NULL,
                    x %s NOT NULL,
                    y %s NOT NULL,
                    z %s NOT NULL,
                    enabled %s NOT NULL DEFAULT true,
                    last_import TIMESTAMP,
                    created_at %s,
                    updated_at %s,
                    FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE CASCADE,
                    UNIQUE(world_name, x, y, z)
                )
                """, textType, textType, textType, intType, intType, intType, booleanType, timestampDefault, timestampUpdate),

                // Importer filters table
                String.format("""
                CREATE TABLE IF NOT EXISTS importer_filters (
                    id %s PRIMARY KEY %s,
                    importer_id %s NOT NULL,
                    item_hash %s NOT NULL,
                    item_data %s,
                    filter_type %s NOT NULL DEFAULT 'whitelist',
                    created_at %s,
                    FOREIGN KEY (importer_id) REFERENCES importers(importer_id) ON DELETE CASCADE,
                    UNIQUE(importer_id, item_hash, filter_type)
                )
                """, intType, autoIncrement, textType, textType, longTextType, textType, timestampDefault),

                // Security terminals table
                String.format("""
                CREATE TABLE IF NOT EXISTS security_terminals (
                    terminal_id %s PRIMARY KEY,
                    world_name %s NOT NULL,
                    x %s NOT NULL,
                    y %s NOT NULL,
                    z %s NOT NULL,
                    owner_uuid %s NOT NULL,
                    owner_name %s NOT NULL,
                    network_id %s,
                    created_at %s,
                    FOREIGN KEY (network_id) REFERENCES networks(network_id) ON DELETE SET NULL,
                    UNIQUE(world_name, x, y, z)
                )
                """, textType, textType, intType, intType, intType, textType, textType, textType, timestampDefault),

                // Security terminal trusted players table
                String.format("""
                CREATE TABLE IF NOT EXISTS security_terminal_players (
                    id %s PRIMARY KEY %s,
                    terminal_id %s NOT NULL,
                    player_uuid %s NOT NULL,
                    player_name %s NOT NULL,
                    drive_bay_access %s NOT NULL DEFAULT false,
                    block_modification_access %s NOT NULL DEFAULT false,
                    skin_texture_url %s,
                    added_at %s,
                    FOREIGN KEY (terminal_id) REFERENCES security_terminals(terminal_id) ON DELETE CASCADE,
                    UNIQUE(terminal_id, player_uuid)
                )
                """, intType, autoIncrement, textType, textType, textType, booleanType, booleanType, textType, timestampDefault)
        };
    }

    private void createTables() throws SQLException {
        String[] tableCreationQueries = getTableCreationQueries();
        String[] indexCreationQueries = getIndexCreationQueries();

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                // Create tables
                for (String query : tableCreationQueries) {
                    stmt.execute(query);
                }

                // Create indexes
                for (String query : indexCreationQueries) {
                    stmt.execute(query);
                }

                conn.commit();
                plugin.getLogger().info("Database tables and indexes created successfully!");

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Get index creation queries
     */
    private String[] getIndexCreationQueries() {
        return new String[] {
                "CREATE INDEX IF NOT EXISTS idx_network_blocks_location ON network_blocks(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_network_blocks_network ON network_blocks(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_custom_block_markers_location ON custom_block_markers(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_storage_disks_network ON storage_disks(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_storage_disks_crafter ON storage_disks(crafter_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_storage_disks_tier ON storage_disks(tier)",
                "CREATE INDEX IF NOT EXISTS idx_drive_bay_slots_location ON drive_bay_slots(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_drive_bay_slots_network ON drive_bay_slots(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_drive_bay_slots_disk ON drive_bay_slots(disk_id)",
                "CREATE INDEX IF NOT EXISTS idx_storage_items_disk ON storage_items(disk_id)",
                "CREATE INDEX IF NOT EXISTS idx_storage_items_hash ON storage_items(item_hash)",
                "CREATE INDEX IF NOT EXISTS idx_networks_owner ON networks(owner_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_exporters_location ON exporters(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_exporters_network ON exporters(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_exporters_enabled ON exporters(enabled)",
                "CREATE INDEX IF NOT EXISTS idx_exporter_filters_exporter ON exporter_filters(exporter_id)",
                "CREATE INDEX IF NOT EXISTS idx_exporter_filters_hash ON exporter_filters(item_hash)",
                "CREATE INDEX IF NOT EXISTS idx_importers_location ON importers(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_importers_network ON importers(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_importers_enabled ON importers(enabled)",
                "CREATE INDEX IF NOT EXISTS idx_importer_filters_importer ON importer_filters(importer_id)",
                "CREATE INDEX IF NOT EXISTS idx_importer_filters_hash ON importer_filters(item_hash)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminals_location ON security_terminals(world_name, x, y, z)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminals_owner ON security_terminals(owner_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminals_network ON security_terminals(network_id)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminal_players_terminal ON security_terminal_players(terminal_id)",
                "CREATE INDEX IF NOT EXISTS idx_security_terminal_players_uuid ON security_terminal_players(player_uuid)"
        };
    }

    /**
     * Get a connection from the pool
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Database connection pool is not available");
        }
        return dataSource.getConnection();
    }

    /**
     * Migrate database to remove the unique constraint that prevents multiple cells of same item type
     * AND update cell counts to 64
     */
    private void migrateDatabaseSchema() throws SQLException {
        try (Connection conn = getConnection()) {
            // First run tier support migration
            migrateTierSupport();

            // Then run cell count migration
            migrateCellCounts();

            // Run exporter filters migration
            migrateExporterFilters();

            // Run slot targeting migration
            migrateSlotTargeting();

            // Run bottle XP support migration
            migrateBottleXpSupport();

            // Check if we need to migrate storage_items table constraint
            boolean needsMigration = false;

            if (plugin.getConfigManager().isMySql()) {
                // MySQL: Check for unique constraint via INFORMATION_SCHEMA
                String checkQuery = """
                    SELECT COUNT(*) as count FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                    WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'storage_items'
                    AND CONSTRAINT_TYPE = 'UNIQUE' AND CONSTRAINT_NAME LIKE '%disk_id%item_hash%'
                    """;
                try (var stmt = conn.prepareStatement(checkQuery)) {
                    stmt.setString(1, plugin.getConfigManager().getMysqlDatabase());
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next() && rs.getInt("count") > 0) {
                            needsMigration = true;
                            plugin.getLogger().info("Database migration needed - removing unique constraint on storage_items");
                        }
                    }
                }
            } else {
                // SQLite: Check table definition
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='storage_items'")) {
                    if (rs.next()) {
                        String tableSql = rs.getString("sql");
                        if (tableSql.contains("UNIQUE(disk_id, item_hash)")) {
                            needsMigration = true;
                            plugin.getLogger().info("Database migration needed - removing unique constraint on storage_items");
                        }
                    }
                }
            }

            if (needsMigration) {
                conn.setAutoCommit(false);

                try {
                    if (plugin.getConfigManager().isMySql()) {
                        // MySQL: Drop constraint and recreate table
                        // Step 1: Create new table
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("""
                            CREATE TABLE storage_items_new (
                                id INT PRIMARY KEY AUTO_INCREMENT,
                                disk_id VARCHAR(255) NOT NULL,
                                item_hash VARCHAR(255) NOT NULL,
                                item_data TEXT NOT NULL,
                                quantity INT NOT NULL DEFAULT 0,
                                max_stack_size INT NOT NULL DEFAULT 64,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                FOREIGN KEY (disk_id) REFERENCES storage_disks(disk_id) ON DELETE CASCADE,
                                CHECK (quantity >= 0 AND quantity <= 8128)
                            )
                            """);
                        }

                        // Step 2: Copy data
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("""
                            INSERT INTO storage_items_new
                            (id, disk_id, item_hash, item_data, quantity, max_stack_size, created_at, updated_at)
                            SELECT id, disk_id, item_hash, item_data, quantity, max_stack_size, created_at, updated_at
                            FROM storage_items
                            """);
                        }

                        // Step 3: Drop old table
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("DROP TABLE storage_items");
                        }

                        // Step 4: Rename new table
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("RENAME TABLE storage_items_new TO storage_items");
                        }

                        // Step 5: Recreate indexes
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("CREATE INDEX idx_storage_items_disk ON storage_items(disk_id)");
                            stmt.execute("CREATE INDEX idx_storage_items_hash ON storage_items(item_hash)");
                        }
                    } else {
                        // SQLite: Use existing migration logic
                        // Step 1: Create new table without the unique constraint and with higher quantity limit
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("""
                            CREATE TABLE storage_items_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                disk_id TEXT NOT NULL,
                                item_hash TEXT NOT NULL,
                                item_data TEXT NOT NULL,
                                quantity INTEGER NOT NULL DEFAULT 0,
                                max_stack_size INTEGER NOT NULL DEFAULT 64,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                FOREIGN KEY (disk_id) REFERENCES storage_disks(disk_id) ON DELETE CASCADE,
                                CHECK (quantity >= 0 AND quantity <= 8128)
                            )
                            """);
                        }

                        // Step 2: Copy data from old table to new table
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("""
                            INSERT INTO storage_items_new
                            (id, disk_id, item_hash, item_data, quantity, max_stack_size, created_at, updated_at)
                            SELECT id, disk_id, item_hash, item_data, quantity, max_stack_size, created_at, updated_at
                            FROM storage_items
                            """);
                        }

                        // Step 3: Drop old table
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("DROP TABLE storage_items");
                        }

                        // Step 4: Rename new table
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("ALTER TABLE storage_items_new RENAME TO storage_items");
                        }

                        // Step 5: Recreate indexes
                        try (var stmt = conn.createStatement()) {
                            stmt.execute("CREATE INDEX IF NOT EXISTS idx_storage_items_disk ON storage_items(disk_id)");
                            stmt.execute("CREATE INDEX IF NOT EXISTS idx_storage_items_hash ON storage_items(item_hash)");
                        }
                    }

                    conn.commit();
                    plugin.getLogger().info("Database migration completed successfully - multiple cells per item type now allowed with higher quantity limits");

                } catch (Exception e) {
                    conn.rollback();
                    throw new SQLException("Database migration failed", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * Execute a database transaction
     */
    public void executeTransaction(DatabaseTransaction transaction) throws SQLException {
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            transaction.execute(conn);

            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    plugin.getLogger().log(Level.WARNING, "Failed to rollback transaction", rollbackEx);
                }
            }
            throw new SQLException("Transaction failed", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException closeEx) {
                    plugin.getLogger().log(Level.WARNING, "Failed to close connection", closeEx);
                }
            }
        }
    }

    /**
     * Execute a simple update query
     */
    public int executeUpdate(String sql, Object... parameters) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < parameters.length; i++) {
                stmt.setObject(i + 1, parameters[i]);
            }

            return stmt.executeUpdate();
        }
    }

    /**
     * Shutdown the database connection pool
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool shutdown successfully!");
        }
    }

    /**
     * Functional interface for database transactions
     */
    @FunctionalInterface
    public interface DatabaseTransaction {
        void execute(Connection connection) throws Exception;
    }
}