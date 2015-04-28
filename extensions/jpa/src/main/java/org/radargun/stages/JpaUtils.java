package org.radargun.stages;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaDelete;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

import org.radargun.traits.JpaProvider;
import org.radargun.traits.Transactional;

/**
 * Common JPA operations
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class JpaUtils {
   public static int getNumEntries(EntityManagerFactory entityManagerFactory, Transactional transactional, Class entityClazz) {
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

   public static void dropEntities(JpaProvider jpaProvider, Transactional transactional, Class<?> targetEntity) {
      EntityManagerFactory emf = jpaProvider.getEntityManagerFactory();
      CriteriaBuilder cb = emf.getCriteriaBuilder();
      EntityManager em = emf.createEntityManager();
      Transactional.Transaction tx = transactional.getTransaction();
      tx.wrap(em);
      tx.begin();
      try {
         CriteriaDelete criteriaDelete = cb.createCriteriaDelete(targetEntity);
         criteriaDelete.from(targetEntity);
         em.createQuery(criteriaDelete).executeUpdate();
         tx.commit();
      } finally {
         em.close();
      }
   }
}
