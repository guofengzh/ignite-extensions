/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.examples.datagrid.hibernate;

import java.net.URL;
import java.util.Arrays;
import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.examples.ExampleNodeStartup;
import org.apache.ignite.examples.ExamplesUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.stat.CacheRegionStatistics;
//import org.hibernate.stat.SecondLevelCacheStatistics;
import org.hibernate.stat.Statistics;

import static org.apache.ignite.cache.CacheAtomicityMode.ATOMIC;
import static org.apache.ignite.cache.CacheAtomicityMode.TRANSACTIONAL;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;

/**
 * This example demonstrates the use of Ignite In-Memory Data Ignite cluster as a Hibernate
 * Second-Level cache provider.
 * <p>
 * The Hibernate Second-Level cache (or "L2 cache" shortly) lets you significantly
 * reduce the number of requests to the underlying SQL database. Because database
 * access is known to be an expansive operation, using L2 cache may improve
 * performance dramatically.
 * <p>
 * This example defines 2 entity classes: {@link User} and {@link Post}, with
 * 1 <-> N relation, and marks them with appropriate annotations for Hibernate
 * object-relational mapping to SQL tables of an underlying H2 in-memory database.
 * The example launches node in the same JVM and registers it in
 * Hibernate configuration as an L2 cache implementation. It then stores and
 * queries instances of the entity classes to and from the database, having
 * Hibernate SQL output, L2 cache statistics output, and Ignite cache metrics
 * output enabled.
 * <p>
 * When running example, it's easy to notice that when an object is first
 * put into a database, the L2 cache is not used and it's contents is empty.
 * However, when an object is first read from the database, it is immediately
 * stored in L2 cache (which is Ignite In-Memory Data Ignite cluster in fact), which can
 * be seen in stats output. Further requests of the same object only read the data
 * from L2 cache and do not hit the database.
 * <p>
 * In this example, the Hibernate query cache is also enabled. Query cache lets you
 * avoid hitting the database in case of repetitive queries with the same parameter
 * values. You may notice that when the example runs the same query repeatedly in
 * loop, only the first query hits the database and the successive requests take the
 * data from L2 cache.
 * <p>
 * Note: this example uses {@link AccessType#READ_ONLY} L2 cache access type, but you
 * can experiment with other access types by modifying the Hibernate configuration file
 * {@code IGNITE_HOME/examples/config/hibernate/example-hibernate-L2-cache.xml}, used by the example.
 * <p>
 * Remote nodes should always be started with special configuration file which
 * enables P2P class loading: {@code 'ignite.{sh|bat} examples/config/example-ignite.xml'}.
 * <p>
 * Alternatively you can run {@link ExampleNodeStartup} in another JVM which will
 * start node with {@code examples/config/example-ignite.xml} configuration.
 */
public class HibernateL2CacheExample {
    /** JDBC URL for backing database (an H2 in-memory database is used). */
    private static final String JDBC_URL = "jdbc:h2:mem:example;DB_CLOSE_DELAY=-1";

    /** Path to hibernate configuration file (will be resolved from application {@code CLASSPATH}). */
    private static final String HIBERNATE_CFG = "hibernate/example-hibernate-L2-cache.xml";

    /** Entity names for stats output. */
    private static final List<String> ENTITY_NAMES =
        Arrays.asList(User.class.getName(), Post.class.getName(), User.class.getName() + ".posts");

    /** Caches' names. */
    private static final String USER_CACHE_NAME = "org.apache.ignite.examples.datagrid.hibernate.User";

    /** */
    private static final String USER_POSTS_CACHE_NAME = "org.apache.ignite.examples.datagrid.hibernate.User.posts";

    /** */
    private static final String POST_CACHE_NAME = "org.apache.ignite.examples.datagrid.hibernate.Post";

    /** */
    static final String MODULE_PATH = System.getProperty("user.dir");

    /**
     * Executes example.
     *
     * @param args Command line arguments, none required.
     * @throws IgniteException If example execution failed.
     */
    public static void main(String[] args) throws IgniteException {
        // Start the node, run the example, and stop the node when finished.
        try (Ignite ignite = Ignition.start(MODULE_PATH + "/config/example-ignite.xml")) {
            // We use a single session factory, but create a dedicated session
            // for each transaction or query. This way we ensure that L1 cache
            // is not used (L1 cache has per-session scope only).
            System.out.println();
            System.out.println(">>> Hibernate L2 cache example started.");

            // Auto-close cache at the end of the example.
            try (
                // Create all required caches.
                IgniteCache c1 = createCache(timestampsCacheName(), ATOMIC);
                IgniteCache c2 = createCache(queryResultsCacheName(), ATOMIC);
                IgniteCache c3 = createCache(USER_CACHE_NAME, TRANSACTIONAL);
                IgniteCache c4 = createCache(USER_POSTS_CACHE_NAME, TRANSACTIONAL);
                IgniteCache c5 = createCache(POST_CACHE_NAME, TRANSACTIONAL)
            ) {
                URL hibernateCfg = ExamplesUtils.url(HIBERNATE_CFG);

                try (SessionFactory sesFactory = createHibernateSessionFactory(hibernateCfg)) {
                    System.out.println();
                    System.out.println(">>> Creating objects.");

                    final long userId;

                    Session ses = sesFactory.openSession();

                    try {
                        Transaction tx = ses.beginTransaction();

                        User user = new User("jedi", "Luke", "Skywalker");

                        user.getPosts().add(new Post(user, "Let the Force be with you."));

                        ses.save(user);

                        tx.commit();

                        // Create a user object, store it in DB, and save the database-generated
                        // object ID. You may try adding more objects in a similar way.
                        userId = user.getId();
                    }
                    finally {
                        ses.close();
                    }

                    // Output L2 cache and Ignite cache stats. You may notice that
                    // at this point the object is not yet stored in L2 cache, because
                    // the read was not yet performed.
                    printStats(sesFactory);

                    System.out.println();
                    System.out.println(">>> Querying object by ID.");

                    // Query user by ID several times. First time we get an L2 cache
                    // miss, and the data is queried from DB, but it is then stored
                    // in cache and successive queries hit the cache and return
                    // immediately, no SQL query is made.
                    for (int i = 0; i < 3; i++) {
                        ses = sesFactory.openSession();

                        try {
                            Transaction tx = ses.beginTransaction();

                            User user = (User)ses.get(User.class, userId);

                            System.out.println("User: " + user);

                            for (Post post : user.getPosts())
                                System.out.println("\tPost: " + post);

                            tx.commit();
                        }
                        finally {
                            ses.close();
                        }
                    }

                    // Output the stats. We should see 1 miss and 2 hits for
                    // User and Collection object (stored separately in L2 cache).
                    // The Post is loaded with the collection, so it won't imply
                    // a miss.
                    printStats(sesFactory);
                }
            }
            finally {
                // Distributed cache could be removed from cluster only by #destroyCache() call.
                ignite.destroyCache(timestampsCacheName());
                ignite.destroyCache(queryResultsCacheName());
                ignite.destroyCache(USER_CACHE_NAME);
                ignite.destroyCache(USER_POSTS_CACHE_NAME);
                ignite.destroyCache(POST_CACHE_NAME);
            }
        }
    }

    /**
     * Creates cache.
     *
     * @param name Cache name.
     * @param atomicityMode Atomicity mode.
     * @return Cache configuration.
     */
    private static IgniteCache createCache(String name, CacheAtomicityMode atomicityMode) {
        CacheConfiguration ccfg = new CacheConfiguration(name);

        ccfg.setAtomicityMode(atomicityMode);
        ccfg.setWriteSynchronizationMode(FULL_SYNC);

        return Ignition.ignite().getOrCreateCache(ccfg);
    }

    /**
     * Creates a new Hibernate {@link SessionFactory} using a programmatic
     * configuration.
     *
     * @param hibernateCfg Hibernate configuration file.
     * @return New Hibernate {@link SessionFactory}.
     */
    private static SessionFactory createHibernateSessionFactory(URL hibernateCfg) {
        StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();

        builder.applySetting("hibernate.connection.url", JDBC_URL);
        builder.applySetting("hibernate.show_sql", true);

        builder.configure(hibernateCfg);

        return new MetadataSources(builder.build()).buildMetadata().buildSessionFactory();
    }

    /**
     * Prints Hibernate L2 cache statistics to standard output.
     *
     * @param sesFactory Hibernate {@link SessionFactory}, for which to print
     *                   statistics.
     */
    private static void printStats(SessionFactory sesFactory) {
        System.out.println("=== Hibernate L2 cache statistics ===");

        for (String entityName : ENTITY_NAMES) {
            System.out.println("\tEntity: " + entityName);

            //SecondLevelCacheStatistics stats =
            //    sesFactory.getStatistics().getSecondLevelCacheStatistics(entityName);

            CacheRegionStatistics secondLevelCacheStatistics =
                    sesFactory.getStatistics().getDomainDataRegionStatistics(entityName);
            System.out.println("\t\tPuts: " + secondLevelCacheStatistics.getPutCount());
            System.out.println("\t\tHits: " + secondLevelCacheStatistics.getHitCount());
            System.out.println("\t\tMisses: " + secondLevelCacheStatistics.getMissCount());
        }

        System.out.println("=====================================");
    }

    /**
     * Returns the name of the timestamps cache to a specific version of apache-hibernate.
     *
     * @return Name of the update timestamps cache.
     */
    private static String timestampsCacheName() {
        return isIgniteHibernate51orBelowEnabled() ?
            // Represents the name of timestamps region specific to hibernate 5.1 {@see HibernateTimestampsRegion}.
            "org.hibernate.cache.spi.UpdateTimestampsCache" :
            // Represents the name of timestamps region specific to hibernate 5.3 {@see IgniteTimestampsRegion}.
            "default-update-timestamps-region";
    }

    /**
     * Returns the name of the query results cache to a specific version of apache-hibernate.
     *
     * @return Name of the update timestamps cache.
     */
    private static String queryResultsCacheName() {
        return isIgniteHibernate51orBelowEnabled() ?
            // Represents the name of query results region specific to hibernate 5.1 {@see HibernateQueryResultsRegion}.
            "org.hibernate.cache.internal.StandardQueryCache" :
            // Represents the name of query results region specific to hibernate 5.3 {@see IgniteQueryResultsRegion}.
            "default-query-results-region";
    }

    /**
     * Returns {@code true} if ignite-hibernate 5.1 is enabled.
     *
     * @return {@code true} if ignite-hibernate 5.1 is enabled.
     */
    private static boolean isIgniteHibernate51orBelowEnabled() {
        try {
            Class.forName("org.apache.ignite.cache.hibernate.HibernateTimestampsRegion");

            return true;
        }
        catch (ClassNotFoundException ignore) {
            return false;
        }
    }
}
