package com.uddernetworks.newocr.altsearcher;

import javafx.util.Pair;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Main {

    public static final double AFFECT_BACKWARDS = 1D; // Originally 2
    public static final FontBounds[] FONT_BOUNDS = {
            new FontBounds(0, 12),
            new FontBounds(13, 20),
            new FontBounds(21, 30),
            new FontBounds(31, 100),
    };

    private static final Color[] TEMP = new Color[] {
            Color.RED,
            Color.GREEN,
            Color.BLUE
    };

    private static int inc = 0;

    public static final boolean AVERAGE_DIFF = true; // true for average, false for max and min
    public static String trainString = "!#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghjiklmnopqrstuvwxyz{|}~W W";
    private static DatabaseManager databaseManager;

    private static int letterIndex = 0;

    private static BufferedImage testImageShit;

    public static void main(String[] args) throws IOException { // alphabet48
        databaseManager = new DatabaseManager(args[0], args[1], args[2]);

        Scanner scanner = new Scanner(System.in);

        Arrays.asList("letters.sql", "sectionData.sql").parallelStream().forEach(table -> {
            try {
                URL url = Main.class.getClassLoader().getResource(table);
                String tables = new BufferedReader(new InputStreamReader(url.openStream())).lines().collect(Collectors.joining("\n"));
                try (Connection connection = databaseManager.getDataSource().getConnection();
                     PreparedStatement statement = connection.prepareStatement(tables)) {

                    statement.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        databaseManager.initializeStatements();

        System.out.println("Do you want to train? yes/no");

        String inputLine = scanner.nextLine();
        if (inputLine.equalsIgnoreCase("yes") || inputLine.equalsIgnoreCase("y")) {
            System.out.println("AFFECT_BACKWARDS = " + AFFECT_BACKWARDS);
            System.out.println("Generating features...");
            long start = System.currentTimeMillis();
            generateFeatures(new File("E:\\NewOCR\\training.png"));
            System.out.println("Finished training in " + (System.currentTimeMillis() - start) + "ms");
//            System.exit(0);
        }

        long start = System.currentTimeMillis();

        BufferedImage input = ImageIO.read(new File("E:\\NewOCR\\SS.png"));
        boolean[][] values = createGrid(input);
        List<SearchCharacter> searchCharacters = new ArrayList<>();

        BufferedImage temp = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_ARGB);
        rewriteImage(temp, input);
        input = temp;

        filter(input);
        toGrid(input, values);

        SearchImage searchImage = new SearchImage(values, input.getWidth(), input.getHeight());

        List<Map.Entry<Integer, Integer>> coordinates = new ArrayList<>();

        for (int y = input.getHeight(); 0 <= --y; ) {
            for (int x = 0; x < input.getWidth(); x++) {
                if (getLetterFrom(searchImage, input, x, y, coordinates, searchCharacters)) continue;
            }
        }

        Map<FontBounds, List<SearchCharacter>> searchLines = new HashMap<>();

        searchCharacters.forEach(searchCharacter -> {
            FontBounds bounds = matchNearestFontSize(searchCharacter.getHeight());
            searchLines.putIfAbsent(bounds, new ArrayList<>());
            searchLines.get(bounds).add(searchCharacter);
        });

        searchLines.keySet().parallelStream().forEach(fontBounds -> {
            try {
                databaseManager.getAllCharacterSegments(fontBounds).get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        });

        Map<Integer, List<DatabaseCharacter>> lines = new LinkedHashMap<>();

        AtomicBoolean first = new AtomicBoolean(true);

        BufferedImage finalInput = input;
        searchLines.values()
                .stream()
                .flatMap(List::stream)
                .parallel()
                .map(Main::getCharacterFor)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(DatabaseCharacter::getX))
                .forEachOrdered(databaseCharacter -> {
                    System.out.println("First = " + databaseCharacter.getLetter());
//                    colorRow(finalInput, Color.RED, (int) (databaseCharacter.getY() + databaseCharacter.getMinCenter()), 0, finalInput.getWidth());
//                    colorRow(finalInput, Color.GREEN, (int) (databaseCharacter.getY() + databaseCharacter.getMaxCenter()), 0, finalInput.getWidth());
                    double centerDiff = (databaseCharacter.getMaxCenter() - databaseCharacter.getMinCenter()) / 2 + databaseCharacter.getMinCenter();
                    double threashold = Math.max(centerDiff * 1.5D, 2D);
//                    colorRow(finalInput, Color.GREEN, (int) (databaseCharacter.getY() - centerDiff), 0, finalInput.getWidth());

                    System.out.println("Min: " + databaseCharacter.getMinCenter());
                    System.out.println("Max: " + databaseCharacter.getMaxCenter());

                    int center = lines.keySet()
                            .stream()
                            .filter(y -> isWithin(y, (int) (databaseCharacter.getY() - centerDiff), threashold)).findFirst().orElseGet(() -> {
                                lines.put((int) (databaseCharacter.getY() - centerDiff), new LinkedList<>());

                                if (first.get()) {
                                    colorRow(finalInput, Color.GREEN, (int) (databaseCharacter.getY() + databaseCharacter.getMinCenter()), 0, finalInput.getWidth());
                                    colorRow(finalInput, Color.GREEN, (int) (databaseCharacter.getY() + databaseCharacter.getMaxCenter()), 0, finalInput.getWidth());

                                    first.set(false);
                                } else {
                                    colorRow(finalInput, Color.RED, (int) (databaseCharacter.getY() + databaseCharacter.getMinCenter()), 0, finalInput.getWidth());
                                    colorRow(finalInput, Color.RED, (int) (databaseCharacter.getY() + databaseCharacter.getMaxCenter()), 0, finalInput.getWidth());

                                }

                                return (int) (databaseCharacter.getY() - centerDiff);
                            });

                    System.out.println("centerDiff = " + centerDiff);
                    System.out.println("center = " + center);

                    double ratio = databaseCharacter.getAvgWidth() / databaseCharacter.getAvgHeight();
                    System.out.println("Database ratio: " + ratio + " Real: " + databaseCharacter.getRatio() + " database:" + databaseCharacter.getAvgWidth() + " / " + databaseCharacter.getAvgHeight());

                    double diff = Math.max(ratio, databaseCharacter.getRatio()) - Math.min(ratio, databaseCharacter.getRatio());
                    if (diff > 0.2D) {
                        System.out.println("Diff is: " + diff);
                        return;
                    }

                    lines.get(center).add(databaseCharacter);
                });

        // End ordering

        System.out.println("lines = " + lines);

        Map<Integer, List<DatabaseCharacter>> sortedLines = new LinkedHashMap<>();

        lines.keySet().stream().sorted().forEach(y -> sortedLines.put(y, lines.get(y)));

//        sortedLines.values().forEach(line -> {
//            if (line.isEmpty()) return;
//            try {
//                line.addAll(getSpacesFor(line, line.get(0).getHeight()));
//            } catch (ExecutionException | InterruptedException e) {
//                e.printStackTrace();
//            }
//        });

        System.out.println("lines = " + lines);

        lines.clear();
        sortedLines.keySet().stream().sorted().forEach(y -> {
            List<DatabaseCharacter> line = sortedLines.get(y);
            lines.put(y, line.stream().sorted(Comparator.comparingInt(DatabaseCharacter::getX)).collect(Collectors.toList()));
        });

        lines.keySet().forEach(y -> {
            System.out.println(String.join("", lines.get(y)
                    .stream()
                    .map(DatabaseCharacter::getLetter)
                    .map(String::valueOf)
                    .collect(Collectors.joining(""))));
        });

        System.out.println("Finished in " + (System.currentTimeMillis() - start) + "ms");

        ImageIO.write(temp, "png", new File("E:\\NewOCR\\binariazed.png"));

        System.exit(0);

//        searchCharacters = searchCharactersCopy;
//
//        BufferedImage finalInput = input;
//        searchCharacters.forEach(searchCharacter -> searchCharacter.drawTo(finalInput));

//            System.out.println(searchCharacters.size() + " characters found");


//            AFFECT_BACKWARDS += 0.3D;
//        }

//        List<Map.Entry<Double, Double>> entries = new ArrayList<>(averageToBack.entrySet());
//        Collections.reverse(entries);
////        System.out.println(averageToBack);
//        System.out.println("\n\n====================");
//        entries.forEach(entry -> {
//            System.out.println(percent.format(entry.getKey() * 100) + "% \t\t| " + entry.getValue());
//        });
//
//        ImageIO.write(temp, "png", new File("E:\\NewOCR\\tempout.png"));
    }

    private static List<DatabaseCharacter> getSpacesFor(List<DatabaseCharacter> line, int fontSize) throws ExecutionException, InterruptedException {
        List<DatabaseCharacter> ret = new ArrayList<>();
        System.out.println("fontSize = " + fontSize);
        List<DatabaseCharacter> data = databaseManager.getAllCharacterSegments(matchNearestFontSize(fontSize)).get();
        DatabaseCharacter space = data.stream().filter(databaseCharacter -> databaseCharacter.getLetter() == ' ').findFirst().orElse(null);
        if (space == null) {
            System.err.println("No space found for current font size: " + fontSize);
            return line;
        }

        System.out.println("line = " + line);

        DatabaseCharacter prev = null;
        for (DatabaseCharacter databaseCharacter : line) {
            if (prev == null) {
                prev = databaseCharacter;
                continue;
            }

//            System.out.println(databaseCharacter.getX() + " - " + prev.getX() + " + " + prev.getWidth());

            int leftX = prev.getX() + prev.getWidth();
            int rightX = databaseCharacter.getX();
            double gap = rightX - leftX;

//            System.out.println("Gap: " + gap);

            double ratio = space.getAvgWidth() / space.getAvgHeight();
            double usedWidth = ratio * databaseCharacter.getWidth();

//            System.out.println("Ratio: " + ratio);
//            System.out.println("Using width: " + usedWidth);

            int spaces = spaceRound(gap / usedWidth);

            System.out.println(spaces + "\t" + (gap / usedWidth) + " (" + spaceRound(gap / usedWidth) + ")");
            System.out.println(databaseCharacter.getLetter());

            for (int i = 0; i < spaces; i++) {
                DatabaseCharacter insertingSpace = space.copy();
//                System.out.println("X is: " + ((int) (leftX + (usedWidth * (i - 1)))));
                insertingSpace.setX((int) (leftX + (usedWidth * (i - 1))));
                ret.add(insertingSpace);
            }

            prev = databaseCharacter;
        }

        return ret;
    }

    private static int spaceRound(double input) {
        int known = (int) Math.floor(input);
        double extra = input % 1;

        known += checkDifference(extra, 1, 0.2D) ? 1 : 0;

        return known;
    }

    public static void generateFeatures(File file) throws IOException {
        Map<FontBounds, List<TrainedCharacterData>> trainedCharacterDataList = new HashMap<>();
        Arrays.stream(FONT_BOUNDS).forEach(fontBounds -> trainedCharacterDataList.put(fontBounds, new ArrayList<>()));
        BufferedImage input = ImageIO.read(file);
        boolean[][] values = createGrid(input);
        List<SearchCharacter> searchCharacters = new ArrayList<>();

        BufferedImage temp = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_ARGB);
        rewriteImage(temp, input);
        input = temp;

        filter(input);
        toGrid(input, values);

//        ImageIO.write(input, "png", new File("E:\\NewOCR\\binariazed.png"));

//        printOut(values);
        Main.testImageShit = input;

        SearchImage searchImage = new SearchImage(values, input.getWidth(), input.getHeight());

        boolean[][] valuesClone = new boolean[values.length][values[0].length];
        for (int y = 0; y < values.length; y++) {
            boolean[] row = new boolean[values[y].length];
            if (values[y].length >= 0) System.arraycopy(values[y], 0, row, 0, values[y].length);

            valuesClone[y] = row;
        }

        List<Map.Entry<Integer, Integer>> coordinates = new ArrayList<>();

//        List<Double> doubles = new ArrayList<>();

        for (int y = input.getHeight(); 0 <= --y; ) {
            for (int x = 0; x < input.getWidth(); x++) {
                if (getLetterFrom(searchImage, input, x, y, coordinates, searchCharacters)) continue;
            }
        }

        System.out.println("\t\t\t\t\t\t\tHas dots: " + searchCharacters.stream().filter(searchCharacter -> !searchCharacter.hasDot()).count());

//        searchCharacters.forEach(searchCharacter -> searchCharacter.drawTo());
        System.out.println("CHARS: " + searchCharacters.size());

        trainedCharacterDataList.values().forEach(dataList -> {
            IntStream.range('!', '~' + 1).forEach(letter -> dataList.add(new TrainedCharacterData((char) letter)));
//            dataList.add(new TrainedCharacterData((char) 1));
            dataList.add(new TrainedCharacterData(' '));
//            dataList.add(new TrainedCharacterData((char) 2));
        });

//        int maxWidth = searchCharacters.stream().mapToInt(SearchCharacter::getHeight).max().getAsInt();

        BufferedImage finalInput = input;
//        searchCharacters.stream().sorted().forEach(searchCharacter -> searchCharacter.drawTo(finalInput));
        Collections.sort(searchCharacters);


        System.out.println("searchCharacters = " + searchCharacters.size());

//        printOut(valuesClone);

        System.out.println("TOTAL CHARACTERS: " + trainString.length());

        // topY, bottomY
        List<Pair<Integer, Integer>> lineBounds = getLineBoundsForTesting(valuesClone);
        System.out.println("lineBounds = " + lineBounds);

//        lineBounds.forEach(pair -> {
//            int topY = pair.getKey();
//            int bottomY = pair.getValue();
//            colorRow(finalInput, Color.GREEN, topY, 0, finalInput.getWidth());
//            colorRow(finalInput, Color.GREEN, bottomY, 0, finalInput.getWidth());
//        });

        System.out.println("\t\t\t\t\t\t\t111 Has dots: " + searchCharacters.stream().filter(searchCharacter -> !searchCharacter.hasDot()).count());

        List<SearchCharacter> searchCharactersCopy = new ArrayList<>(searchCharacters);

        System.out.println("\t\t\t\t\t\t\t222 Has dots: " + searchCharactersCopy.stream().filter(searchCharacter -> !searchCharacter.hasDot()).count());

        for (Pair<Integer, Integer> lineBound : lineBounds) {
            List<SearchCharacter> line = findCharacterAtLine(lineBound.getKey(), lineBound.getValue(), searchCharacters);

//            System.out.println("line = " + line.size());

            if (!line.isEmpty()) {

//                System.out.println("\t\t\t" + line.size());

                final boolean[] first = {false};

                letterIndex = 0;
                inc = 0;

                AtomicInteger beforeSpaceX = new AtomicInteger();

                line.forEach(searchCharacter -> {
                    if (first[0]) {
                        letterIndex = 0;
                        first[0] = false;
                        inc = 0;
                    }

//                    List<TrainedCharacterData> characterList = trainedCharacterDataList.get(trainedCharacterDataList.keySet().stream().filter(fontBounds -> fontBounds.isInbetween(searchCharacter.getHeight())).findFirst().orElse(null));
//                    List<TrainedCharacterData> characterList = trainedCharacterDataList.get(trainedCharacterDataList.keySet()
//                            .stream()
//                            .filter(fontBounds ->
//                                    fontBounds.isInbetween(searchCharacter.getHeight()))
//                            .findFirst()
//                            .orElse(null));

                    AtomicInteger index = new AtomicInteger(0);
//                    List<TrainedCharacterData> characterList = new LinkedList<>();



//                    trainedCharacterDataList.keySet()
//                            .stream()
//                            .filter(fontBounds ->
//                                    fontBounds.isInbetween(searchCharacter.getHeight()))
//                            .forEach(fontBounds -> characterList.add(trainedCharacterDataList.get(fontBounds).get(index.getAndIncrement())));

                    char current = searchCharacter.getKnownChar() == ' ' ? ' ' : trainString.charAt(letterIndex++);

                    TrainedCharacterData trainedSearchCharacter = trainedCharacterDataList.get(trainedCharacterDataList.keySet()
                            .stream()
                            .filter(fontBounds ->
                                    fontBounds.isInbetween(searchCharacter.getHeight()))
                            .findFirst()
                            .get())
                            .stream()
                            .filter(trainedCharacterData -> trainedCharacterData.getValue() == current)
                            .findFirst()
                            .orElse(null);

                    TrainedCharacterData spaceTrainedCharacter = trainedCharacterDataList.get(trainedCharacterDataList.keySet()
                            .stream()
                            .filter(fontBounds ->
                                    fontBounds.isInbetween(searchCharacter.getHeight()))
                            .findFirst()
                            .get())
                            .stream()
                            .filter(trainedCharacterData -> trainedCharacterData.getValue() == ' ')
                            .findFirst()
                            .orElse(null);

                    if (letterIndex == trainString.length() - 2) {
                        beforeSpaceX.set(searchCharacter.getX() + searchCharacter.getWidth());
                        letterIndex++;
                        return;
                    } else if (letterIndex == trainString.length()) {
//                        TrainedCharacterData trainedCharacterData = getTrainedData(' ', characterList);
                        spaceTrainedCharacter.recalculateTo(searchCharacter.getX() - beforeSpaceX.get(), searchCharacter.getHeight());
                        letterIndex = 0;
                        return;
                    } else {
                        searchCharacter.setKnownChar(current);
                    }

//                    searchCharacter.drawTo(finalInput, TEMP[inc++]);
//                    if (inc >= 3) inc = 0;

//                    if (characterList != null) {
//                        TrainedCharacterData trainedCharacterData = getTrainedData(current, characterList);
                    trainedSearchCharacter.recalculateTo(searchCharacter);
//                        trainedCharacterData.recalculateCenter((double) searchCharacter.getY() - (double) lineBound.getKey()); // This gets just from top

//                        System.out.println("=====\t" + trainedCharacterData.getValue() + "\t=====");

                        double halfOfLineHeight = ((double) lineBound.getValue() - (double) lineBound.getKey()) / 2;
//                        System.out.println("halfOfLineHeight = " + halfOfLineHeight);
//                        if (halfOfLineHeight < 0) System.out.println("SHIT");
                        double middleToTopChar = (double) searchCharacter.getY() - (double) lineBound.getKey();
//                        System.out.println("middleToTopChar = " + middleToTopChar);
//                        if (middleToTopChar < 0) System.out.println("Other SHIT: " + middleToTopChar);
                        double topOfLetterToCenter = halfOfLineHeight - middleToTopChar;
//                        System.out.println("topOfLetterToCenter = " + topOfLetterToCenter);

//                        if (topOfLetterToCenter < 0) System.out.println("two SHIT");

//                        System.out.println("\n");

                        if (trainedSearchCharacter.getValue() == 'F' || trainedSearchCharacter.getValue() == 'o') {
                            System.out.println(trainedSearchCharacter.getValue() + "\t" + topOfLetterToCenter + " (" + searchCharacter.getHeight() + ")");
                        }

                        // TODO: Make letters' minFontSize and maxFontSize in the database (Derived from the FontBounds) be stored PER LINE and NOT per character dimensions. This will allow the centers of characters to line up based on their height, to have a single uniform line instead of trying to get the next character based on the previous one (Which could cause a very large staggering effect to be allowed with any tolerance above 0).

                        colorRow(finalInput, Color.RED, (int) (searchCharacter.getY() + topOfLetterToCenter), searchCharacter.getX(), searchCharacter.getWidth());

                        trainedSearchCharacter.recalculateCenter(topOfLetterToCenter); // This NOW gets offset from top of
                        trainedSearchCharacter.setHasDot(searchCharacter.hasDot());
                        trainedSearchCharacter.setLetterMeta(searchCharacter.getLetterMeta());
//                    }

                    if (letterIndex >= trainString.length()) {
                        letterIndex = 0;
                    }
                });

                searchCharacters.removeAll(line);
            }
        }

//        ImageIO.write(input, "png", new File("E:\\NewOCR\\output.png"));

        searchCharacters = searchCharactersCopy;

        System.out.println(searchCharacters.size() + " characters found");

        long start = System.currentTimeMillis();
        System.out.println("Writing output...");
        ImageIO.write(input, "png", new File("E:\\NewOCR\\outputNEW.png"));
        System.out.println("Wrote output in " + (System.currentTimeMillis() - start) + "ms");

//        System.exit(0);

        System.out.println("Writing data to database...");
        start = System.currentTimeMillis();

//        trainedCharacterDataList.values().stream().flatMap(List::stream).forEach(TrainedCharacterData::finishRecalculations);

        System.out.println("trainedCharacterDataList = " + trainedCharacterDataList);

        trainedCharacterDataList
                .forEach((fontBounds, databaseTrainedCharacters) -> databaseTrainedCharacters
                        .parallelStream().forEach(databaseTrainedCharacter -> {
                            if (databaseTrainedCharacter.isEmpty()) return;
            databaseTrainedCharacter.finishRecalculations();
            char letter = databaseTrainedCharacter.getValue();

            Future databaseFuture = databaseManager.clearLetterSegments(letter, fontBounds.getMinFont(), fontBounds.getMaxFont());
            while (!databaseFuture.isDone()) {
            }

            databaseFuture = databaseManager.createLetterEntry(letter, databaseTrainedCharacter.getWidthAverage(), databaseTrainedCharacter.getHeightAverage(), fontBounds.getMinFont(), fontBounds.getMaxFont(), databaseTrainedCharacter.getMinCenter(), databaseTrainedCharacter.getMaxCenter(), databaseTrainedCharacter.hasDot(), databaseTrainedCharacter.getLetterMeta(), letter == ' ');
            while (!databaseFuture.isDone()) {
            }

            if (letter != ' ') {
                databaseFuture = databaseManager.addLetterSegments(letter, fontBounds.getMinFont(), fontBounds.getMaxFont(), databaseTrainedCharacter.getSegmentPercentages());
                while (!databaseFuture.isDone()) {
                }
            }
        }));

//        System.out.println("Finished training in " + (System.currentTimeMillis() - start) + "ms");

//        trainedCharacterData.forEach(TrainedCharacterData::preformRecalculations);

//        ImageIO.write(input, "png", new File("E:\\NewOCR\\output.png"));
    }

    private static boolean isWithin(int one, int two, double within) {
        double diff = Math.max((double) one, (double) two) - Math.min((double) one, (double) two);
        return diff <= within;
    }

    private static double[] getDifferencesFrom(double[] input1, double[] input2) {
        if (input1.length != input2.length) return null;
        double[] ret = new double[input1.length];

        for (int i = 0; i < input1.length; i++) {
            double one = input1[i];
            double two = input2[i];

            ret[i] = Math.max(one, two) - Math.min(one, two);
        }

        return ret;
    }

    private static void scanFrom(BufferedImage image, int x, int y, Color color, List<Map.Entry<Integer, Integer>> coordinates) {
//        System.out.println("(" + x + ", " + y + ")");
        if (image.getRGB(x, y) == color.getRGB()) {
            image.setRGB(x, y, Color.WHITE.getRGB());
            coordinates.add(new AbstractMap.SimpleEntry<>(x, y));

            scanFrom(image, x, y + 1, color, coordinates);
            scanFrom(image, x, y - 1, color, coordinates);
            scanFrom(image, x + 1, y, color, coordinates);
            scanFrom(image, x - 1, y, color, coordinates);
            scanFrom(image, x + 1, y + 1, color, coordinates);
            scanFrom(image, x + 1, y - 1, color, coordinates);
            scanFrom(image, x - 1, y + 1, color, coordinates);
            scanFrom(image, x - 1, y - 1, color, coordinates);
        }
    }

    private static boolean getLetterFrom(SearchImage searchImage, BufferedImage binaryImage, int x, int y, List<Map.Entry<Integer, Integer>> coordinates, List<SearchCharacter> searchCharacters) {
        SearchCharacter searchCharacter;

        if (binaryImage.getRGB(x, y) == Color.RED.getRGB()) {
            scanFrom(binaryImage, x, y, Color.RED, coordinates);

            searchCharacter = new SearchCharacter(coordinates);
            searchCharacter.setKnownChar(' ');
        } else {
            searchImage.scanFrom(x, y, coordinates);
            if (coordinates.size() == 0) return false;

            searchCharacter = new SearchCharacter(coordinates);

            if (doDotStuff(searchCharacter, coordinates, searchCharacters)) return true;
            if (doPercentStuff(searchCharacter, coordinates, searchCharacters)) return true;

            Optional<SearchCharacter> possibleDot = getBaseForPercent(searchCharacters, searchCharacter);
            if (possibleDot.isPresent()) {
                combine(possibleDot.get(), searchCharacter, coordinates, CombineMethod.PERCENTAGE_CIRCLE, LetterMeta.PERCENT);
                searchCharacters.remove(searchCharacter);
                return true;
            }

            if (searchCharacter.isProbablyDot() && !searchCharacter.hasDot()) {
                possibleDot = getBaseOfDot(searchCharacters, searchCharacter);
                if (possibleDot.isPresent()) {
                    combine(possibleDot.get(), searchCharacter, coordinates, CombineMethod.DOT, LetterMeta.DOT_ABOVE);
                    searchCharacters.remove(searchCharacter);
                    return true;
                }
            }

            // For ! or ?
            possibleDot = getDotUnderLetter(searchCharacters, searchCharacter);
            if (possibleDot.isPresent()) {
                combine(possibleDot.get(), searchCharacter, coordinates, CombineMethod.DOT, LetterMeta.DOT_UNDER);
                searchCharacters.remove(searchCharacter);
                return true;
            }

            if (searchCharacter.isProbablyColon() && isAllBlack(searchCharacter) && !searchCharacter.hasDot()) {
                possibleDot = getBottomColon(searchCharacters, searchCharacter);
                if (possibleDot.isPresent()) {
                    combine(possibleDot.get(), searchCharacter, coordinates, CombineMethod.COLON, LetterMeta.EVEN_DOTS);
                    searchCharacters.remove(searchCharacter);
                    return true;
                }
            }
        }

        searchCharacter.applySections();
        searchCharacter.analyzeSlices();

        searchCharacters.add(searchCharacter);
        coordinates.clear();

        return false;
    }

    private static FontBounds matchNearestFontSize(int fontSize) {
        return Arrays.stream(FONT_BOUNDS).filter(fontBounds -> fontBounds.isInbetween(fontSize)).findFirst().get();
    }

    private static int doneee = 0;

    private static DatabaseCharacter getCharacterFor(SearchCharacter searchCharacter) {
        Map<DatabaseCharacter, Double> diffs = new HashMap<>(); // The lower value the better

        System.out.println("Height: " + searchCharacter.getHeight() + " " + matchNearestFontSize(searchCharacter.getHeight()));

        try {
            List<DatabaseCharacter> data = new ArrayList<>(databaseManager.getAllCharacterSegments(matchNearestFontSize(searchCharacter.getHeight())).get());
//            System.out.println(data.size());
//            System.out.println(searchCharacter.getHeight() + "] data = " + data);

//            System.out.println("Search: " + searchCharacter.hasDot());

            data.stream()
                    .filter(character -> character.hasDot() == searchCharacter.hasDot())
                    .filter(character -> character.getLetterMeta() == searchCharacter.getLetterMeta())
                    .forEach(character -> {
                double[] charDifference = getDifferencesFrom(searchCharacter.getSegmentPercentages(), character.getData());

                double value = 1;
                if (AVERAGE_DIFF) {
                    value = Arrays.stream(charDifference).average().getAsDouble();
                }

                DatabaseCharacter using = character.copy();
                using.setX(searchCharacter.getX());
                using.setY(searchCharacter.getY());
                using.setWidth(searchCharacter.getWidth());
                using.setHeight(searchCharacter.getHeight());
                using.setRatio(((double) searchCharacter.getWidth()) / ((double) searchCharacter.getHeight()));
//                using.setCenterExact(searchCharacter.getY() + using.getCenter());
                diffs.put(using, value);
            });

        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

//        System.out.println(diffs);


//        System.out.println("diffs = " + diffs);

        LinkedList<Map.Entry<DatabaseCharacter, Double>> entries = new LinkedList<>(sortByValue(diffs)
                .entrySet());

//        System.out.println(entries);

//        System.out.println("Got one: " + entries);

        if (entries.size() < 2) return null;

        DatabaseCharacter first = entries.removeFirst().getKey();
        DatabaseCharacter second = entries.removeFirst().getKey();

//        System.out.println(first.getLetter() + "\t\t" + (first.getAvgWidth() / first.getAvgHeight()) + " vs " + (second.getAvgWidth() / second.getAvgHeight()) + " REAL: " + ((double) searchCharacter.getWidth() / (double) searchCharacter.getHeight()));
//        System.out.println("\t\t^ " + searchCharacter.getWidth()  + " x " + searchCharacter.getHeight());

        System.out.println("\t\t\tFirst: min: " + first.getMinCenter() + " max: " + first.getMaxCenter());

//        System.out.println(entries);
//        System.out.println("\n");
//        return entries.isEmpty() ? null : entries.getFirst().getKey(); // 017458380571894187 before 04165299523632378
        return first; // 017458380571894187 before 04165299523632378
    }

    private static <K, V extends Comparable<? super V>> Map<K, V> sortByValue(Map<K, V> map) {
        List<Map.Entry<K, V>> list = new ArrayList<>(map.entrySet());
        list.sort(Map.Entry.comparingByValue());
//        Collections.reverse(list);

        Map<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : list) {
            result.put(entry.getKey(), entry.getValue());
        }

        return result;
    }

    private static List<Pair<Integer, Integer>> getLineBoundsForTesting(boolean[][] values) {
        // topY, bottomY
        List<Pair<Integer, Integer>> lines = new ArrayList<>();

        int height = 0;
        for (int y = 0; y < values.length; y++) {
            if (isRowPopulated(values, y)) {
                height++;
            } else if (height > 0) {
                int heightUntil = 0;
                int finalSpace = -1;

                // Seeing if the gap under the character is <= the height of the above piece. This is mainly for seeing
                // if the dot on an 'i' is <= is above the rest of the character the same amount as its height (Making it a proper 'i' in Verdana
                for (int i = 0; i < height; i++) {
                    if (y + i >= values.length) {
                        finalSpace = 0;
                        break;
                    }

                    if (isRowPopulated(values, y + i)) {
                        if (finalSpace == -1) finalSpace = heightUntil;
                    } else {
                        heightUntil++;
                    }
                }

                if (finalSpace > 0) {
//                    System.out.println("Vertical separation was " + finalSpace + " yet height was " + height);
                    if (height == finalSpace) {
                        y += finalSpace;
                        height += finalSpace;
                    } else {
                        lines.add(new Pair<>(y - height, y));
                        height = 0;
                    }
                } else {
                    lines.add(new Pair<>(y - height, y));
                    height = 0;
                }
            } else {
                if (height == 0) continue;
                lines.add(new Pair<>(y - height, y));
                height = 0;
            }
        }

        return lines;
    }

    private static boolean isRowPopulated(boolean[][] values, int y) {
        for (int x = 0; x < values[y].length; x++) {
            if (values[y][x]) return true;
        }

        return false;
    }

    private static TrainedCharacterData getTrainedData(char cha, List<TrainedCharacterData> trainedCharacterDataList) {
        return trainedCharacterDataList.stream().filter(characterData -> characterData.getValue() == cha).findFirst().get();
    }

    private static List<SearchCharacter> findCharacterAtY(int y, List<SearchCharacter> searchCharacters) {
        return findCharacterAtY(y, searchCharacters, -1);
    }

    private static List<SearchCharacter> findCharacterAtY(int y, List<SearchCharacter> searchCharacters, int addY) {
        Optional<SearchCharacter> optionalSearchCharacter = searchCharacters
                .stream()
                .filter(searchCharacter -> searchCharacter.isInYBounds(y))
                .findFirst();

        if (!optionalSearchCharacter.isPresent()) return new ArrayList<>();
        SearchCharacter betterYCharacter = optionalSearchCharacter.get();
        int betterY = addY == -1 ? betterYCharacter.getY() + betterYCharacter.getHeight() / 2 : addY;

        List<SearchCharacter> temp = searchCharacters
                .stream()
                .sorted()
                .filter(searchCharacter -> searchCharacter.isInYBounds(betterY))
                .collect(Collectors.toCollection(LinkedList::new));

        Optional<SearchCharacter> maxOneOptional = temp.stream().sorted((o1, o2) -> o2.getHeight() - o1.getHeight()).findFirst();
        int otherBetterY = -1;
        if (maxOneOptional.isPresent()) {
            SearchCharacter maxOne = maxOneOptional.get();
            otherBetterY = maxOne.getY() + maxOne.getHeight() / 2;
            int finalOtherBetterY = otherBetterY;
            temp = searchCharacters
                    .stream()
                    .sorted()
                    .filter(searchCharacter -> searchCharacter.isInYBounds(betterY) || searchCharacter.isInYBounds(finalOtherBetterY))
                    .collect(Collectors.toCollection(LinkedList::new));
        }

        if (!temp.isEmpty()) {
            for (int x = 0; x < testImageShit.getWidth(); x++) {
                drawGuides(x, otherBetterY == -1 ? betterY : otherBetterY, Color.GREEN);
            }
        }

        return temp;
    }

    private static List<SearchCharacter> findCharacterAtLine(int topY, int bottomY, List<SearchCharacter> searchCharacters) {
        return searchCharacters
                .stream()
                .sorted()
                .filter(searchCharacter -> isWithin(topY, bottomY, searchCharacter.getY()))
                .collect(Collectors.toCollection(LinkedList::new));
    }

    private static boolean isWithin(int lowerBound, int upperBound, int value) {
        return lowerBound <= value && value <= upperBound;
    }

    private static boolean isWithin(double lowerBound, double upperBound, double value) {
        return lowerBound <= value && value <= upperBound;
    }

    // Is difference equal to or under
    private static boolean checkDifference(double num1, double num2, double amount) {
//        System.out.println("Diff: " + (Math.max(num1, num2) - Math.min(num1, num2)) + " <= " + amount);
        return Math.max(num1, num2) - Math.min(num1, num2) <= amount;
    }

    private static void makeImage(boolean[][] values, String name) {
        try {
            BufferedImage image = new BufferedImage(values[0].length, values.length, BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, (values[y][x] ? Color.BLACK : Color.WHITE).getRGB());
                }
            }

            ImageIO.write(image, "png", new File("E:\\NewOCR\\" + name + ".png"));
        } catch (IOException e) {
//            e.printStackTrace();
        }
    }

    public static Stream<boolean[][]> getHorizontalHalf(boolean[][] values) {
        int topHeight = values.length / 2;
        int bottomHeight = values.length - topHeight;

        boolean[][] topHalf = new boolean[topHeight][];
        boolean[][] bottomHalf = new boolean[bottomHeight][];

        for (int y = 0; y < values.length; y++) {
            if (y < topHeight) {
                topHalf[y] = values[y];
            } else {
                bottomHalf[y - topHeight] = values[y];
            }
        }

        return Stream.of(topHalf, bottomHalf).sequential();
    }

    public static Stream<boolean[][]> getHorizontalThird(boolean[][] values) {
        int topHeight = values.length / 3;
        int middleHeight = values.length - topHeight * 2;
        int bottomHeight = topHeight;

        boolean[][] topThird = new boolean[topHeight][];
        boolean[][] middleThird = new boolean[middleHeight][];
        boolean[][] bottomThird = new boolean[bottomHeight][];

        for (int y = 0; y < values.length; y++) {
            if (y < topHeight) {
                topThird[y] = values[y];
            } else if (y < topHeight + middleHeight) {
                middleThird[y - topHeight] = values[y];
            } else {
                bottomThird[y - topHeight - middleHeight] = values[y];
            }
        }

        return Stream.of(topThird, middleThird, bottomThird).sequential();
    }

    public static Stream<boolean[][]> getVerticalHalf(boolean[][] values) {
        if (values.length == 0) return Stream.of(null, null);
        int leftHeight = values[0].length / 2;
        int rightHeight = values[0].length - leftHeight;

        boolean[][] leftHalf = new boolean[values.length][];
        boolean[][] rightHalf = new boolean[values.length][];

        for (int i = 0; i < values.length; i++) {
            leftHalf[i] = new boolean[leftHeight];
            rightHalf[i] = new boolean[rightHeight];
        }

        for (int y = 0; y < values.length; y++) {
            for (int x = 0; x < values[0].length; x++) {
                if (x < leftHeight) {
                    leftHalf[y][x] = values[y][x];
                } else {
                    rightHalf[y][x - leftHeight] = values[y][x];
                }
            }
        }

        return Stream.of(leftHalf, rightHalf).sequential();
    }

    public static Stream<boolean[][]> getVerticalThird(boolean[][] values) {
        if (values.length == 0) return Stream.of(null, null, null);
        int leftHeight = values[0].length / 3;
        int middleHeight = values[0].length - leftHeight * 2;
        int rightHeight = leftHeight;

        boolean[][] leftHalf = new boolean[values.length][];
        boolean[][] middleHalf = new boolean[values.length][];
        boolean[][] rightHalf = new boolean[values.length][];

        for (int i = 0; i < values.length; i++) {
            leftHalf[i] = new boolean[leftHeight];
            middleHalf[i] = new boolean[middleHeight];
            rightHalf[i] = new boolean[rightHeight];
        }

        for (int y = 0; y < values.length; y++) {
            for (int x = 0; x < values[0].length; x++) {
                if (x < leftHeight) {
                    leftHalf[y][x] = values[y][x];
                } else if (x < middleHeight + leftHeight) {
                    middleHalf[y][x - leftHeight] = values[y][x];
                } else {
                    rightHalf[y][x - leftHeight - middleHeight] = values[y][x];
                }
            }
        }

        return Stream.of(leftHalf, middleHalf, rightHalf).sequential();
    }

    public static Map<boolean[][], Integer> getDiagonal(boolean[][] values, boolean increasing) {
        double slope = (double) values.length / (double) values[0].length;

        List<Integer> yPositions = new ArrayList<>();

        for (int x = 0; x < values[0].length; x++) {
            double y = slope * x;
            if (increasing) y = values.length - y;
            yPositions.add((int) y);
        }

        boolean[][] topHalf = new boolean[values.length][];
        boolean[][] bottomHalf = new boolean[values.length][];
        int topSize = 0;
        int bottomSize = 0;

        for (int i = 0; i < values.length; i++) {
            topHalf[i] = new boolean[values[0].length];
            bottomHalf[i] = new boolean[values[0].length];
        }

        for (int x = 0; x < values[0].length; x++) {
            int yPos = yPositions.get(x);
            for (int y = 0; y < values.length; y++) {
                if (y < yPos) {
                    bottomHalf[y][x] = values[y][x];
                    bottomSize++;
                } else {
                    topHalf[y][x] = values[y][x];
                    topSize++;
                }
            }
        }

        Map<boolean[][], Integer> ret = new LinkedHashMap<>();
        ret.put(topHalf, topSize);
        ret.put(bottomHalf, bottomSize);
        return ret;
    }

    private static void rewriteImage(BufferedImage temp, BufferedImage input) {
        for (int y = 0; y < temp.getHeight(); y++) {
            for (int x = 0; x < temp.getWidth(); x++) {
                temp.setRGB(x, y, input.getRGB(x, y));
            }
        }
    }

    private static void toGrid(BufferedImage input, boolean[][] values) {
        int arrX = 0;
        int arrY = 0;
        for (int y = 0; y < input.getHeight(); y++) {
            for (int x = 0; x < input.getWidth(); x++) {
                values[arrY][arrX++] = new Color(input.getRGB(x, y)).equals(Color.BLACK);
            }

            arrX = 0;
            arrY++;
        }
    }

    private static boolean doDotStuff(SearchCharacter dotCharacter, List<Map.Entry<Integer, Integer>> coordinates, List<SearchCharacter> searchCharacters) {
        if (!dotCharacter.isProbablyDot()) return false;
        SearchCharacter baseCharacter = getBaseOfDot(searchCharacters, dotCharacter).orElse(null);
        if (baseCharacter != null) {
            combine(baseCharacter, dotCharacter, coordinates, CombineMethod.DOT, LetterMeta.DOT_ABOVE);
            baseCharacter.setHasDot(true);
            dotCharacter.setHasDot(true);
            return true;
        }

        return false;
    }

    private static boolean doPercentStuff(SearchCharacter percentDotCharacter, List<Map.Entry<Integer, Integer>> coordinates, List<SearchCharacter> searchCharacters) {
        if (!percentDotCharacter.isProbablyCircleOfPercent()) return false;
        SearchCharacter baseCharacter = getBaseForPercent(searchCharacters, percentDotCharacter).orElse(null);
        if (baseCharacter != null) {
            combine(baseCharacter, percentDotCharacter, coordinates, CombineMethod.PERCENTAGE_CIRCLE, LetterMeta.PERCENT);
            baseCharacter.setHasDot(true);
            percentDotCharacter.setHasDot(true);
            return true;
        }

        return false;
    }

    /*private static boolean doApostropheStuff(SearchCharacter rightApostrophe, List<Map.Entry<Integer, Integer>> coordinates, List<SearchCharacter> searchCharacters) {
        if (!rightApostrophe.isProbablyApostraphe()) return false;
        SearchCharacter leftApostrophe = getLeftApostrophe(searchCharacters, rightApostrophe).orElse(null);
        if (leftApostrophe != null) {
            combine(leftApostrophe, rightApostrophe, coordinates, CombineMethod.APOSTROPHE);
            return true;
        }

        return false;
    }*/

    private static void combine(SearchCharacter baseCharacter, SearchCharacter adding, List<Map.Entry<Integer, Integer>> coordinates, CombineMethod combineMethod, LetterMeta letterMeta) {
        int minX = Math.min(baseCharacter.getX(), adding.getX());
        int minY = Math.min(baseCharacter.getY(), adding.getY());
        int maxX = Math.max(baseCharacter.getX() + baseCharacter.getWidth(), adding.getX() + adding.getWidth());
        int maxY = Math.max(baseCharacter.getY() + baseCharacter.getHeight(), adding.getY() + adding.getHeight());
        baseCharacter.setWidth(maxX - minX);
        baseCharacter.setHeight(maxY - minY);
        baseCharacter.setX(minX);
        baseCharacter.setY(minY);
        baseCharacter.setLetterMeta(letterMeta);

        switch (combineMethod) {
            case DOT:
            case COLON:
                maxX = baseCharacter.getX() + baseCharacter.getWidth();
                maxY = baseCharacter.getY() + baseCharacter.getHeight();
                baseCharacter.setHeight(maxY - adding.getY());
                baseCharacter.setY(adding.getY());

                int dotMaxX = adding.getX() + adding.getWidth();

                if (dotMaxX > maxX) {
                    baseCharacter.setWidth(dotMaxX - baseCharacter.getX());
                }

                baseCharacter.addDot(coordinates);
                break;
            case PERCENTAGE_CIRCLE:
                baseCharacter.addPercentageCircle(coordinates, adding.getY() + (adding.getHeight() / 2) < baseCharacter.getY() + (baseCharacter.getHeight() / 2));
                break;
            case APOSTROPHE:
                baseCharacter.addPercentageCircle(coordinates, false);
                break;
        }

        coordinates.clear();
    }

    // I think for i and j
    private static Optional<SearchCharacter> getBaseOfDot(List<SearchCharacter> characters, SearchCharacter dotCharacter) {
        return characters.parallelStream()
                .filter(character -> !character.equals(dotCharacter))
                .filter(character -> !character.hasDot())
                .filter(character -> character.isInBounds(dotCharacter.getX() + (dotCharacter.getWidth() / 2), character.getY() + 4))
                .filter(character -> character.getHeight() > dotCharacter.getHeight() * 5)
                .filter(baseCharacter -> {
                    int below = dotCharacter.getY() + dotCharacter.getHeight() + 1;

                    return checkDifference(below, baseCharacter.getY(), dotCharacter.getHeight() + 2);
                })
                .findFirst();
    }

    // For ! or ?
    private static Optional<SearchCharacter> getDotUnderLetter(List<SearchCharacter> characters, SearchCharacter baseCharacter) {
        return characters.parallelStream()
                .filter(character -> !character.equals(baseCharacter))
                .filter(character -> !character.hasDot())
                .filter(SearchCharacter::isProbablyDot)
                .filter(character -> baseCharacter.isInBounds(character.getX() + (character.getWidth() / 2), baseCharacter.getY() + 4))
                .filter(character -> baseCharacter.getHeight() > character.getHeight() * 2)
                .filter(dotCharacter -> {
                    int below = dotCharacter.getY() - dotCharacter.getHeight();
                    int mod = dotCharacter.getHeight();
                    return checkDifference(below, baseCharacter.getY() + baseCharacter.getHeight(), mod + 2);
                })
                .findFirst();
    }

    // : or ;
    private static Optional<SearchCharacter> getBottomColon(List<SearchCharacter> characters, SearchCharacter topDot) {
        return characters.stream()
                .filter(character -> !character.equals(topDot))
                .filter(character -> !character.hasDot())
                .filter(character -> topDot.isInXBounds(character.getX() + (character.getWidth() / 2)))
                .filter(character -> {
//                    System.out.println("Got to!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                    double ratio = (double) topDot.getHeight() / (double) character.getHeight();
//                    System.out.println("ratio = " + ratio);
                    if (character.getWidth() * 2 < topDot.getWidth()) return false;
                    return (ratio >= 0.25 && ratio <= 0.5) || (topDot.getHeight() == character.getHeight() && topDot.getWidth() == character.getWidth());
                })
                .filter(bottomCharacter -> {
//                    int below = bottomCharacter.getY() - bottomCharacter.getHeight() * 2;
                    double mult = ((double) bottomCharacter.getWidth() / (double) bottomCharacter.getHeight() > 3 && Arrays.deepEquals(bottomCharacter.getValues(), topDot.getValues())) ? 5 : 5;
                    int mod = (int) (topDot.getHeight() * mult);

//                    System.out.println("mult = " + mult);
//                    System.out.println("mod = " + mod);

                    return checkDifference(bottomCharacter.getY(), topDot.getY() + topDot.getHeight(), mod + 1);
                })
                .findFirst();
    }

    private static void drawGuides(int x, int y, Color color) {
        if (x < 0 || y < 0) return;
        if (x >= testImageShit.getWidth() || y >= testImageShit.getWidth()) return;
        testImageShit.setRGB(x, y, color.getRGB());
    }

    private static boolean isAllBlack(SearchCharacter searchCharacter) {
        for (boolean[] row : searchCharacter.getValues()) {
            for (boolean bool : row) {
                if (!bool) return false;
            }
        }

        return true;
    }

    private static Optional<SearchCharacter> getBaseForPercent(List<SearchCharacter> characters, SearchCharacter circleOfPercent) {
        return characters.parallelStream()
                .filter(searchCharacter -> searchCharacter.isOverlaping(circleOfPercent))
                .findFirst();
    }

    public static void colorRow(BufferedImage image, Color color, int y, int x, int width) {
        for (int x2 = 0; x2 < width; x2++) {
            image.setRGB(x2 + x, y, color.getRGB());
        }
    }

    public static void colorColumn(BufferedImage image, Color color, int x, int y, int height) {
        for (int y2 = 0; y2 < height; y2++) {
            image.setRGB(x, y + y2, color.getRGB());
        }
    }

    private static boolean[][] createGrid(BufferedImage bufferedImage) {
        boolean[][] values = new boolean[bufferedImage.getHeight()][];
        for (int i = 0; i < values.length; i++) {
            boolean[] row = new boolean[bufferedImage.getWidth()];
            for (int i1 = 0; i1 < row.length; i1++) row[i1] = false;

            values[i] = row;
        }

        return values;
    }

    public static void printOut(boolean[][] values) {
        for (boolean[] row : values) {
            for (boolean bool : row) {
                System.out.print(bool ? "1" : "0");
            }

            System.out.println("");
        }
    }

    private static void filter(BufferedImage bufferedImage) {
        int red = Color.RED.getRGB();
        for (int y = 0; y < bufferedImage.getHeight(); y++) {
            for (int x = 0; x < bufferedImage.getWidth(); x++) {
                Color writeColor = isBlack(bufferedImage, x, y) ? new Color(0, 0, 0, 255) : new Color(255, 255, 255, 255);
                if (bufferedImage.getRGB(x, y) == red) {
                    writeColor = Color.RED;
                }

                bufferedImage.setRGB(x, y, writeColor.getRGB());
            }
        }
    }

    private static boolean isBlack(BufferedImage image, int x, int y) {
        try {
            Color pixel = new Color(image.getRGB(x, y));
//            System.out.println(pixel);
            return (pixel.getRed() + pixel.getGreen() + pixel.getBlue()) / 3 < 255 * 0.75;
        } catch (ArrayIndexOutOfBoundsException e) {
            return true;
        }
    }

}
