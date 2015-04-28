package org.radargun.stages;

import org.radargun.DistStageAck;
import org.radargun.config.Property;
import org.radargun.config.Stage;
import org.radargun.jpa.EntityGenerator;
import org.radargun.traits.InjectTrait;
import org.radargun.traits.JpaProvider;
import org.radargun.traits.Transactional;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Stage(doc = "Removes all entities from database.")
public class DropEntitiesStage extends AbstractDistStage {
   @Property(doc = "Entity generator used for class selection.", complexConverter = EntityGenerator.Converter.class)
   EntityGenerator entityGenerator;

   @Property(name = "class", doc = "Type of entity that should be removed.")
   String clazzName;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   JpaProvider jpaProvider;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   Transactional transactional;

   @Override
   public DistStageAck executeOnSlave() {
      Class<?> targetEntity = null;
      if (clazzName == null) {
         if (entityGenerator == null) {
            entityGenerator = (EntityGenerator) slaveState.get(EntityGenerator.ENTITY_GENERATOR);
         }
         if (entityGenerator == null) {
            return errorResponse("No entity class/generator specified, and none used before.");
         }
         targetEntity = entityGenerator.entityClass();
      } else {
         try {
            targetEntity = Class.forName(clazzName);
         } catch (ClassNotFoundException e) {
            return errorResponse("Cannot find entity class", e);
         }
      }
      JpaUtils.dropEntities(jpaProvider, transactional, targetEntity);
      return successfulResponse();
   }

}
