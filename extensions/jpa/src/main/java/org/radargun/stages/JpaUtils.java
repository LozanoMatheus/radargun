package org.radargun.stages;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

import org.radargun.traits.Transactional;

/**
 * // TODO: Document this
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class JpaUtils {
   static int getNumEntries(EntityManagerFactory entityManagerFactory, Transactional transactional, Class entityClazz) {
      EntityManager entityManager = entityManagerFactory.createEntityManager();
      Transactional.Transaction tx = transactional.getTransaction();
      tx.wrap(entityManager);
      tx.begin();
      try {
         CriteriaBuilder cb = entityManager.getCriteriaBuilder();
         CriteriaQuery<Long> query = cb.createQuery(Long.class);
         Root root = query.from(entityClazz);
         query = query.select(cb.count(root));
         return entityManager.createQuery(query).getSingleResult().intValue();
      } finally {
         tx.commit();
         entityManager.close();
      }
   }
}
