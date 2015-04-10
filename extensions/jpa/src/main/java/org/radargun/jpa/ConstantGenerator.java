package org.radargun.jpa;

import java.util.Random;

import org.radargun.config.DefinitionElement;
import org.radargun.config.Property;
import org.radargun.jpa.entities.Constant;
import org.radargun.utils.RandomHelper;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@DefinitionElement(name = "constant", doc = "Immutable entity with simple string")
public class ConstantGenerator implements EntityGenerator<Constant> {
   @Property(doc = "Length of the contained string")
   int length;

   @Override
   public Constant create(Random random) {
      return new Constant(RandomHelper.randomString(length, length, random));
   }

   @Override
   public void mutate(Constant entity, Random random) {
      throw new UnsupportedOperationException();
   }

   @Override
   public Class<Constant> entityClass() {
      return Constant.class;
   }

   @Override
   public boolean hasGeneratedId() {
      return true;
   }

   @Override
   public boolean hasForeignKeys() {
      return false;
   }
}
