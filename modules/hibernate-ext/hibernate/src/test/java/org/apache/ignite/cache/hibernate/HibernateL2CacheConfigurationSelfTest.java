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

package org.apache.ignite.cache.hibernate;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.cache.Cache;
import jakarta.persistence.Cacheable;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.processors.cache.IgniteCacheProxy;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cfg.Configuration;
import org.junit.Test;
import static org.apache.ignite.cache.CacheAtomicityMode.ATOMIC;
import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.cache.hibernate.HibernateAccessStrategyFactory.REGION_CACHE_PROPERTY;
import static org.hibernate.cache.spi.RegionFactory.DEFAULT_QUERY_RESULTS_REGION_UNQUALIFIED_NAME;
import static org.hibernate.cache.spi.RegionFactory.DEFAULT_UPDATE_TIMESTAMPS_REGION_UNQUALIFIED_NAME;
import static org.hibernate.cfg.AvailableSettings.CACHE_REGION_FACTORY;
import static org.hibernate.cfg.AvailableSettings.GENERATE_STATISTICS;
import static org.hibernate.cfg.AvailableSettings.HBM2DDL_AUTO;
import static org.hibernate.cfg.AvailableSettings.CONNECTION_HANDLING ;
import static org.hibernate.cfg.AvailableSettings.USE_QUERY_CACHE;
import static org.hibernate.cfg.AvailableSettings.USE_SECOND_LEVEL_CACHE;

/**
 * Tests Hibernate L2 cache configuration.
 */
public class HibernateL2CacheConfigurationSelfTest extends GridCommonAbstractTest {
    /** */
    public static final String ENTITY1_NAME = Entity1.class.getName();

    /** */
    public static final String ENTITY2_NAME = Entity2.class.getName();

    /** */
    public static final String ENTITY3_NAME = Entity3.class.getName();

    /** */
    public static final String ENTITY4_NAME = Entity4.class.getName();

    /** */
    public static final String CONNECTION_URL = "jdbc:h2:mem:example;DB_CLOSE_DELAY=-1";

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        startGrid(0);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        for (IgniteCacheProxy<?, ?> cache : ((IgniteKernal)grid(0)).caches())
            cache.clear();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(new TcpDiscoveryVmIpFinder(true));

        cfg.setDiscoverySpi(discoSpi);

        cfg.setCacheConfiguration(cacheConfiguration(ENTITY3_NAME),
            cacheConfiguration(ENTITY4_NAME),
            cacheConfiguration("cache1"),
            cacheConfiguration("cache2"),
            cacheConfiguration("cache3"),
            cacheConfiguration(DEFAULT_UPDATE_TIMESTAMPS_REGION_UNQUALIFIED_NAME),
            cacheConfiguration(DEFAULT_QUERY_RESULTS_REGION_UNQUALIFIED_NAME));

        return cfg;
    }

    /**
     * @param cacheName Cache name.
     * @return Cache configuration.
     */
    private CacheConfiguration cacheConfiguration(String cacheName) {
        CacheConfiguration cfg = new CacheConfiguration();

        cfg.setName(cacheName);

        cfg.setCacheMode(PARTITIONED);

        cfg.setAtomicityMode(ATOMIC);

        cfg.setStatisticsEnabled(true);

        return cfg;
    }

    /**
     * @param igniteInstanceName Ignite instance name.
     * @return Hibernate configuration.
     */
    protected Configuration hibernateConfiguration(String igniteInstanceName) {
        Configuration cfg = new Configuration();

        cfg.addAnnotatedClass(Entity1.class);
        cfg.addAnnotatedClass(Entity2.class);
        cfg.addAnnotatedClass(Entity3.class);
        cfg.addAnnotatedClass(Entity4.class);

        cfg.setProperty(HibernateAccessStrategyFactory.DFLT_ACCESS_TYPE_PROPERTY, AccessType.NONSTRICT_READ_WRITE.name());

        cfg.setProperty(HBM2DDL_AUTO, "create");

        cfg.setProperty(GENERATE_STATISTICS, "true");

        cfg.setProperty(USE_SECOND_LEVEL_CACHE, "true");

        cfg.setProperty(USE_QUERY_CACHE, "true");

        cfg.setProperty(CACHE_REGION_FACTORY, HibernateRegionFactory.class.getName());
        //cfg.setProperty(CONNECTION_HANDLING, "on_close"); ERROR!
        cfg.setProperty(CONNECTION_HANDLING, "IMMEDIATE_ACQUISITION_AND_HOLD");

        cfg.setProperty(HibernateAccessStrategyFactory.IGNITE_INSTANCE_NAME_PROPERTY, igniteInstanceName);

        cfg.setProperty(REGION_CACHE_PROPERTY + ENTITY1_NAME, "cache1");
        cfg.setProperty(REGION_CACHE_PROPERTY + ENTITY2_NAME, "cache2");
        cfg.setProperty(
            REGION_CACHE_PROPERTY + DEFAULT_UPDATE_TIMESTAMPS_REGION_UNQUALIFIED_NAME,
            DEFAULT_UPDATE_TIMESTAMPS_REGION_UNQUALIFIED_NAME
        );
        cfg.setProperty(
            REGION_CACHE_PROPERTY + DEFAULT_QUERY_RESULTS_REGION_UNQUALIFIED_NAME,
            DEFAULT_QUERY_RESULTS_REGION_UNQUALIFIED_NAME
        );

        return cfg;
    }

    /**
     * Tests property {@link HibernateAccessStrategyFactory#REGION_CACHE_PROPERTY}.
     */
    @Test
    public void testPerRegionCacheProperty() {
        testCacheUsage(1, 1, 0, 1, 1);
    }

    /**
     * @param expCache1 Expected size of cache with name 'cache1'.
     * @param expCache2 Expected size of cache with name 'cache2'.
     * @param expCache3 Expected size of cache with name 'cache3'.
     * @param expCacheE3 Expected size of cache with name {@link #ENTITY3_NAME}.
     * @param expCacheE4 Expected size of cache with name {@link #ENTITY4_NAME}.
     */
    @SuppressWarnings("unchecked")
    private void testCacheUsage(int expCache1, int expCache2, int expCache3, int expCacheE3, int expCacheE4) {
        SessionFactory sesFactory = startHibernate(getTestIgniteInstanceName(0));

        try {
            Session ses = sesFactory.openSession();

            try {
                Transaction tx = ses.beginTransaction();

                ses.save(new Entity1());
                ses.save(new Entity2());
                ses.save(new Entity3());
                ses.save(new Entity4());

                tx.commit();
            }
            finally {
                ses.close();
            }

            ses = sesFactory.openSession();

            try {
                List<Entity1> list1 = getResultsList(ses, Entity1.class);

                assertEquals(1, list1.size());

                for (Entity1 e : list1) {
                    ses.load(ENTITY1_NAME, e.getId());
                    assertNotNull(e.getId());
                }

                List<Entity2> list2 = getResultsList(ses, Entity2.class);

                assertEquals(1, list2.size());

                for (Entity2 e : list2)
                    assertNotNull(e.getId());

                List<Entity3> list3 = getResultsList(ses, Entity3.class);

                assertEquals(1, list3.size());

                for (Entity3 e : list3)
                    assertNotNull(e.getId());

                List<Entity4> list4 = getResultsList(ses, Entity4.class);

                assertEquals(1, list4.size());

                for (Entity4 e : list4)
                    assertNotNull(e.getId());
            }
            finally {
                ses.close();
            }

            IgniteCache<Object, Object> cache1 = grid(0).cache("cache1");
            IgniteCache<Object, Object> cache2 = grid(0).cache("cache2");
            IgniteCache<Object, Object> cache3 = grid(0).cache("cache3");
            IgniteCache<Object, Object> cacheE3 = grid(0).cache(ENTITY3_NAME);
            IgniteCache<Object, Object> cacheE4 = grid(0).cache(ENTITY4_NAME);

            assertEquals("Unexpected entries: " + toSet(cache1.iterator()), expCache1, cache1.size());
            assertEquals("Unexpected entries: " + toSet(cache2.iterator()), expCache2, cache2.size());
            assertEquals("Unexpected entries: " + toSet(cache3.iterator()), expCache3, cache3.size());
            assertEquals("Unexpected entries: " + toSet(cacheE3.iterator()), expCacheE3, cacheE3.size());
            assertEquals("Unexpected entries: " + toSet(cacheE4.iterator()), expCacheE4, cacheE4.size());
        }
        finally {
            sesFactory.close();
        }
    }

    /** */
    private <T> List<T> getResultsList(Session ses, Class<T> entityClass) {
        CriteriaBuilder builder = ses.getCriteriaBuilder();
        CriteriaQuery<T> query = builder.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);
        return ses.createQuery(query).getResultList();
    }

    /**
     *
     */
    private <K, V> Set<Cache.Entry<K, V>> toSet(Iterator<Cache.Entry<K, V>> iter) {
        Set<Cache.Entry<K, V>> set = new HashSet<>();

        while (iter.hasNext())
            set.add(iter.next());

        return set;
    }

    /**
     * @param igniteInstanceName Name of the grid providing caches.
     * @return Session factory.
     */
    private SessionFactory startHibernate(String igniteInstanceName) {
        Configuration cfg = hibernateConfiguration(igniteInstanceName);

        StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();

        builder.applySetting("hibernate.connection.url", CONNECTION_URL);
        builder.applySetting("hibernate.show_sql", false);
        builder.applySettings(cfg.getProperties());

        return cfg.buildSessionFactory(builder.build());
    }

    /**
     * Test Hibernate entity1.
     */
    @jakarta.persistence.Entity
    @SuppressWarnings({"PublicInnerClass", "UnnecessaryFullyQualifiedName"})
    @Cacheable
    @org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
    public static class Entity1 {
        /** */
        private int id;

        /**
         * @return ID.
         */
        @Id
        @GeneratedValue
        public int getId() {
            return id;
        }

        /**
         * @param id ID.
         */
        public void setId(int id) {
            this.id = id;
        }
    }

    /**
     * Test Hibernate entity2.
     */
    @jakarta.persistence.Entity
    @SuppressWarnings({"PublicInnerClass", "UnnecessaryFullyQualifiedName"})
    @Cacheable
    @org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
    public static class Entity2 {
        /** */
        private int id;

        /**
         * @return ID.
         */
        @Id
        @GeneratedValue
        public int getId() {
            return id;
        }

        /**
         * @param id ID.
         */
        public void setId(int id) {
            this.id = id;
        }
    }

    /**
     * Test Hibernate entity3.
     */
    @jakarta.persistence.Entity
    @SuppressWarnings({"PublicInnerClass", "UnnecessaryFullyQualifiedName"})
    @Cacheable
    @org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
    public static class Entity3 {
        /** */
        private int id;

        /**
         * @return ID.
         */
        @Id
        @GeneratedValue
        public int getId() {
            return id;
        }

        /**
         * @param id ID.
         */
        public void setId(int id) {
            this.id = id;
        }
    }

    /**
     * Test Hibernate entity4.
     */
    @jakarta.persistence.Entity
    @SuppressWarnings({"PublicInnerClass", "UnnecessaryFullyQualifiedName"})
    @Cacheable
    @org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
    public static class Entity4 {
        /** */
        private int id;

        /**
         * @return ID.
         */
        @Id
        @GeneratedValue
        public int getId() {
            return id;
        }

        /**
         * @param id ID.
         */
        public void setId(int id) {
            this.id = id;
        }
    }
}
