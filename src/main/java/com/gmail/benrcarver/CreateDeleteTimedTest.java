package com.gmail.benrcarver;

import com.gmail.benrcarver.distributed.util.Utils;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.fs.FileStatus;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class CreateDeleteTimedTest {
    /**
     * The default number of files to create.
     */
    private static final int DEFAULT_NUM_FILES = 5;

    /**
     * The default directory in which the files will be created.
     */
    private static final String DEFAULT_TARGET_DIRECTORY = "/cdtt/";

    private static final String DIRECTORY_OPTION = "directory";
    private static final String NUM_FILES_OPTION = "num_files";

    public static void main(String[] args) throws IOException, InterruptedException {
        Options options = new Options();

        Option numFilesOption = new Option("n", NUM_FILES_OPTION,
                true, "The number of files to create.");
        numFilesOption.setRequired(false);

        Option targetDirectoryOption = new Option("d", DIRECTORY_OPTION,
                true, "The target directory in which the files will be created.");
        targetDirectoryOption.setRequired(false);

        options.addOption(numFilesOption);
        options.addOption(targetDirectoryOption);

        CommandLineParser parser = new GnuParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("utility-name", options);
            System.exit(1);
        }

        int numFilesToCreate = DEFAULT_NUM_FILES;
        String targetDirectoryPath = DEFAULT_TARGET_DIRECTORY;

        if (cmd.hasOption(DIRECTORY_OPTION))
            targetDirectoryPath = cmd.getOptionValue(DIRECTORY_OPTION);

        if (cmd.hasOption(NUM_FILES_OPTION))
            numFilesToCreate = Integer.parseInt(cmd.getOptionValue(NUM_FILES_OPTION));

        if (!targetDirectoryPath.startsWith("/"))
            targetDirectoryPath = "/" + targetDirectoryPath;

        if (!targetDirectoryPath.endsWith("/"))
            targetDirectoryPath += "/";

        System.out.println("Starting HdfsTest now.");
        Configuration configuration = Utils.getConfiguration();
        System.out.println("Created configuration.");
        DistributedFileSystem hdfs = new DistributedFileSystem();
        System.out.println("Created DistributedFileSystem object.");

        try {
            hdfs.initialize(new URI("hdfs://10.241.64.14:9000"), configuration);
            System.out.println("Called initialize() successfully.");
        } catch (URISyntaxException | IOException ex) {
            ex.printStackTrace();
        }

        Path[] filePaths = new Path[numFilesToCreate];
        String[] fileContents = new String[numFilesToCreate];
        for (int i = 0; i < numFilesToCreate; i++) {
            filePaths[i] = new Path("hdfs://10.241.64.14:9000" + targetDirectoryPath + "cdttFile" + i + ".txt");
            fileContents[i] = "Hello World from " + targetDirectoryPath + "testFile-" + i + ".txt!";
        }

        Instant testStart = Instant.now();

        System.out.println("\nCreating and writing to files now...\n");
        for (int i = 0; i < numFilesToCreate; i++) {
            Path filePath = filePaths[i];
            String fileContent = fileContents[i];

            System.out.println("\nCreating file \"" + filePath + "\" with contents \"" + fileContent + "\"...\n");

            FSDataOutputStream outputStream = hdfs.create(filePath);
            System.out.println("\nCalled create() successfully.\n");

            BufferedWriter br = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));
            System.out.println("Created BufferedWriter object.");

            br.write(fileContent);
            System.out.println("Wrote \"" + fileContent + "\" using BufferedWriter.");

            br.close();
            System.out.println("Closed BufferedWriter.");

            // Just pause a bit here to try to get the connection establishment to occur.
            Thread.sleep(25);
        }

        Instant createFinished = Instant.now();

        Duration createDuration = Duration.between(testStart, createFinished);

        System.out.println("\nFinished creating all " + numFilesToCreate + " files. Create phase took: "
            + DurationFormatUtils.formatDurationHMS(createDuration.toMillis()) + ".\n");

        hdfs.printDebugInformation();
        hdfs.printOperationsPerformed();

        System.out.println("Beginning delete phase now.\n");

        List<String> failedDeletions = new ArrayList<String>();
        for (int i = 0; i < numFilesToCreate; i++) {
            Path filePath = filePaths[i];
            boolean success = hdfs.delete(filePath, true);
            if (!success) {
                System.out.println("\n\nERROR: Deletion of file" + filePath + " failed!\n\n");
                failedDeletions.add(filePath.toString());
            }
        }

        Instant deleteFinished = Instant.now();
        Duration deleteDuration = Duration.between(createFinished, deleteFinished);
        Duration totalTimeElapsed = Duration.between(testStart, deleteFinished);

        System.out.println("\nFinished deleting all " + numFilesToCreate + " files. Delete phase took " +
                DurationFormatUtils.formatDurationHMS(deleteDuration.toMillis()));
        System.out.println("\n==========================================");
        System.out.println("Create phase: " + DurationFormatUtils.formatDurationHMS((createDuration.toMillis())));
        System.out.println("Delete phase: " + DurationFormatUtils.formatDurationHMS((deleteDuration.toMillis())));
        System.out.println("Total time elapsed: " + totalTimeElapsed);

        hdfs.printDebugInformation();
        hdfs.printOperationsPerformed();
        hdfs.close();
        System.out.println("Closed DistributedFileSystem object.\n");

        if (failedDeletions.size() > 0) {
            for (String path : failedDeletions)
                System.out.println("Failed to delete: " + path);

            throw new IOException("There " + (failedDeletions.size() == 1 ? "was one failed deletion" :
                    "were " + failedDeletions.size() + " failed deletions."));
        }
    }

    public static String humanReadableFormat(Duration duration) {
        return duration.toString()
                .substring(2)
                .replaceAll("(\\d[HMS])(?!$)", "$1 ")
                .toLowerCase();
    }
}
