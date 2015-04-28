package org.radargun.stages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReferenceArray;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Root;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;

import org.radargun.logging.Log;
import org.radargun.logging.LogFactory;
import org.radargun.stages.test.TestStage;
import org.radargun.traits.JpaProvider;
import org.radargun.traits.Transactional;

/**
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
class QueryThread extends Thread {
   private final static Log log = LogFactory.getLog(QueryThread.class);
   private final static boolean trace = log.isTraceEnabled();

   private final AtomicReferenceArray loadedIds;
   private final HashMap<Object, EntityRecord> idToIndex = new HashMap<>();
   private final TestStage stage;
   private final JpaProvider jpaProvider;
   private final Transactional transactional;
   private final int queryMaxResults;
   private final Class<?> entityClazz;
   private SingularAttribute idProperty;
   private final int executingSlaves;
   private final int myIndex;
   private final ThreadLocalRandom random = ThreadLocalRandom.current();
   private volatile ConcurrentLinkedQueue<Object> overflowIds = new ConcurrentLinkedQueue<>();

   public QueryThread(TestStage stage, Class<?> entityClazz, AtomicReferenceArray loadedIds, JpaProvider jpaProvider, Transactional transactional, int queryMaxResults) {
      super("QueryUpdater");
      this.stage = stage;
      this.loadedIds = loadedIds;
      this.jpaProvider = jpaProvider;
      this.transactional = transactional;
      this.queryMaxResults = queryMaxResults;
      this.entityClazz = entityClazz;

      executingSlaves = stage.getExecutingSlaves().size();
      myIndex = stage.getExecutingSlaveIndex();

      EntityType entityType = jpaProvider.getEntityManagerFactory().getMetamodel().entity(entityClazz);
      Set<SingularAttribute> singularAttributes = entityType.getSingularAttributes();
      for (SingularAttribute singularAttribute : singularAttributes) {
         if (singularAttribute.isId()){
            idProperty = singularAttribute;
            break;
         }
      }
   }

   @Override
   public void run() {
      while (!stage.isFinished() && !stage.isTerminated()) {
         dirtyUpdateLoadedIds();
      }
   }

   public void dirtyUpdateLoadedIds() {
      ArrayList<Object> newIds = new ArrayList<Object>();
      boolean[] found = new boolean[loadedIds.length()];

      EntityManager entityManager = jpaProvider.getEntityManagerFactory().createEntityManager();
      Transactional.Transaction tx = transactional.getTransaction();
      tx.wrap(entityManager);
      tx.begin();
      int existing = 0;
      try {
         List<Object> list;
         Object maxId = null;
         do {
            list = dirtyList(entityManager, maxId);
            for (Object id : list) {
               EntityRecord record = idToIndex.get(id);
               if (record != null) {
                  if (!found[record.index]) {
                     ++existing;
                  }
                  found[record.index] = true;
                  record.found = true;
               } else {
                  newIds.add(id);
               }
            }
            if (!list.isEmpty()) {
               maxId = list.get(list.size() - 1);
            }
         } while (list.size() == queryMaxResults);
         tx.commit();
         tx = null;
      } finally {
         if (tx != null) {
            tx.rollback();
         };
         entityManager.close();
      }
      log.debugf("Finished dirty enumeration, %d indexed ids, %d existing, %d new IDs", idToIndex.size(), existing, newIds.size());

      for (Iterator<EntityRecord> iterator = idToIndex.values().iterator(); iterator.hasNext(); ) {
         EntityRecord record = iterator.next();
         if (record.found) {
            record.found = false;
         } else {
            iterator.remove();
         }
      }

      int holeIndex = 0;
      for (int index = 0; index < found.length; ++index) {
         if (found[index]) {
            continue;
         }
         if (holeIndex < newIds.size()) {
            Object newId = newIds.get(holeIndex);
            if (trace) log.tracef("Replacing removed entry with %s on %d", newId, index);
            loadedIds.set(index, newId);
            idToIndex.put(newId, new EntityRecord(index, false));
         } else {
            if (trace) log.tracef("Nothing to replace with on %d", index);
            loadedIds.set(index, null);
         }
         holeIndex++;
      }
      if (holeIndex < newIds.size()) {
         log.debugf("Finished dirty update, %d indexed ids, %d new entities overflowing", idToIndex.size(), newIds.size() - holeIndex);
         ConcurrentLinkedQueue<Object> overflowIds = new ConcurrentLinkedQueue<>();
         while (holeIndex < newIds.size()) {
            // due to the order of iteration, we would probably delete the last entities.
            // To put random entities into the overflow, let's mix them randomly into the loadedIds and overflow the old ones
            Object id = newIds.get(holeIndex);
            int index = random.nextInt(loadedIds.length());
            Object prevId = loadedIds.getAndSet(index, id);
            idToIndex.remove(prevId);
            idToIndex.put(id, new EntityRecord(index, false));
            // insert only some ids to prevent transaction failures on other slaves
            if (prevId.hashCode() % executingSlaves == myIndex) {
               overflowIds.add(prevId);
            }
            holeIndex++;
         }
         this.overflowIds = overflowIds;
      } else {
         log.debugf("Finished dirty update, %d indexed ids, %d left empty", idToIndex.size(), holeIndex - newIds.size());
      }

   }

   private List<Object> dirtyList(EntityManager entityManager, Object maxId) {
      CriteriaBuilder cb = entityManager.getCriteriaBuilder();
      CriteriaQuery<Object> query = cb.createQuery(Object.class);
      Root root = query.from(entityClazz);
      Path idPath = root.get(idProperty);
      query.select(idPath).orderBy(cb.asc(idPath));
      if (maxId != null) {
         query.where(cb.gt(idPath, (Number) maxId));
      }
      return entityManager.createQuery(query).setLockMode(LockModeType.NONE)
            .setMaxResults(queryMaxResults).getResultList();
   }

   public Object getNextOverflowId() {
      return overflowIds.poll();
   }

   private static class EntityRecord {
      int index;
      boolean found;

      public EntityRecord(int index, boolean found) {
         this.index = index;
         this.found = found;
      }
   }
}
