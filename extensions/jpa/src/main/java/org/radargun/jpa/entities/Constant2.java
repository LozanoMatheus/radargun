package org.radargun.jpa.entities;

import javax.persistence.Entity;
import javax.persistence.Id;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Immutable;

/**
 * Read-only cached entity, with non-generated id.
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
@Immutable
public class Constant2 {
   @Id
   long id;

   String value;

   public Constant2() {
   }

   public Constant2(long id, String value) {
      this.id = id;
      this.value = value;
   }

   public long getId() {
      return id;
   }

   public String getValue() {
      return value;
   }

   @Override
   public String toString() {
      final StringBuilder sb = new StringBuilder("Constant{");
      sb.append("id=").append(id);
      sb.append(", value='").append(value).append('\'');
      sb.append('}');
      return sb.toString();
   }
}
