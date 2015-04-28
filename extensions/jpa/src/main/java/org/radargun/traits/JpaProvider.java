package org.radargun.traits;

import javax.persistence.EntityManagerFactory;

import org.radargun.Operation;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Trait(doc = "Provides JPA EntityManagerFactory")
public interface JpaProvider {
   String TRAIT = JpaProvider.class.getSimpleName();
   Operation FIND = Operation.register(TRAIT + ".Find");
   Operation PERSIST = Operation.register(TRAIT + ".Persist");
   Operation REMOVE = Operation.register(TRAIT + ".Remove");
   Operation QUERY = Operation.register(TRAIT + ".Query");

   EntityManagerFactory getEntityManagerFactory();

   void clearSecondLevelCaches();
}
