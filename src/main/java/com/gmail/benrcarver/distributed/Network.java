package com.gmail.benrcarver.distributed;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.EndPoint;
import com.gmail.benrcarver.distributed.workload.BMOpStats;
import com.gmail.benrcarver.distributed.workload.WorkloadResponse;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SynchronizedDescriptiveStatistics;

public class Network {
    public static void register (EndPoint endPoint) {
        Kryo kryo = endPoint.getKryo();

        kryo.setReferences(true);
        kryo.setRegistrationRequired(false);
        kryo.setWarnUnregisteredClasses(true);

        kryo.register(WorkloadResponse.class);
        kryo.register(String[].class);
        kryo.register(DistributedBenchmarkResult.class);
        kryo.register(java.util.HashMap.class);
        kryo.register(BMOpStats.class);
        kryo.register(java.util.ArrayList.class);
        kryo.register(java.util.List.class);
        kryo.register(byte[].class);
        kryo.register(java.util.HashMap[].class);
        kryo.register(DescriptiveStatistics.class);
        kryo.register(SynchronizedDescriptiveStatistics.class);
        kryo.register(java.util.HashMap[].class);
    }
}
