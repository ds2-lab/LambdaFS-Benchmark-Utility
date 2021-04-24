package hopsfs;

/**
 * Encapsulates a NameNode to which we will send requests to during the benchmark.
 */
public class HopsFSNameNode {
    /**
     * Indicates whether the NameNode should retrieve requested "metadata" from NDB or its local storage.
     */
    private final String dataSource;

    /**
     * The NDB URI that the NameNode should issue NDB requests to.
     */
    private final String ndbConnectionUri;

    /**
     * The query that we want the NameNode to execute.
     */
    private final String query;

    /**
     * URI of the NameNode.
     */
    private final String nameNodeUri;

    /**
     * The number of queries the NameNode should execute PER RPC REQUEST.
     *
     * The total number of queries executed by this NameNode will be equal to:
     *
     *      numThreads * numRpc * numQueries,
     *
     * assuming the NameNode is directed to issue NDB queries rather than retrieve
     * data from its local memory/cache).
     */
    private final int numQueries;

    /**
     * The number of RPC calls we should issue to this NameNode PER THREAD.
     */
    private final int numRpc;

    /**
     * Current HopsFS API requires an ID, which is the ID of the desired user in the NDB database.
     */
    private final int id;

    /**
     * The number of threads that will target this NameNode during this test.
     */
    private final  int numThreads;

    public HopsFSNameNode(String dataSource, String ndbConnectionUri, String query, String nameNodeUri,
                          int numQueries, int numRpc, int numThreads, int id) {
        this.dataSource = dataSource;
        this.ndbConnectionUri = ndbConnectionUri;
        this.query = query;
        this.nameNodeUri = nameNodeUri;
        this.numQueries = numQueries;
        this.numRpc = numRpc;
        this.numThreads = numThreads;
        this.id = id;
    }

    public String getDataSource() {
        return dataSource;
    }

    public String getNdbConnectionUri() {
        return ndbConnectionUri;
    }

    public String getQuery() {
        return query;
    }

    public String getNameNodeUri() {
        return nameNodeUri;
    }

    public int getNumQueries() {
        return numQueries;
    }

    public int getNumRpc() {
        return numRpc;
    }

    public int getNumThreads() {
        return numThreads;
    }

    public int getId() {
        return id;
    }
}
