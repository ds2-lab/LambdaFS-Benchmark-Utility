package com.gmail.benrcarver.distributed;

import com.gmail.benrcarver.distributed.util.TreeNode;
import com.gmail.benrcarver.distributed.util.Utils;

import java.io.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import io.hops.metrics.TransactionEvent;
import io.hops.transaction.context.TransactionsStats;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SynchronizedDescriptiveStatistics;
import io.hops.leader_election.node.SortedActiveNodeList;
import io.hops.leader_election.node.ActiveNode;
import org.apache.commons.math3.util.Pair;
import org.apache.hadoop.hdfs.serverless.operation.ActiveServerlessNameNodeList;
import org.apache.hadoop.hdfs.serverless.operation.ActiveServerlessNameNode;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.fs.FileStatus;
import io.hops.metrics.OperationPerformed;

import static com.gmail.benrcarver.distributed.Commander.consistencyEnabled;
import static com.gmail.benrcarver.distributed.Constants.*;

// TODO: Condense the various functions and build some generic framework for executing benchmarks.
//       Like, many of the function calls here have a lot of boilerplate code to create the infrastructure
//       to temporarily store metrics info and print the results. The only difference is the FS operation
//       being called. So, at some point I can generify everything.
public class Commands {
    public static final Log LOG = LogFactory.getLog(Commands.class);
    private static final Scanner scanner = new Scanner(System.in);

    private static final String EMPTY_STRING = "";

    public static volatile boolean TRACK_OP_PERFORMED = false;

    /**
     * When true, we do not store OperationsPerformed, and we clear them after each trial,
     * in addition to performing GCs.
     */
    public static boolean BENCHMARKING_MODE = false;

    /**
     * Indicates whether the JVM is running a follower process.
     * If true, then we are a follower.
     * If false, then we are a commander (i.e., a leader).
     */
    public static volatile boolean IS_FOLLOWER = false;

    /**
     * Indicates whether the target filesystem is Serverless HopsFS or Vanilla HopsFS.
     *
     * If true, then the target filesystem is Serverless HopsFS.
     * If false, then the target filesystem is Vanilla HopsFS.
     */
    public static volatile boolean IS_SERVERLESS = true;

    /**
     * Used to cache clients for reuse.
     */
    public static BlockingQueue<DistributedFileSystem> hdfsClients
            = new ArrayBlockingQueue<DistributedFileSystem>(1024);

    /**
     * Retrieve an HDFS client to use during a benchmark. This will attempt to reuse an existing client.
     * If none are available, then a new client is created.
     * @param sharedHdfs
     * @param nameNodeEndpoint
     * @return
     */
    private static DistributedFileSystem getHdfsClient(DistributedFileSystem sharedHdfs, String nameNodeEndpoint) {
        DistributedFileSystem hdfs;
        hdfs = hdfsClients.poll();

        if (hdfs != null) {
            hdfs.setConsistencyProtocolEnabled(consistencyEnabled);
            hdfs.setBenchmarkModeEnabled(Commands.BENCHMARKING_MODE);
            hdfs.setConsistencyProtocolEnabled(sharedHdfs.getConsistencyProtocolEnabled());
            hdfs.setServerlessFunctionLogLevel(sharedHdfs.getServerlessFunctionLogLevel());
        }
        else {
            hdfs = Commander.initDfsClient(sharedHdfs, nameNodeEndpoint, false);
        }
        return hdfs;
    }

    private static void returnHdfsClient(DistributedFileSystem hdfs) throws InterruptedException {
        hdfsClients.add(hdfs);
    }

    /**
     * Generic driver used to execute all/most benchmarks.
     *
     * @param sharedHdfs The primary/shared HDFS instance used to collect/aggregate metric information.
     * @param nameNodeEndpoint Direct HTTP requests here.
     * @param numThreads Number of HopsFS clients to use (one per thread).
     * @param fileBatches Batches of files for each client. One batch per thread.
     * @param operationsPerFile The number of times each client should perform the given operation on a file.
     * @param opCode Identifies the benchmark being performed. Stored with the metric results of the benchmark.
     * @param operation This is what the threads call. We basically specify the FS operation here. Like, we provide
     *                  the code that executes the FS operation one time, and we execute it however many times
     *                  we're supposed to based on the {@code } parameter.
     * @return A result containing all the metric information and whatnot.
     */
    public static DistributedBenchmarkResult executeBenchmark(
            DistributedFileSystem sharedHdfs,
            final Configuration configuration,
            String nameNodeEndpoint,
            int numThreads,
            String[][] fileBatches,
            int operationsPerFile,
            int opCode,
            FSOperation operation) throws InterruptedException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("At start of benchmark, the HDFS Clients Cache has " + hdfsClients.size() + " clients.");
        }

        Thread[] threads = new Thread[numThreads];

        // Used to synchronize threads; they each connect to HopsFS and then
        // count down. So, they all cannot start until they are all connected.
        final CountDownLatch startLatch = new CountDownLatch(numThreads);

        // Used to synchronize threads; they block when they finish executing to avoid using CPU cycles
        // by aggregating their results. Once all the threads have finished, they aggregate their results.
        final CountDownLatch endLatch = new CountDownLatch(numThreads);
        final Semaphore endSemaphore = new Semaphore((numThreads * -1) + 1);

        final BlockingQueue<List<OperationPerformed>> operationsPerformed =
                new java.util.concurrent.ArrayBlockingQueue<>(numThreads);
        final BlockingQueue<HashMap<String, TransactionsStats.ServerlessStatisticsPackage>> statisticsPackages
                = new ArrayBlockingQueue<>(numThreads);
        final BlockingQueue<HashMap<String, List<TransactionEvent>>> transactionEvents
                = new ArrayBlockingQueue<>(numThreads);

        // Keep track of number of successful operations.
        AtomicInteger numSuccessfulOps = new AtomicInteger(0);
        AtomicInteger numOps = new AtomicInteger(0);

        final SynchronizedDescriptiveStatistics latencyHttp = new SynchronizedDescriptiveStatistics();
        final SynchronizedDescriptiveStatistics latencyTcp = new SynchronizedDescriptiveStatistics();
        final SynchronizedDescriptiveStatistics latencyBoth = new SynchronizedDescriptiveStatistics();

        for (int i = 0; i < numThreads; i++) {
            final String[] filesForCurrentThread = fileBatches[i];
            final int threadId = i;
            Thread thread = new Thread(() -> {
                DistributedFileSystem hdfs = getHdfsClient(sharedHdfs, nameNodeEndpoint);

                startLatch.countDown();
                int numSuccessfulOpsCurrentThread = 0;
                int numOpsCurrentThread = 0;

                for (String filePath : filesForCurrentThread) {
                    for (int j = 0; j < operationsPerFile; j++) {
                        if (operation.call(hdfs, filePath, EMPTY_STRING))
                            numSuccessfulOpsCurrentThread++;
                        numOpsCurrentThread++;
                    }
                }

                // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
                // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
                // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
                // so that all the statistics are placed into the appropriate collections where we can aggregate them.
                endSemaphore.release();

                if (LOG.isDebugEnabled()) LOG.debug("Thread " + threadId + " has finished executing.");

                endLatch.countDown();

                numSuccessfulOps.addAndGet(numSuccessfulOpsCurrentThread);
                numOps.addAndGet(numOpsCurrentThread);

                if (!BENCHMARKING_MODE) {
                    //if (LOG.isDebugEnabled()) LOG.debug("[THREAD " + threadId + "] Collecting operations performed.");
                    operationsPerformed.add(hdfs.getOperationsPerformed());
                    if (TRACK_OP_PERFORMED) {
                        //if (LOG.isDebugEnabled()) LOG.debug("[THREAD " + threadId + "] Collecting statistics packages & tx events.");
                        statisticsPackages.add(hdfs.getStatisticsPackages());
                        transactionEvents.add(hdfs.getTransactionEvents());
                    }
                }

                //if (LOG.isDebugEnabled()) LOG.debug("[THREAD " + threadId + "] Collecting HTTP latencies.");
                for (double latency : hdfs.getLatencyHttpStatistics().getValues()) {
                    latencyHttp.addValue(latency);
                    latencyBoth.addValue(latency);
                }

                //if (LOG.isDebugEnabled()) LOG.debug("[THREAD " + threadId + "] Collecting TCP latencies.");
                for (double latency : hdfs.getLatencyTcpStatistics().getValues()) {
                    latencyTcp.addValue(latency);
                    latencyBoth.addValue(latency);
                }

                try {
                    // First clear the metric data associated with the client.
                    clearMetricDataNoPrompt(hdfs);

                    // Now return the client to the pool so that it can be used again in the future.
                    returnHdfsClient(hdfs);
                } catch (InterruptedException e) {
                    LOG.error("Encountered error when trying to return HDFS client. Closing it instead.");
                    e.printStackTrace();

                    LOG.warn("[THREAD " + threadId + "] Terminating HDFS connection.");
                    try {
                        hdfs.close();

                        LOG.warn("[THREAD " + threadId + "] Terminated HDFS connection.");
                    } catch (IOException ex) {
                        LOG.error("Encountered IOException while closing DistributedFileSystem object:", ex);
                    }
                }
            });
            threads[i] = thread;
        }

        LOG.info("Starting threads.");
        long start = System.currentTimeMillis();
        for (Thread thread : threads) {
            thread.start();
        }

        // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
        // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
        // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
        // so that all the statistics are placed into the appropriate collections where we can aggregate them.
        endSemaphore.acquire();
        long end = System.currentTimeMillis();

        LOG.info("Benchmark completed in " + (end - start) + "ms. Joining the " + threads.length + " threads now...");
        for (Thread thread : threads) {
            thread.join();
        }

        List<OperationPerformed> allOperationsPerformed = new ArrayList<>();
        Pair<Integer, Integer> cacheHitsAndMisses = mergeMetricInformation(sharedHdfs, operationsPerformed,
                statisticsPackages, transactionEvents, allOperationsPerformed);
        int totalCacheHits = cacheHitsAndMisses.getFirst();
        int totalCacheMisses = cacheHitsAndMisses.getSecond();

        double durationSeconds = (end - start) / 1.0e3;

        // TODO: Verify that I've calculated the total number of operations correctly.
        int numSuccess = numSuccessfulOps.get();
        double totalOperations = numOps.get();
        LOG.info("Finished performing all " + totalOperations + " operations in " + durationSeconds + " sec.");
        double totalThroughput = totalOperations / durationSeconds;
        double successThroughput = numSuccess / durationSeconds;
        LOG.info("Number of successful operations: " + numSuccess);
        LOG.info("Number of failed operations: " + (totalOperations - numSuccess));

        printLatencyStatistics(latencyBoth, latencyTcp, latencyHttp);
        sharedHdfs.addLatencies(latencyTcp.getValues(), latencyHttp.getValues());
        LOG.info("Total Throughput: " + totalThroughput + " ops/sec.");
        LOG.info("Successful Throughput: " + successThroughput + " ops/sec.");

        if (LOG.isDebugEnabled())
            LOG.debug("At end of benchmark, the HDFS Clients Cache has " + hdfsClients.size() + " clients.");

        return new DistributedBenchmarkResult(null, opCode, numSuccess,
                durationSeconds, start, end, totalCacheHits, totalCacheMisses,
                TRACK_OP_PERFORMED ? allOperationsPerformed.toArray(new OperationPerformed[0]) : null,
                TRACK_OP_PERFORMED ? transactionEvents.toArray(new HashMap[0]) : null,
                latencyTcp, latencyHttp);
    }

    public static DistributedBenchmarkResult writeFilesToDirectories(DistributedFileSystem hdfs,
                                               final Configuration configuration,
                                               String nameNodeEndpoint)
            throws IOException, InterruptedException {
        System.out.print("Manually input (comma-separated list) [1], or specify file containing directories [2]? \n>");
        int choice = Integer.parseInt(scanner.nextLine());

        List<String> directories = null;
        if (choice == 1) {
            System.out.print("Please enter the directories as a comma-separated list:\n> ");
            String listOfDirectories = scanner.nextLine();
            directories = Arrays.asList(listOfDirectories.split(","));

            if (directories.size() == 1)
                LOG.info("1 directory specified.");
            else
                LOG.info(directories.size() + " directories specified.");
        }
        else if (choice == 2) {
            System.out.print("Please provide path to file containing HopsFS directories:\n> ");
            String filePath = scanner.nextLine();
            directories = Utils.getFilePathsFromFile(filePath);

            if (directories.size() == 1)
                LOG.info("1 directory specified in file.");
            else
                LOG.info(directories.size() + " directories specified in file.");
        }
        else {
            LOG.error("Invalid option specified (" + choice + "). Please enter \"1\" or \"2\" for this prompt.");
        }

        System.out.print("Number of threads? \n>");
        int numberOfThreads = Integer.parseInt(scanner.nextLine());

        int filesPerDirectory = 10;
        System.out.print("Number of files per directory (default " + filesPerDirectory + "):\n> ");
        try {
            filesPerDirectory = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException ex) {
            LOG.info("Defaulting to " + filesPerDirectory + ".");
        }

        int minLength = 0;
        System.out.print("Min string length (default " + minLength + "):\n> ");
        try {
            minLength = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException ex) {
            LOG.info("Defaulting to " + minLength + ".");
        }

        int maxLength = 0;
        System.out.print("Max string length (default " + maxLength + "):\n> ");
        try {
            maxLength = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException ex) {
            LOG.info("Defaulting to " + maxLength + ".");
        }

        // Generate the file contents and file names. targetDirectories has length equal to numThreads
        // except when randomWrites is true (in which case, in may vary). But either way, each thread
        // will be reading n files, so this works.
        int totalNumberOfFiles = filesPerDirectory * directories.size();
        LOG.info("Generating " + filesPerDirectory + " files for each directory (total of " + totalNumberOfFiles + " files).");
        final String[] targetPaths = new String[totalNumberOfFiles];

        int counter = 0;
        // Generate file names and subsequently the full file paths.
        for (String targetDirectory : directories) {
            // File names.
            String[] targetFiles = Utils.getFixedLengthRandomStrings(filesPerDirectory, 15);

            // Create the full paths.
            for (String targetFile : targetFiles) {
                targetPaths[counter++] = targetDirectory + "/" + targetFile;
            }
        }

        LOG.info("Generated a total of " + totalNumberOfFiles + " file(s).");

        Utils.write("./output/writeToDirectoryPaths-" + Instant.now().toEpochMilli()+ ".txt", targetPaths);

        int numWritesPerThread = targetPaths.length / numberOfThreads;
        final String[][] targetPathsPerThread = Utils.splitArray(targetPaths, numWritesPerThread);
        assert(targetPathsPerThread != null);
        assert(targetPathsPerThread.length == numberOfThreads);

        return executeBenchmark(hdfs, configuration, nameNodeEndpoint, numberOfThreads, targetPathsPerThread,
                1, OP_WRITE_FILES_TO_DIRS, new FSOperation(nameNodeEndpoint, configuration) {
                    @Override
                    public boolean call(DistributedFileSystem hdfs, String path, String content) {
                        return createFile(path, content, hdfs, nameNodeEndpoint);
                    }
                });
    }

    public static void clearMetricData(DistributedFileSystem hdfs) {
        if (!IS_SERVERLESS) {
            LOG.error("Clearing statistics packages is not supported by Vanilla HopsFS!");
            return;
        }

        System.out.print("Are you sure? (y/N)\n> ");
        String input = scanner.nextLine();

        if (input.equalsIgnoreCase("y")) {
            clearMetricDataNoPrompt(hdfs);

            for (DistributedFileSystem otherHdfs : hdfsClients) {
                clearMetricDataNoPrompt(otherHdfs);
            }

            LOG.debug("Cleared both statistics and latency values.");
        } else {
            LOG.info("NOT clearing statistics packages.");
        }
    }

    /**
     * Clear the statistics packages, transaction events, and operations performed metrics objects
     * on the given DistributedFileSystem instance.
     *
     * This also clears the latency values.
     *
     * @param hdfs The instance for which the aforementioned metrics objects should be cleared.
     */
    public static void clearMetricDataNoPrompt(DistributedFileSystem hdfs) {
        hdfs.clearStatistics(true, true, true);
        hdfs.clearLatencyValues();
    }

    /**
     * Merge a bunch of different metrics objects into the provided DistributedFileSystem instance.
     *
     * @param sharedHdfs The instance into which we are merging all the metrics objects.
     * @param allOperationsPerformed An empty list. By the end of this method's execution, this will contain all of
     *                               the {@link OperationPerformed} instances contained within the lists in the
     *                               {@code operationsPerformed} argument.
     * @return A {@link Pair<Long, Long>} containing the total cache hits as the first element and the total cache
     * misses as the second element.
     */
    private static Pair<Integer, Integer> mergeMetricInformation(
            final DistributedFileSystem sharedHdfs,
            final BlockingQueue<List<OperationPerformed>> operationsPerformed,
            final BlockingQueue<HashMap<String, TransactionsStats.ServerlessStatisticsPackage>> statisticsPackages,
            final BlockingQueue<HashMap<String, List<TransactionEvent>>> transactionEvents,
            final List<OperationPerformed> allOperationsPerformed) {
        int totalCacheHits = 0;
        int totalCacheMisses = 0;

        if (!BENCHMARKING_MODE) {
            for (List<OperationPerformed> opsPerformed : operationsPerformed) {
                if (!IS_FOLLOWER)
                    sharedHdfs.addOperationPerformeds(opsPerformed);
                for (OperationPerformed op : opsPerformed) {
                    totalCacheHits += op.getMetadataCacheHits();
                    totalCacheMisses += op.getMetadataCacheMisses();
                    allOperationsPerformed.add(op);
                }
            }

            if (!IS_FOLLOWER) {
                for (HashMap<String, TransactionsStats.ServerlessStatisticsPackage> statPackages : statisticsPackages) {
                    //LOG.info("Adding list of " + statPackages.size() + " statistics packages to master/shared HDFS object.");
                    sharedHdfs.mergeStatisticsPackages(statPackages, true);
                }

                for (HashMap<String, List<TransactionEvent>> txEvents : transactionEvents) {
                    // LOG.info("Merging " + txEvents.size() + " new transaction event(s) into master/shared HDFS object.");
                    sharedHdfs.mergeTransactionEvents(txEvents, true);
                }
            }
        }

        return new Pair<>(totalCacheHits, totalCacheMisses);
    }

    /**
     * Gets the user inputs for this benchmark, then calls the actual benchmark itself.
     */
    public static DistributedBenchmarkResult strongScalingBenchmark(final Configuration configuration,
                                                                    final DistributedFileSystem sharedHdfs,
                                                                    final String endpoint,
                                                                    int numFilesPerThread, int readsPerFile,
                                                                    int numThreads, String inputPath)
            throws FileNotFoundException, InterruptedException {
        List<String> paths = Utils.getFilePathsFromFile(inputPath);

        if (paths.size() < numFilesPerThread) {
            LOG.error("ERROR: The file should contain at least " + numFilesPerThread +
                    " HopsFS file path(s); however, it contains just " + paths.size() + " HopsFS file path(s).");
            LOG.error("Aborting operation.");
            return null;
        }

        // Select a random subset of size n, where n is the number of files each thread should read.
        Collections.shuffle(paths);

        String[][] filesPerThread = new String[numThreads][numFilesPerThread];
        int counter = 0;
        for (int i = 0; i < numThreads; i++) {
            for (int j = 0; j < numFilesPerThread; j++) {
                // Select the first j files for thread i.
                filesPerThread[i][j] = paths.get(counter);
            }

            // Each thread receives a random subset of files.
            // So, we shuffle the paths each time to ensure each thread gets a random subset.
            Collections.shuffle(paths);
        }

        LOG.debug("Each of the " + numThreads + " thread(s) will read " + numFilesPerThread + " random file(s).");

        return executeBenchmark(
                sharedHdfs, configuration, endpoint, numThreads, filesPerThread, readsPerFile, OP_STRONG_SCALING_READS,
                new FSOperation(endpoint, configuration) {
                    @Override
                    public boolean call(DistributedFileSystem hdfs, String path, String content) {
                        return readFile(path, hdfs, endpoint);
                    }
                });

        /*Thread[] threads = new Thread[numThreads];

        // Used to synchronize threads; they each connect to HopsFS and then
        // count down. So, they all cannot start until they are all connected.
        final CountDownLatch startLatch = new CountDownLatch(numThreads);
        final Semaphore endSemaphore = new Semaphore((numThreads * -1) + 1);

        final BlockingQueue<List<OperationPerformed>> operationsPerformed =
                new java.util.concurrent.ArrayBlockingQueue<>(numThreads);
        final BlockingQueue<HashMap<String, TransactionsStats.ServerlessStatisticsPackage>> statisticsPackages
                = new ArrayBlockingQueue<>(numThreads);
        final BlockingQueue<HashMap<String, List<TransactionEvent>>> transactionEvents
                = new ArrayBlockingQueue<>(numThreads);

        final SynchronizedDescriptiveStatistics latencyHttp = new SynchronizedDescriptiveStatistics();
        final SynchronizedDescriptiveStatistics latencyTcp = new SynchronizedDescriptiveStatistics();
        final SynchronizedDescriptiveStatistics latencyBoth = new SynchronizedDescriptiveStatistics();

        for (int i = 0; i < numThreads; i++) {
            Thread thread = new Thread(() -> {
                DistributedFileSystem hdfs = Commander.initDfsClient(endpoint);

                startLatch.countDown();

                for (String filePath : selectedPaths) {
                    for (int j = 0; j < readsPerFile; j++)
                        readFile(filePath, hdfs, endpoint);
                }

                // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
                // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
                // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
                // so that all the statistics are placed into the appropriate collections where we can aggregate them.
                endSemaphore.release();

                if (!BENCHMARKING_MODE) {
                    operationsPerformed.add(hdfs.getOperationsPerformed());
                    statisticsPackages.add(hdfs.getStatisticsPackages());
                    transactionEvents.add(hdfs.getTransactionEvents());
                }

                for (double latency : hdfs.getLatencyStatistics().getValues()) {
                    latencyBoth.addValue(latency);
                }

                for (double latency : hdfs.getLatencyHttpStatistics().getValues()) {
                    latencyHttp.addValue(latency);
                }

                for (double latency : hdfs.getLatencyTcpStatistics().getValues()) {
                    latencyTcp.addValue(latency);
                }

                try {
                    hdfs.close();
                } catch (IOException ex) {
                    LOG.error("Encountered IOException while closing DistributedFileSystem object:", ex);
                }
            });
            threads[i] = thread;
        }

        LOG.info("Starting threads.");
        long start = System.currentTimeMillis();
        for (Thread thread : threads) {
            thread.start();
        }

        // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
        // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
        // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
        // so that all the statistics are placed into the appropriate collections where we can aggregate them.
        endSemaphore.acquire();
        long end = System.currentTimeMillis();

        LOG.info("Benchmark completed in " + (end - start) + "ms. Joining threads now...");
        for (Thread thread : threads) {
            thread.join();
        }

        List<OperationPerformed> allOperationsPerformed = new ArrayList<OperationPerformed>();
        Pair<Integer, Integer> cacheHitsAndMisses = mergeMetricInformation(sharedHdfs, operationsPerformed,
                statisticsPackages, transactionEvents, allOperationsPerformed);
        int totalCacheHits = cacheHitsAndMisses.getFirst();
        int totalCacheMisses = cacheHitsAndMisses.getSecond();

        double durationSeconds = (end - start) / 1000.0;
        double totalReads = (double)filesPerThread * (double)readsPerFile * (double)numThreads;
        double throughput = (totalReads / durationSeconds);

        printLatencyStatistics(latencyBoth, latencyTcp, latencyHttp);
        sharedHdfs.addLatencies(latencyTcp.getValues(), latencyHttp.getValues());
        LOG.info("Throughput: " + throughput + " ops/sec.");

        return new DistributedBenchmarkResult(null, OP_STRONG_SCALING_READS, (int)totalReads, durationSeconds,
                start, end, totalCacheHits, totalCacheMisses,
                TRACK_OP_PERFORMED ? allOperationsPerformed.toArray(new OperationPerformed[0]) : null,
                TRACK_OP_PERFORMED ? transactionEvents.toArray(new HashMap[0]) : null,
                latencyTcp, latencyHttp);*/
    }

    /**
     * Each thread reads N files. There may be duplicates. We randomly select N files (with replacement) from
     * the source set of files.
     *
     * This is the WEAK SCALING (read) benchmark.
     *
     * @param numThreads Number of threads.
     * @param filesPerThread How many files should be read by each thread.
     * @param inputPath Path to local file containing HopsFS file paths (of the files to read).
     */
    public static DistributedBenchmarkResult weakScalingBenchmarkV2(final Configuration configuration,
                                                        final DistributedFileSystem sharedHdfs,
                                                        final String nameNodeEndpoint, int numThreads,
                                                        final int filesPerThread, String inputPath,
                                                        boolean shuffle, int opCode)
            throws InterruptedException, FileNotFoundException {
        List<String> paths = Utils.getFilePathsFromFile(inputPath);

        if (shuffle) {
            LOG.debug("Shuffling paths.");
            Collections.shuffle(paths);
        }

        if (paths.size() < numThreads) {
            LOG.error("ERROR: The file should contain at least " + numThreads +
                    " HopsFS file path(s); however, it contains just " + paths.size() + " HopsFS file path(s).");
            LOG.error("Aborting operation.");
            return null;
        }

        Random rng = new Random(); // TODO: Optionally seed this?

        final String[][] fileBatches = new String[numThreads][filesPerThread];
        int counter = 0;
        for (int i = 0; i < numThreads; i++) {
            for (int j = 0; j < filesPerThread; j++) {
                int filePathIndex;
                if (shuffle)
                    filePathIndex = rng.nextInt(paths.size());
                else
                    filePathIndex = counter++;
                fileBatches[i][j] = paths.get(filePathIndex);
            }
            counter = 0;

            if (shuffle)
                Collections.shuffle(paths);
        }

        return executeBenchmark(
                sharedHdfs, configuration, nameNodeEndpoint, numThreads, fileBatches, 1, opCode,
                new FSOperation(nameNodeEndpoint, configuration) {
                    @Override
                    public boolean call(DistributedFileSystem hdfs, String path, String content) {
                        return readFile(path, hdfs, nameNodeEndpoint);
                    }
                });

//        Thread[] threads = new Thread[numThreads];
//
//        // Used to synchronize threads; they each connect to HopsFS and then
//        // count down. So, they all cannot start until they are all connected.
//        final CountDownLatch latch = new CountDownLatch(numThreads);
//        final Semaphore endSemaphore = new Semaphore((numThreads * -1) + 1);
//
//        final java.util.concurrent.BlockingQueue<List<OperationPerformed>> operationsPerformed =
//                new java.util.concurrent.ArrayBlockingQueue<>(numThreads);
//        final BlockingQueue<HashMap<String, TransactionsStats.ServerlessStatisticsPackage>> statisticsPackages
//                = new ArrayBlockingQueue<>(numThreads);
//        final BlockingQueue<HashMap<String, List<TransactionEvent>>> transactionEvents
//                = new ArrayBlockingQueue<>(numThreads);
//
//        final SynchronizedDescriptiveStatistics latencyHttp = new SynchronizedDescriptiveStatistics();
//        final SynchronizedDescriptiveStatistics latencyTcp = new SynchronizedDescriptiveStatistics();
//        final SynchronizedDescriptiveStatistics latencyBoth = new SynchronizedDescriptiveStatistics();
//
//        for (int i = 0; i < numThreads; i++) {
//            final String[] filesToRead = fileBatches[i];
//
//            Thread thread = new Thread(() -> {
//                DistributedFileSystem hdfs = Commander.initDfsClient(nameNodeEndpoint);
//
//                latch.countDown();
//
//                for (String s : filesToRead)
//                    readFile(s, hdfs, nameNodeEndpoint);
//
//                // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
//                // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
//                // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
//                // so that all the statistics are placed into the appropriate collections where we can aggregate them.
//                endSemaphore.release();
//
//                if (!BENCHMARKING_MODE) {
//                    operationsPerformed.add(hdfs.getOperationsPerformed());
//                    if (TRACK_OP_PERFORMED) {
//                        statisticsPackages.add(hdfs.getStatisticsPackages());
//                        transactionEvents.add(hdfs.getTransactionEvents());
//                    }
//                }
//
//                for (double latency : hdfs.getLatencyStatistics().getValues()) {
//                    latencyBoth.addValue(latency);
//                }
//
//                for (double latency : hdfs.getLatencyHttpStatistics().getValues()) {
//                    latencyHttp.addValue(latency);
//                }
//
//                for (double latency : hdfs.getLatencyTcpStatistics().getValues()) {
//                    latencyTcp.addValue(latency);
//                }
//
//                try {
//                    hdfs.close();
//                } catch (IOException ex) {
//                    LOG.error("Encountered IOException while closing DistributedFileSystem object:", ex);
//                }
//            });
//            threads[i] = thread;
//        }
//
//        LOG.info("Starting threads.");
//        long start = System.currentTimeMillis();
//        for (Thread thread : threads) {
//            thread.start();
//        }
//
//        // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
//        // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
//        // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
//        // so that all the statistics are placed into the appropriate collections where we can aggregate them.
//        endSemaphore.acquire();
//        long end = System.currentTimeMillis();
//
//        LOG.info("Benchmark completed in " + (end - start) + "ms. Joining threads now...");
//        for (Thread thread : threads) {
//            thread.join();
//        }
//
//        List<OperationPerformed> allOperationsPerformed = new ArrayList<OperationPerformed>();
//        Pair<Integer, Integer> cacheHitsAndMisses = mergeMetricInformation(sharedHdfs, operationsPerformed,
//                statisticsPackages, transactionEvents, allOperationsPerformed);
//        int totalCacheHits = cacheHitsAndMisses.getFirst();
//        int totalCacheMisses = cacheHitsAndMisses.getSecond();
//
//        // double durationSeconds = duration.getSeconds() + (duration.getNano() / 1e9);
//        double durationSeconds = (end - start) / 1000.0;
//        double totalReads = (double)numThreads * (double)numFilesToRead;
//        double throughput = (totalReads / durationSeconds);
//        LOG.info("Finished performing all " + totalReads + " file reads in " + durationSeconds);
//
//        printLatencyStatistics(latencyBoth, latencyTcp, latencyHttp);
//        sharedHdfs.addLatencies(latencyTcp.getValues(), latencyHttp.getValues());
//        LOG.info("Throughput: " + throughput + " ops/sec.");
//
//        return new DistributedBenchmarkResult(null, OP_WEAK_SCALING_READS_V2, (int)totalReads,
//                durationSeconds, start, end, totalCacheHits, totalCacheMisses,
//                TRACK_OP_PERFORMED ? allOperationsPerformed.toArray(new OperationPerformed[0]) : null,
//                TRACK_OP_PERFORMED ? transactionEvents.toArray(new HashMap[0]) : null,
//                latencyTcp, latencyHttp);
    }

    /**
     * Read N files using N threads (so, each thread reads 1 file). Each file is read a specified number of times.
     *
     * This is the WEAK SCALING (read) benchmark.
     *
     * @param numThreads Number of threads & number of files to read, as each thread reads one file.
     * @param readsPerFile How many times each file should be read.
     * @param inputPath Path to local file containing HopsFS filepaths (of the files to read).
     */
    public static DistributedBenchmarkResult weakScalingReadsV1(final Configuration configuration,
                                                                final DistributedFileSystem sharedHdfs,
                                                                final String nameNodeEndpoint, int numThreads,
                                                                int readsPerFile, String inputPath, boolean shuffle,
                                                                int opCode)
            throws InterruptedException, FileNotFoundException {
        List<String> paths = Utils.getFilePathsFromFile(inputPath);

        if (shuffle) {
            LOG.debug("Shuffling paths.");
            Collections.shuffle(paths);
        }

        if (paths.size() < numThreads) {
            LOG.error("ERROR: The file should contain at least " + numThreads +
                    " HopsFS file path(s); however, it contains just " + paths.size() + " HopsFS file path(s).");
            LOG.error("Aborting operation.");
            return null;
        }

        String[][] pathsPerThread = new String[numThreads][1];
        for (int i = 0; i < numThreads; i++) {
            pathsPerThread[i][0] = paths.get(i);
        }

        return executeBenchmark(
                sharedHdfs, configuration, nameNodeEndpoint, numThreads, pathsPerThread, readsPerFile, opCode,
                new FSOperation(nameNodeEndpoint, configuration) {
                    @Override
                    public boolean call(DistributedFileSystem hdfs, String path, String content) {
                        return readFile(path, hdfs, nameNodeEndpoint);
                    }
                });

        /*Thread[] threads = new Thread[n];

        // Used to synchronize threads; they each connect to HopsFS and then
        // count down. So, they all cannot start until they are all connected.
        final CountDownLatch latch = new CountDownLatch(n);
        final Semaphore endSemaphore = new Semaphore((n * -1) + 1);

        final java.util.concurrent.BlockingQueue<List<OperationPerformed>> operationsPerformed =
                new java.util.concurrent.ArrayBlockingQueue<>(n);
        final BlockingQueue<HashMap<String, TransactionsStats.ServerlessStatisticsPackage>> statisticsPackages
                = new ArrayBlockingQueue<>(n);
        final BlockingQueue<HashMap<String, List<TransactionEvent>>> transactionEvents
                = new ArrayBlockingQueue<>(n);

        final SynchronizedDescriptiveStatistics latencyHttp = new SynchronizedDescriptiveStatistics();
        final SynchronizedDescriptiveStatistics latencyTcp = new SynchronizedDescriptiveStatistics();
        final SynchronizedDescriptiveStatistics latencyBoth = new SynchronizedDescriptiveStatistics();

        for (int i = 0; i < n; i++) {
            final String filePath = paths.get(i);
            Thread thread = new Thread(() -> {
                DistributedFileSystem hdfs = Commander.initDfsClient(nameNodeEndpoint);

                latch.countDown();

                for (int j = 0; j < readsPerFile; j++)
                    readFile(filePath, hdfs, nameNodeEndpoint);

                // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
                // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
                // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
                // so that all the statistics are placed into the appropriate collections where we can aggregate them.
                endSemaphore.release();

                if (!BENCHMARKING_MODE) {
                    operationsPerformed.add(hdfs.getOperationsPerformed());
                    if (TRACK_OP_PERFORMED) {
                        statisticsPackages.add(hdfs.getStatisticsPackages());
                        transactionEvents.add(hdfs.getTransactionEvents());
                    }
                }

                for (double latency : hdfs.getLatencyStatistics().getValues()) {
                    latencyBoth.addValue(latency);
                }

                for (double latency : hdfs.getLatencyHttpStatistics().getValues()) {
                    latencyHttp.addValue(latency);
                }

                for (double latency : hdfs.getLatencyTcpStatistics().getValues()) {
                    latencyTcp.addValue(latency);
                }

                try {
                    hdfs.close();
                } catch (IOException ex) {
                    LOG.error("Encountered IOException while closing DistributedFileSystem object:", ex);
                }
            });
            threads[i] = thread;
        }

        LOG.info("Starting threads.");
        long start = System.currentTimeMillis();
        for (Thread thread : threads) {
            thread.start();
        }

        // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
        // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
        // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
        // so that all the statistics are placed into the appropriate collections where we can aggregate them.
        endSemaphore.acquire();
        long end = System.currentTimeMillis();

        LOG.info("Benchmark completed in " + (end - start) + "ms. Joining threads now...");
        for (Thread thread : threads) {
            thread.join();
        }

        List<OperationPerformed> allOperationsPerformed = new ArrayList<OperationPerformed>();
        Pair<Integer, Integer> cacheHitsAndMisses = mergeMetricInformation(sharedHdfs, operationsPerformed,
                statisticsPackages, transactionEvents, allOperationsPerformed);
        int totalCacheHits = cacheHitsAndMisses.getFirst();
        int totalCacheMisses = cacheHitsAndMisses.getSecond();

        // double durationSeconds = duration.getSeconds() + (duration.getNano() / 1e9);
        double durationSeconds = (end - start) / 1000.0;
        double totalReads = (double)n * (double)readsPerFile;
        double throughput = (totalReads / durationSeconds);
        LOG.info("Finished performing all " + totalReads + " file reads in " + durationSeconds);

        if (!BENCHMARKING_MODE)
            LOG.info("Cache Hits: " + totalCacheHits + ", Cache Misses: " + totalCacheMisses + ". [Hitrate: " + ((double) totalCacheHits / (totalCacheHits + totalCacheMisses)) + "]");

        printLatencyStatistics(latencyBoth, latencyTcp, latencyHttp);
        LOG.info("Throughput: " + throughput + " ops/sec.");
        sharedHdfs.addLatencies(latencyTcp.getValues(), latencyHttp.getValues());

        return new DistributedBenchmarkResult(null, OP_WEAK_SCALING_READS, (int)totalReads, durationSeconds,
                start, end, totalCacheHits, totalCacheMisses,
                TRACK_OP_PERFORMED ? allOperationsPerformed.toArray(new OperationPerformed[0]) : null,
                TRACK_OP_PERFORMED ? transactionEvents.toArray(new HashMap[0]) : null,
                latencyTcp, latencyHttp);*/
    }

    /**
     * Print the statistics contained within the three DescriptiveStatistics instances.
     * @param latencyBoth Contains latencies for both HTTP and TCP requests.
     * @param latencyTcp Contains latencies for just TCP requests.
     * @param latencyHttp Contains latencies for just HTTP requests.
     */
    private static void printLatencyStatistics(DescriptiveStatistics latencyBoth,
                                               DescriptiveStatistics latencyTcp,
                                               DescriptiveStatistics latencyHttp) {
        LOG.info("Latency TCP & HTTP (ms) [min: " + latencyBoth.getMin() + ", max: " + latencyBoth.getMax() +
                ", avg: " + latencyBoth.getMean() + ", std dev: " + latencyBoth.getStandardDeviation() +
                ", N: " + latencyBoth.getN() + "]");
        LOG.info("Latency TCP (ms) [min: " + latencyTcp.getMin() + ", max: " + latencyTcp.getMax() +
                ", avg: " + latencyTcp.getMean() + ", std dev: " + latencyTcp.getStandardDeviation() +
                ", N: " + latencyTcp.getN() + "]");
        LOG.info("Latency HTTP (ms) [min: " + latencyHttp.getMin() + ", max: " + latencyHttp.getMax() +
                ", avg: " + latencyHttp.getMean() + ", std dev: " + latencyHttp.getStandardDeviation() +
                ", N: " + latencyHttp.getN() + "]");
    }

    /**
     * Print the operations performed. Optionally write them to a CSV.
     */
    public static void printOperationsPerformed(DistributedFileSystem hdfs) throws IOException {
        if (!IS_SERVERLESS) {
            LOG.error("This operation is not supported by Vanilla HopsFS!");
            return;
        }

        System.out.print("Write to CSV? \n> ");
        String input = scanner.nextLine();

        hdfs.printOperationsPerformed();

        HashMap<String, List<TransactionEvent>> transactionEvents = hdfs.getTransactionEvents();
        ArrayList<TransactionEvent> allTransactionEvents = new ArrayList<TransactionEvent>();

        for (Map.Entry<String, List<TransactionEvent>> entry : transactionEvents.entrySet()) {
            allTransactionEvents.addAll(entry.getValue());
        }

        System.out.println("====================== Transaction Events ====================================================================================");

        System.out.println("\n-- SUMS ----------------------------------------------------------------------------------------------------------------------");
        System.out.println(TransactionEvent.getMetricsHeader());
        System.out.println(TransactionEvent.getMetricsString(TransactionEvent.getSums(allTransactionEvents)));

        System.out.println("\n-- AVERAGES ------------------------------------------------------------------------------------------------------------------");
        System.out.println(TransactionEvent.getMetricsHeader());
        System.out.println(TransactionEvent.getMetricsString(TransactionEvent.getAverages(allTransactionEvents)));

        System.out.println("\n==============================================================================================================================");

        if (input.equalsIgnoreCase("y")) {
            System.out.print("File path? (no extension)\n> ");
            String baseFilePath = scanner.nextLine();

            BufferedWriter opsPerformedWriter = new BufferedWriter(new FileWriter(baseFilePath + ".csv"));
            List<OperationPerformed> operationsPerformed = hdfs.getOperationsPerformed();

            opsPerformedWriter.write(OperationPerformed.getHeader());
            opsPerformedWriter.newLine();
            for (OperationPerformed op : operationsPerformed) {
                op.write(opsPerformedWriter);
            }
            opsPerformedWriter.close();

            BufferedWriter txEventsWriter = new BufferedWriter(new FileWriter(baseFilePath + "-txevents.csv"));

            txEventsWriter.write(TransactionEvent.getHeader());
            txEventsWriter.newLine();

            for (Map.Entry<String, List<TransactionEvent>> entry : transactionEvents.entrySet()) {
                List<TransactionEvent> txEvents = entry.getValue();

                for (TransactionEvent transactionEvent : txEvents) {
                    transactionEvent.write(txEventsWriter);
                }
            }

            txEventsWriter.close();
        }
    }

    public static DistributedBenchmarkResult getFileStatusOperation(final Configuration configuration,
                                                                    DistributedFileSystem sharedHdfs,
                                                                    final String endpoint) throws InterruptedException {
        System.out.print("Path to local file containing HopsFS/HDFS paths:\n> ");
        String localFilePath = scanner.nextLine();

        System.out.print("Stat calls per file/directory:\n> ");
        int opsPerFile = Integer.parseInt(scanner.nextLine());

        System.out.print("Number of threads:\n> ");
        int numThreads = Integer.parseInt(scanner.nextLine());

        List<String> paths;
        try {
            paths = Utils.getFilePathsFromFile(localFilePath);
        } catch (FileNotFoundException ex) {
            LOG.error("Could not find file: '" + localFilePath + "'");
            return null;
        }
        int numPaths = paths.size();

        int filesPerArray = (int)Math.floor((double)numPaths/numThreads);
        int remainder = numPaths % numThreads;

        if (remainder != 0) {
            LOG.info("Assigning all but last thread " + filesPerArray +
                    " files. The last thread will be assigned " + remainder + " files.");
        } else {
            LOG.info("Assigning each thread " + filesPerArray + " files.");
        }

        String[][] pathsPerThread = Utils.splitArray(paths.toArray(new String[0]), filesPerArray);

        assert pathsPerThread != null;
        LOG.info("pathsPerThread.length: " + pathsPerThread.length);

        return executeBenchmark(
                sharedHdfs, configuration, endpoint, numThreads, pathsPerThread, opsPerFile, OP_GET_FILE_STATUS,
                new FSOperation(endpoint, configuration) {
                    @Override
                    public boolean call(DistributedFileSystem hdfs, String path, String content) {
                        return getFileStatus(path, hdfs, endpoint);
                    }
                });

        /*Thread[] threads = new Thread[numThreads];

        // Used to synchronize threads; they each connect to HopsFS and then
        // count down. So, they all cannot start until they are all connected.
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final Semaphore endSemaphore = new Semaphore((numThreads * -1) + 1);

        final BlockingQueue<List<OperationPerformed>> operationsPerformed =
                new java.util.concurrent.ArrayBlockingQueue<>(numThreads);
        final BlockingQueue<HashMap<String, TransactionsStats.ServerlessStatisticsPackage>> statisticsPackages
                = new ArrayBlockingQueue<>(numThreads);
        final BlockingQueue<HashMap<String, List<TransactionEvent>>> transactionEvents
                = new ArrayBlockingQueue<>(numThreads);

        final SynchronizedDescriptiveStatistics latencyHttp = new SynchronizedDescriptiveStatistics();
        final SynchronizedDescriptiveStatistics latencyTcp = new SynchronizedDescriptiveStatistics();
        final SynchronizedDescriptiveStatistics latencyBoth = new SynchronizedDescriptiveStatistics();

        for (int i = 0; i < numThreads; i++) {
            final String[] pathsForThread = pathsPerThread[i];
            Thread thread = new Thread(() -> {
                DistributedFileSystem hdfs = Commander.initDfsClient(endpoint);

                latch.countDown();

                for (String filePath : pathsForThread) {
                    for (int j = 0; j < opsPerFile; j++)
                        getFileStatus(filePath, hdfs, endpoint);
                }

                // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
                // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
                // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
                // so that all the statistics are placed into the appropriate collections where we can aggregate them.
                endSemaphore.release();

                if (!BENCHMARKING_MODE) {
                    operationsPerformed.add(hdfs.getOperationsPerformed());
                    if (TRACK_OP_PERFORMED) {
                        statisticsPackages.add(hdfs.getStatisticsPackages());
                        transactionEvents.add(hdfs.getTransactionEvents());
                    }
                }

                for (double latency : hdfs.getLatencyStatistics().getValues()) {
                    latencyBoth.addValue(latency);
                }

                for (double latency : hdfs.getLatencyHttpStatistics().getValues()) {
                    latencyHttp.addValue(latency);
                }

                for (double latency : hdfs.getLatencyTcpStatistics().getValues()) {
                    latencyTcp.addValue(latency);
                }

                try {
                    hdfs.close();
                } catch (IOException ex) {
                    LOG.error("Encountered IOException while closing DistributedFileSystem object:", ex);
                }
            });
            threads[i] = thread;
        }

        LOG.info("Starting threads.");
        long start = System.currentTimeMillis();
        for (Thread thread : threads) {
            thread.start();
        }

        // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
        // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
        // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
        // so that all the statistics are placed into the appropriate collections where we can aggregate them.
        endSemaphore.acquire();
        long end = System.currentTimeMillis();

        LOG.info("Benchmark completed in " + (end - start) + "ms. Joining threads now...");
        for (Thread thread : threads) {
            thread.join();
        }

        List<OperationPerformed> allOperationsPerformed = new ArrayList<>();
        Pair<Integer, Integer> cacheHitsAndMisses = mergeMetricInformation(sharedHdfs, operationsPerformed,
                statisticsPackages, transactionEvents, allOperationsPerformed);
        int totalCacheHits = cacheHitsAndMisses.getFirst();
        int totalCacheMisses = cacheHitsAndMisses.getSecond();

        double durationSeconds = (end - start) / 1000.0;

        LOG.info("Finished performing all " + (opsPerFile * paths.size()) + " file reads in " + durationSeconds);
        double totalReads = (double)numPaths * (double)opsPerFile;
        double throughput = (totalReads / durationSeconds);

        printLatencyStatistics(latencyBoth, latencyTcp, latencyHttp);
        sharedHdfs.addLatencies(latencyTcp.getValues(), latencyHttp.getValues());
        LOG.info("Throughput: " + throughput + " ops/sec.");

        return new DistributedBenchmarkResult(null, OP_GET_FILE_STATUS, (int)totalReads, durationSeconds,
                start, end, totalCacheHits, totalCacheMisses,
                TRACK_OP_PERFORMED ? allOperationsPerformed.toArray(new OperationPerformed[0]) : null,
                TRACK_OP_PERFORMED ? transactionEvents.toArray(new HashMap[0]) : null,
                latencyTcp, latencyHttp);*/
    }

    public static void readFilesOperation(final Configuration configuration,
                                          DistributedFileSystem sharedHdfs,
                                          final String nameNodeEndpoint,
                                          int opCode)
            throws InterruptedException {
        System.out.print("Path to local file containing HopsFS/HDFS paths:\n> ");
        String localFilePath = scanner.nextLine();

        System.out.print("Reads per file:\n> ");
        int readsPerFile = Integer.parseInt(scanner.nextLine());

        System.out.print("Number of threads:\n> ");
        int numThreads = Integer.parseInt(scanner.nextLine());

        readFiles(localFilePath, readsPerFile, numThreads, configuration, sharedHdfs, nameNodeEndpoint, opCode);
    }

    public static DistributedBenchmarkResult deleteFilesOperation(DistributedFileSystem sharedHdfs, final String nameNodeEndpoint) {
        System.out.print("Path to file containing HopsFS paths? \n> ");
        String input = scanner.nextLine();
        return deleteFiles(input, sharedHdfs, nameNodeEndpoint);
    }

    /**
     * Delete the files listed in the file specified by the path argument.
     * @param localPath Text file containing HopsFS file paths to delete.
     */
    public static DistributedBenchmarkResult deleteFiles(String localPath, DistributedFileSystem sharedHdfs, final String nameNodeEndpoint) {
        List<String> paths;
        try {
            paths = Utils.getFilePathsFromFile(localPath);
        } catch (FileNotFoundException ex) {
            LOG.error("Could not find file: '" + localPath + "'");
            return null;
        }

        int numSuccessfulDeletes = 0;
        long s = System.currentTimeMillis();
        List<Long> latencies = new ArrayList<>();
        for (String path : paths) {
            try {
                Path filePath = new Path(nameNodeEndpoint + path);
                long start = System.currentTimeMillis();
                boolean success = sharedHdfs.delete(filePath, true);
                long end = System.currentTimeMillis();

                if (success) {
                    LOG.debug("\t Successfully deleted '" + path + "' in " + (end - start) + " ms.");
                    numSuccessfulDeletes++;
                }
                else
                    LOG.error("\t Failed to delete '" + path + "' after " + (end - start) + " ms.");

                latencies.add(end - start);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        long t = System.currentTimeMillis();
        double durationSeconds = (t - s) / 1000.0;

        LOG.info("Finished performing all " + paths.size() + " delete operations in " + durationSeconds + " sec.");
        LOG.info("Total number of ops: " + paths.size() + ". Number of successful ops: " + numSuccessfulDeletes + ".");
        double throughput = (numSuccessfulDeletes / durationSeconds);
        LOG.info("Throughput: " + throughput + " ops/sec.");
        LOG.info("Throughput (ALL ops): " + (paths.size() / durationSeconds) + " ops/sec.");
        /*LOG.info("(Note that, if we were deleting directories, then the number of 'actual' deletes " +
                "-- and therefore the overall throughput -- could be far higher...");*/

        LOG.info("Latencies (ms): ");
        for (Long latency : latencies)
            System.out.println(latency);

        return new DistributedBenchmarkResult(null, OP_DELETE_FILES, paths.size(), durationSeconds, s, t);
    }

    /**
     * Specify a file on the local filesystem containing a bunch of HopsFS file paths. Read the local file in order
     * to get all the HopsFS file paths, then read those files a configurable number of times.
     * @param path Path to file containing a bunch of HopsFS files.
     * @param readsPerFile Number of times each file should be read.
     * @param numThreads Number of threads to use when performing the reads concurrently.
     */
    public static DistributedBenchmarkResult readFiles(String path, int readsPerFile, int numThreads, final Configuration configuration,
                                 DistributedFileSystem sharedHdfs, final String nameNodeEndpoint, int opCode)
            throws InterruptedException {
        List<String> paths;
        try {
            paths = Utils.getFilePathsFromFile(path);
        } catch (FileNotFoundException ex) {
            LOG.error("Could not find file: '" + path + "'");
            return null;
        }
        int n = paths.size();

        int filesPerArray = (int)Math.floor((double)n/numThreads);
        int remainder = n % numThreads;

        if (remainder != 0) {
            LOG.info("Assigning all but last thread " + filesPerArray +
                    " files. The last thread will be assigned " + remainder + " files.");
        } else {
            LOG.info("Assigning each thread " + filesPerArray + " files.");
        }

        String[][] pathsPerThread = Utils.splitArray(paths.toArray(new String[0]), filesPerArray);

        assert pathsPerThread != null;
        LOG.info("pathsPerThread.length: " + pathsPerThread.length);

        return executeBenchmark(
                sharedHdfs, configuration, nameNodeEndpoint, numThreads, pathsPerThread, readsPerFile, opCode,
                new FSOperation(nameNodeEndpoint, configuration) {
                    @Override
                    public boolean call(DistributedFileSystem hdfs, String path, String content) {
                        return readFile(path, hdfs, nameNodeEndpoint);
                    }
                });

//        Thread[] threads = new Thread[numThreads];
//
//        // Used to synchronize threads; they each connect to HopsFS and then
//        // count down. So, they all cannot start until they are all connected.
//        final CountDownLatch latch = new CountDownLatch(numThreads);
//        final Semaphore endSemaphore = new Semaphore((numThreads * -1) + 1);
//
//        final BlockingQueue<List<OperationPerformed>> operationsPerformed =
//                new java.util.concurrent.ArrayBlockingQueue<>(numThreads);
//        final BlockingQueue<HashMap<String, TransactionsStats.ServerlessStatisticsPackage>> statisticsPackages
//                = new ArrayBlockingQueue<>(numThreads);
//        final BlockingQueue<HashMap<String, List<TransactionEvent>>> transactionEvents
//                = new ArrayBlockingQueue<>(numThreads);
//
//        final SynchronizedDescriptiveStatistics latencyHttp = new SynchronizedDescriptiveStatistics();
//        final SynchronizedDescriptiveStatistics latencyTcp = new SynchronizedDescriptiveStatistics();
//        final SynchronizedDescriptiveStatistics latencyBoth = new SynchronizedDescriptiveStatistics();
//
//        for (int i = 0; i < numThreads; i++) {
//            final String[] pathsForThread = pathsPerThread[i];
//            Thread thread = new Thread(() -> {
//                DistributedFileSystem hdfs = Commander.initDfsClient(nameNodeEndpoint);
//
//                latch.countDown();
//
//                for (String filePath : pathsForThread) {
//                    for (int j = 0; j < readsPerFile; j++)
//                        readFile(filePath, hdfs, nameNodeEndpoint);
//                }
//
//                // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
//                // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
//                // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
//                // so that all the statistics are placed into the appropriate collections where we can aggregate them.
//                endSemaphore.release();
//
//                if (!BENCHMARKING_MODE) {
//                    operationsPerformed.add(hdfs.getOperationsPerformed());
//                    if (TRACK_OP_PERFORMED) {
//                        statisticsPackages.add(hdfs.getStatisticsPackages());
//                        transactionEvents.add(hdfs.getTransactionEvents());
//                    }
//                }
//
//                for (double latency : hdfs.getLatencyStatistics().getValues()) {
//                    latencyBoth.addValue(latency);
//                }
//
//                for (double latency : hdfs.getLatencyHttpStatistics().getValues()) {
//                    latencyHttp.addValue(latency);
//                }
//
//                for (double latency : hdfs.getLatencyTcpStatistics().getValues()) {
//                    latencyTcp.addValue(latency);
//                }
//
//                try {
//                    hdfs.close();
//                } catch (IOException ex) {
//                    LOG.error("Encountered IOException while closing DistributedFileSystem object:", ex);
//                }
//            });
//            threads[i] = thread;
//        }
//
//        LOG.info("Starting threads.");
//        long start = System.currentTimeMillis();
//        for (Thread thread : threads) {
//            thread.start();
//        }
//
//        // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
//        // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
//        // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
//        // so that all the statistics are placed into the appropriate collections where we can aggregate them.
//        endSemaphore.acquire();
//        long end = System.currentTimeMillis();
//
//        LOG.info("Benchmark completed in " + (end - start) + "ms. Joining threads now...");
//        for (Thread thread : threads) {
//            thread.join();
//        }
//
//        int totalCacheHits = 0;
//        int totalCacheMisses = 0;
//
//        if (!BENCHMARKING_MODE) {
//            for (List<OperationPerformed> opsPerformed : operationsPerformed) {
//                if (!IS_FOLLOWER)
//                    sharedHdfs.addOperationPerformeds(opsPerformed);
//                for (OperationPerformed op : opsPerformed) {
//                    totalCacheHits += op.getMetadataCacheHits();
//                    totalCacheMisses += op.getMetadataCacheMisses();
//                }
//            }
//
//            if (!IS_FOLLOWER) {
//                for (HashMap<String, TransactionsStats.ServerlessStatisticsPackage> statPackages : statisticsPackages) {
//                    sharedHdfs.mergeStatisticsPackages(statPackages, true);
//                }
//
//                for (HashMap<String, List<TransactionEvent>> txEvents : transactionEvents) {
//                    sharedHdfs.mergeTransactionEvents(txEvents, true);
//                }
//            }
//        }
//
//        double durationSeconds = (end - start) / 1000.0;
//
//        LOG.info("Finished performing all " + (readsPerFile * paths.size()) + " file reads in " + durationSeconds);
//        double totalReads = (double)n * (double)readsPerFile;
//        double throughput = (totalReads / durationSeconds);
//
//        printLatencyStatistics(latencyBoth, latencyTcp, latencyHttp);
//        sharedHdfs.addLatencies(latencyTcp.getValues(), latencyHttp.getValues());
//        LOG.info("Throughput: " + throughput + " ops/sec.");
    }

    /**
     * Write a bunch of files to a target directory.
     *
     * @param sharedHdfs Passed by main thread. We only use this if we're doing single-threaded.
     */
    public static void writeFilesToDirectory(DistributedFileSystem sharedHdfs,
                                             final Configuration configuration,
                                             final String nameNodeEndpoint)
            throws InterruptedException, IOException {
        System.out.print("Target directory:\n> ");
        String targetDirectory = scanner.nextLine();

        System.out.print("Number of files:\n> ");
        int numFiles = Integer.parseInt(scanner.nextLine());

        System.out.print("Min string length:\n> ");
        int minLength = Integer.parseInt(scanner.nextLine());

        System.out.print("Max string length:\n> ");
        int maxLength = Integer.parseInt(scanner.nextLine());

        // If 'y', create the files one-by-one. If 'n', we'll use a configurable number of threads.
        System.out.print("Sequentially create files? [Y/n]\n>");
        String resp = scanner.nextLine();

        int numThreads = 1;
        // If they answered anything other than 'y', then abort.
        if (resp.equalsIgnoreCase("n")) {
            System.out.print("Number of threads:\n> ");
            numThreads = Integer.parseInt(scanner.nextLine());
        }

        writeFilesInternal(numFiles, minLength, maxLength, numThreads, Collections.singletonList(targetDirectory),
                sharedHdfs, OP_WEAK_SCALING_WRITES, configuration, nameNodeEndpoint, false);
    }

    /**
     * Write a bunch of files to a bunch of directories.
     *
     * The {@code targetDirectories} list is expected to have size equal to {@code numThreads}, unless
     * {@code randomWrites} is true. When {@code randomWrites} is true, we just generate a bunch of random writes
     * using all provided directories as part of the sample space.
     *
     * @param filesPerDirectory Number of files per directory (or per thread for random writes).
     * @param minLength Minimum length of randomly-generated file contents.
     * @param maxLength Maximum length of randomly-generated file contents.
     * @param numThreads The number of threads to use when performing the operation.
     * @param targetDirectories The target directories.
     * @param sharedHdfs Shared/master DistributedFileSystem instance.
     * @param configuration Configuration for per-thread DistributedFileSystem objects.
     * @param randomWrites Generate a bunch of random writes across all directories,
     *                     rather than doing the writes per-directory.
     */
    public static DistributedBenchmarkResult writeFilesInternal(int filesPerDirectory, int minLength, int maxLength,
                                                                int numThreads, List<String> targetDirectories,
                                                                DistributedFileSystem sharedHdfs, int opCode,
                                                                Configuration configuration,
                                                                final String nameNodeEndpoint, boolean randomWrites)
            throws IOException, InterruptedException {
        // Generate the file contents and file names. targetDirectories has length equal to numThreads
        // except when randomWrites is true (in which case, in may vary). But either way, each thread
        // will be reading n files, so this works.
        int totalNumberOfFiles = filesPerDirectory * numThreads;
        LOG.info("Generating " + filesPerDirectory + " files for each directory (total of " + totalNumberOfFiles + " files).");
        final String[] targetPaths = new String[totalNumberOfFiles];

        // The standard way of generating files. Just generate a bunch of files for each provided directory.
        if (!randomWrites) {
            int counter = 0;
            // Generate file names and subsequently the full file paths.
            for (String targetDirectory : targetDirectories) {
                // File names.
                String[] targetFiles = Utils.getFixedLengthRandomStrings(filesPerDirectory, 15);

                // Create the full paths.
                for (String targetFile : targetFiles) {
                    targetPaths[counter++] = targetDirectory + "/" + targetFile;
                }
            }
        } else {
            // Generate truly random reads using the `targetDirectories` list as the sample space from which
            // we draw random directories to write to. We generate random writes with replacement from the
            // targetDirectories list.
            Random rng = new Random();

            // Generate the filenames. These will be appended to the end of the directories.
            Utils.getFixedLengthRandomStrings(totalNumberOfFiles, 20, targetPaths);

            for (int i = 0; i < totalNumberOfFiles; i++) {
                // Randomly select a directory from the list of all target directories.
                String directory = targetDirectories.get(rng.nextInt(targetDirectories.size()));

                // We initially put all the randomly-generated filenames in 'targetPaths'. Now, we prepend each
                // randomly-generated filename with the randomly-selected directory. We used targetPaths to store
                // the filenames first just to avoid allocating too many arrays for large read tests.
                targetPaths[i] = directory + "/" + targetPaths[i];
            }
        }

        LOG.info("Generated a total of " + totalNumberOfFiles + " file(s).");

        Utils.write("./output/writeToDirectoryPaths-" + Instant.now().toEpochMilli()+ ".txt", targetPaths);

        // TODO: Are we splitting this correctly?
        int numWritesPerThread = targetPaths.length / numThreads;
        final String[][] targetPathsPerThread = Utils.splitArray(targetPaths, numWritesPerThread);
        assert(targetPathsPerThread != null);
        assert(targetPathsPerThread.length == numThreads);

        return executeBenchmark(
                sharedHdfs, configuration, nameNodeEndpoint, numThreads, targetPathsPerThread, 1, opCode,
                new FSOperation(nameNodeEndpoint, configuration) {
                    @Override
                    public boolean call(DistributedFileSystem hdfs, String path, String content) {
                        return createFile(path, content, hdfs, nameNodeEndpoint);
                    }
                });

//        final BlockingQueue<List<OperationPerformed>> operationsPerformed =
//                new java.util.concurrent.ArrayBlockingQueue<>(numThreads);
//        final BlockingQueue<HashMap<String, TransactionsStats.ServerlessStatisticsPackage>> statisticsPackages
//                = new ArrayBlockingQueue<>(numThreads);
//        final BlockingQueue<HashMap<String, List<TransactionEvent>>> transactionEvents
//                = new ArrayBlockingQueue<>(numThreads);
//        final SynchronizedDescriptiveStatistics latencyHttp = new SynchronizedDescriptiveStatistics();
//        final SynchronizedDescriptiveStatistics latencyTcp = new SynchronizedDescriptiveStatistics();
//        final SynchronizedDescriptiveStatistics latencyBoth = new SynchronizedDescriptiveStatistics();
//        List<OperationPerformed> allOpsPerformed = new ArrayList<>();
//        final BlockingQueue<Integer> numSuccessPerThread = new ArrayBlockingQueue<>(numThreads);
//        long start, end;
//        int numSuccess = 0;
//        if (numThreads == 1) {
//            start = System.currentTimeMillis();
//
//            numSuccess = createFiles(targetPaths, content, sharedHdfs, nameNodeEndpoint);
//
//            end = System.currentTimeMillis();
//            LOG.info("");
//            LOG.info("");
//            LOG.info("===============================");
//        } else {
//            int remainder = totalNumberOfFiles % numThreads;
//
//            if (remainder != 0) {
//                LOG.info("Assigning all but last thread " + n +
//                        " files. The last thread will be assigned " + remainder + " files.");
//            } else {
//                LOG.info("Assigning each thread " + n + " files.");
//            }
//
//            final String[][] contentPerArray = Utils.splitArray(content, n);
//            final String[][] targetPathsPerArray = Utils.splitArray(targetPaths, n);
//
//            assert targetPathsPerArray != null;
//            assert contentPerArray != null;
//
//            final CountDownLatch latch = new CountDownLatch(numThreads);
//            final Semaphore endSemaphore = new Semaphore((numThreads * -1) + 1);
//
//            Thread[] threads = new Thread[numThreads];
//
//            for (int i = 0; i < numThreads; i++) {
//                final int idx = i;
//                Thread thread = new Thread(() -> {
//                    DistributedFileSystem hdfs = Commander.initDfsClient(nameNodeEndpoint);
//
//                    latch.countDown();
//                    int localNumSuccess = createFiles(targetPathsPerArray[idx], contentPerArray[idx], hdfs, nameNodeEndpoint);
//
//                    endSemaphore.release();
//
//                    if (!BENCHMARKING_MODE) {
//                        operationsPerformed.add(hdfs.getOperationsPerformed());
//                        statisticsPackages.add(hdfs.getStatisticsPackages());
//                        transactionEvents.add(hdfs.getTransactionEvents());
//                        numSuccessPerThread.add(localNumSuccess);
//                    }
//
//                    for (double latency : hdfs.getLatencyStatistics().getValues()) {
//                        latencyBoth.addValue(latency);
//                    }
//
//                    for (double latency : hdfs.getLatencyHttpStatistics().getValues()) {
//                        latencyHttp.addValue(latency);
//                    }
//
//                    for (double latency : hdfs.getLatencyTcpStatistics().getValues()) {
//                        latencyTcp.addValue(latency);
//                    }
//
//                    try {
//                        hdfs.close();
//                    } catch (IOException ex) {
//                        LOG.error("Encountered IOException while closing DistributedFileSystem object:", ex);
//                    }
//                });
//                threads[i] = thread;
//            }
//
//            LOG.info("Starting threads.");
//            start = System.currentTimeMillis();
//            for (Thread thread : threads) {
//                thread.start();
//            }
//
//            // This way, we don't have to wait for all the statistics to be added to lists and whatnot.
//            // As soon as the threads finish, they call release() on the endSemaphore. Once all threads have
//            // done this, we designate the benchmark as ended and record the stop time. Then we join the threads
//            // so that all the statistics are placed into the appropriate collections where we can aggregate them.
//            endSemaphore.acquire();
//            end = System.currentTimeMillis();
//
//            LOG.info("Benchmark completed in " + (end - start) + "ms. Joining threads now...");
//            for (Thread thread : threads) {
//                thread.join();
//            }
//
//            LOG.info("");
//            LOG.info("");
//            LOG.info("===============================");
//            printLatencyStatistics(latencyBoth, latencyTcp, latencyHttp);
//            sharedHdfs.addLatencies(latencyTcp.getValues(), latencyHttp.getValues());
//
//            if (!BENCHMARKING_MODE) {
//                for (List<OperationPerformed> opsPerformed : operationsPerformed) {
//                    sharedHdfs.addOperationPerformeds(opsPerformed);
//                    allOpsPerformed.addAll(opsPerformed);
//                }
//
//                for (HashMap<String, TransactionsStats.ServerlessStatisticsPackage> statPackages : statisticsPackages) {
//                    //LOG.info("Adding list of " + statPackages.size() + " statistics packages to master/shared HDFS object.");
//                    sharedHdfs.mergeStatisticsPackages(statPackages, true);
//                }
//
//                for (HashMap<String, List<TransactionEvent>> txEvents : transactionEvents) {
//                    //LOG.info("Merging " + txEvents.size() + " new transaction event(s) into master/shared HDFS object.");
//                    sharedHdfs.mergeTransactionEvents(txEvents, true);
//                }
//            }
//
//            for (Integer localNumSuccess : numSuccessPerThread) {
//                numSuccess += localNumSuccess;
//            }
//        }
//
//        double durationSeconds = (end - start) / 1000.0;
//        filesPerSec = numSuccess / durationSeconds;
//        LOG.info("Number of successful write operations: " + numSuccess);
//        LOG.info("Number of failed write operations: " + (totalNumberOfFiles - numSuccess));
//        LOG.info("Time elapsed: " + durationSeconds);
//        LOG.info("Aggregate throughput: " + filesPerSec + " ops/sec.");
//
//        // Print the throughput including failed ops if there was at least one failed op.
//        if (totalNumberOfFiles != numSuccess)
//            LOG.info("Aggregate throughput including failures: " + (totalNumberOfFiles / durationSeconds) + " ops/sec.");
//
//        return new DistributedBenchmarkResult(null, 0, numSuccess,
//                durationSeconds, start, end, 0, 0,
//                TRACK_OP_PERFORMED ? allOpsPerformed.toArray(new OperationPerformed[0]) : null,
//                TRACK_OP_PERFORMED ? transactionEvents.toArray(new HashMap[0]) : null,
//                latencyTcp, latencyHttp);
    }

    /**
     * Check if the user is trying to cancel the current operation.
     *
     * @param input The user's input.
     */
    private static void checkForCancel(String input) {
        if (input.equalsIgnoreCase("abort") || input.equalsIgnoreCase("cancel") ||
                input.equalsIgnoreCase("exit"))
            throw new IllegalArgumentException("User specified '" + input + "'. Aborting operation.");
    }

    public static boolean getFileStatus(String path, DistributedFileSystem hdfs, String nameNodeEndpoint) {
        Path filePath = new Path(nameNodeEndpoint + path);
        try {
            FileStatus status = hdfs.getFileStatus(filePath);
            if (status == null) {
                LOG.warn("Received null result from getFileInfo('" + filePath + "')...");
                return false;
            }
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return false;
    }

    public static void createDirectories(DistributedFileSystem hdfs, String nameNodeEndpoint) {
        System.out.print("Base name for the directories:\n> ");
        String baseDirName = scanner.nextLine();

        int numDirectoriesToCreate = getIntFromUser("How many directories should be created?");

        long s = System.currentTimeMillis();
        for (int i = 0; i < numDirectoriesToCreate; i++) {
            mkdir(baseDirName + i + "/", hdfs, nameNodeEndpoint);
        }
        long t = System.currentTimeMillis();

        double durationSeconds = (t - s) / 1000.0;
        double throughput = (double)numDirectoriesToCreate / durationSeconds;

        LOG.info("Created " + numDirectoriesToCreate + " directories in " + durationSeconds + " seconds.");
        LOG.info("Throughput: " + throughput + " ops/sec.");
    }

    public static void createSubtree(DistributedFileSystem hdfs, String nameNodeEndpoint) throws IOException {
        System.out.print("Subtree root directory:\n> ");
        String subtreeRootPath = scanner.nextLine();

        System.out.print("Subtree depth:\n> ");
        int subtreeDepth = Integer.parseInt(scanner.nextLine());

        System.out.print("Max subdirs:\n> ");
        int maxSubDirs = Integer.parseInt(scanner.nextLine());

        int height = subtreeDepth + 1;
        double totalPossibleDirectories = (Math.pow(maxSubDirs, height + 1) - 1) / (maxSubDirs - 1);
        LOG.info("\nThis could create a maximum of " + totalPossibleDirectories + " directories.");
        System.out.print("Is this okay? [y/N]\n >");

        String resp = scanner.nextLine();

        // If they answered anything other than 'y', then abort.
        if (!resp.equalsIgnoreCase("y")) {
            LOG.info("\nAborting.");
            return;
        }

        int numDirectoriesCreated = 0;
        int filesCreated = 0;

        Instant start = Instant.now();

        int currentDepth = 0;

        mkdir(subtreeRootPath, hdfs, nameNodeEndpoint);
        numDirectoriesCreated++;

        Set<String> directoriesCreated = new HashSet<String>();
        Stack<TreeNode> directoryStack = new Stack<TreeNode>();
        TreeNode subtreeRoot = new TreeNode(subtreeRootPath, new ArrayList<TreeNode>());
        directoryStack.push(subtreeRoot);

        while (currentDepth <= subtreeDepth) {
            LOG.info("=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=");
            LOG.info("CURRENT DEPTH: " + currentDepth);
            LOG.info("DIRECTORIES CREATED: " + numDirectoriesCreated);
            LOG.info("=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=");
            List<Stack<TreeNode>> currentDepthStacks = new ArrayList<>();
            while (!directoryStack.empty()) {
                TreeNode directory = directoryStack.pop();
                //directoriesCreated.add(directory.getPath());

                String basePath = directory.getPath() + "/dir";

                Stack<TreeNode> stack = createChildDirectories(basePath, maxSubDirs, hdfs, nameNodeEndpoint, directoriesCreated);
                directory.addChildren(stack);
                numDirectoriesCreated += stack.size();
                currentDepthStacks.add(stack);
            }

            for (Stack<TreeNode> stack : currentDepthStacks) {
                directoryStack.addAll(stack);
            }

            currentDepth++;
        }

        Instant end = Instant.now();
        Duration subtreeCreationDuration = Duration.between(start, end);

        LOG.info("=== Subtree Creation Completed ===");
        LOG.info("Time elapsed: " + subtreeCreationDuration.toString());
        LOG.info("Directories created: " + numDirectoriesCreated);
        LOG.info("Files created: " + filesCreated + "\n");

        LOG.info("subtreeRoot children: " + subtreeRoot.getChildren().size());
        LOG.info("directoriesCreated.size(): " + directoriesCreated.size());
        LOG.info(subtreeRoot.toString());

//        for (String path : directoriesCreated) {
//            System.out.println(path);
//        }

        Utils.write("./output/createSubtree-" + Instant.now().toEpochMilli()+ ".txt", directoriesCreated.toArray(new String[0]));

        LOG.info("==================================");
    }

    public static Stack<TreeNode> createChildDirectories(String basePath, int subDirs,
                                                         DistributedFileSystem hdfs,
                                                         String nameNodeEndpoint,
                                                         Collection<String> directoriesCreated) {
        Stack<TreeNode> directoryStack = new Stack<TreeNode>();
        for (int i = 0; i < subDirs; i++) {
            String path = basePath + i;
            mkdir(path, hdfs, nameNodeEndpoint);
            directoriesCreated.add(path);
            TreeNode node = new TreeNode(path, new ArrayList<TreeNode>());
            directoryStack.push(node);
        }

        return directoryStack;
    }

    public static void createFileOperation(DistributedFileSystem hdfs, String nameNodeEndpoint) {
        System.out.print("File path:\n> ");
        String fileName = scanner.nextLine();

        checkForCancel(fileName);

        System.out.print("File contents:\n> ");
        String fileContents = scanner.nextLine().trim();

        checkForCancel(fileName);

        createFile(fileName, fileContents, hdfs, nameNodeEndpoint);
    }

    /**
     * Create files using the names and contents provide by the two parameters.
     *
     * The two argument lists must have the same length.
     *
     * @param names File names.
     * @param content File contents.
     *
     * @return The number of successful create operations.
     */
    public static int createFiles(String[] names, String[] content, DistributedFileSystem hdfs, String nameNodeEndpoint) {
        assert(names.length == content.length);

        int numSuccess = 0;

        for (int i = 0; i < names.length; i++) {
            LOG.info("Writing file " + i + "/" + names.length);
            long s = System.currentTimeMillis();
            boolean success = createFile(names[i], content[i], hdfs, nameNodeEndpoint);
            if (success) numSuccess++;
            long t = System.currentTimeMillis();
            LOG.info("Wrote file " + (i+1) + "/" + names.length + " in " + (t - s) + " ms.");
        }

        return numSuccess;
    }

    /**
     * Create a new file with the given name and contents.
     * @param name The name of the file.
     * @param contents The content to be written to the file.
     *
     * @return True if the create operation succeeds, otherwise false.
     */
    public static boolean createFile(String name,
                                     String contents,
                                     DistributedFileSystem hdfs,
                                     String nameNodeEndpoint) {
        Path filePath = new Path(nameNodeEndpoint + name);

        try {
            FSDataOutputStream outputStream = hdfs.create(filePath);

            if (!contents.isEmpty()) {
                BufferedWriter br = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));
                br.write(contents);
                br.close();
                LOG.info("\t Successfully created non-empty file '" + filePath + "'");
            } else {
                LOG.info("\t Successfully created empty file '" + filePath + "'");
                outputStream.close();
            }

            return true;
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        return false;
    }

    /**
     * Check if the user is trying to cancel the current operation.
     *
     * @param input The user's input.
     */
    private static void checkForExit(String input) {
        if (input.equalsIgnoreCase("abort") || input.equalsIgnoreCase("cancel") ||
                input.equalsIgnoreCase("exit"))
            throw new IllegalArgumentException("User specified '" + input + "'. Aborting operation.");
    }

    public static void renameOperation(DistributedFileSystem hdfs, String nameNodeEndpoint) {
        System.out.print("Original file path:\n> ");
        String originalFileName = scanner.nextLine();
        checkForExit(originalFileName);

        System.out.print("Renamed file path:\n> ");
        String renamedFileName = scanner.nextLine();
        checkForExit(renamedFileName);

        Path filePath = new Path(nameNodeEndpoint + originalFileName);
        Path filePathRename = new Path(nameNodeEndpoint + renamedFileName);

        try {
            LOG.info("\t Original file path: \"" + originalFileName + "\"");
            LOG.info("\t New file path: \"" + renamedFileName + "\"");
            hdfs.rename(filePath, filePathRename);
            LOG.info("\t Finished rename operation.");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void listOperation(DistributedFileSystem hdfs, String nameNodeEndpoint) {
        System.out.print("Target directory:\n> ");
        String targetDirectory = scanner.nextLine();

        try {
            FileStatus[] fileStatus = hdfs.listStatus(new Path(nameNodeEndpoint + targetDirectory));
            for(FileStatus status : fileStatus)
                LOG.info(status.getPath().toString());
            LOG.info("Directory '" + targetDirectory + "' contains " + fileStatus.length + " files.");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Create a new directory with the given path.
     * @param path The path of the new directory.
     */
    public static void mkdir(String path, DistributedFileSystem hdfs, String nameNodeEndpoint) {
        Path filePath = new Path(nameNodeEndpoint + path);

        try {
            LOG.info("\t Attempting to create new directory: \"" + path + "\"");
            boolean directoryCreated = hdfs.mkdirs(filePath);
            LOG.info("\t Directory created successfully: " + directoryCreated);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void getActiveNameNodesOperation(DistributedFileSystem hdfs) {
        if (!IS_SERVERLESS) {
            LOG.error("Getting the active NNs is not supported by Vanilla HopsFS!");
            return;
        }

        SortedActiveNodeList activeNameNodes = null;
        try {
            activeNameNodes = hdfs.getActiveNamenodesForClient();
        } catch (IOException ex) {
            LOG.error("Could not retrieve Active NameNodes due to exception.");
            ex.printStackTrace();
            return;
        }

        if (activeNameNodes instanceof ActiveServerlessNameNodeList) {
            ActiveServerlessNameNodeList activeServerlessNameNodes = (ActiveServerlessNameNodeList)activeNameNodes;
            List<ActiveNode> activeNodes = activeServerlessNameNodes.getActiveNodes();
            HashMap<Integer, List<Long>> nodesPerDeployment = new HashMap<>();
            int numDeployments = activeServerlessNameNodes.getNumDeployments();
            for (ActiveNode node : activeNodes) {
                ActiveServerlessNameNode serverlessNode = (ActiveServerlessNameNode)node;
                int deployment = serverlessNode.getDeploymentNumber();
                long nnId = serverlessNode.getId();

                List<Long> nodesInDeployment = nodesPerDeployment.computeIfAbsent(deployment, d -> new ArrayList<>());
                nodesInDeployment.add(nnId);
            }

            int totalActiveNNs = 0;
            System.out.println("== Active NNs per Deployment =============");
            for (int i = 0; i < numDeployments; i++) {
                List<Long> nodes = nodesPerDeployment.getOrDefault(i, new ArrayList<>());
                int numNodes = nodes.size();
                totalActiveNNs += numNodes;
                System.out.println("Deployment " + i + ": " + numNodes);
                if (nodes.size() > 0)
                    System.out.println("Nodes: " + StringUtils.join(nodes, ","));
                System.out.println();
            }

            System.out.println("Total Number of Active NameNodes: " + totalActiveNNs);
        }
    }

    public static void mkdirOperation(DistributedFileSystem hdfs, String nameNodeEndpoint) {
        System.out.print("New directory path:\n> ");
        String newDirectoryName = scanner.nextLine();

        mkdir(newDirectoryName, hdfs, nameNodeEndpoint);
    }

    public static void appendOperation(DistributedFileSystem hdfs, String nameNodeEndpoint) {
        System.out.print("File path:\n> ");
        String fileName = scanner.nextLine();
        System.out.print("Content to append:\n> ");
        String fileContents = scanner.nextLine();

        Path filePath = new Path(nameNodeEndpoint + fileName);

        try {
            FSDataOutputStream outputStream = hdfs.append(filePath);
            LOG.info("\t Called append() successfully.");
            BufferedWriter br = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));
            LOG.info("\t Created BufferedWriter object.");
            br.write(fileContents);
            LOG.info("\t Appended \"" + fileContents + "\" to file using BufferedWriter.");
            br.close();
            LOG.info("\t Closed BufferedWriter.");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void prewarmOperation(DistributedFileSystem hdfs) {
        int threadsPerDeployment = getIntFromUser("Number of threads to use for each deployment?");
        int pingsPerThread = getIntFromUser("How many times should each thread pings its assigned deployment?");

        try {
            hdfs.prewarm(threadsPerDeployment, pingsPerThread);
        } catch (IOException ex) {
            LOG.info("Encountered IOException while pre-warming NNs.");
            ex.printStackTrace();
        }
    }

    public static void pingOperation(DistributedFileSystem hdfs) {
        System.out.print("Target deployment:\n> ");
        int targetDeployment = Integer.parseInt(scanner.nextLine());

        try {
            hdfs.ping(targetDeployment);
        } catch (IOException ex) {
            LOG.info("Encountered IOException while pinging NameNode deployment " +
                    targetDeployment + ".");
            ex.printStackTrace();
        }
    }

    public static void readOperation(DistributedFileSystem hdfs, String nameNodeEndpoint) {
        System.out.print("File path:\n> ");
        String fileName = scanner.nextLine();
        readFile(fileName, hdfs, nameNodeEndpoint);
    }

    /**
     * Read the HopsFS/HDFS file at the given path.
     * @param fileName The path to the file to read.
     * @return The latency of the operation in nanoseconds.
     */
    public static boolean readFile(String fileName, DistributedFileSystem hdfs, String nameNodeEndpoint) {
        Path filePath = new Path(nameNodeEndpoint + fileName);

        try {
            FSDataInputStream inputStream = hdfs.open(filePath);

            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
            String line = null;

            while ((line = br.readLine()) != null)
                LOG.info(line);
            inputStream.close();
            br.close();
            return true;
        } catch (IOException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    private static int getIntFromUser(String prompt) {
        System.out.print(prompt + "\n> ");
        String input = scanner.nextLine();
        checkForCancel(input);
        return Integer.parseInt(input);
    }

    public static void deleteOperation(DistributedFileSystem hdfs, final String nameNodeEndpoint) {
        System.out.print("File or directory path:\n> ");
        String targetPath = scanner.nextLine();

        Path filePath = new Path(nameNodeEndpoint + targetPath);

        long s = System.currentTimeMillis();
        try {
            boolean success = hdfs.delete(filePath, true);
            long t = System.currentTimeMillis();

            if (success)
                LOG.info("Successfully deleted '" + filePath + "' in " + (t - s) + " milliseconds.");
            else
                LOG.error("Failed to delete '" + filePath + "' after " + (t - s) + " milliseconds.");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
