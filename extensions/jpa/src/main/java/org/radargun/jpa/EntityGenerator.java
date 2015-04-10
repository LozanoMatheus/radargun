package org.radargun.jpa;

import java.util.Random;

import org.radargun.utils.ReflexiveConverters;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public interface EntityGenerator<T> {
   static final String ENTITY_GENERATOR = "ENTITY_GENERATOR";

   /**
    * @return New instance of this entity.
    */
   T create(Random random);

   /**
    * Mutate given entity, changing some properties
    *
    * @param entity
    * @param random
    */
   void mutate(T entity, Random random);

   /**
    * @return Class that this generator creates
    */
   Class<T> entityClass();

   /**
    * @return True if the ID of this entity is automatically generated.
    */
   boolean hasGeneratedId();

   /**
    * @return True if this entity contains reference to other entities
    */
   boolean hasForeignKeys();

   static class Converter extends ReflexiveConverters.ObjectConverter {
      public Converter() {
         super(EntityGenerator.class);
      }
   }
}
