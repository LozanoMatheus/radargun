package org.radargun.stages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
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

   public QueryThread(TestStage stage, Class<?> entityClazz, AtomicReferenceArray loadedIds, JpaProvider jpaProvider, Transactional transactional, int queryMaxResults) {
      super("QueryUpdater");
      this.stage = stage;
      this.loadedIds = loadedIds;
      this.jpaProvider = jpaProvider;
      this.transactional = transactional;
      this.queryMaxResults = queryMaxResults;
      this.entityClazz = entityClazz;

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
      Root root = query.from(entityClazz);
      Path idPath = root.get(idProperty);
      query.select(idPath);
      return entityManager.createQuery(query).setLockMode(LockModeType.NONE)
            .setFirstResult(offset).setMaxResults(maxResults)
            .getResultList();
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
