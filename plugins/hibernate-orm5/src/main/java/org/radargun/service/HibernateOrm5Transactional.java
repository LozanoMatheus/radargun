package org.radargun.service;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.spi.PersistenceUnitTransactionType;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;

import org.hibernate.jpa.internal.EntityManagerFactoryImpl;
import org.radargun.logging.Log;
import org.radargun.logging.LogFactory;
import org.radargun.traits.Transactional;
import org.radargun.utils.Utils;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class HibernateOrm5Transactional implements Transactional {
   private final static Log log = LogFactory.getLog(HibernateOrm5Transactional.class);
   private final static boolean trace = log.isTraceEnabled();
   private final HibernateOrm5Service service;
   private final TransactionManager tm = com.arjuna.ats.jta.TransactionManager.transactionManager();
   private boolean initialized = false;
   private boolean jta;

   public HibernateOrm5Transactional(HibernateOrm5Service service, int transactionTimeout) {
      this.service = service;
      if (transactionTimeout > 0) {
         try {
            tm.setTransactionTimeout(transactionTimeout);
         } catch (SystemException e) {
            log.error("Failed to set transaction timeout", e);
         }
      }
   }

   private void init() {
      if (initialized) return;
      if (!service.isRunning()) {
         throw new IllegalStateException("init() can be called only when the service is started");
      }
      EntityManagerFactory emf = service.getEntityManagerFactory();
      try {
         // TODO: find getter somewhere
         jta = PersistenceUnitTransactionType.JTA.equals(Utils.getField(EntityManagerFactoryImpl.class, emf, "transactionType"));
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
      initialized = true;
   }

   @Override
   public Configuration getConfiguration(String resourceName) {
      return Configuration.TRANSACTIONAL;
   }

   @Override
   public Transaction getTransaction() {
      init();
      return new Transaction();
   }

   private class Transaction implements Transactional.Transaction {
      private EntityManager entityManger;

      @Override
      public <T> T wrap(T resource) {
         if (resource instanceof EntityManager) {
            this.entityManger = (EntityManager) resource;
         } else {
            throw new IllegalArgumentException("Cannot wrap " + resource);
         }
         return resource;
      }

      @Override
      public void begin() {
         if (trace) log.trace("BEGIN");
         if (jta) {
            try {
               tm.begin();
            } catch (Exception e) {
               throw new RuntimeException("Cannot begin transaction", e);
            }
            entityManger.joinTransaction();
         } else {
            entityManger.getTransaction().begin();
         }
      }

      @Override
      public void commit() {
         if (trace) log.trace("COMMIT");
         try {
            if (jta) {
               try {
                  tm.commit();
               } catch (Exception e) {
                  throw new RuntimeException("Cannot commit transaction", e);
               }
            } else {
               entityManger.getTransaction().commit();
            }
         } catch (RuntimeException e) {
            log.trace("COMMIT failed");
            throw e;
         }
      }

      @Override
      public void rollback() {
         if (trace) log.trace("ROLLBACK");
         try {
            if (jta) {
               try {
                  tm.rollback();
               } catch (Exception e) {
                  throw new RuntimeException("Cannot rollback transaction", e);
               }
            } else {
               entityManger.getTransaction().rollback();
            }
         } catch (RuntimeException e) {
            log.trace("ROLLBACK failed");
            throw e;
         }
      }
   }
}
