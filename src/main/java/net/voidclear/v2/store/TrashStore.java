package net.voidclear.v2.store;

import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Shared, paged item storage. Bukkit inventory objects never leave the owning player thread. */
public final class TrashStore implements AutoCloseable {
    public static final int CAPACITY = 10_000;
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    public record Entry(long id, byte[] bytes) {
        public Entry { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private final Logger logger;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String table;
    private volatile long databaseRevision;
    private final Map<Long, byte[]> items = new LinkedHashMap<>();
    /** Last committed database snapshot, reused only while its revision is unchanged. */
    private final Map<Long, byte[]> baseItems = new LinkedHashMap<>();
    /** True only when the table's positions are known to be exactly 0..n-1 in baseItems order. */
    private boolean baseCompact;
    private final java.util.concurrent.ScheduledExecutorService watcher;
    private long nextId = 1;
    private long bytes;
    private volatile boolean closed;
    private long changeVersion;
    private Long cachedItemCount;
    private record Decoded(byte[] data, ItemStack stack) { }
    private final Map<Long, Decoded> decoded = new LinkedHashMap<>(128, 0.75f, true);
    private long decodedBytes;
    private boolean inTransaction;
    private Connection transactionConnection;
    private java.util.UUID sessionOwner;
    private final List<Runnable> committedEffects = new ArrayList<>();
    /** One parked MySQL connection; a fresh one costs a TCP+auth handshake plus four driver init queries. */
    private final Object idleLock = new Object();
    private Connection idleConnection;
    private boolean idleClosed;

    /** Retained even after a bin clear: a world may still contain an older saved entity. */
    public record SweepReceipt(long sequence, byte[] before, byte[] remainder) {
        public SweepReceipt { before = before.clone(); remainder = remainder == null ? null : remainder.clone(); }
        @Override public byte[] before() { return before.clone(); }
        @Override public byte[] remainder() { return remainder == null ? null : remainder.clone(); }
    }
    private final java.util.Set<java.util.UUID> uncertainSweeps = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Entities with a committed receipt; receipts are never deleted, so this mirrors the table without locking. */
    private final java.util.Set<java.util.UUID> receiptEntities = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public record SweepDeposit(java.util.UUID entity, long appliedSequence, ItemStack stack,
                               java.util.function.BiConsumer<ItemStack, Long> applyRemainder) { }

    public boolean uncertainSweep(java.util.UUID entity) { return uncertainSweeps.contains(entity); }

    // shortcut: only this server's receipts are indexed; a world copied between servers sharing MySQL needs a database lookup here.
    public boolean hasSweepReceipts(java.util.UUID entity) { return receiptEntities.contains(entity); }

    public boolean mysql() { return jdbcUrl.startsWith("jdbc:mysql:"); }

    private static final class Published {
        final List<Entry> entries;
        final long revision;
        final Map<Long,Entry> byId;
        volatile Long count;
        Published(List<Entry> entries, long revision) {
            this.entries=entries; this.revision=revision;
            this.byId=entries.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Entry::id,entry->entry));
        }
    }
    private volatile Published published = new Published(List.of(), 0);

    /** Readers never wait on a JDBC writer, and never see its uncommitted working copy. */
    private void publish() {
        if (!mysql()) return;
        published = new Published(items.entrySet().stream().map(row -> new Entry(row.getKey(), row.getValue())).toList(), databaseRevision);
    }

    private TrashStore(String jdbcUrl, String user, String password, String prefix, Logger logger) {
        this.logger = logger;
        this.jdbcUrl = jdbcUrl;
        this.dbUser = user;
        this.dbPassword = password;
        if (prefix == null || !prefix.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("非法数据库表前缀");
        this.table = prefix + "trash_items";
        try {
            initializeDatabase();
            loadDatabase();
            synchronized (this) { publish(); }
        } catch (RuntimeException failed) {
            synchronized (idleLock) { idleClosed = true; if (idleConnection != null) closeQuietly(idleConnection); idleConnection = null; }
            throw failed;
        }
        if (mysql()) {
            watcher = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "VoidClear-trash-watch");
                thread.setDaemon(true);
                return thread;
            });
            watcher.scheduleWithFixedDelay(() -> {
                try { refreshDatabaseIfSafe(); }
                catch (RuntimeException failed) { logger.log(Level.WARNING, "垃圾箱数据库刷新失败", failed); }
            }, 2, 2, TimeUnit.SECONDS);
        } else watcher = null;
    }

    public static TrashStore sqlite(Path file, Logger logger) {
        return new TrashStore("jdbc:sqlite:" + file.toAbsolutePath(), null, null, "voidclear_", logger);
    }

    public static TrashStore mysql(String url, String user, String password, String prefix, Logger logger) {
        if (url == null || !url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("MySQL JDBC 地址无效");
        return new TrashStore(url, user, password, prefix, logger);
    }

    /** Startup-only SQLite import: rows and receipt commit together; the source is never rewritten. */
    public synchronized void migrateLegacy(Path source) {
        if (mysql() || closed) throw new IllegalStateException("迁移只允许在 SQLite 启动阶段执行");
        if (!Files.isRegularFile(source)) return;
        try (Connection connection = connection()) {
            if (Files.size(source) > MAX_BYTES * 2) throw new IOException("旧垃圾箱文件过大");
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + "_migration (id INT PRIMARY KEY, source_hash VARCHAR(64) NOT NULL)");
            try (var rows = connection.createStatement().executeQuery("SELECT source_hash FROM " + table + "_migration WHERE id=1")) {
                if (rows.next()) {
                    if (!hash.equals(rows.getString(1))) throw new IOException("旧文件与已提交迁移凭据不同，请管理员核查，拒绝重复导入");
                    return; // A retained source must never refill a subsequently emptied bin.
                }
            }
            try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table + "_sweeps")) {
                if (!rows.next() || rows.getLong(1) != 0) throw new IOException("已有清扫凭据但没有迁移凭据，拒绝把旧文件重新导入已清空的垃圾箱");
            }
            try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table + "_player_ops")) {
                if (!rows.next() || rows.getLong(1) != 0) throw new IOException("已有玩家交易日志，拒绝旧文件重导入");
            }
            if (!items.isEmpty()) throw new IllegalStateException("旧垃圾箱与非空数据库同时存在，拒绝自动合并，请管理员核查");
            Map<Long, byte[]> staged = readLegacy(source);
            if (Files.size(source) > MAX_BYTES * 2 || !hash.equals(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))))
                throw new IOException("迁移读取期间源文件发生变化，拒绝导入");
            connection.setAutoCommit(false);
            try {
                try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
                    if (!rows.next() || rows.getLong(1) != 0) throw new SQLException("迁移目标数据库已非空，拒绝覆盖");
                }
                try (var insert = connection.prepareStatement("INSERT INTO " + table
                        + " (id,position,item_data,updated_at) VALUES (?,?,?,?)")) {
                    int position = 0;
                    for (var row : staged.entrySet()) {
                        insert.setLong(1, row.getKey()); insert.setInt(2, position++);
                        insert.setBytes(3, row.getValue()); insert.setLong(4, System.currentTimeMillis()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (var receipt = connection.prepareStatement("INSERT INTO " + table + "_migration (id,source_hash) VALUES (1,?)")) {
                    receipt.setString(1, hash); receipt.executeUpdate();
                }
                connection.createStatement().executeUpdate("UPDATE " + table + "_meta SET revision=revision+1 WHERE id=1");
                connection.commit();
            } catch (SQLException | RuntimeException failed) {
                try { connection.rollback(); } catch (SQLException rollback) { failed.addSuppressed(rollback); }
                throw failed;
            }
            copyInto(items, staged); copyInto(baseItems, staged);
            bytes = items.values().stream().mapToLong(data -> data.length).sum();
            for (long id : items.keySet()) nextId = Math.max(nextId, id + 1);
            databaseRevision++; cachedItemCount = null; changeVersion++;
        } catch (Exception failed) {
            throw new IllegalStateException("旧垃圾箱迁移未确认，拒绝继续启动；保留源文件，请勿手动重复导入", failed);
        }
        try {
            Files.move(source, source.resolveSibling(source.getFileName() + ".migrated"));
        } catch (IOException failed) {
            logger.log(Level.WARNING, "旧垃圾箱已事务导入，但源文件未改名；数据库迁移凭据将阻止重复导入", failed);
        }
    }

    private Connection connection() throws SQLException {
        if (!mysql()) {
            Connection connection = dbUser == null ? DriverManager.getConnection(jdbcUrl) : DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA synchronous=FULL");
                return connection;
            } catch (SQLException failed) {
                try { connection.close(); } catch (SQLException close) { failed.addSuppressed(close); }
                throw failed;
            }
        }
        Connection raw;
        synchronized (idleLock) { raw = idleConnection; idleConnection = null; }
        if (raw != null && !raw.isValid(2)) { closeQuietly(raw); raw = null; }
        if (raw == null) {
            java.util.Properties properties = new java.util.Properties();
            if (dbUser != null) properties.setProperty("user", dbUser);
            if (dbPassword != null) properties.setProperty("password", dbPassword);
            properties.setProperty("connectTimeout", "3000");
            properties.setProperty("socketTimeout", "5000");
            // Lets the release-time rollback after a commit stay local instead of costing a round trip.
            properties.setProperty("useLocalTransactionState", "true");
            properties.setProperty("useLocalSessionState", "true");
            properties.setProperty("elideSetAutoCommits", "true");
            raw = DriverManager.getConnection(jdbcUrl, properties);
        }
        Connection owned = raw;
        boolean[] released = {false};
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(TrashStore.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        if (!released[0]) { released[0] = true; release(owned); }
                        return null;
                    }
                    if (method.getName().equals("isClosed") && method.getParameterCount() == 0 && released[0]) return true;
                    if (released[0]) throw new SQLException("连接已归还");
                    try { return method.invoke(owned, args); }
                    catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
                });
    }

    /** Park a clean connection for reuse; anything uncertain is closed instead. */
    private void release(Connection raw) {
        try {
            if (raw.isClosed()) return;
            if (!raw.getAutoCommit()) { raw.rollback(); raw.setAutoCommit(true); }
        } catch (SQLException failed) { closeQuietly(raw); return; }
        Connection displaced;
        synchronized (idleLock) {
            if (idleClosed) displaced = raw;
            else { displaced = idleConnection; idleConnection = raw; }
        }
        if (displaced != null) closeQuietly(displaced);
    }

    private static void closeQuietly(Connection connection) {
        try { connection.close(); } catch (SQLException ignored) { }
    }

    private void initializeDatabase() {
        String auto = jdbcUrl.startsWith("jdbc:sqlite") ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT PRIMARY KEY AUTO_INCREMENT";
        String engine = mysql() ? " ENGINE=InnoDB" : "";
        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + table + " (id " + auto + ", position INT NOT NULL, item_data BLOB NOT NULL, updated_at BIGINT NOT NULL)" + engine)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + table + "_meta (id INT PRIMARY KEY, revision BIGINT NOT NULL)" + engine)) {
                statement.executeUpdate();
            }
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + "_sweeps (entity_id VARCHAR(36) NOT NULL, sequence BIGINT NOT NULL, before_data BLOB NOT NULL,"
                    + " remainder_data BLOB, PRIMARY KEY(entity_id,sequence))" + engine);
            String journalBlob = mysql() ? "MEDIUMBLOB" : "BLOB";
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + "_player_ops (op_id VARCHAR(36) PRIMARY KEY, player_id VARCHAR(36) NOT NULL,"
                    + " session_id VARCHAR(36) NOT NULL, state VARCHAR(16) NOT NULL, evidence " + journalBlob
                    + " NOT NULL, created_at BIGINT NOT NULL)" + engine);
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + "_players (player_id VARCHAR(36) PRIMARY KEY, op_id VARCHAR(36) NOT NULL,"
                    + " session_id VARCHAR(36) NOT NULL, state VARCHAR(16) NOT NULL)" + engine);
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + "_player_drops (entity_id VARCHAR(36) PRIMARY KEY, op_id VARCHAR(36) NOT NULL,"
                    + " ordinal INT NOT NULL, item_data BLOB NOT NULL, UNIQUE(op_id,ordinal))" + engine);
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + "_sessions (player_id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL)" + engine);
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + "_item_fences (op_id VARCHAR(36) NOT NULL, item_id BIGINT NOT NULL, PRIMARY KEY(op_id,item_id))" + engine);
            connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + "_drop_chunks (entity_id VARCHAR(36) PRIMARY KEY, world_id VARCHAR(36) NOT NULL, chunk_x INT NOT NULL, chunk_z INT NOT NULL)" + engine);
            if (!journalIndexExists(connection)) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE INDEX " + table + "_ops_lookup ON " + table
                            + "_player_ops (player_id,state,session_id)");
                } catch (SQLException failed) {
                    // Another server may finish the same additive schema upgrade first.
                    if (!journalIndexExists(connection)) throw failed;
                }
            }
            if (mysql()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (?,?,?,?,?,?,?,?,?)")) {
                    statement.setString(1, table); statement.setString(2, table + "_meta"); statement.setString(3, table + "_sweeps");
                    statement.setString(4, table + "_player_ops"); statement.setString(5, table + "_players");
                    statement.setString(6, table + "_player_drops");
                    statement.setString(7, table + "_sessions");
                    statement.setString(8, table + "_item_fences");
                    statement.setString(9, table + "_drop_chunks");
                    try (ResultSet rows = statement.executeQuery()) {
                        int found = 0;
                        while (rows.next()) {
                            if (!"InnoDB".equalsIgnoreCase(rows.getString(2))) throw new SQLException("垃圾箱 MySQL 表必须使用 InnoDB：" + rows.getString(1));
                            found++;
                        }
                        if (found != 9) throw new SQLException("无法验证垃圾箱 MySQL 表的事务引擎");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + table + "_meta (id,revision) SELECT 1,0 WHERE NOT EXISTS (SELECT 1 FROM " + table + "_meta WHERE id=1)")) {
                try { statement.executeUpdate(); } catch (SQLException ignored) { /* MySQL may race; existing row is fine. */ }
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("垃圾箱数据库初始化失败", failed);
        }
    }

    private boolean journalIndexExists(Connection connection) throws SQLException {
        try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null,
                table + "_player_ops", false, false)) {
            while (indexes.next()) if ((table + "_ops_lookup").equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
            return false;
        }
    }

    private void loadDatabase() {
        try (Connection connection = connection()) {
            databaseRevision = readRevision(connection, false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,item_data FROM " + table + " ORDER BY position,id")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] data = rows.getBytes(2);
                    if (data == null || data.length == 0 || items.size() >= CAPACITY || bytes + data.length > MAX_BYTES)
                        throw new SQLException("数据库垃圾箱数据无效或超限，拒绝加载不完整快照");
                    long id = rows.getLong(1);
                    items.put(id, data);
                    bytes += data.length;
                    nextId = Math.max(nextId, id + 1);
                }
            }
            copyInto(baseItems, items);
            }
            loadSweepReceipts(connection);
        } catch (SQLException failed) {
            throw new IllegalStateException("垃圾箱数据库读取失败", failed);
        }
    }

    private static Map<Long, byte[]> readLegacy(Path file) {
        Map<Long, byte[]> staged = new LinkedHashMap<>();
        long bytes = 0;
        try {
            if (Files.size(file) > MAX_BYTES * 2) throw new IOException("垃圾箱文件过大");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            if (yaml.getInt("version") != 1 || !yaml.isList("items")) throw new IOException("未知垃圾箱格式");
            for (Object value : yaml.getList("items")) {
                if (!(value instanceof Map<?, ?> row) || !(row.get("id") instanceof Number id)
                        || !(row.get("data") instanceof String encoded)) throw new IOException("无效物品记录");
                long key = id.longValue();
                byte[] data = Base64.getDecoder().decode(encoded);
                if (key < 1 || key == Long.MAX_VALUE || data.length == 0 || staged.containsKey(key)
                        || staged.size() >= CAPACITY || bytes + data.length > MAX_BYTES) {
                    throw new IOException("无效编号或容量超限");
                }
                staged.put(key, data);
                bytes += data.length;
            }
            return staged;
        } catch (Exception broken) {
            throw new IllegalStateException("垃圾箱读取失败，原文件未修改：" + file, broken);
        }
    }

    public int size() {
        if (mysql() && !Thread.holdsLock(this)) return published.entries.size();
        synchronized (this) { return items.size(); }
    }

    /** Actual item quantity, not the number of occupied stacks used for pagination. */
    public long itemCount() {
        if (mysql() && !Thread.holdsLock(this)) {
            Published snapshot = published;
            Long count = snapshot.count;
            if (count == null) snapshot.count = count = snapshot.entries.stream()
                    .mapToLong(row -> ItemStack.deserializeBytes(row.bytes()).getAmount()).sum();
            return count;
        }
        synchronized (this) {
        if (cachedItemCount == null) {
            cachedItemCount = items.entrySet().stream().mapToLong(row -> decoded(row.getKey(), row.getValue()).getAmount()).sum();
        }
        return cachedItemCount;
        }
    }

    /** Cached stacks are read-only; byte arrays in items are replaced, never mutated in place. */
    private ItemStack decoded(long id, byte[] data) {
        Decoded cached = decoded.get(id);
        if (cached != null && (cached.data() == data || same(cached.data(), data))) return cached.stack();
        ItemStack stack = ItemStack.deserializeBytes(data);
        if (cached != null) { decoded.remove(id); decodedBytes -= cached.data().length; }
        // shortcut: cap serialized cache payload at 8 MiB and skip large NBT; measure heap before increasing it.
        if (data.length <= 8192) {
            while (!decoded.isEmpty() && (decoded.size() >= CAPACITY || decodedBytes + data.length > 8L * 1024 * 1024)) {
                Long oldest = decoded.keySet().iterator().next();
                decodedBytes -= decoded.remove(oldest).data().length;
            }
            decoded.put(id, new Decoded(data, stack)); decodedBytes += data.length;
        }
        return stack;
    }

    private void pruneDecoded() {
        var entries = decoded.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!items.containsKey(entry.getKey())) {
                decodedBytes -= entry.getValue().data().length;
                entries.remove();
            }
        }
    }

    public record Snapshot(long revision, List<Entry> entries) {
        public Snapshot { entries = List.copyOf(entries); }
    }
    public long revision() { return mysql() ? published.revision : databaseRevision; }
    public Snapshot snapshot() {
        if (mysql() && !Thread.holdsLock(this)) { Published view=published; return new Snapshot(view.revision,view.entries); }
        synchronized(this) { return new Snapshot(databaseRevision,page(0,CAPACITY)); }
    }
    public boolean isCurrentSelection(List<Entry> selected) {
        if (closed) return false;
        if(mysql() && !Thread.holdsLock(this)) {
            Published view=published;
            var seen=new java.util.HashSet<Long>();
            for(Entry entry:selected) {
                Entry current=view.byId.get(entry.id());
                if(!seen.add(entry.id()) || current==null || !same(current.bytes,entry.bytes)) return false;
            }
            return true;
        }
        synchronized(this) {
            var seen=new java.util.HashSet<Long>();
            for(Entry entry:selected) if(!seen.add(entry.id()) || !same(items.get(entry.id()),entry.bytes)) return false;
            return true;
        }
    }

    public List<Entry> page(int page, int pageSize) {
        if (page < 0 || pageSize < 1) throw new IllegalArgumentException("Invalid page");
        if (mysql() && !Thread.holdsLock(this)) return List.copyOf(publishedPage(page, pageSize));
        synchronized (this) {
        return items.entrySet().stream().skip((long) page * pageSize).limit(pageSize)
                .map(row -> new Entry(row.getKey(), row.getValue())).toList();
        }
    }

    /** A private view; public callers get an independent immutable list. */
    private List<Entry> publishedPage(int page, int pageSize) {
        List<Entry> entries = published.entries;
        int from = (int) Math.min((long) page * pageSize, entries.size());
        int to = (int) Math.min((long) from + pageSize, entries.size());
        return entries.subList(from, to);
    }

    public boolean isCurrentPage(int page, int pageSize, List<Entry> displayed) {
        if (closed || page < 0 || pageSize < 1) return false;
        if (mysql() && !Thread.holdsLock(this)) return samePage(publishedPage(page, pageSize), displayed);
        synchronized (this) {
            long from = Math.min((long) page * pageSize, items.size());
            if (displayed.size() != Math.min(pageSize, items.size() - from)) return false;
            var rows = items.entrySet().stream().skip(from).limit(pageSize).iterator();
            for (Entry expected : displayed) {
                var row = rows.next();
                if (row.getKey() != expected.id || !same(row.getValue(), expected.bytes)) return false;
            }
            return true;
        }
    }

    /** Adds one swept stack. The caller removes the entity only after this method accepts it. */
    public synchronized boolean deposit(ItemStack stack, Consumer<ItemStack> applyRemainder) {
        if (!inTransaction) return transaction(() -> deposit(stack, applyRemainder));
        return depositPlanned(stack, remainder -> afterCommit(() -> applyRemainder.accept(remainder)));
    }

    private boolean depositPlanned(ItemStack stack, Consumer<ItemStack> planRemainder) {
        if (closed || stack == null || stack.isEmpty()) return false;
        ItemStack incoming = stack.clone();
        Map<Long, byte[]> proposed = new LinkedHashMap<>();
        proposed.putAll(items);
        long proposedBytes = bytes;
        boolean changedExisting = false;

        // Match and fill existing stacks first, just like a normal chest.
        for (Map.Entry<Long, byte[]> row : new ArrayList<>(proposed.entrySet())) {
            if (incoming.isEmpty()) break;
            ItemStack existing;
            try {
                existing = decoded(row.getKey(), row.getValue());
            } catch (RuntimeException broken) {
                continue;
            }
            if (!incoming.isSimilar(existing)) continue;
            int room = Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            int moved = Math.min(room, incoming.getAmount());
            if (moved < 1) continue;
            ItemStack combined = existing.clone();
            combined.setAmount(existing.getAmount() + moved);
            byte[] combinedData = combined.serializeAsBytes();
            long total = proposedBytes - row.getValue().length + combinedData.length;
            if (total > MAX_BYTES) continue;
            proposed.put(row.getKey(), combinedData);
            proposedBytes = total;
            incoming.setAmount(incoming.getAmount() - moved);
            changedExisting = true;
        }

        if (!incoming.isEmpty()) {
            byte[] data = incoming.serializeAsBytes();
            long total = proposedBytes + data.length;
            if (proposed.size() >= CAPACITY || total > MAX_BYTES || nextId == Long.MAX_VALUE) {
                if (!changedExisting) return false;
            } else {
                proposed.put(nextId++, data);
                proposedBytes = total;
                incoming.setAmount(0);
            }
        }

        // Remove only what was actually accepted; a remainder stays in the world.
        ItemStack remainder = incoming.isEmpty() ? null : incoming.clone();
        planRemainder.accept(remainder);
        items.clear();
        items.putAll(proposed);
        bytes = proposedBytes;
        changed();
        return true;
    }

    public synchronized List<SweepReceipt> sweepReceipts(java.util.UUID entity) {
        try (var connection = connection()) { return List.copyOf(readSweepReceipts(connection, entity)); }
        catch (SQLException failed) { throw new IllegalStateException("无法读取来源实体凭据", failed); }
    }

    /** The source receipt and accepted bin bytes share one commit, including SQLite. */
    public synchronized boolean depositEntity(java.util.UUID entity, long appliedSequence, ItemStack stack,
                                               java.util.function.BiConsumer<ItemStack, Long> applyRemainder) {
        if (uncertainSweeps.contains(entity)) throw new IllegalStateException("清扫提交结果未确认，来源实体已隔离");
        if (!inTransaction) {
            try { return transaction(() -> depositEntity(entity, appliedSequence, stack, applyRemainder)); }
            catch (RuntimeException failed) { uncertainSweeps.add(entity); throw failed; }
        }
        List<SweepReceipt> receipts = readSweepReceipts(transactionConnection, entity);
        String invalid = invalidSweepSource(receipts, appliedSequence);
        if (invalid != null) throw new IllegalStateException(invalid);
        return depositChecked(new SweepDeposit(entity, appliedSequence, stack, applyRemainder), receipts);
    }

    /**
     * One connection and one bin snapshot for a whole chunk. A source failing its receipt check is isolated and
     * skipped; a database failure isolates every source in the batch because its commit outcome is unknown.
     */
    public synchronized int depositEntities(List<SweepDeposit> batch) {
        List<SweepDeposit> pending = batch.stream().filter(deposit -> !uncertainSweeps.contains(deposit.entity())).toList();
        if (pending.isEmpty()) return 0;
        try {
            return transaction(() -> {
                int accepted = 0;
                for (SweepDeposit deposit : pending) {
                    List<SweepReceipt> receipts = readSweepReceipts(transactionConnection, deposit.entity());
                    String invalid = invalidSweepSource(receipts, deposit.appliedSequence());
                    if (invalid != null) {
                        uncertainSweeps.add(deposit.entity());
                        logger.log(Level.SEVERE, "清扫来源实体已隔离：" + deposit.entity() + "；" + invalid);
                        continue;
                    }
                    if (depositChecked(deposit, receipts)) accepted++;
                }
                return accepted;
            });
        } catch (RuntimeException failed) {
            pending.forEach(deposit -> uncertainSweeps.add(deposit.entity()));
            throw failed;
        }
    }

    private static String invalidSweepSource(List<SweepReceipt> receipts, long appliedSequence) {
        long latest = receipts.isEmpty() ? 0 : receipts.getLast().sequence();
        if (appliedSequence != latest) return "掉落物有未核对的清扫凭据，拒绝再次入箱";
        if (!receipts.isEmpty() && receipts.getLast().remainder() == null) return "来源实体已全部入箱，拒绝复用其编号";
        return null;
    }

    private boolean depositChecked(SweepDeposit deposit, List<SweepReceipt> receipts) {
        long latest = receipts.isEmpty() ? 0 : receipts.getLast().sequence();
        ItemStack[] remainder = new ItemStack[1];
        if (!depositPlanned(deposit.stack(), value -> remainder[0] = value)) return false;
        long sequence = Math.addExact(latest, 1);
        SweepReceipt receipt = new SweepReceipt(sequence, deposit.stack().serializeAsBytes(),
                remainder[0] == null ? null : remainder[0].serializeAsBytes());
        recordSweep(transactionConnection, deposit.entity(), receipt);
        afterCommit(() -> receiptEntities.add(deposit.entity()));
        afterCommit(() -> deposit.applyRemainder().accept(remainder[0], sequence));
        return true;
    }

    private void loadSweepReceipts(Connection connection) throws SQLException {
        // Only the UUID, sequence and payload lengths are needed to build the startup index.
        String beforeLength = mysql() ? "LENGTH(before_data)" : "LENGTH(CAST(before_data AS BLOB))";
        String remainderLength = mysql() ? "LENGTH(remainder_data)" : "LENGTH(CAST(remainder_data AS BLOB))";
        try (var statement = connection.prepareStatement("SELECT entity_id,sequence," + beforeLength + ","
                + remainderLength + " FROM " + table + "_sweeps ORDER BY entity_id,sequence")) {
            if (mysql()) statement.setFetchSize(Integer.MIN_VALUE);
            try (var rows = statement.executeQuery()) {
                java.util.UUID previous = null;
                long sequence = 0;
                boolean terminal = false;
                while (rows.next()) {
                    java.util.UUID entity = java.util.UUID.fromString(rows.getString(1));
                    long current = rows.getLong(2);
                    long before = rows.getLong(3);
                    long remainder = rows.getLong(4);
                    boolean noRemainder = rows.wasNull();
                    if (current < 1 || before < 1 || before > MAX_BYTES
                            || (!noRemainder && (remainder < 1 || remainder > MAX_BYTES)))
                        throw new SQLException("清扫来源凭据损坏，拒绝继续");
                    if (!entity.equals(previous)) { sequence = 0; terminal = false; }
                    if (current != sequence + 1 || terminal)
                        throw new SQLException("清扫来源凭据不连续或终止后重复使用，拒绝继续");
                    previous = entity;
                    sequence = current;
                    terminal = noRemainder;
                    receiptEntities.add(entity);
                }
            }
        }
    }

    private List<SweepReceipt> readSweepReceipts(Connection connection, java.util.UUID entity) {
        List<SweepReceipt> result = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT sequence,before_data,remainder_data FROM "
                + table + "_sweeps WHERE entity_id=? ORDER BY sequence")) {
            statement.setString(1, entity.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    SweepReceipt receipt = sweepReceipt(rows, 1);
                    if (receipt.sequence() != result.size() + 1L || (!result.isEmpty() && result.getLast().remainder() == null))
                        throw new SQLException("清扫来源凭据不连续或终止后重复使用");
                    result.add(receipt);
                }
            }
        } catch (SQLException failed) { throw new IllegalStateException("无法核对清扫来源凭据", failed); }
        return result;
    }

    private static SweepReceipt sweepReceipt(ResultSet rows, int offset) throws SQLException {
        long sequence = rows.getLong(offset);
        byte[] before = rows.getBytes(offset + 1), remainder = rows.getBytes(offset + 2);
        if (sequence < 1 || before == null || before.length == 0 || before.length > MAX_BYTES
                || (remainder != null && (remainder.length == 0 || remainder.length > MAX_BYTES)))
            throw new SQLException("清扫来源凭据损坏，拒绝继续");
        return new SweepReceipt(sequence, before, remainder);
    }

    private void recordSweep(Connection connection, java.util.UUID entity, SweepReceipt receipt) {
        try (var statement = connection.prepareStatement("INSERT INTO " + table
                + "_sweeps (entity_id,sequence,before_data,remainder_data) VALUES (?,?,?,?)")) {
            statement.setString(1, entity.toString()); statement.setLong(2, receipt.sequence());
            statement.setBytes(3, receipt.before()); statement.setBytes(4, receipt.remainder());
            if (statement.executeUpdate() != 1) throw new SQLException("清扫凭据写入行数不符");
        } catch (SQLException failed) { throw new IllegalStateException("清扫凭据写入失败，拒绝入箱", failed); }
    }

    /** Offline compatibility helper for the storage check; production uses deposit(ItemStack, ...). */
    public synchronized boolean collect(byte[] data, Runnable removeEntity) {
        if (closed) return false;
        if (!inTransaction) return transaction(() -> collect(data, removeEntity));
        if (closed || data == null || data.length == 0 || nextId == Long.MAX_VALUE) return false;
        if (items.size() >= CAPACITY || bytes + data.length > MAX_BYTES) return false;
        afterCommit(removeEntity);
        items.put(nextId++, data.clone());
        bytes += data.length;
        changed();
        return true;
    }

    /** Replaces one displayed page after vanilla inventory mechanics have completed. */
    public synchronized boolean replacePage(int page, int pageSize, List<Entry> displayed, List<ItemStack> slots) {
        if (!inTransaction) return transaction(() -> replacePage(page, pageSize, displayed, slots));
        if (closed || page < 0 || pageSize < 1 || displayed.size() > pageSize || slots.size() != pageSize) return false;
        List<Entry> current = page(page, pageSize);
        // IDs survive sweep merges and page shifts; legacy operations without a fence remain globally blocked.
        if (!selectionUnfenced(current)) return false;
        if (!samePage(current, displayed)) return false;

        List<Map.Entry<Long, byte[]>> all = new ArrayList<>(items.entrySet());
        int start = page * pageSize;
        if (start < 0 || start > all.size()) return false;
        int end = Math.min(all.size(), start + displayed.size());
        List<Map.Entry<Long, byte[]>> replacement = new ArrayList<>();
        for (int i = 0; i < pageSize; i++) {
            ItemStack stack = slots.get(i);
            if (stack == null || stack.isEmpty()) continue;
            byte[] data = stack.serializeAsBytes();
            long id = i < displayed.size() && same(displayed.get(i).bytes(), data)
                    ? displayed.get(i).id() : newId();
            replacement.add(Map.entry(id, data));
        }
        List<Map.Entry<Long, byte[]>> result = new ArrayList<>();
        result.addAll(all.subList(0, start));
        result.addAll(replacement);
        if (end < all.size()) result.addAll(all.subList(end, all.size()));
        long totalBytes = result.stream().mapToLong(row -> row.getValue().length).sum();
        if (result.size() > CAPACITY || totalBytes > MAX_BYTES) return false;

        items.clear();
        bytes = 0;
        for (Map.Entry<Long, byte[]> row : result) {
            items.put(row.getKey(), row.getValue());
            bytes += row.getValue().length;
        }
        changed();
        return true;
    }

    public record PlayerCheckpoint(java.util.UUID operation, java.util.UUID session, String state) { }

    public record LoadedDrop(long sequence, byte[] data) {
        public LoadedDrop { data = data.clone(); }
        @Override public byte[] data() { return data.clone(); }
    }

    public record DropChunk(java.util.UUID world, int x, int z) { }

    public synchronized List<DropChunk> playerDropChunks(java.util.UUID player) {
        try (var connection = connection(); var query = connection.prepareStatement("SELECT DISTINCT c.world_id,c.chunk_x,c.chunk_z FROM "
                + table + "_drop_chunks c JOIN " + table + "_player_drops d ON d.entity_id=c.entity_id JOIN "
                + table + "_player_ops o ON o.op_id=d.op_id WHERE o.player_id=? AND o.state='DROPS'")) {
            query.setString(1, player.toString());
            var chunks = new ArrayList<DropChunk>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) chunks.add(new DropChunk(java.util.UUID.fromString(rows.getString(1)), rows.getInt(2), rows.getInt(3)));
            }
            return List.copyOf(chunks);
        } catch (SQLException failed) { throw new IllegalStateException("无法读取掉落来源区块", failed); }
    }

    public synchronized void bindSessionOwner(java.util.UUID owner) {
        if (sessionOwner != null) throw new IllegalStateException("会话归属不可重复绑定");
        sessionOwner = java.util.Objects.requireNonNull(owner);
    }

    private PlayerCheckpoint transactionCheckpoint(java.util.UUID player, java.util.UUID session) throws SQLException {
        // Standalone store checks have no installed player admission service.
        return sessionOwner == null ? playerCheckpoint(transactionConnection, player)
                : sessionCheckpoint(transactionConnection, player, sessionOwner, session, "数据库事务未持有玩家独占会话");
    }

    /** No expiry: a partitioned old server must never lose ownership just because a timer elapsed. */
    public synchronized boolean acquirePlayerSession(java.util.UUID player, java.util.UUID owner, java.util.UUID session) {
        return transaction(() -> {
            try {
                boolean exists;
                try (var query = transactionConnection.prepareStatement("SELECT owner_id FROM " + table + "_sessions WHERE player_id=?")) {
                    query.setString(1, player.toString());
                    try (var rows = query.executeQuery()) {
                        exists = rows.next();
                        if (exists && !owner.toString().equals(rows.getString(1))) return false;
                    }
                }
                try (var write = transactionConnection.prepareStatement(exists
                        ? "UPDATE " + table + "_sessions SET session_id=? WHERE player_id=? AND owner_id=?"
                        : "INSERT INTO " + table + "_sessions (session_id,player_id,owner_id) VALUES (?,?,?)")) {
                    write.setString(1, session.toString()); write.setString(2, player.toString()); write.setString(3, owner.toString());
                    if (write.executeUpdate() != 1) throw new SQLException("玩家会话归属写入行数不符");
                }
                return true;
            } catch (SQLException failed) { throw new IllegalStateException("玩家会话归属未确认", failed); }
        });
    }

    public synchronized boolean ownsPlayerSession(java.util.UUID player, java.util.UUID owner, java.util.UUID session) {
        try (var connection = connection(); var query = connection.prepareStatement("SELECT 1 FROM " + table
                + "_sessions WHERE player_id=? AND owner_id=? AND session_id=?")) {
            query.setString(1, player.toString()); query.setString(2, owner.toString()); query.setString(3, session.toString());
            try (var rows = query.executeQuery()) { return rows.next(); }
        } catch (SQLException failed) { throw new IllegalStateException("无法核对玩家会话归属", failed); }
    }

    /** Keep the normal cross-server write lock until the offline receipt operation has finished. No rows are removed. */
    public synchronized <T> T withOrphanReceipt(java.util.UUID player, java.util.UUID receipt,
                                               java.util.function.Supplier<T> operation) {
        return transaction(() -> {
            if (sessionOwner == null) throw new IllegalStateException("本服会话归属未绑定");
            try {
                try (var query = transactionConnection.prepareStatement("SELECT owner_id FROM " + table + "_sessions WHERE player_id=?")) {
                    query.setString(1, player.toString());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || !sessionOwner.toString().equals(rows.getString(1)))
                            throw new IllegalStateException("没有本服独占归属；请先让玩家尝试登录本服，异服归属不得强制接管");
                    }
                }
                String[] checks = {
                        "SELECT 1 FROM " + table + "_players WHERE player_id=? OR op_id=?",
                        "SELECT 1 FROM " + table + "_player_ops WHERE player_id=? OR op_id=?",
                        "SELECT 1 FROM " + table + "_player_drops WHERE op_id=?",
                        "SELECT 1 FROM " + table + "_item_fences WHERE op_id=?"};
                for (int index = 0; index < checks.length; index++) {
                    try (var query = transactionConnection.prepareStatement(checks[index])) {
                        if (index < 2) { query.setString(1, player.toString()); query.setString(2, receipt.toString()); }
                        else query.setString(1, receipt.toString());
                        try (var rows = query.executeQuery()) {
                            if (rows.next()) throw new IllegalStateException("仍有交易、历史或来源凭据，禁止清理收据；请保留数据核查");
                        }
                    }
                }
                return operation.get();
            } catch (SQLException failed) { throw new IllegalStateException("无法确认收据属于孤立旧记录", failed); }
        });
    }

    public synchronized void releasePlayerSession(java.util.UUID player, java.util.UUID owner, java.util.UUID session) {
        transaction(() -> {
            try (var query = transactionConnection.prepareStatement("DELETE FROM " + table
                    + "_sessions WHERE player_id=? AND owner_id=? AND session_id=?")) {
                query.setString(1, player.toString()); query.setString(2, owner.toString()); query.setString(3, session.toString());
                if (query.executeUpdate() != 1) throw new SQLException("会话已变化，拒绝释放");
                return true;
            } catch (SQLException failed) { throw new IllegalStateException("玩家会话未安全释放", failed); }
        });
    }

    /** Finish an already saved debit/credit, never replay its planned effects. */
    public synchronized boolean recoverSavedPlayer(java.util.UUID player, Path playerFile, String receiptKey) {
        return recoverSavedPlayer(player, playerFile, receiptKey, Map.of());
    }

    public synchronized boolean recoverSavedPlayer(java.util.UUID player, Path playerFile, String receiptKey,
            Map<java.util.UUID, LoadedDrop> loadedDrops) {
        return transaction(() -> {
            try {
                PlayerCheckpoint current = transactionCheckpoint(player, null);
                if (current == null || !current.state().equals("PREPARED")) return false;
                var evidence = new java.util.Properties();
                try (var query = transactionConnection.prepareStatement("SELECT evidence FROM " + table
                        + "_player_ops WHERE op_id=? AND player_id=? AND session_id=? AND state='PREPARED'")) {
                    query.setString(1, current.operation().toString()); query.setString(2, player.toString());
                    query.setString(3, current.session().toString());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) return false;
                        byte[] data = rows.getBytes(1);
                        if (data == null || data.length == 0 || data.length > 8 * 1024 * 1024) return false;
                        try (var input = new java.io.ByteArrayInputStream(data)) { evidence.load(input); }
                    }
                }
                if (!"36".equals(evidence.getProperty("planned.storage.length"))
                        || !"1".equals(evidence.getProperty("planned.cursor.length"))
                        || !"empty".equals(evidence.getProperty("planned.cursor.0"))
                        || !"0".equals(evidence.getProperty("planned.drops.length"))) return false;
                if (evidence.containsKey("source")) {
                    // A remainder must already exist in a loaded world source, or have a terminal receipt; never respawn it.
                    var entity = java.util.UUID.fromString(evidence.getProperty("claim.entity", ""));
                    var parent = java.util.UUID.fromString(evidence.getProperty("claim.parent", ""));
                    long sequence = Long.parseLong(evidence.getProperty("claim.sequence", ""));
                    if (sequence < 1 || !playerDropReady(transactionConnection, entity, parent)) return false;
                    var receipts = readSweepReceipts(transactionConnection, entity);
                    if (sequence > receipts.size() || receipts.getLast().remainder() != null
                            && !loadedDropMatches(transactionConnection, entity, receipts, loadedDrops.get(entity))) return false;
                    var claimed = receipts.get(Math.toIntExact(sequence - 1));
                    String remainder = evidence.getProperty("remainder", "");
                    if (!java.util.Arrays.equals(claimed.before(), java.util.Base64.getDecoder().decode(evidence.getProperty("source")))
                            || !java.util.Arrays.equals(claimed.remainder(), "empty".equals(remainder) ? null
                                    : java.util.Base64.getDecoder().decode(remainder))) return false;
                    for (int i = Math.toIntExact(sequence); i < receipts.size(); i++) {
                        if (!java.util.Arrays.equals(receipts.get(i - 1).remainder(), receipts.get(i).before())) return false;
                    }
                }
                try (var query = transactionConnection.prepareStatement("SELECT 1 FROM " + table + "_player_drops WHERE op_id=?")) {
                    query.setString(1, current.operation().toString());
                    try (var rows = query.executeQuery()) { if (rows.next()) return false; }
                }
                PlayerSaveReceipt.verifyAndForce(playerFile, receiptKey, current.operation().toString());
                writePlayerCheckpoint(player, current.session(), current.operation(), "DONE", current);
                return true;
            } catch (IOException | IllegalArgumentException invalid) {
                logger.log(Level.WARNING, "玩家收据不能证明交易完成，保留隔离：" + player, invalid);
                return false;
            } catch (SQLException failed) { throw new IllegalStateException("玩家落盘收据恢复失败", failed); }
        });
    }

    public synchronized void registerPlayerDrop(java.util.UUID entity, java.util.UUID operation, int ordinal, byte[] data) {
        registerPlayerDrop(entity, operation, ordinal, data, null);
    }

    public synchronized void registerPlayerDrop(java.util.UUID entity, java.util.UUID operation, int ordinal, byte[] data, DropChunk chunk) {
        transaction(() -> {
            try (var insert = transactionConnection.prepareStatement("INSERT INTO " + table
                    + "_player_drops (entity_id,op_id,ordinal,item_data) VALUES (?,?,?,?)")) {
                insert.setString(1, entity.toString()); insert.setString(2, operation.toString());
                insert.setInt(3, ordinal); insert.setBytes(4, data);
                if (insert.executeUpdate() != 1) throw new SQLException("掉落来源日志行数不符");
                if (chunk != null) try (var location = transactionConnection.prepareStatement("INSERT INTO " + table
                        + "_drop_chunks (entity_id,world_id,chunk_x,chunk_z) VALUES (?,?,?,?)")) {
                    location.setString(1, entity.toString()); location.setString(2, chunk.world().toString());
                    location.setInt(3, chunk.x()); location.setInt(4, chunk.z());
                    if (location.executeUpdate() != 1) throw new SQLException("来源区块写入行数不符");
                }
                return true;
            } catch (SQLException failed) { throw new IllegalStateException("掉落来源未确认", failed); }
        });
    }

    private boolean playerDropReady(Connection connection, java.util.UUID entity, java.util.UUID operation) throws SQLException {
        try (var query = connection.prepareStatement("SELECT o.state FROM " + table + "_player_drops d JOIN "
                + table + "_player_ops o ON d.op_id=o.op_id WHERE d.entity_id=? AND d.op_id=?")) {
            query.setString(1, entity.toString()); query.setString(2, operation.toString());
            try (var rows = query.executeQuery()) { return rows.next() && java.util.Set.of("DONE", "DROPS", "CURSOR").contains(rows.getString(1)); }
        }
    }

    public synchronized boolean playerDropReady(java.util.UUID entity, java.util.UUID operation) {
        try (var connection = connection()) { return playerDropReady(connection, entity, operation); }
        catch (SQLException failed) { throw new IllegalStateException("无法核对掉落来源", failed); }
    }

    /** Claim source and player evidence atomically; a resurrected source follows the retained sweep receipt. */
    public synchronized boolean claimPlayerDrop(java.util.UUID player, java.util.UUID session, java.util.UUID operation,
            java.util.UUID expectedOperation, byte[] evidence, java.util.UUID entity, java.util.UUID parent, long applied, ItemStack before, ItemStack remainder) {
        return transaction(() -> {
            try {
                if (!playerDropReady(transactionConnection, entity, parent)) return false;
                List<SweepReceipt> receipts = readSweepReceipts(transactionConnection, entity);
                long latest = receipts.isEmpty() ? 0 : receipts.getLast().sequence();
                if (latest != applied || !receipts.isEmpty() && receipts.getLast().remainder() == null)
                    throw new IllegalStateException("掉落来源存在未核对领取");
                byte[] expected = receipts.isEmpty() ? playerDropBytes(transactionConnection, entity) : receipts.getLast().remainder();
                ItemStack expectedStack = expected == null ? null : ItemStack.deserializeBytes(expected);
                if (expectedStack == null || !expectedStack.isSimilar(before) || expectedStack.getAmount() != before.getAmount())
                    throw new IllegalStateException("掉落物与持久来源字节不符");
                preparePlayer(player, session, operation, expectedOperation, evidence);
                SweepReceipt next = new SweepReceipt(latest + 1, before.serializeAsBytes(),
                        remainder == null ? null : remainder.serializeAsBytes());
                recordSweep(transactionConnection, entity, next);
                if (remainder == null) finishConsumedDrops(transactionConnection, parent);
                return true;
            } catch (SQLException failed) { throw new IllegalStateException("掉落领取日志失败", failed); }
        });
    }

    private byte[] playerDropBytes(Connection connection, java.util.UUID entity) throws SQLException {
        try (var query = connection.prepareStatement("SELECT item_data FROM " + table + "_player_drops WHERE entity_id=?")) {
            query.setString(1, entity.toString());
            try (var rows = query.executeQuery()) { return rows.next() ? rows.getBytes(1) : null; }
        }
    }

    private void finishConsumedDrops(Connection connection, java.util.UUID parent) throws SQLException {
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table + "_player_drops d WHERE d.op_id=? AND NOT EXISTS (SELECT 1 FROM "
                + table + "_sweeps s WHERE s.entity_id=d.entity_id AND s.remainder_data IS NULL)")) {
            query.setString(1, parent.toString());
            try (var rows = query.executeQuery()) { if (!rows.next() || rows.getInt(1) != 0) return; }
        }
        try (var update = connection.prepareStatement("UPDATE " + table + "_player_ops SET state='DONE' WHERE op_id=? AND state='DROPS'")) {
            update.setString(1, parent.toString()); update.executeUpdate();
        }
        try (var update = connection.prepareStatement("UPDATE " + table + "_players SET state='DONE' WHERE op_id=? AND state='DROPS'")) {
            update.setString(1, parent.toString()); update.executeUpdate();
        }
    }

    public synchronized boolean unresolvedPlayerDrops(java.util.UUID player) {
        try (var connection = connection(); var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table
                + "_player_ops o WHERE o.player_id=? AND o.state='DROPS'")) {
            query.setString(1, player.toString());
            try (var rows = query.executeQuery()) { return !rows.next() || rows.getInt(1) != 0; }
        } catch (SQLException failed) { throw new IllegalStateException("无法核对玩家掉落凭据", failed); }
    }

    /** Admission proof only: all outstanding sources must be present in the loaded world or durably consumed. */
    public synchronized boolean verifiedPlayerDrops(java.util.UUID player, Map<java.util.UUID, LoadedDrop> loadedDrops) {
        try (var connection = connection(); var query = connection.prepareStatement("SELECT o.op_id,o.evidence FROM " + table
                + "_player_ops o WHERE o.player_id=? AND o.state='DROPS'")) {
            query.setString(1, player.toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var evidence = new java.util.Properties();
                    byte[] bytes = rows.getBytes(2);
                    if (bytes == null || bytes.length == 0 || bytes.length > 8 * 1024 * 1024) return false;
                    try (var input = new java.io.ByteArrayInputStream(bytes)) { evidence.load(input); }
                    if (!"empty".equals(evidence.getProperty("planned.cursor.0"))) return false;
                    int planned = Integer.parseInt(evidence.getProperty("planned.drops.length", "-1"));
                    if (planned <= 0 || planned > 4096) return false;
                    int count = 0;
                    try (var sources = connection.prepareStatement("SELECT entity_id,ordinal,item_data FROM " + table + "_player_drops WHERE op_id=?")) {
                        sources.setString(1, rows.getString(1));
                        try (var drops = sources.executeQuery()) {
                            while (drops.next()) {
                                int ordinal = drops.getInt(2);
                                if (ordinal < 0 || ordinal >= planned || !java.util.Arrays.equals(drops.getBytes(3),
                                        java.util.Base64.getDecoder().decode(evidence.getProperty("planned.drops." + ordinal, "")))) return false;
                                var entity = java.util.UUID.fromString(drops.getString(1));
                                var receipts = readSweepReceipts(connection, entity);
                                if (!loadedDropMatches(connection, entity, receipts, loadedDrops.get(entity))) return false;
                                count++;
                            }
                        }
                    }
                    if (count != planned) return false;
                }
            }
            return true;
        } catch (IOException | IllegalArgumentException invalid) {
            logger.log(Level.WARNING, "掉落来源不足以证明可续接，保留隔离：" + player, invalid);
            return false;
        } catch (SQLException failed) { throw new IllegalStateException("无法核对已加载掉落来源", failed); }
    }

    private boolean loadedDropMatches(Connection connection, java.util.UUID entity, List<SweepReceipt> receipts, LoadedDrop loaded) throws SQLException {
        byte[] expected = playerDropBytes(connection, entity);
        if (expected == null) return false;
        for (int i = 0; i < receipts.size(); i++) {
            var step = receipts.get(i);
            if (step.sequence() != i + 1L || !java.util.Arrays.equals(expected, step.before())) return false;
            expected = step.remainder();
        }
        if (expected == null) return true;
        if (loaded == null || loaded.sequence() < 0 || loaded.sequence() > receipts.size()) return false;
        byte[] observed = loaded.sequence() == 0 ? playerDropBytes(connection, entity)
                : receipts.get(Math.toIntExact(loaded.sequence() - 1)).remainder();
        return observed != null && java.util.Arrays.equals(observed, loaded.data());
    }

    public record UnfinishedOperation(java.util.UUID player, java.util.UUID operation, java.util.UUID session,
                                      String state, long createdAt, String owner) { }
    public record Diagnostics(long revision, long itemGroups, long sessions, Map<String, Long> unfinished,
                              List<UnfinishedOperation> recent) { }

    /** Independent SELECTs only; never hold the store monitor or load evidence/item blobs for diagnostics. */
    public Diagnostics diagnostics() {
        if (closed) throw new IllegalStateException("数据库已关闭");
        try (Connection connection = connection()) {
            long revision, groups, sessions;
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT revision FROM " + table + "_meta WHERE id=1")) {
                if (!rows.next()) throw new SQLException("缺少数据库版本");
                revision = rows.getLong(1);
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rows.next(); groups = rows.getLong(1);
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM " + table + "_sessions")) {
                rows.next(); sessions = rows.getLong(1);
            }
            var counts = new LinkedHashMap<String, Long>();
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT state,COUNT(*) FROM "
                    + table + "_player_ops WHERE state<>'DONE' GROUP BY state ORDER BY state")) {
                while (rows.next()) counts.put(rows.getString(1), rows.getLong(2));
            }
            var recent = new ArrayList<UnfinishedOperation>();
            try (var query = connection.createStatement(); var rows = query.executeQuery(
                    "SELECT o.player_id,o.op_id,o.session_id,o.state,o.created_at,s.owner_id FROM " + table
                    + "_player_ops o LEFT JOIN " + table + "_sessions s ON s.player_id=o.player_id AND s.session_id=o.session_id"
                    + " WHERE o.state<>'DONE' ORDER BY CASE WHEN o.state='QUARANTINED' THEN 0 ELSE 1 END,o.created_at DESC,o.op_id LIMIT 10")) {
                while (rows.next()) recent.add(new UnfinishedOperation(java.util.UUID.fromString(rows.getString(1)),
                        java.util.UUID.fromString(rows.getString(2)), java.util.UUID.fromString(rows.getString(3)),
                        rows.getString(4), rows.getLong(5), rows.getString(6)));
            }
            return new Diagnostics(revision, groups, sessions, Map.copyOf(counts), List.copyOf(recent));
        } catch (SQLException failed) { throw new IllegalStateException("无法读取数据库诊断", failed); }
    }

    /** Read from SQL, not a server-local cache: another server must see the same fence. */
    public synchronized PlayerCheckpoint playerCheckpoint(java.util.UUID player) {
        if (closed) throw new IllegalStateException("玩家交易凭据需要可用数据库");
        try (Connection connection = connection()) { return playerCheckpoint(connection, player); }
        catch (SQLException failed) { throw new IllegalStateException("无法核对玩家交易凭据", failed); }
    }

    private PlayerCheckpoint playerCheckpoint(Connection connection, java.util.UUID player) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT op_id,session_id,state FROM " + table + "_players WHERE player_id=?")) {
            statement.setString(1, player.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? checkpoint(rows) : null; }
        }
    }

    /** Ownership check and checkpoint read in one round trip; throws {@code notOwned} if the session is not ours. */
    public synchronized PlayerCheckpoint sessionCheckpoint(java.util.UUID player, java.util.UUID owner, java.util.UUID session, String notOwned) {
        if (closed) throw new IllegalStateException("玩家交易凭据需要可用数据库");
        try (var connection = connection()) {
            return sessionCheckpoint(connection, player, owner, java.util.Objects.requireNonNull(session), notOwned);
        } catch (SQLException failed) { throw new IllegalStateException("无法核对玩家会话与交易凭据", failed); }
    }

    private PlayerCheckpoint sessionCheckpoint(Connection connection, java.util.UUID player, java.util.UUID owner,
            java.util.UUID session, String notOwned) throws SQLException {
        try (var query = connection.prepareStatement("SELECT p.op_id,p.session_id,p.state FROM "
                + table + "_sessions s LEFT JOIN " + table + "_players p ON p.player_id=s.player_id"
                + " WHERE s.player_id=? AND s.owner_id=?" + (session == null ? "" : " AND s.session_id=?"))) {
            query.setString(1, player.toString()); query.setString(2, owner.toString());
            if (session != null) query.setString(3, session.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException(notOwned);
                return rows.getString(1) == null ? null : checkpoint(rows);
            }
        }
    }

    private static PlayerCheckpoint checkpoint(java.sql.ResultSet rows) throws SQLException {
        String state = rows.getString(3);
        if (!java.util.Set.of("PREPARED", "CURSOR", "DROPS", "DONE", "QUARANTINED").contains(state))
            throw new SQLException("玩家交易状态损坏");
        return new PlayerCheckpoint(java.util.UUID.fromString(rows.getString(1)),
                java.util.UUID.fromString(rows.getString(2)), state);
    }

    private boolean selectionUnfenced(List<Entry> selected) {
        try (var query = transactionConnection.prepareStatement("SELECT f.item_id FROM " + table
                + "_players p LEFT JOIN " + table + "_item_fences f ON f.op_id=p.op_id"
                + " WHERE p.state IN ('PREPARED','QUARANTINED')"); var rows = query.executeQuery()) {
            var pageIds = selected.stream().map(Entry::id).collect(java.util.stream.Collectors.toSet());
            while (rows.next()) {
                long id = rows.getLong(1);
                if (rows.wasNull() || pageIds.contains(id)) return false;
            }
        } catch (SQLException failed) { throw new IllegalStateException("无法核对待确认物品，拒绝页面交易", failed); }
        return true;
    }

    /** Withdraw-only filtered view: match stable IDs and exact bytes under the same database lock as receipts. */
    public synchronized boolean withdrawPlayerSelection(java.util.UUID player, java.util.UUID session, java.util.UUID operation,
            java.util.UUID expectedOperation, byte[] evidence, List<Entry> displayed, List<ItemStack> slots) {
        return transaction(() -> {
            if (displayed.isEmpty() || displayed.size()>54 || slots.size()<displayed.size() || slots.size()>54) return false;
            for(int i=displayed.size();i<slots.size();i++) if(slots.get(i)!=null && !slots.get(i).isEmpty()) return false;
            var seen=new java.util.HashSet<Long>();
            var replacements=new java.util.HashMap<Long,byte[]>();
            boolean changed=false;
            for(int i=0;i<displayed.size();i++) {
                Entry before=displayed.get(i);
                if(!seen.add(before.id()) || !same(items.get(before.id()),before.bytes())) return false;
                ItemStack after=slots.get(i);
                if(after==null || after.isEmpty()) { replacements.put(before.id(),null); changed=true; continue; }
                byte[] data=after.serializeAsBytes();
                if(same(before.bytes(),data)) continue;
                ItemStack original=decoded(before.id(),before.bytes());
                if(!original.isSimilar(after) || after.getAmount()<1 || after.getAmount()>=original.getAmount()) return false;
                replacements.put(before.id(),data); changed=true;
            }
            if(!changed || !selectionUnfenced(displayed)) return false;
            var proposed=new LinkedHashMap<Long,byte[]>();
            var newIds=new ArrayList<Long>();
            for(var row:items.entrySet()) {
                if(!replacements.containsKey(row.getKey())) proposed.put(row.getKey(),row.getValue());
                else if(replacements.get(row.getKey())!=null) {
                    long id=newId(); newIds.add(id); proposed.put(id,replacements.get(row.getKey()));
                }
            }
            long total=proposed.values().stream().mapToLong(value->value.length).sum();
            if(total>MAX_BYTES) return false;
            preparePlayer(player,session,operation,expectedOperation,evidence);
            items.clear(); items.putAll(proposed); bytes=total; changed();
            for(long id:newIds) recordItemFence(operation,id);
            return true;
        });
    }

    /** Evidence and the bin edit commit together, before any player or world effect. */
    public synchronized boolean replacePlayerPage(java.util.UUID player, java.util.UUID session, java.util.UUID operation,
            java.util.UUID expectedOperation, byte[] evidence, int page, int pageSize, List<Entry> displayed, List<ItemStack> slots) {
        return transaction(() -> {
            var originalIds = new java.util.HashSet<>(items.keySet());
            if (!replacePage(page, pageSize, displayed, slots)) return false;
            preparePlayer(player, session, operation, expectedOperation, evidence);
            // Only new/replaced rows can contain an unconfirmed player deposit; removed IDs are never reused.
            for (long id : items.keySet()) if (!originalIds.contains(id)) recordItemFence(operation, id);
            return true;
        });
    }

    public synchronized void preparePlayerOnly(java.util.UUID player, java.util.UUID session, java.util.UUID operation,
            java.util.UUID expectedOperation, byte[] evidence) {
        transaction(() -> { preparePlayer(player, session, operation, expectedOperation, evidence); return true; });
    }

    private void preparePlayer(java.util.UUID player, java.util.UUID session, java.util.UUID operation,
            java.util.UUID expectedOperation, byte[] evidence) {
        if (evidence == null || evidence.length == 0 || evidence.length > 8 * 1024 * 1024)
            throw new IllegalArgumentException("玩家交易证据为空或超过8MiB");
        try {
            PlayerCheckpoint previous = transactionCheckpoint(player, session);
            if (!java.util.Objects.equals(previous == null ? null : previous.operation(), expectedOperation))
                throw new IllegalStateException("玩家交易收据不一致：player=" + player + " expected=" + expectedOperation
                        + " current=" + (previous == null ? null : previous.operation())
                        + " state=" + (previous == null ? null : previous.state()) + "；拒绝执行，需核查存档与数据库");
            if (previous != null && !(previous.state().equals("DONE")
                    || java.util.Set.of("CURSOR", "DROPS").contains(previous.state()) && previous.session().equals(session)))
                throw new IllegalStateException("玩家存在未确认交易，拒绝重入");
            try (var insert = transactionConnection.prepareStatement("INSERT INTO " + table
                    + "_player_ops (op_id,player_id,session_id,state,evidence,created_at) VALUES (?,?,?,'PREPARED',?,?)")) {
                insert.setString(1, operation.toString()); insert.setString(2, player.toString());
                insert.setString(3, session.toString()); insert.setBytes(4, evidence);
                insert.setLong(5, System.currentTimeMillis()); insert.executeUpdate();
            }
            try (var write = transactionConnection.prepareStatement(previous == null
                    ? "INSERT INTO " + table + "_players (op_id,session_id,state,player_id) VALUES (?,?,'PREPARED',?)"
                    : "UPDATE " + table + "_players SET op_id=?,session_id=?,state='PREPARED' WHERE player_id=?")) {
                write.setString(1, operation.toString()); write.setString(2, session.toString()); write.setString(3, player.toString());
                if (write.executeUpdate() != 1) throw new SQLException("玩家交易隔离写入行数不符");
            }
            recordItemFence(operation, 0); // Explicit empty fence distinguishes new operations from legacy evidence.
            if (previous != null) try (var delete = transactionConnection.prepareStatement(
                    "DELETE FROM " + table + "_item_fences WHERE op_id=?")) {
                delete.setString(1, previous.operation().toString()); delete.executeUpdate();
            }
        } catch (SQLException failed) { throw new IllegalStateException("玩家交易日志写入失败", failed); }
    }

    private void recordItemFence(java.util.UUID operation, long id) {
        try (var insert = transactionConnection.prepareStatement("INSERT INTO " + table + "_item_fences (op_id,item_id) VALUES (?,?)")) {
            insert.setString(1, operation.toString()); insert.setLong(2, id);
            if (insert.executeUpdate() != 1) throw new SQLException("物品隔离写入行数不符");
        } catch (SQLException failed) { throw new IllegalStateException("物品隔离记录失败", failed); }
    }

    /** Only the live applying session may acknowledge; this never grants, restores or spawns anything. */
    public synchronized void checkpointPlayer(java.util.UUID player, java.util.UUID session, java.util.UUID operation, String state) {
        if (!java.util.Set.of("CURSOR", "DROPS", "DONE", "QUARANTINED").contains(state)) throw new IllegalArgumentException("state");
        transaction(() -> {
            try { writePlayerCheckpoint(player, session, operation, state, transactionCheckpoint(player, session)); }
            catch (SQLException failed) { throw new IllegalStateException("无法确认交易会话归属", failed); }
            return true;
        });
    }

    private void writePlayerCheckpoint(java.util.UUID player, java.util.UUID session, java.util.UUID operation, String state,
            PlayerCheckpoint current) {
            try {
                if (current == null || !current.session().equals(session) || !current.operation().equals(operation)
                        || current.state().equals("QUARANTINED")) throw new IllegalStateException("玩家交易凭据已变化");
                try (var update = transactionConnection.prepareStatement("UPDATE " + table + "_players SET state=? WHERE player_id=?")) {
                    update.setString(1, state); update.setString(2, player.toString());
                    if (update.executeUpdate() != 1) throw new SQLException("玩家确认写入行数不符");
                }
                try (var update = transactionConnection.prepareStatement("UPDATE " + table + "_player_ops SET state=? WHERE op_id=?")) {
                    update.setString(1, state); update.setString(2, operation.toString());
                    if (update.executeUpdate() != 1) throw new SQLException("玩家日志确认行数不符");
                }
                if (!state.equals("QUARANTINED")) try (var delete = transactionConnection.prepareStatement(
                        "DELETE FROM " + table + "_item_fences WHERE op_id=? AND item_id<>0")) {
                    delete.setString(1, operation.toString()); delete.executeUpdate();
                }
                // A durable empty cursor closes this session's earlier cursor-only steps, never unresolved drops.
                if (state.equals("DONE")) try (var update = transactionConnection.prepareStatement("UPDATE " + table
                        + "_player_ops SET state=CASE WHEN EXISTS (SELECT 1 FROM " + table
                        + "_player_drops d WHERE d.op_id=" + table + "_player_ops.op_id AND NOT EXISTS (SELECT 1 FROM "
                        + table + "_sweeps s WHERE s.entity_id=d.entity_id AND s.remainder_data IS NULL))"
                        + " THEN 'DROPS' ELSE 'DONE' END WHERE player_id=? AND session_id=? AND state='CURSOR'")) {
                    update.setString(1, player.toString()); update.setString(2, session.toString()); update.executeUpdate();
                }
            } catch (SQLException failed) { throw new IllegalStateException("玩家交易确认失败", failed); }
    }

    /** Compare immutable entries without copying their serialized payloads. */
    public static boolean samePage(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).id() != b.get(i).id() || !same(a.get(i).bytes, b.get(i).bytes)) return false;
        }
        return true;
    }

    private long newId() {
        if (nextId == Long.MAX_VALUE) throw new IllegalStateException("垃圾箱编号耗尽");
        return nextId++;
    }

    /** Legacy-safe atomic claim helper retained for offline storage checks. */
    public synchronized boolean claim(long id, Function<byte[], byte[]> transfer) {
        if (mysql()) throw new IllegalStateException("MySQL must use replacePage; claim callbacks cannot be rolled back");
        if (closed) return false;
        if (!inTransaction) return transaction(() -> claim(id, transfer));
        byte[] current = items.get(id);
        if (closed || current == null) return false;
        byte[] remainder = transfer.apply(current.clone());
        if (remainder != null && remainder.length == 0) throw new IllegalArgumentException("Empty remainder");
        if (remainder == null) items.remove(id);
        else items.put(id, remainder.clone());
        bytes += (remainder == null ? 0 : remainder.length) - current.length;
        changed();
        return true;
    }

    public synchronized List<Long> ids() { return List.copyOf(items.keySet()); }

    public synchronized int clear(List<Long> confirmed) {
        if (closed) return 0;
        if (!inTransaction) return transaction(() -> clear(confirmed));
        if (closed) return 0;
        int removed = 0;
        for (Long id : confirmed) {
            byte[] old = items.remove(id);
            if (old != null) { bytes -= old.length; removed++; }
        }
        if (removed > 0) changed();
        return removed;
    }

    /** Count and clear the same locked database snapshot, including remote additions. */
    public synchronized long clearAll() {
        if (!inTransaction) return transaction(this::clearAll);
        long count = itemCount();
        clear(ids());
        return count;
    }

    private void afterCommit(Runnable effect) {
        if (inTransaction) committedEffects.add(effect);
        else effect.run();
    }

    /** Serialize all servers on the metadata row; stale pages are rejected, never rebased into grants. */
    private synchronized <T> T transaction(java.util.function.Supplier<T> operation) {
        // Production MySQL callers reserve asynchronously; this lock protects only the JDBC working copy.
        if (closed) throw new IllegalStateException("垃圾箱已关闭");
        Map<Long, byte[]> before = new LinkedHashMap<>();
        copyInto(before, items);
        long beforeRevision = databaseRevision;
        boolean committed = false;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!mysql()) connection.createStatement().executeUpdate("UPDATE " + table + "_meta SET revision=revision WHERE id=1");
                long lockedRevision = readRevision(connection, true);
                Map<Long, byte[]> fresh = new LinkedHashMap<>();
                Map<Long, Integer> positions = new LinkedHashMap<>();
                long total = 0;
                boolean freshCompact = true;
                if (lockedRevision == databaseRevision && baseCompact) {
                    // Every committed write bumps revision and resets baseItems, so an unchanged revision means baseItems is the table.
                    int position = 0;
                    for (var row : baseItems.entrySet()) {
                        fresh.put(row.getKey(), row.getValue().clone()); positions.put(row.getKey(), position++); total += row.getValue().length;
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT id,item_data,position FROM " + table + " ORDER BY position,id"); ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            byte[] data = rows.getBytes(2);
                            if (data == null || data.length == 0 || fresh.size() >= CAPACITY || total + data.length > MAX_BYTES)
                                throw new SQLException("数据库垃圾箱数据无效或超限，拒绝覆盖");
                            if (rows.getInt(3) != fresh.size()) freshCompact = false;
                            fresh.put(rows.getLong(1), data); positions.put(rows.getLong(1), rows.getInt(3)); total += data.length;
                        }
                    }
                }
                databaseRevision = lockedRevision;
                copyInto(items, fresh); bytes = total;
                if (lockedRevision != beforeRevision) cachedItemCount = null;
                // Revision-based ranges prevent deleted IDs being reused by another server (stale-page ABA).
                nextId = Math.max(nextId, Math.addExact(Math.multiplyExact(databaseRevision, CAPACITY + 1L), 1));
                for (Long id : fresh.keySet()) nextId = Math.max(nextId, Math.addExact(id, 1));
                inTransaction = true;
                transactionConnection = connection;
                T result = operation.get();
                boolean rewritten = !sameContents(fresh, items);
                if (rewritten) {
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE id=?");
                         PreparedStatement update = connection.prepareStatement("UPDATE " + table
                                 + " SET position=?,item_data=?,updated_at=? WHERE id=?");
                         PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table
                                  + " (id,position,item_data,updated_at) VALUES (?,?,?,?)")) {
                        int deletes = 0, updates = 0, inserts = 0;
                        for (Long id : fresh.keySet()) {
                            if (!items.containsKey(id)) { delete.setLong(1, id); delete.addBatch(); deletes++; }
                        }
                        int position = 0;
                        long now = System.currentTimeMillis();
                        for (var row : items.entrySet()) {
                            byte[] original = fresh.get(row.getKey());
                            if (original == null) {
                                insert.setLong(1, row.getKey()); insert.setInt(2, position);
                                insert.setBytes(3, row.getValue()); insert.setLong(4, now); insert.addBatch(); inserts++;
                            } else if (positions.get(row.getKey()) != position || !same(original, row.getValue())) {
                                update.setInt(1, position); update.setBytes(2, row.getValue()); update.setLong(3, now);
                                update.setLong(4, row.getKey()); update.addBatch(); updates++;
                            }
                            position++;
                        }
                        if (deletes > 0) verifyBatch(delete.executeBatch());
                        if (updates > 0) verifyBatch(update.executeBatch());
                        if (inserts > 0) verifyBatch(insert.executeBatch());
                    }
                    try (PreparedStatement update = connection.prepareStatement("UPDATE " + table + "_meta SET revision=? WHERE id=1")) {
                        update.setLong(1, Math.addExact(databaseRevision, 1));
                        if (update.executeUpdate() != 1) throw new SQLException("垃圾箱版本记录更新行数不符，拒绝提交");
                    }
                    databaseRevision++;
                }
                connection.commit();
                committed = true;
                baseCompact = rewritten || freshCompact;
                if (rewritten || lockedRevision != beforeRevision) {
                    copyInto(baseItems, items); pruneDecoded(); cachedItemCount = null;
                    publish();
                }
                changeVersion++;
                inTransaction = false;
                List<Runnable> effects = new ArrayList<>(committedEffects);
                committedEffects.clear();
                // Every committed source must still be removed even if an earlier one in the batch failed.
                RuntimeException effectFailure = null;
                for (Runnable effect : effects) {
                    try { effect.run(); }
                    catch (RuntimeException failed) {
                        if (effectFailure == null) effectFailure = failed; else effectFailure.addSuppressed(failed);
                    }
                }
                if (effectFailure != null) throw effectFailure;
                return result;
            } catch (SQLException | RuntimeException failed) {
                if (!committed) {
                    try { connection.rollback(); } catch (SQLException rollback) { failed.addSuppressed(rollback); }
                }
                throw failed;
            } finally {
                inTransaction = false;
                transactionConnection = null;
                committedEffects.clear();
            }
        } catch (SQLException | RuntimeException failed) {
            if (!committed) {
                copyInto(items, before); bytes = items.values().stream().mapToLong(value -> value.length).sum();
                databaseRevision = beforeRevision; cachedItemCount = null;
                pruneDecoded();
            }
            if (committed) logger.log(Level.SEVERE, "垃圾箱数据库已提交，但提交后处理失败；不得自动回滚或补发，请核查玩家、世界与数据库", failed);
            throw new IllegalStateException(committed
                    ? "垃圾箱数据库已提交，但后续处理失败，请管理员核查；不得自动重试或补发"
                    : "垃圾箱数据库事务失败，未确认的操作不得发放物品", failed);
        }
    }

    private static void verifyBatch(int[] counts) throws SQLException {
        // JDBC may return SUCCESS_NO_INFO with rewritten batches; exact counts then cannot be verified.
        for (int count : counts) {
            if (count != 1 && count != java.sql.Statement.SUCCESS_NO_INFO)
                throw new SQLException("垃圾箱差量写入行数不符，拒绝提交");
        }
    }

    private static boolean sameContents(Map<Long, byte[]> a, Map<Long, byte[]> b) {
        if (!a.keySet().equals(b.keySet()) || !new ArrayList<>(a.keySet()).equals(new ArrayList<>(b.keySet()))) return false;
        return a.entrySet().stream().allMatch(row -> same(row.getValue(), b.get(row.getKey())));
    }

    private void changed() {
        cachedItemCount = null;
        if (items.isEmpty()) { decoded.clear(); decodedBytes = 0; }
        changeVersion++;
    }

    private void refreshDatabaseIfSafe() {
        long version;
        synchronized (this) {
            if (closed) return;
            version = changeVersion;
        }
        Map<Long, byte[]> fresh = new LinkedHashMap<>();
        long total = 0;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            long revision = readRevision(connection, false);
            if (revision == databaseRevision) {
                connection.commit();
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,item_data FROM " + table + " ORDER BY position,id")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] data = rows.getBytes(2);
                    if (data == null || data.length == 0 || fresh.size() >= CAPACITY || total + data.length > MAX_BYTES)
                        throw new SQLException("数据库垃圾箱数据无效或超限，保留原有内存快照");
                    fresh.put(rows.getLong(1), data); total += data.length;
                }
            }
            }
            connection.commit();
            synchronized (this) {
                if (closed || version != changeVersion) return;
                items.clear(); items.putAll(fresh); bytes = total;
                pruneDecoded();
                cachedItemCount = null;
                copyInto(baseItems, fresh); baseCompact = false;
                databaseRevision = revision;
                fresh.keySet().forEach(id -> nextId = Math.max(nextId, id + 1));
                publish();
            }
        } catch (SQLException failed) { throw new IllegalStateException("垃圾箱数据库刷新失败", failed); }
    }

    private long readRevision(Connection connection, boolean lock) throws SQLException {
        // SQLite has no SELECT ... FOR UPDATE; its transaction already serializes the writer.
        String sql = "SELECT revision FROM " + table + "_meta WHERE id=1"
                + (lock && jdbcUrl.startsWith("jdbc:mysql:") ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("垃圾箱版本记录不存在");
            return rows.getLong(1);
        }
    }

    private static void copyInto(Map<Long, byte[]> target, Map<Long, byte[]> source) {
        target.clear();
        source.forEach((id, data) -> target.put(id, data.clone()));
    }

    @Override
    public void close() {
        if (watcher != null) watcher.shutdownNow();
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        Connection parked;
        synchronized (idleLock) { idleClosed = true; parked = idleConnection; idleConnection = null; }
        if (parked != null) closeQuietly(parked);
    }

    private static boolean same(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }
}
