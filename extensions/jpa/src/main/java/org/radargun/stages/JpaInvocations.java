package org.radargun.stages;

import javax.persistence.EntityManager;

import org.radargun.Operation;
import org.radargun.logging.Log;
import org.radargun.logging.LogFactory;
import org.radargun.stages.test.Invocation;
import org.radargun.traits.JpaProvider;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class JpaInvocations {
   private static final Log log = LogFactory.getLog(JpaInvocations.class);
   private static final boolean trace = log.isTraceEnabled();

   public static class Find implements Invocation {
      private final static Operation TX = JpaProvider.FIND.derive("TX");
      private final EntityManager entityManager;
      private final Class clazz;
      private final Object id;

      public Find(EntityManager entityManager, Class clazz, Object id) {
         this.entityManager = entityManager;
         this.clazz = clazz;
         this.id = id;
      }

      @Override
      public Object invoke() {
         if (trace) log.tracef("FIND %s: %s", clazz, id);
         return entityManager.find(clazz, id);
      }

      @Override
      public Operation operation() {
         return JpaProvider.FIND;
      }

      @Override
      public Operation txOperation() {
         return TX;
      }
   }

   public static class Persist implements Invocation {
      private final static Operation TX = JpaProvider.PERSIST.derive("TX");
      private final EntityManager entityManager;
      private final Object entity;

      public Persist(EntityManager entityManager, Object entity) {
         this.entityManager = entityManager;
         this.entity = entity;
      }

      @Override
      public Object invoke() {
         if (trace) log.tracef("PERSIST %s", entity);
         entityManager.persist(entity);
         return null;
      }

      @Override
      public Operation operation() {
         return JpaProvider.PERSIST;
      }

      @Override
      public Operation txOperation() {
         return TX;
      }
   }

   public static class Create extends Persist {
      private final static Operation CREATE = JpaProvider.PERSIST.derive("Create");
      private final static Operation TX = CREATE.derive("TX");

      public Create(EntityManager entityManager, Object entity) {
         super(entityManager, entity);
      }

      @Override
      public Operation operation() {
         return CREATE;
      }

      @Override
      public Operation txOperation() {
         return TX;
      }
   }

   public static class Update extends Persist {
      private final static Operation UPDATE = JpaProvider.PERSIST.derive("Update");
      private final static Operation TX = UPDATE.derive("TX");

      public Update(EntityManager entityManager, Object entity) {
         super(entityManager, entity);
      }

      @Override
      public Operation operation() {
         return UPDATE;
      }

      @Override
      public Operation txOperation() {
         return TX;
      }
   }

   public static class Remove implements Invocation {
      private final static Operation TX = JpaProvider.REMOVE.derive("TX");
      private final EntityManager entityManager;
      private final Object entity;

      public Remove(EntityManager entityManager, Object entity) {
         this.entityManager = entityManager;
         this.entity = entity;
      }

      @Override
      public Object invoke() {
         if (trace) log.tracef("REMOVE %s", entity);
         entityManager.remove(entity);
         return null;
      }

      @Override
      public Operation operation() {
         return JpaProvider.REMOVE;
      }

      @Override
      public Operation txOperation() {
         return TX;
      }
   }
}
