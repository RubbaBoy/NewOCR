package com.uddernetworks.newocr.recognition;

import com.uddernetworks.newocr.character.TrainedCharacterData;
import com.uddernetworks.newocr.train.ComputerTrainGenerator;
import com.uddernetworks.newocr.train.TrainGeneratorOptions;

import java.io.File;
import java.util.List;

/**
 * The main class that handles training of an image/font.
 *
 * @author Adam Yarris
 * @version 2.0.0
 * @since April 25, 2019
 */
public interface Train {

    /**
     * Scans the input image and creates training data based off of it. It must be an input image created from
     * {@link ComputerTrainGenerator} or something of a similar format.
     *
     * @param file The input image to be trained from
     */
    void trainImage(File file);

    /**
     * Scans the input image and creates training data based off of it. It must be an input image created from
     * {@link ComputerTrainGenerator} or something of a similar format.
     *
     * @param file             The input image to be trained from
     * @param generatorOptions The options to use, in order to detect font sizes
     */
    void trainImage(File file, TrainGeneratorOptions generatorOptions);

    /**
     * Gets the {@link TrainedCharacterData} with the known letter value of the given character, with the same modifier.
     * If a character is not found, it will be created and added to the given list.
     *
     * @param trainedCharacterDataList The list of {@link TrainedCharacterData}s to search though
     * @param current                  The character to find
     * @param finalModifier            The modifier for the character to find
     * @return The {@link TrainedCharacterData} with the same character and modifier
     */
    TrainedCharacterData getTrainedCharacter(List<TrainedCharacterData> trainedCharacterDataList, char current, int finalModifier);
}
