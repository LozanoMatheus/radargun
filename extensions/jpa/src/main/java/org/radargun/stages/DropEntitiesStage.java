package org.radargun.stages;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaDelete;

import org.radargun.DistStageAck;
import org.radargun.config.Property;
import org.radargun.config.Stage;
import org.radargun.traits.InjectTrait;
import org.radargun.traits.JpaProvider;
import org.radargun.traits.Transactional;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Stage(doc = "Removes all entities from database.")
public class DropEntitiesStage extends AbstractDistStage {
   @Property(name = "class", doc = "Type of entity that should be removed.", optional = false)
   String clazzName;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   JpaProvider jpaProvider;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   Transactional transactional;

   @Override
   public DistStageAck executeOnSlave() {
      Class<?> targetEntity = null;
      try {
         targetEntity = Class.forName(clazzName);
      } catch (ClassNotFoundException e) {
         return errorResponse("Cannot find entity class", e);
      }
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
      return successfulResponse();
   }
}
