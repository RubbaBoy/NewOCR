package com.uddernetworks.newocr.recognition;

import com.uddernetworks.newocr.character.ImageLetter;
import com.uddernetworks.newocr.character.SearchCharacter;
import com.uddernetworks.newocr.database.DatabaseManager;
import com.uddernetworks.newocr.detection.SearchImage;
import com.uddernetworks.newocr.train.TrainedCharacterData;
import com.uddernetworks.newocr.utils.Histogram;
import com.uddernetworks.newocr.utils.IntPair;
import com.uddernetworks.newocr.utils.OCRUtils;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static com.uddernetworks.newocr.utils.OCRUtils.diff;

public class OCRActions implements Actions {

    private DatabaseManager databaseManager;

    public OCRActions(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public void getLetters(SearchImage searchImage, List<SearchCharacter> searchCharacters) {
        var coordinates = new ArrayList<IntPair>();

        var width = searchImage.getWidth();
        var height = searchImage.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                searchImage.scanFrom(x, y, coordinates);

                if (!coordinates.isEmpty()) {
                    searchCharacters.add(new SearchCharacter(new ArrayList<>(coordinates)));
                    coordinates.clear();
                }
            }
        }
    }

    @Override
    public void getLettersDuringTraining(SearchImage searchImage, List<SearchCharacter> searchCharacters) {
        var histogram = new Histogram(searchImage);
        for (var coords : histogram.getWholeLines()) {
            var fromY = coords.getKey();
            var toY = coords.getValue();

            var sub = searchImage.getSubimage(0, fromY, searchImage.getWidth(), toY - fromY);

//                ImageIO.write(sub.toImage(), "png", new File("E:\\NewOCR\\ind\\" + fromY + ".png"));

            var width = sub.getWidth();
            var height = sub.getHeight();

            var coordinates = new ArrayList<IntPair>();
            var found = new ArrayList<SearchCharacter>();

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    sub.scanFrom(x, y, coordinates);

                    if (!coordinates.isEmpty()) {
                        found.add(new SearchCharacter(new ArrayList<>(coordinates)));
                        coordinates.clear();
                    }
                }
            }

            System.out.println("Found size before: " + found.size());

            // Get the characters with horizontal overlap

            var ignored = new HashSet<SearchCharacter>();

            for (SearchCharacter part1 : found) {
                if (ignored.contains(part1)) continue;

                for (SearchCharacter part2 : found) {
                    if (part1.equals(part2)) continue;

                    if (part1.isOverlapingX(part2)) {
                        part1.merge(part2);
                        ignored.add(part2);
                        break;
                    }
                }
            }

            ignored.forEach(found::remove);
            Collections.sort(found);

            searchCharacters.addAll(found);
        }
    }

    @Override
    public Optional<ImageLetter> getCharacterFor(SearchCharacter searchCharacter) {
        try {
            Object2DoubleMap<ImageLetter> diffs = new Object2DoubleOpenHashMap<>(); // The lower value the better

            var data = new ArrayList<>(databaseManager.getAllCharacterSegments().get());

            data.stream()
                    .filter(character -> character.hasDot() == searchCharacter.hasDot())
                    .filter(character -> character.getLetterMeta() == searchCharacter.getLetterMeta())
                    .forEach(character ->
                            OCRUtils.getDifferencesFrom(searchCharacter.getSegmentPercentages(), character.getData()).ifPresent(charDifference -> {
                                var value = Arrays.stream(charDifference).average().orElse(0);
                                // Gets the difference of the database character and searchCharacter (Lower is better)
                                var imageLetter = new ImageLetter(character.getLetter(), searchCharacter.getX(), searchCharacter.getY(), searchCharacter.getWidth(), searchCharacter.getHeight(), character.getAvgWidth(), character.getAvgHeight(), ((double) searchCharacter.getWidth()) / ((double) searchCharacter.getHeight()), searchCharacter.getCoordinates());
                                imageLetter.setMaxCenter(character.getMaxCenter());
                                imageLetter.setMinCenter(character.getMinCenter());
                                diffs.put(imageLetter, value);
                            }));

            return getCharacterFor(searchCharacter, diffs);

        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

        return Optional.empty();
    }

    @Override
    public Optional<ImageLetter> getCharacterFor(SearchCharacter searchCharacter, List<TrainedCharacterData> data) {
        Object2DoubleMap<ImageLetter> diffs = new Object2DoubleOpenHashMap<>(); // The lower value the better

        data.stream()
                .filter(character -> character.hasDot() == searchCharacter.hasDot())
                .filter(character -> character.getLetterMeta() == searchCharacter.getLetterMeta())
                .forEach(character -> {
                    character.finishRecalculations();
                    OCRUtils.getDifferencesFrom(searchCharacter.getSegmentPercentages(), character.getSegmentPercentages()).ifPresent(charDifference -> {
                        var value = Arrays.stream(charDifference).average().orElse(0);
                        // Gets the difference of the database character and searchCharacter (Lower is better)
                        diffs.put(new ImageLetter(character.getValue(), searchCharacter.getX(), searchCharacter.getY(), searchCharacter.getWidth(), searchCharacter.getHeight(), character.getWidthAverage(), character.getHeightAverage(), ((double) searchCharacter.getWidth()) / ((double) searchCharacter.getHeight()), searchCharacter.getCoordinates()), value);
                    });
                });

        return getCharacterFor(searchCharacter, diffs);
    }

    @Override
    public Optional<ImageLetter> getCharacterFor(SearchCharacter searchCharacter, Object2DoubleMap<ImageLetter> diffs) {
        // TODO: The following code can definitely be improved
        var entries = diffs.object2DoubleEntrySet()
                .stream()
                .sorted(Comparator.comparingDouble(Object2DoubleMap.Entry::getDoubleValue))
                .limit(10)
                .collect(Collectors.toList());

        // If there's no characters found, don't continue
        if (entries.isEmpty()) {
            return Optional.empty();
        }

        var firstEntry = entries.get(0);

        double ratio = firstEntry.getKey().getAverageWidth() / firstEntry.getKey().getAverageHeight();
        double searchRatio = (double) searchCharacter.getWidth() / (double) searchCharacter.getHeight();

        var ratioDifference = diff(ratio, searchRatio);

        var secondEntry = entries.get(1);

        // Skip everything if it has a very high confidence of the character (Much higher than the closest one OR is <= 0.01 in confidence),
        // and make sure that the difference in width/height is very low, or else it will continue and sort by width/height difference.
        var bigDifference = firstEntry.getDoubleValue() * 2 > secondEntry.getDoubleValue(); // If true, SORT
        var verysmallDifference = ratioDifference <= 0.01 || firstEntry.getDoubleValue() <= 0.01; // If true, skip sorting
        var ratioDiff = ratioDifference > 0.1; // If true, SORT

        if (!verysmallDifference && (bigDifference || ratioDiff)) {
            entries.sort(Comparator.comparingDouble(entry -> {
                // Lower is more similar
                return compareSizes(searchCharacter.getWidth(), searchCharacter.getHeight(), entry.getKey().getAverageWidth(), entry.getKey().getAverageHeight());
            }));
        }

        ImageLetter first = entries.get(0).getKey();
        first.setValues(searchCharacter.getValues());
        return Optional.of(first);
    }

    @Override
    public double compareSizes(double width1, double height1, double width2, double height2) {
        var res = 0D;

        double ratio1 = width1 / height1;
        double ratio2 = width2 / height2;
        double ratioDiff = diff(ratio1, ratio2);

        if ((width1 > height1 && width2 < height2)
                || (width1 < height1 && width2 > height2)) {
            // If they aren't rotated the right way (E.g. tall rectangle isn't similar to a wide one)
            if (ratioDiff > 0.5) {
                res += 300D;
            }
        }

        res += ratioDiff;
        return res;
    }

}
