package org.radargun.stages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceUnitUtil;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Root;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;

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
   private final boolean trace = log.isTraceEnabled();
   private final Operation UPDATE_QUERY = JpaProvider.QUERY.derive("PagedAll");

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
   private SingularAttribute idProperty;
   // used only by the query thread
   private HashMap<Object, EntityRecord> idToIndex = new HashMap<>();
   private AtomicReferenceArray loadedIds;
   private Thread queryThread;
   private Selector<StressorType> threadTypeSelector;

   private static class EntityRecord {
      int index;
      boolean found;

      public EntityRecord(int index, boolean found) {
         this.index = index;
         this.found = found;
      }
   }

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
      EntityType entityType = entityManagerFactory.getMetamodel().entity(entityGenerator.entityClass());
      Set<SingularAttribute> singularAttributes = entityType.getSingularAttributes();
      for (SingularAttribute singularAttribute : singularAttributes) {
         if (singularAttribute.isId()){
            idProperty=singularAttribute;
            break;
         }
      }

      int numEntries = getNumEntries();

      loadedIds = new AtomicReferenceArray(numEntries);
      log.infof("Database contains %d entities", numEntries);
      dirtyUpdateLoadedIds();
      log.info("First update finished");

      queryThread = new Thread(new Runnable() {
         @Override
         public void run() {
            while (!finished && !terminated) {
               dirtyUpdateLoadedIds();
            }
         }
      }, "Updater");
      queryThread.start();
   }

   private void dirtyUpdateLoadedIds() {
      ArrayList<Object> newIds = new ArrayList<Object>();
      boolean[] found = new boolean[loadedIds.length()];

      EntityManager entityManager = entityManagerFactory.createEntityManager();
      Transactional.Transaction tx = transactional.getTransaction();
      tx.wrap(entityManager);
      tx.begin();
      int existing = 0;
      try {
         for (int offset = 0;; offset += queryMaxResults) {
            List<Object> list = dirtyList(entityManager, offset, queryMaxResults);
            for (Object id : list) {
               EntityRecord record = idToIndex.get(id);
               if (record != null) {
                  found[record.index] = true;
                  record.found = true;
                  ++existing;
               } else {
                  newIds.add(id);
               }
            }
            if (list.size() < queryMaxResults) break;
         }
      } finally {
         tx.commit();
         entityManager.close();
      }
      log.debugf("Finished dirty enumeration, %d existing, %d new IDs", existing, newIds.size());

      for (Iterator<EntityRecord> iterator = idToIndex.values().iterator(); iterator.hasNext(); ) {
         EntityRecord record = iterator.next();
         if (record.found) {
            record.found = false;
         } else {
            iterator.remove();
         }
      }

      int newIdsIndex = 0;
      for (int index = 0; index < found.length; ++index) {
         if (found[index]) {
            continue;
         }
         if (newIdsIndex < newIds.size()) {
            Object newId = newIds.get(newIdsIndex);
            if (trace) log.tracef("Replacing removed entry with %s on %d", newId, index);
            loadedIds.set(index, newId);
            idToIndex.put(newId, new EntityRecord(index, false));
         } else {
            if (trace) log.tracef("Nothing to replace with on %d", index);
            loadedIds.set(index, null);
         }
         newIdsIndex++;
      }
      if (newIdsIndex < newIds.size()) {
         log.debugf("Finished dirty update, %d new entities ignored", newIds.size() - newIdsIndex);
      } else {
         log.debugf("Finished dirty update, %d left empty", newIdsIndex - newIds.size());
      }

   }

   private List<Object> dirtyList(EntityManager entityManager, int offset, int maxResults) {
      CriteriaBuilder cb = entityManager.getCriteriaBuilder();
      CriteriaQuery<Object> query = cb.createQuery(Object.class);
      Root root = query.from(entityGenerator.entityClass());
      Path idPath = root.get(idProperty);
      query.select(idPath);
      return entityManager.createQuery(query).setLockMode(LockModeType.NONE)
            .setFirstResult(offset).setMaxResults(maxResults)
            .getResultList();
   }

   private int getNumEntries() {
      EntityManager entityManager = entityManagerFactory.createEntityManager();
      Transactional.Transaction tx = transactional.getTransaction();
      tx.wrap(entityManager);
      tx.begin();
      try {
         CriteriaBuilder cb = entityManager.getCriteriaBuilder();
         CriteriaQuery<Long> query = cb.createQuery(Long.class);
         Root root = query.from(entityGenerator.entityClass());
         query = query.select(cb.count(root));
         return entityManager.createQuery(query).getSingleResult().intValue();
      } finally {
         tx.commit();
         entityManager.close();
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

   private enum StressorType {
      //CREATE,
      READ,
      UPDATE,
      //DELETE,
      DELETE_AND_CREATE
   }

   private class CrudLogic extends OperationLogic {
      private EntityManager entityManager;
      private PersistenceUnitUtil util;
      private StressorType stressorType;

      @Override
      public void init(Stressor stressor) {
         super.init(stressor);
         entityManager = entityManagerFactory.createEntityManager();
         util = entityManagerFactory.getPersistenceUnitUtil();
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
      }

      @Override
      public void run(Operation operation) throws RequestException {
         if (entityManager == null) {
            entityManager = entityManagerFactory.createEntityManager();
         }
         int index;
         Object id;
         Object entity;
         switch (stressorType) {
//            case CREATE:
//               entity = entityGenerator.create(stressor.getRandom());
//               stressor.makeRequest(new JpaInvocations.Create(entityManager, entity));
//               break;
            case READ:
               index = stressor.getRandom().nextInt(loadedIds.length());
               id = getIdNotNull(index);
               stressor.makeRequest(new JpaInvocations.Find(entityManager, entityGenerator.entityClass(), id));
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
      }

      private Object getIdNotNull(int index) {
         Object id;
//         while (!finished && !terminated) {
         for (;;) {
            id = loadedIds.get(index);
            if (id != null) {
               return id;
            }
            index = (index + 1) % loadedIds.length();
         }
//         throw new RuntimeException("Test was terminated");
      }
   }
}
