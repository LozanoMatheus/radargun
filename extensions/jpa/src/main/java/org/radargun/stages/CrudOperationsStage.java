package org.radargun.stages;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.radargun.Operation;
import org.radargun.config.Property;
import org.radargun.config.Stage;
import org.radargun.jpa.EntityGenerator;
import org.radargun.stages.test.OperationLogic;
import org.radargun.stages.test.Stressor;
import org.radargun.stages.test.TestStage;
import org.radargun.traits.InjectTrait;
import org.radargun.traits.JpaProvider;
import org.radargun.traits.Transactional;
import org.radargun.utils.Selector;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Stage(doc = "Tests create-read-update-delete operations with JPA entities.")
public class CrudOperationsStage extends TestStage {
   @Property(doc = "Generator of the entities", complexConverter = EntityGenerator.Converter.class)
   protected EntityGenerator entityGenerator;

   @Property(doc = "Number of threads creating new entities on one node. Default is 0.")
   protected int numCreatorThreadsPerNode = 0;

   @Property(doc = "Number of threads reading data on one node. Default is 0.")
   protected int numReaderThreadsPerNode = 0;

   @Property(doc = "Number of threads reading data on one node. Default is 0.")
   protected int numUpdaterThreadsPerNode = 0;

   @Property(doc = "Number of threads removing data on one node. Default is 0")
   protected int numDeleterThreadsPerNode = 0;

   @Property(doc = "Max number of identifiers returned within one id update query. Default is 1000")
   protected int queryMaxResults = 1000;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   protected JpaProvider jpaProvider;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   protected Transactional transactional;

   private EntityManagerFactory entityManagerFactory;
   private AtomicReferenceArray loadedIds;
   private QueryThread queryThread;
   private Selector<StressorType> threadTypeSelector;

   @Override
   public void init() {
      if (totalThreads > 0)
         throw new IllegalArgumentException("Cannot set total-threads on this stage.");
      if (numThreadsPerNode > 0)
         throw new IllegalArgumentException("Cannot set num-threads-per-node on this stage.");
      numThreadsPerNode = numCreatorThreadsPerNode + numReaderThreadsPerNode + numUpdaterThreadsPerNode + numDeleterThreadsPerNode;
      if (numThreadsPerNode <= 0)
         throw new IllegalArgumentException("You have to set num-(creator|reader|updater|deleter)-thread-per-node");
   }

   @Override
   public Map<String, Object> createMasterData() {
      return Collections.singletonMap(LoadEntitiesStage.LOADED_IDS, masterState.get(LoadEntitiesStage.LOADED_IDS));
   }

   @Override
   protected void prepare() {
      threadTypeSelector = new Selector.Builder<>(StressorType.class)
//            .add(StressorType.CREATE, numCreatorThreadsPerNode)
            .add(StressorType.READ, numReaderThreadsPerNode)
            .add(StressorType.UPDATE, numUpdaterThreadsPerNode)
//            .add(StressorType.DELETE, numDeleterThreadsPerNode)
            .add(StressorType.DELETE_AND_CREATE, numCreatorThreadsPerNode + numDeleterThreadsPerNode)
            .build();

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

   private enum StressorType {
      //CREATE,
      READ,
      UPDATE,
      //DELETE,
      DELETE_AND_CREATE
   }

   private class CrudLogic extends OperationLogic {
      private EntityManager entityManager;
      private StressorType stressorType;

      @Override
      public void init(Stressor stressor) {
         super.init(stressor);
         stressor.setUseTransactions(true);
         stressorType = threadTypeSelector.select(stressor.getThreadIndex());
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
         try {
            switch (stressorType) {
//            case CREATE:
//               entity = entityGenerator.create(stressor.getRandom());
//               stressor.makeRequest(new JpaInvocations.Create(entityManager, entity));
//               break;
               case READ:
                  index = stressor.getRandom().nextInt(loadedIds.length());
                  id = getIdNotNull(index);
                  stressor.makeRequest(new JpaInvocations.Find(entityManager, entityGenerator.entityClass(), id));
                  break;
               case UPDATE:
                  do {
                     index = stressor.getRandom().nextInt(loadedIds.length());
                     id = getIdNotNull(index);
                     entity = stressor.makeRequest(new JpaInvocations.Find(entityManager, entityGenerator.entityClass(), id), false);
                  } while (entity == null);
                  entityGenerator.mutate(entity, stressor.getRandom());
                  stressor.makeRequest(new JpaInvocations.Update(entityManager, entity));
                  break;
//            case DELETE:
//               do {
//                  index = stressor.getRandom().nextInt(loadedIds.length());
//                  id = getIdNotNull(index);
//                  entity = stressor.makeRequest(new JpaInvocations.Find(entityManager, entityGenerator.entityClass(), id), false);
//               } while (entity == null);
//               stressor.makeRequest(new JpaInvocations.Remove(entityManager, entity));
//               break;
               case DELETE_AND_CREATE:
                  do {
                     index = stressor.getRandom().nextInt(loadedIds.length());
                     id = getIdNotNull(index);
                     entity = stressor.makeRequest(new JpaInvocations.Find(entityManager, entityGenerator.entityClass(), id), false);
                  } while (entity == null);
                  stressor.makeRequest(new JpaInvocations.Remove(entityManager, entity), false);
                  entity = entityGenerator.create(stressor.getRandom());
                  stressor.makeRequest(new JpaInvocations.Create(entityManager, entity));
                  break;
            }
         } finally {
            if (!stressor.isUseTransactions()) {
               entityManager.close();
            }
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
