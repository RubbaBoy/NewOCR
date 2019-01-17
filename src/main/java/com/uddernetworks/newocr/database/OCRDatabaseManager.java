package com.uddernetworks.newocr.database;

import com.uddernetworks.newocr.FontBounds;
import com.uddernetworks.newocr.LetterMeta;
import com.uddernetworks.newocr.character.SearchCharacter;
import com.uddernetworks.newocr.utils.ConversionUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.sql.DataSource;

public class OCRDatabaseManager implements DatabaseManager {

    private final boolean useInternal;
    private DataSource dataSource;
    private ExecutorService executor = Executors.newCachedThreadPool();
    private String createLetterEntry;
    private String clearLetterSegments;
    private String addLetterSegment;
    private String selectSegments;
    private String selectAllSegments;
    private String getLetterEntry;
    private String getSpaceEntry;
    private String addLetterSize;
    private String getLetterSize;

    private final ConcurrentMap<FontBounds, List<DatabaseCharacter>> databaseCharacterCache = new ConcurrentHashMap<>();

    /**
     * Connects to the database with the given credentials, and executes the queries found in letters.sql and sectionData.sql
     *
     * @param databaseURL The URL to the database
     * @param username    The username of the connecting account
     * @param password    The password of the connecting account
     * @throws IOException
     */
    public OCRDatabaseManager(String databaseURL, String username, String password) throws IOException {
        this(false, null, databaseURL, username, password);
    }

    /**
     * Connects to the internal database provided by HSQLDB in the given location, and executes the queries found in
     * letters.sql and sectionData.sql. This option can be over 12x faster than the MySQL variant.
     *
     * @param filePath The file without an extension of the database. If this doesn't exist, it will be created
     * @throws IOException
     */
    public OCRDatabaseManager(File filePath) throws IOException {
        this(true, filePath, null, null, null);
    }

    public OCRDatabaseManager(boolean useInternal, File filePath, String databaseURL, String username, String password) throws IOException {
        this.useInternal = useInternal;
        
        var config = new HikariConfig();

        try {
            Class.forName(useInternal ? "org.hsqldb.jdbcDriver" : "com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        if (useInternal) {
            filePath.getParentFile().mkdirs();
            config.setJdbcUrl("jdbc:hsqldb:file:" + filePath);
            config.setUsername("SA");
            config.setPassword("");
        } else {
            config.setDriverClassName("com.mysql.jdbc.Driver");
            config.setJdbcUrl(databaseURL);
            config.setUsername(username);
            config.setPassword(password);
        }

        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "1000");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "8192");
        
        dataSource = new HikariDataSource(config);

        List.of("letters.sql", "sectionData.sql", "sizing.sql").parallelStream().forEach(table -> {
            var stream = OCRDatabaseManager.class.getResourceAsStream("/" + table);
            
            try (var reader = new BufferedReader(new InputStreamReader(stream));
                 var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(reader.lines().collect(Collectors.joining("\n")))) {
                statement.executeUpdate();
            } catch (IOException | SQLException e) {
                e.printStackTrace();
            }
        });

        initializeStatements();

        try (var connection = this.dataSource.getConnection();
             var useMySQL = connection.prepareStatement("SET DATABASE SQL SYNTAX MYS TRUE")) {
            useMySQL.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Ran internally after the DatabaseManager has been created to read the *.sql files in the /resources/ directory
     * for future queries.
     *
     * @throws IOException
     */
    private void initializeStatements() throws IOException {
        this.createLetterEntry = getQuery("createLetterEntry");
        this.clearLetterSegments = getQuery("clearLetterSegments");
        this.addLetterSegment = getQuery("addLetterSegment");
        this.selectSegments = getQuery("selectSegments");
        this.selectAllSegments = getQuery("selectAllSegments");
        this.getLetterEntry = getQuery("getLetterEntry");
        this.getSpaceEntry = getQuery("getSpaceEntry");
        this.addLetterSize = getQuery("addLetterSize");
        this.getLetterSize = getQuery("getLetterSize");
    }

    /**
     * Gets the string query from the resource file given.
     *
     * @param name The resource file to read
     * @return The string contents of it
     * @throws IOException
     */
    private String getQuery(String name) throws IOException {
        var resource = Objects.requireNonNull(getClass().getClassLoader().getResource(name + ".sql"));
        
        try (var reader = new BufferedReader(new InputStreamReader(resource.openStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    @Override
    public DataSource getDataSource() {
        return this.dataSource;
    }

    @Override
    public void createLetterEntry(char letter, double averageWidth, double averageHeight, int minFontSize, int maxFontSize, double minCenter, double maxCenter, boolean hasDot, LetterMeta letterMeta, boolean isLetter) {
        try (var connection = dataSource.getConnection(); var createLetterEntry = connection.prepareStatement(this.createLetterEntry)) {
            createLetterEntry.setInt(1, letter);
            createLetterEntry.setDouble(2, averageWidth);
            createLetterEntry.setDouble(3, averageHeight);
            createLetterEntry.setInt(4, minFontSize);
            createLetterEntry.setInt(5, maxFontSize);
            createLetterEntry.setDouble(6, minCenter);
            createLetterEntry.setDouble(7, maxCenter);
            createLetterEntry.setBoolean(8, hasDot);
            createLetterEntry.setInt(9, letterMeta.getID());
            createLetterEntry.setBoolean(10, isLetter);
            createLetterEntry.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void clearLetterSegments(char letter, int minFontSize, int maxFontSize) {
        List.of("letters", "sectionData").forEach(table -> {
            var query = String.format(this.clearLetterSegments, table);
            
            try (var connection = dataSource.getConnection(); var clearLetterSegments = connection.prepareStatement(query)) {
                clearLetterSegments.setInt(1, letter);
                clearLetterSegments.setInt(2, minFontSize);
                clearLetterSegments.setInt(3, maxFontSize);
                clearLetterSegments.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void addLetterSegments(char letter, int minFontSize, int maxFontSize, double[] segments) {
        try (var connection = dataSource.getConnection(); var addLetterSegment = connection.prepareStatement(this.addLetterSegment)) {
            for (int i = 0; i < segments.length; i++) {
                addLetterSegment.setInt(1, letter);
                addLetterSegment.setInt(2, minFontSize);
                addLetterSegment.setInt(3, maxFontSize);
                addLetterSegment.setInt(4, i);
                addLetterSegment.setDouble(5, segments[i]);
                addLetterSegment.addBatch();
            }

            addLetterSegment.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Future<List<DatabaseCharacter>> getAllCharacterSegments(FontBounds fontBounds) {
        return executor.submit(() -> {
            var cachedValue = databaseCharacterCache.compute(fontBounds, (k, v) -> {
               if (v == null || v.isEmpty()) {
                   return null;
               }
               
               return v;
            });
            
            if (cachedValue != null) {
                return cachedValue;
            }

            var databaseCharacters = new ArrayList<DatabaseCharacter>();

            try (var connection = dataSource.getConnection(); var selectSegments = connection.prepareStatement(this.selectAllSegments)) {
                selectSegments.setInt(1, fontBounds.getMinFont());
                selectSegments.setInt(2, fontBounds.getMaxFont());

                var resultSet = selectSegments.executeQuery();

                while (resultSet.next()) {
                    var letter = resultSet.getString("letter").charAt(0);
                    var sectionIndex = resultSet.getInt("sectionIndex");
                    var data = resultSet.getDouble("data");

                    var databaseCharacter = getDatabaseCharacter(databaseCharacters, letter, newDatabaseCharacter -> {
                        try (var getLetterEntry = connection.prepareCall(this.getLetterEntry)) {
                            getLetterEntry.setInt(1, letter);
                            getLetterEntry.setInt(2, fontBounds.getMinFont());
                            getLetterEntry.setInt(3, fontBounds.getMaxFont());
                            
                            var resultSet1 = getLetterEntry.executeQuery();

                            if (!resultSet1.next()) {
                                return;
                            }

                            var avgWidth = resultSet1.getDouble("avgWidth");
                            var avgHeight = resultSet1.getDouble("avgHeight");
                            var minFontSize = resultSet1.getInt("minFontSize");
                            var maxFontSize = resultSet1.getInt("maxFontSize");
                            var minCenter = resultSet1.getDouble("minCenter");
                            var maxCenter = resultSet1.getDouble("maxCenter");
                            var hasDot = resultSet1.getBoolean("hasDot");
                            var letterMetaID = resultSet1.getInt("letterMeta");

                            newDatabaseCharacter.setData(avgWidth, avgHeight, minFontSize, maxFontSize, minCenter, maxCenter);
                            newDatabaseCharacter.setHasDot(hasDot);
                            LetterMeta.fromID(letterMetaID).ifPresent(newDatabaseCharacter::setLetterMeta);
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    });

                    databaseCharacter.addDataPoint(sectionIndex, data);
                    
                    if (!databaseCharacters.contains(databaseCharacter)) {
                        databaseCharacters.add(databaseCharacter);
                    }
                }

                try (var selectSpace = connection.prepareStatement(this.getSpaceEntry)) {
                    var spaceResult = selectSpace.executeQuery();

                    if (spaceResult.next()) {
                        var avgWidth = spaceResult.getDouble("avgWidth");
                        var avgHeight = spaceResult.getDouble("avgHeight");
                        var minFontSize = spaceResult.getInt("minFontSize");
                        var maxFontSize = spaceResult.getInt("maxFontSize");

                        var spaceCharacter = new DatabaseCharacter(' ');
                        spaceCharacter.setData(avgWidth, avgHeight, minFontSize, maxFontSize, 0, 0);
                        databaseCharacters.add(spaceCharacter);
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            this.databaseCharacterCache.put(fontBounds, databaseCharacters);

            return databaseCharacters;
        });
    }

    @Override
    public void addLetterSize(int fontSize, List<SearchCharacter> searchCharacterList) {
        executor.execute(() -> {
            try (var connection = dataSource.getConnection(); var insertSize = connection.prepareStatement(this.addLetterSize)) {
                for (SearchCharacter searchCharacter : searchCharacterList) {
                    insertSize.setInt(1, searchCharacter.getKnownChar());
                    insertSize.setInt(2, fontSize);
                    insertSize.setInt(3, searchCharacter.getHeight());
                    insertSize.addBatch();
                    insertSize.clearParameters();
                }

                insertSize.executeBatch();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public Future<Integer> getLetterSize(char character, int height) {
        return executor.submit(() -> {
            int result = -1;

            try (var connection = dataSource.getConnection(); var getSize = connection.prepareStatement(this.getLetterSize)) {
                getSize.setInt(1, character);
                getSize.setInt(2, height);

                var resultSet = getSize.executeQuery();

                if (resultSet.next()) {
                    result = ConversionUtils.pixelToPoint(resultSet.getInt(1));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            return result;
        });
    }

    @Override
    public void shutdown() {
        if (!this.executor.isShutdown()) {
            this.executor.shutdown();
        }
    }

    @Override
    public boolean usesInternal() {
        return this.useInternal;
    }

    /**
     * Gets the {@link DatabaseCharacter} with the character value given from a list of {@link DatabaseCharacter}s.
     * If one is not found, one is created.
     *
     * @param databaseCharacters The list of {@link DatabaseCharacter}s to search from
     * @param letter             The character the value must match
     * @param onCreate           An action to do when a {@link DatabaseCharacter} is created, usually adding more info from it
     *                           from a database.
     * @return The created {@link DatabaseCharacter}
     */
    private DatabaseCharacter getDatabaseCharacter(List<DatabaseCharacter> databaseCharacters, char letter, Consumer<DatabaseCharacter> onCreate) {
        return databaseCharacters.stream().filter(cha -> cha.getLetter() == letter).findFirst().orElseGet(() -> {
            var databaseCharacter = new DatabaseCharacter(letter);
            onCreate.accept(databaseCharacter);
            return databaseCharacter;
        });
    }
    
}