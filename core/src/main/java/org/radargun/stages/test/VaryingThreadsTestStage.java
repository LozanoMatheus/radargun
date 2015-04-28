package org.radargun.stages.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.radargun.DistStageAck;
import org.radargun.Operation;
import org.radargun.StageResult;
import org.radargun.config.Property;
import org.radargun.config.Stage;
import org.radargun.reporting.Report;
import org.radargun.state.SlaveState;
import org.radargun.stats.Statistics;
import org.radargun.utils.MinMax;
import org.radargun.utils.Projections;
import org.radargun.utils.TimeConverter;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Stage(doc = "Test stage with varying number of threads.")
public abstract class VaryingThreadsTestStage extends TestStage {

   @Property(doc = "Minimum number of threads waiting for next operation. Default is 1.")
   private int minWaitingThreads = 1;

   @Property(doc = "Maximum number of executing threads. Default is 1000.")
   private int maxThreads = 1000;

   @Property(doc = "Minimum delay between creating another thread. Default is 20 ms.", converter = TimeConverter.class)
   private long minThreadCreationDelay = 20;

   AtomicInteger waitingThreads = new AtomicInteger();
   ConcurrentMap<Operation, AtomicInteger> actuallyExecuting = new ConcurrentHashMap<>();
   AtomicInteger nextThreadIndex = new AtomicInteger();
   AtomicLong lastCreated = new AtomicLong(Long.MIN_VALUE);
   VaryingStressors stressors = new VaryingStressors();

   @Override
   protected OperationSelector createOperationSelector() {
      throw new UnsupportedOperationException("This method needs to be overriden, returning SchedulingOperationSelector");
   }

   @Override
   protected OperationSelector wrapOperationSelector(final OperationSelector operationSelector) {
      return new OperationSelector() {
         private ThreadLocal<Operation> lastOperation = new ThreadLocal<>();

         @Override
         public void start() {
            operationSelector.start();
         }

         @Override
         public Operation next(Random random) {
            Operation lastOperation = this.lastOperation.get();
            if (lastOperation != null) {
               waitingThreads.incrementAndGet();
               actuallyExecuting.get(lastOperation).decrementAndGet();
            }
            Operation operation = null;
            try {
               operation = operationSelector.next(random);
               this.lastOperation.set(operation);
               AtomicInteger act = actuallyExecuting.get(operation);
               if (act == null) {
                  act = new AtomicInteger();
                  AtomicInteger prev = actuallyExecuting.putIfAbsent(operation, act);
                  if (prev != null) act = prev;
               }
               act.incrementAndGet();
               return operation;
            } finally {
               if (waitingThreads.decrementAndGet() <= minWaitingThreads && !isTerminated() && !isFinished()) {
                  long now = System.currentTimeMillis();
                  long timestamp = lastCreated.get();
                  boolean set = false;
                  while (timestamp + minThreadCreationDelay < now) {
                     if (set = lastCreated.compareAndSet(timestamp, now)) {
                        break;
                     }
                     timestamp = lastCreated.get();
                  }
                  if (set) {
                     addStressor();
                     for (Map.Entry<Operation, AtomicInteger> entry : actuallyExecuting.entrySet()) {
                        log.infof("%s: %d threads", entry.getKey(), entry.getValue().get());
                     }
                  }
               }
            }
         }
      };
   }

   private void addStressor() {
      int threadIndex = nextThreadIndex.getAndIncrement();
      if (threadIndex >= maxThreads) {
         return;
      }
      // mark non-started thread as waiting
      waitingThreads.incrementAndGet();
      Stressor stressor = new Stressor(this, getLogic(), -1, threadIndex);
      stressors.add(stressor);
      log.infof("Creating stressor %s", stressor.getName());
      stressor.start();
   }

   @Override
   protected Stressors startStressors() {
      for (int i = 0; i < minWaitingThreads + 1; ++i) {
         addStressor();
      }
      return stressors;
   }

   @Override
   protected VaryingThreadsStatisticsAck newStatisticsAck(List<Stressor> stressors) {
      List<List<Statistics>> results = gatherResults(stressors, new StatisticsResultRetriever());
      return new VaryingThreadsStatisticsAck(slaveState, results, Math.min(nextThreadIndex.get(), maxThreads));
   }

   @Override
   public StageResult processAckOnMaster(List<DistStageAck> acks) {
      StageResult result = super.processAckOnMaster(acks);
      if (result.isError()) return result;
      MinMax.Int usedThreads = new MinMax.Int();
      Map<Integer, Report.SlaveResult> slaveResults = new HashMap<Integer, Report.SlaveResult>();
      for (VaryingThreadsStatisticsAck ack : Projections.instancesOf(acks, VaryingThreadsStatisticsAck.class)) {
         usedThreads.add(ack.usedThreads);
         slaveResults.put(ack.getSlaveIndex(), new Report.SlaveResult(String.valueOf(ack.usedThreads), false));
      }
      Report.Test test = getTest(true); // the test was already created in super.processAckOnMaster
      if (test != null) {
         test.addResult(getTestIteration(), new Report.TestResult("Used threads", slaveResults, usedThreads.toString(), false));
      } else {
         log.info("No test name - results are not recorded");
      }
      return result;
   }

   @Override
   public int getTotalThreads() {
      throw new UnsupportedOperationException();
   }

   @Override
   public int getFirstThreadOn(int slave) {
      throw new UnsupportedOperationException();
   }

   @Override
   public int getNumThreadsOn(int slave) {
      throw new UnsupportedOperationException();
   }

   protected static class VaryingThreadsStatisticsAck extends StatisticsAck {
      public final int usedThreads;

      protected VaryingThreadsStatisticsAck(SlaveState slaveState, List<List<Statistics>> iterations, int usedThreads) {
         super(slaveState, iterations);
         this.usedThreads = usedThreads;
      }
   }

   private class VaryingStressors implements Stressors {
      CopyOnWriteArrayList<Stressor> currentStressors = new CopyOnWriteArrayList<>();

      @Override
      public List<Stressor> getStressors() {
         return currentStressors;
      }

      public void add(Stressor stressor) {
         currentStressors.add(stressor);
      }
   }
}
