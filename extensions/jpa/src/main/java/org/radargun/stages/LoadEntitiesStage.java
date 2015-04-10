package org.radargun.stages;

import java.util.List;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceUnitUtil;

import org.radargun.DistStageAck;
import org.radargun.StageResult;
import org.radargun.config.Property;
import org.radargun.config.Stage;
import org.radargun.jpa.EntityGenerator;
import org.radargun.stages.test.LoadStage;
import org.radargun.state.SlaveState;
import org.radargun.traits.InjectTrait;
import org.radargun.traits.JpaProvider;
import org.radargun.traits.Transactional;
import org.radargun.utils.Projections;
import org.radargun.utils.Utils;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Stage(doc = "Persists JPA entities")
public class LoadEntitiesStage extends LoadStage {
   static final String LOADED_IDS = "LOADED_ENTITY_IDS";

   @Property(doc = "Number of entities inserted into the cache", optional = false)
   protected long numEntites;

   @Property(doc = "Number of operations after which we should flush & clear the entity manager. Default is 100.")
   protected int batchSize = 100;

   @Property(doc = "Send all generated entities' ids to master, which will store them. Default is false.")
   protected boolean storeIds = false;

   @Property(doc = "Generator of the entities", complexConverter = EntityGenerator.Converter.class, optional = false)
   protected EntityGenerator entityGenerator;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   protected JpaProvider jpaProvider;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   protected Transactional transactional;

   private EntityManagerFactory entityManagerFactory;
   private Object[] loadedIds;

   @Override
   protected void prepare() {
      slaveState.put(EntityGenerator.ENTITY_GENERATOR, entityGenerator);
      entityManagerFactory = jpaProvider.getEntityManagerFactory();
   }

   @Override
   protected Loader createLoader(int threadBase, int threadIndex) {
      int totalThreads = getExecutingSlaves().size() * numThreads;
      int globalThreadInidex = threadBase + threadIndex;
      long entriesToLoad = (globalThreadInidex + 1) * numEntites / totalThreads - (globalThreadInidex * numEntites / totalThreads);
      if (entriesToLoad > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("Cannot load that many entities: " + entriesToLoad);
      }
      return new EntityLoader(threadIndex, (int) entriesToLoad);
   }

   @Override
   protected void stopLoaders(List<Loader> loaders) throws Exception {
      super.stopLoaders(loaders);
      if (storeIds) {
         int sumLength = 0;
         for (Loader loader : loaders) {
            sumLength += ((EntityLoader) loader).loadedIds.length;
         }
         loadedIds = new Object[sumLength];
         int index = 0;
         // TODO: optimization for primitive types?
         for (Loader loader : loaders) {
            Object[] ids = ((EntityLoader) loader).loadedIds;
            System.arraycopy(ids, 0, loadedIds, index, ids.length);
            index += ids.length;
         }
      }
   }

   @Override
   protected DistStageAck successfulResponse() {
      return new LoadEntitiesAck(slaveState, loadedIds);
   }

   protected static class LoadEntitiesAck extends DistStageAck {
      private final Object[] loadedIds;

      public LoadEntitiesAck(SlaveState slaveState, Object[] loadedIds) {
         super(slaveState);
         this.loadedIds = loadedIds;
      }
   }

   @Override
   public StageResult processAckOnMaster(List<DistStageAck> acks) {
      StageResult result = super.processAckOnMaster(acks);
      if (result.isError()) return result;
      if (storeIds) {
         int sumLength = 0;
         for (LoadEntitiesAck ack : Projections.instancesOf(acks, LoadEntitiesAck.class)) {
            if (ack.loadedIds != null) sumLength += ack.loadedIds.length;
         }
         loadedIds = new Object[sumLength];
         int index = 0;
         for (LoadEntitiesAck ack : Projections.instancesOf(acks, LoadEntitiesAck.class)) {
            if (ack.loadedIds != null) {
               System.arraycopy(ack.loadedIds, 0, loadedIds, index, ack.loadedIds.length);
               index += ack.loadedIds.length;
            }
         }
         masterState.put(LOADED_IDS, loadedIds);
      }
      return result;
   }

   protected class EntityLoader extends Loader {
      private final EntityManager entityManager;
      private final PersistenceUnitUtil util;
      private final long entriesToLoad;
      private final Object[] loadedIds;

      private Transactional.Transaction tx;
      private int txCurrentSize;
      private int txAttempts;
      private long txBeginSeed;
      private int committedEntries;

      protected EntityLoader(int index, int entriesToLoad) {
         super(index);
         entityManager = entityManagerFactory.createEntityManager();
         util = entityManagerFactory.getPersistenceUnitUtil();
         this.entriesToLoad = entriesToLoad;
         this.loadedIds = storeIds ? new Object[entriesToLoad] : null;
      }

      @Override
      protected boolean loadEntry() {
         if (tx == null) {
            tx = transactional.getTransaction();
            tx.wrap(entityManager);
            txBeginSeed = Utils.getRandomSeed(random);
            try {
               tx.begin();
            } catch (Exception e) {
               log.error("Begin failed");
               throw e;
            }
         }
         Object entity = entityGenerator.create(random);
         try {
            entityManager.persist(entity);
            if (storeIds) {
               loadedIds[committedEntries + txCurrentSize] = util.getIdentifier(entity);
            }
            txCurrentSize++;
         } catch (Exception e) {
            log.warnf(e, "Attempt %d/%d to persist entity failed, waiting %d ms before next attempt",
                  txAttempts + 1, maxLoadAttempts, waitOnError);
            try {
               tx.rollback();
            } catch (Exception re) {
               log.error("Failed to rollback transaction", re);
            }
            restartTx();
            try {
               Thread.sleep(waitOnError);
            } catch (InterruptedException e1) {
               log.warn("Interrupted when waiting after failed operation", e1);
            }
            return true;
         }
         if (txCurrentSize >= batchSize || (committedEntries + txCurrentSize >= entriesToLoad)) {
            try {
               tx.commit();
               logLoaded(txCurrentSize, 0, false);
               committedEntries += txCurrentSize;
               txAttempts = 0;
               txCurrentSize = 0;
               tx = null;
            } catch (Exception e) {
               log.error("Failed to commit transaction", e);
               restartTx();
               return true;
            }
            if (committedEntries >= entriesToLoad) {
               log.info("Finished loading entries");
               return false;
            }
         }
         return true;
      }

      private void restartTx() {
         Utils.setRandomSeed(random, txBeginSeed);
         txCurrentSize = 0;
         tx = null;
         txAttempts++;
         if (txAttempts >= maxLoadAttempts) {
            throw new RuntimeException("Failed to commit transaction " + maxLoadAttempts + " times");
         }
      }
   }
}
