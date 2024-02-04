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

import com.arjuna.ats.arjuna.common.CoreEnvironmentBean;
import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;

import java.io.File;
import java.nio.file.Paths;
import java.util.Collections;
import javax.cache.configuration.Factory;
import jakarta.transaction.*;
import org.apache.commons.dbcp2.managed.BasicManagedDataSource;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cfg.Environment;
import org.hibernate.engine.jdbc.connections.internal.DatasourceConnectionProviderImpl;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.transaction.jta.platform.internal.AbstractJtaPlatform;
import org.hibernate.engine.transaction.jta.platform.spi.JtaPlatform;
import org.hibernate.resource.transaction.backend.jta.internal.JtaTransactionCoordinatorBuilderImpl;
import org.jetbrains.annotations.Nullable;

/**
 *
 * Tests Hibernate L2 cache with TRANSACTIONAL access mode (Hibernate and Cache are configured
 * to use the same TransactionManager).
 */
public class HibernateL2CacheTransactionalSelfTest extends HibernateL2CacheSelfTest {

    private static final TransactionManager transactionManager =  com.arjuna.ats.jta.TransactionManager.transactionManager();
    private static final UserTransaction userTransaction = com.arjuna.ats.jta.UserTransaction.userTransaction();

    private static class TestJtaPlatform extends AbstractJtaPlatform {
        /** {@inheritDoc} */

        @Override protected TransactionManager locateTransactionManager() {
            return transactionManager;
        }

        /** {@inheritDoc} */
        @Override protected UserTransaction locateUserTransaction() {
            return userTransaction;
        }
    }

    /**
     */
    @SuppressWarnings("PublicInnerClass")
    public static class TestTmFactory implements Factory<TransactionManager> {
        /** */
        private static final long serialVersionUID = 0;

        /** {@inheritDoc} */
        @Override public TransactionManager create() {
            return transactionManager;
        }
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();
        setTxLogDir();
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        super.afterTestsStopped();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.getTransactionConfiguration().setTxManagerFactory(new TestTmFactory());
        cfg.getTransactionConfiguration().setUseJtaSynchronization(useJtaSynchronization());

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected CacheConfiguration transactionalRegionConfiguration(String regionName) {
        CacheConfiguration cfg = super.transactionalRegionConfiguration(regionName);

        cfg.setNearConfiguration(null);

        return cfg;
    }

    /** {@inheritDoc} */
    @Nullable @Override protected StandardServiceRegistryBuilder registryBuilder() {
        StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();

        DatasourceConnectionProviderImpl connProvider = new DatasourceConnectionProviderImpl();

        BasicManagedDataSource dataSrc = new BasicManagedDataSource(); // JTA-aware data source.

        dataSrc.setTransactionManager(transactionManager);

        dataSrc.setDefaultAutoCommit(false);

        JdbcDataSource h2DataSrc = new JdbcDataSource();

        h2DataSrc.setURL(CONNECTION_URL);

        dataSrc.setXaDataSourceInstance(h2DataSrc);

        connProvider.setDataSource(dataSrc);

        connProvider.configure(Collections.emptyMap());

        builder.addService(ConnectionProvider.class, connProvider);

        builder.addService(JtaPlatform.class, new TestJtaPlatform());

        builder.applySetting(Environment.TRANSACTION_COORDINATOR_STRATEGY, JtaTransactionCoordinatorBuilderImpl.class.getName());

        return builder;
    }

    /** {@inheritDoc} */
    @Override protected AccessType[] accessTypes() {
        return new AccessType[]{AccessType.TRANSACTIONAL};
    }

    /**
     * @return Whether to use {@link Synchronization}.
     */
    protected boolean useJtaSynchronization() {
        return false;
    }

    /** Helper */
    private static final String TransactionManagerId = "1";

    private void setTxLogDir() throws Exception {
        getPopulator(CoreEnvironmentBean.class).setNodeIdentifier(TransactionManagerId);
        File home = Paths.get("").toAbsolutePath().toFile();;
        String logDir = new File(home, "transaction-logs").getAbsolutePath();
        getPopulator(ObjectStoreEnvironmentBean.class).setObjectStoreDir(logDir);
        getPopulator(ObjectStoreEnvironmentBean.class, "communicationStore").setObjectStoreDir(logDir);
        getPopulator(ObjectStoreEnvironmentBean.class, "stateStore").setObjectStoreDir(logDir);
    }

    private <T> T getPopulator(Class<T> beanClass) {
        return BeanPopulator.getDefaultInstance(beanClass);
    }

    private <T> T getPopulator(Class<T> beanClass, String name) {
        return BeanPopulator.getNamedInstance(beanClass, name);
    }
}
