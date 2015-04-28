package org.radargun.stages;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.radargun.DistStageAck;
import org.radargun.Operation;
import org.radargun.StageResult;
import org.radargun.config.Property;
import org.radargun.config.PropertyDelegate;
import org.radargun.config.Stage;
import org.radargun.jpa.EntityGenerator;
import org.radargun.reporting.Report;
import org.radargun.stages.test.OperationLogic;
import org.radargun.stages.test.OperationSelector;
import org.radargun.stages.test.SchedulingOperationSelector;
import org.radargun.stages.test.Stressor;
import org.radargun.stages.test.VaryingThreadsTestStage;
import org.radargun.state.SlaveState;
import org.radargun.stats.Statistics;
import org.radargun.traits.InjectTrait;
import org.radargun.traits.InternalsExposition;
import org.radargun.traits.JpaProvider;
import org.radargun.traits.Transactional;
import org.radargun.utils.MinMax;
import org.radargun.utils.Projections;
import org.radargun.utils.TimeConverter;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Stage(doc = "Tests create-read-update-delete operations with JPA entities.")
public class CrudOperationsScheduledStage extends VaryingThreadsTestStage {
   private final boolean trace = log.isTraceEnabled();

   @Property(doc = "Generator of the entities", complexConverter = EntityGenerator.Converter.class)
   protected EntityGenerator entityGenerator;

   @PropertyDelegate(prefix = "createTxs.")
   protected InvocationSetting createTxs = new InvocationSetting();

   @PropertyDelegate(prefix = "readTxs.")
   protected InvocationSetting readTxs = new InvocationSetting();

   @PropertyDelegate(prefix = "updateTxs.")
   protected InvocationSetting updateTxs = new InvocationSetting();

   @PropertyDelegate(prefix = "deleteTxs.")
   protected InvocationSetting deleteTxs = new InvocationSetting();

   @Property(doc = "Max number of identifiers returned within one id update query. Default is 1000")
   protected int queryMaxResults = 1000;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   protected JpaProvider jpaProvider;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   protected Transactional transactional;

   @InjectTrait
   protected InternalsExposition internalsExposition;

   private EntityManagerFactory entityManagerFactory;
   private AtomicReferenceArray loadedIds;
   private QueryThread queryThread;

   private static class InvocationSetting {
      @Property(doc = "Number of invocations of given operation per interval (see property interval), on each node. Default is 0.")
      int invocations = 0;

      @Property(doc = "Size of the slot in milliseconds. Raising this risks having bursts" +
            "at the beginning of the interval. Default is 1 ms.", converter = TimeConverter.class)
      long interval = 1;
   }

   @Override
   public void init() {
      if (totalThreads > 0)
         throw new IllegalArgumentException("Cannot set total-threads on this stage.");
      if (numThreadsPerNode > 0)
         throw new IllegalArgumentException("Cannot set num-threads-per-node on this stage.");
   }

   @Override
   protected OperationSelector createOperationSelector() {
      return new SchedulingOperationSelector.Builder()
            .add(JpaInvocations.CREATE, createTxs.invocations, (int) createTxs.interval)
            .add(JpaProvider.FIND, readTxs.invocations, (int) readTxs.interval)
            .add(JpaInvocations.UPDATE, updateTxs.invocations, (int) updateTxs.interval)
            .add(JpaProvider.REMOVE, deleteTxs.invocations, (int) deleteTxs.interval)
            .build();
   }

   @Override
   protected void prepare() {
      if (entityGenerator == null) {
         entityGenerator = (EntityGenerator) slaveState.get(EntityGenerator.ENTITY_GENERATOR);
         if (entityGenerator == null) {
            throw new IllegalStateException("Entity generator was not specified and no entity generator was used before.");
         }
      } else {
         slaveState.put(EntityGenerator.ENTITY_GENERATOR, entityGenerator);
      }

      entityManagerFactory = jpaProvider.getEntityManagerFactory();

      int numEntries = JpaUtils.getNumEntries(entityManagerFactory, transactional, entityGenerator.entityClass());
      loadedIds = new AtomicReferenceArray(numEntries);
      queryThread = new QueryThread(this, entityGenerator.entityClass(), loadedIds, jpaProvider, transactional, queryMaxResults);
      log.infof("Database contains %d entities", numEntries);
      queryThread.dirtyUpdateLoadedIds();
      log.info("First update finished");
      queryThread.start();

      if (internalsExposition != null) {
         internalsExposition.resetCustomStatistics("second-level-cache:" + entityGenerator.entityClass().getName());
      }
   }

   @Override
   protected void destroy() {
      try {
         queryThread.join();
      } catch (InterruptedException e) {
         log.error("Failed to join updater thread", e);
      }
   }

   @Override
   public OperationLogic getLogic() {
      return new CrudLogic();
   }

   @Override
   public boolean isSingleTxType() {
      return true;
   }

   @Override
   protected CrudAck newStatisticsAck(List<Stressor> stressors) {
      long cacheHits = 0, cacheMisses = 0, cacheEntries = 0;
      if (internalsExposition != null) {
         String prefix = "second-level-cache:" + entityGenerator.entityClass().getName() + ":";
         cacheHits = parse(internalsExposition.getCustomStatistics(prefix + "hits"));
         cacheMisses = parse(internalsExposition.getCustomStatistics(prefix + "misses"));
         cacheEntries = parse(internalsExposition.getCustomStatistics(prefix + "numberOfEntries"));
      }
      VaryingThreadsStatisticsAck ack = super.newStatisticsAck(stressors);
      return new CrudAck(slaveState, ack.iterations, ack.usedThreads, cacheHits, cacheMisses, cacheEntries);
   }

   private long parse(String str) {
      if (str == null) return 0;
      return Long.parseLong(str);
   }

   @Override
   public StageResult processAckOnMaster(List<DistStageAck> acks) {
      StageResult result = super.processAckOnMaster(acks);
      if (result.isError()) return result;
      MinMax.Double hitRatio = new MinMax.Double();
      MinMax.Long numEntries = new MinMax.Long();
      Map<Integer, Report.SlaveResult> hitRatios = new HashMap<>();
      Map<Integer, Report.SlaveResult> numEntriesPerSlave = new HashMap<>();
      DecimalFormat formatter = new DecimalFormat("##.#%");
      for (CrudAck ack : Projections.instancesOf(acks, CrudAck.class)) {
         double ratio = 0;
         if (ack.cacheHits + ack.cacheMisses != 0) {
            ratio = (double) ack.cacheHits / (double) (ack.cacheHits + ack.cacheMisses);
            hitRatio.add(ratio);
         }
         hitRatios.put(ack.getSlaveIndex(), new Report.SlaveResult(formatter.format(ratio), false));

         numEntries.add(ack.cacheEntries);
         numEntriesPerSlave.put(ack.getSlaveIndex(), new Report.SlaveResult(String.valueOf(ack.cacheEntries), false));
      }
      Report.Test test = getTest(true);
      if (test != null) {
         test.addResult(getTestIteration(), new Report.TestResult("Hit ratio", hitRatios, hitRatio.toString(formatter), false));
         test.addResult(getTestIteration(), new Report.TestResult("Local cache entries", numEntriesPerSlave, numEntries.toString(), false));
      } else {
         log.info("No test name - results are not recorded");
      }
      return result;
   }

   private static class CrudAck extends VaryingThreadsStatisticsAck {
      private final long cacheHits;
      private final long cacheMisses;
      private final long cacheEntries;

      protected CrudAck(SlaveState slaveState, List<List<Statistics>> iterations, int usedThreads, long cacheHits, long cacheMisses, long cacheEntries) {
         super(slaveState, iterations, usedThreads);
         this.cacheHits = cacheHits;
         this.cacheMisses = cacheMisses;
         this.cacheEntries = cacheEntries;
      }
   }

   private class CrudLogic extends OperationLogic {
      private EntityManager entityManager;

      @Override
      public void init(Stressor stressor) {
         super.init(stressor);
         stressor.setUseTransactions(true);
      }

      @Override
      public void transactionStarted() {
         stressor.wrap(entityManager);
      }

      @Override
      public void transactionEnded() {
         entityManager.clear();
         entityManager.close();
         entityManager = null;
      }

      @Override
      public void run(Operation operation) throws RequestException {
         if (entityManager == null) {
            entityManager = entityManagerFactory.createEntityManager();
         }
         int index;
         Object id;
         Object entity;
         if (operation == JpaInvocations.CREATE) {
            for (int i = 0; i < transactionSize; ++i) {
               entity = entityGenerator.create(stressor.getRandom());
               stressor.makeRequest(new JpaInvocations.Create(entityManager, entity));
            }
         } else if (operation == JpaProvider.FIND) {
            for (int i = 0; i < transactionSize; ++i) {
               index = stressor.getRandom().nextInt(loadedIds.length());
               id = getIdNotNull(index);
               stressor.makeRequest(new JpaInvocations.Find(entityManager, entityGenerator.entityClass(), id));
            }
         } else if (operation == JpaInvocations.UPDATE) {
            for (int i = 0; i < transactionSize; ++i) {
               do {
                  index = stressor.getRandom().nextInt(loadedIds.length());
                  id = getIdNotNull(index);
                  entity = stressor.makeRequest(new JpaInvocations.Find(entityManager, entityGenerator.entityClass(), id), false);
               } while (entity == null);
               entityGenerator.mutate(entity, stressor.getRandom());
               stressor.makeRequest(new JpaInvocations.Update(entityManager, entity));
            }
         } else if (operation == JpaProvider.REMOVE) {
            for (int i = 0; i < transactionSize; ++i) {
               do {
                  // When two stressors try to remove the same entity concurrenlty, the whole
                  // transactions will fail. Therefore, even with the same rate of creates
                  // and removals, usually there are more entries created than removed.
                  // QueryThread stores this surplus and it should be removed before regular ids.
                  id = queryThread.getNextOverflowId();
                  if (id == null) {
                     index = stressor.getRandom().nextInt(loadedIds.length());
                     id = getIdNotNull(index);
                  }
                  entity = stressor.makeRequest(new JpaInvocations.Find(entityManager, entityGenerator.entityClass(), id), false);
               } while (entity == null);
               stressor.makeRequest(new JpaInvocations.Remove(entityManager, entity));
            }
         } else {
            throw new IllegalArgumentException("Unexpected operation: " + operation);
         }
      }

      private Object getIdNotNull(int index) {
         int initialIndex = index;
         Object id;
         while (!terminated) {
            id = loadedIds.get(index);
            if (id != null) {
               return id;
            }
            index = (index + 1) % loadedIds.length();
            if (index == initialIndex) {
               throw new RuntimeException("No set id!");
            }
         }
         throw new RuntimeException("Test was terminated");
      }
   }

}
