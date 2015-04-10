package org.radargun.stages.topology;

import java.util.List;

import org.radargun.DistStageAck;
import org.radargun.config.Property;
import org.radargun.config.Stage;
import org.radargun.stages.AbstractDistStage;
import org.radargun.traits.Clustered;
import org.radargun.traits.InjectTrait;
import org.radargun.traits.TopologyHistory;
import org.radargun.utils.TimeConverter;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Stage(doc = "Waits until some event occurs. Note that the initial rehash is not recorded in this manner, " +
      "therefore waiting for that will result in timeout.")
public class WaitForTopologyEventStage extends AbstractDistStage {
   public enum Type {
      REHASH {
         @Override
         public Checker getChecker(WaitForTopologyEventStage stage) {
            return new TopologyHistoryChecker("__last_rehash_event__", stage) {
               @Override
               public void loadEvents() {
                  events = stage.topologyHistory.getRehashHistory(stage.cacheName);
               }
            };
         }
      },
      TOPOLOGY_UPDATE{
         @Override
         public Checker getChecker(WaitForTopologyEventStage stage) {
            return new TopologyHistoryChecker("__last_topology_event__", stage) {
               @Override
               public void loadEvents() {
                  events = stage.topologyHistory.getTopologyChangeHistory(stage.cacheName);
               }
            };
         }
      },
      MEMBERSHIP_CHANGE {
         @Override
         public Checker getChecker(WaitForTopologyEventStage stage) {
            return new MembershipChecker("__last_view_change__", stage);
         }
      };
      public abstract Checker getChecker(WaitForTopologyEventStage stage);

   }

   public enum Condition {
      START,
      END
   }

   @Property(doc = "Name of the cache where we detect the events. Default is the default cache.")
   private String cacheName;

   @Property(doc = "Wait for the event to happen. Default is true.")
   private boolean wait = true;

   @Property(doc = "Set last state before finishing. Default is true.")
   private boolean set = true;

   @Property(doc = "Type of event we are detecting. Default is REHASH.")
   private Type type = Type.REHASH;

   @Property(doc = "Condition we are waiting for. Default is END.")
   private Condition condition = Condition.END;

   @Property(doc = "How long should we wait until we give up with error, 0 means indefinitely. Default is 10 minutes.", converter = TimeConverter.class)
   private long timeout = 600000;

   @Property(doc = "The minimum number of slaves that participated in this event. Default is 0.")
   private int minMembers = 0;

   @Property(doc = "The maximum number of slaves that participated in this event. Default is infinite.")
   private int maxMembers = Integer.MAX_VALUE;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   private TopologyHistory topologyHistory;

   @InjectTrait
   private Clustered clustered;

   @Override
   public DistStageAck executeOnSlave() {
      if (!isServiceRunning()) {
         return successfulResponse();
      }
      if (type == Type.MEMBERSHIP_CHANGE && clustered == null) {
         return errorResponse("Cluster membership is not supported");
      }
      Checker checker = type.getChecker(this);
      checker.loadEvents();
      if (wait) {
         long deadline = System.currentTimeMillis() + timeout;
         while (timeout <= 0 || System.currentTimeMillis() < deadline) {
            if (checker.check()) {
               break;
            }
            try {
               Thread.sleep(1000);
            } catch (InterruptedException e) {
               return errorResponse("Waiting was interrupted", e);
            }
            checker.loadEvents();
         }
         if (timeout > 0 && System.currentTimeMillis() > deadline) {
            return errorResponse("Waiting has timed out");
         }
      }
      if (set) {
         checker.setLastEvent();
      }
      return successfulResponse();
   }

   protected interface Checker {
      void loadEvents();
      boolean check();
      void setLastEvent();
   }

   protected abstract static class AbstractChecker<T> implements Checker {
      protected final WaitForTopologyEventStage stage;
      protected final String key;
      protected final T setEvent;
      protected List<T> events;

      protected AbstractChecker(String key, WaitForTopologyEventStage stage) {
         this.key = key;
         this.setEvent = (T) stage.slaveState.get(key);
         this.stage = stage;
      }

      @Override
      public void setLastEvent() {
         if (events == null || events.isEmpty()) stage.slaveState.remove(key);
         stage.slaveState.put(key, events.get(events.size() - 1));
      }
   }

   protected abstract static class TopologyHistoryChecker extends AbstractChecker<TopologyHistory.Event> {
      protected TopologyHistoryChecker(String key, WaitForTopologyEventStage stage) {
         super(key, stage);
      }

      @Override
      public boolean check() {
         stage.log.tracef("setEvent=%s, history=%s", setEvent, events);
         if (events.isEmpty()) {
            return false;
         }
         if (stage.condition == Condition.END) {
            for (int i = events.size() - 1; i >= 0; --i) {
               TopologyHistory.Event e = events.get(i);
               if (setEvent != null && setEvent.getEnded() != null && !e.getStarted().after(setEvent.getStarted())) break;
               if (e.getEnded() != null && e.getMembersAtEnd() >= stage.minMembers && e.getMembersAtEnd() <= stage.maxMembers) {
                  return true;
               }
            }
         } else if (stage.condition == Condition.START) {
            for (int i = events.size() - 1; i >= 0; --i) {
               TopologyHistory.Event e = events.get(i);
               if (setEvent != null && !e.getStarted().after(setEvent.getStarted())) break;
               if (e.getMembersAtEnd() >= stage.minMembers && e.getMembersAtEnd() <= stage.maxMembers) {
                  return true;
               }
            }
         }
         return false;
      }
   }

   private static class MembershipChecker extends AbstractChecker<Clustered.Membership> {
      public MembershipChecker(String key, WaitForTopologyEventStage stage) {
         super(key, stage);
      }

      @Override
      public void loadEvents() {
         events = stage.clustered.getMembershipHistory();
      }

      @Override
      public boolean check() {
         loadEvents();
         stage.log.tracef("setEvent=%s, history=%s", setEvent, events);
         if (events == null) return false; // shouldn't happen
         for (int i = events.size() - 1; i >= 0; --i) {
            Clustered.Membership membership = events.get(i);
            if (membership.date.before(setEvent.date)) {
               return false;
            }
            if (membership.members.size() >= stage.minMembers && membership.members.size() <= stage.maxMembers) {
               return true;
            }
         }
         return false;
      }
   }
}
