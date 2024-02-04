package org.apache.ignite.internal.processors.cache;

import com.arjuna.ats.arjuna.common.CoreEnvironmentBean;
import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;

import java.io.File;
import java.nio.file.Paths;

public class TxLogConfig {

    /** Helper */
    private static final String TransactionManagerId = "1";

    public static void setTxLogDir() throws Exception {
        getPopulator(CoreEnvironmentBean.class).setNodeIdentifier(TransactionManagerId);
        File home = Paths.get("").toAbsolutePath().toFile();;
        String logDir = new File(home, "transaction-logs").getAbsolutePath();
        getPopulator(ObjectStoreEnvironmentBean.class).setObjectStoreDir(logDir);
        getPopulator(ObjectStoreEnvironmentBean.class, "communicationStore").setObjectStoreDir(logDir);
        getPopulator(ObjectStoreEnvironmentBean.class, "stateStore").setObjectStoreDir(logDir);
    }

    private static <T> T getPopulator(Class<T> beanClass) {
        return BeanPopulator.getDefaultInstance(beanClass);
    }

    private static <T> T getPopulator(Class<T> beanClass, String name) {
        return BeanPopulator.getNamedInstance(beanClass, name);
    }
}
