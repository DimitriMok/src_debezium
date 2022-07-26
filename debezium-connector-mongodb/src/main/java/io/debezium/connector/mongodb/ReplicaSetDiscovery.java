/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mongodb;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.client.MongoClient;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ServerConnectionState;
import com.mongodb.connection.ServerDescription;

import io.debezium.annotation.ThreadSafe;

/**
 * A component that monitors a single replica set or the set of replica sets that make up the shards in a sharded cluster.
 *
 * @author Randall Hauch
 */
@ThreadSafe
public class ReplicaSetDiscovery {

    /**
     * The database that might be used to check for replica set information in a sharded cluster.
     */
    public static final String CONFIG_DATABASE_NAME = "config";

    /**
     * The database that might be used to check for member information in a replica set.
     */
    public static final String ADMIN_DATABASE_NAME = "admin";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicaSetDiscovery.class);

    private final MongoDbTaskContext context;
    private final String seedAddresses;

    /**
     * Create a cluster component.
     *
     * @param context the replication context; may not be null
     */
    public ReplicaSetDiscovery(MongoDbTaskContext context) {
        this.context = context;
        this.seedAddresses = context.getConnectionContext().hosts();
    }

    /**
     * Connect to the shard cluster or replica set defined by the seed addresses, and obtain the specifications for each of the
     * replica sets.
     *
     * @return the information about the replica sets; never null but possibly empty
     */
    public ReplicaSets getReplicaSets() {
        MongoClient client = context.getConnectionContext().clientFor(seedAddresses);
        Set<ReplicaSet> replicaSetSpecs = new HashSet<>();

        // First see if the addresses are for a config server replica set ...
        String shardsCollection = "shards";
        try {
            MongoUtil.onCollectionDocuments(client, CONFIG_DATABASE_NAME, shardsCollection, doc -> {
                LOGGER.info("Checking shard details from configuration replica set {}", seedAddresses);
                String shardName = doc.getString("_id");
                String hostStr = doc.getString("host");
                String replicaSetName = MongoUtil.replicaSetUsedIn(hostStr);
                replicaSetSpecs.add(new ReplicaSet(hostStr, replicaSetName, shardName));
            });
        }
        catch (MongoInterruptedException e) {
            LOGGER.error("Interrupted while reading the '{}' collection in the '{}' database: {}",
                    shardsCollection, CONFIG_DATABASE_NAME, e.getMessage(), e);
            Thread.currentThread().interrupt();
        }
        catch (MongoException e) {
            LOGGER.error("Error while reading the '{}' collection in the '{}' database: {}",
                    shardsCollection, CONFIG_DATABASE_NAME, e.getMessage(), e);
        }
        if (replicaSetSpecs.isEmpty()) {
            // The addresses may be a replica set ...
            final ClusterDescription clusterDescription = client.getClusterDescription();
            LOGGER.info("Checking current members of replica set at {}", seedAddresses);
            if (clusterDescription != null) {
                // This is a replica set ...
                final List<ServerDescription> serverDescriptions = clusterDescription.getServerDescriptions().stream()
                        .filter(x -> x.getState() == ServerConnectionState.CONNECTED).collect(Collectors.toList());
                if (serverDescriptions.size() == 0) {
                    LOGGER.warn("Server descriptions not available, got '{}'", serverDescriptions);
                }
                else {
                    String addressStr = serverDescriptions.stream().map(x -> x.getAddress().toString()).collect(Collectors.joining(","));
                    String replicaSetName = serverDescriptions.get(0).getSetName();
                    replicaSetSpecs.add(new ReplicaSet(addressStr, replicaSetName, null));
                }
            }
            else {
                LOGGER.debug("Found standalone MongoDB replica set at {}", seedAddresses);
                // We aren't connecting to it as a replica set (likely not using auto-discovery of members),
                // but we can't monitor standalone servers unless they really are replica sets. We already know
                // that we're not connected to a config server replica set, so any replica set name from the seed addresses
                // is almost certainly our replica set name ...
                String replicaSetName = MongoUtil.replicaSetUsedIn(seedAddresses);
                if (replicaSetName != null) {
                    for (String address : MongoUtil.ADDRESS_DELIMITER_PATTERN.split(seedAddresses)) {
                        replicaSetSpecs.add(new ReplicaSet(address, replicaSetName, null));
                    }
                }
            }
        }
        if (replicaSetSpecs.isEmpty()) {
            // Without a replica set name, we can't do anything ...
            LOGGER.error(
                    "Found no replica sets at {}, so there is nothing to monitor and no connector tasks will be started. Check seed addresses in connector configuration.",
                    seedAddresses);
        }
        return new ReplicaSets(replicaSetSpecs);
    }
}
