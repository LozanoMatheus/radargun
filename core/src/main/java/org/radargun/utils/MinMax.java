package org.radargun.utils;

import java.text.DecimalFormat;

/**
 * Computes min/max.
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class MinMax {
   public static class Long {
      long min = java.lang.Long.MAX_VALUE;
      long max = java.lang.Long.MIN_VALUE;

      public void add(long value) {
         min = Math.min(min, value);
         max = Math.max(max, value);
      }

      public String toString() {
         if (min > max) return "none";
         if (min == max) return String.valueOf(max);
         return min + " .. " + max;
      }
   }

   public static class Int {
      int min = Integer.MAX_VALUE;
      int max = Integer.MIN_VALUE;

      public void add(int value) {
         min = Math.min(min, value);
         max = Math.max(max, value);
      }

      public String toString() {
         if (min > max) return "none";
         if (min == max) return String.valueOf(max);
         return min + " .. " + max;
      }
   }

   public static class Double {
      double min = java.lang.Double.POSITIVE_INFINITY;
      double max = java.lang.Double.NEGATIVE_INFINITY;

      public void add(double value) {
         min = Math.min(min, value);
         max = Math.max(max, value);
      }

      public String toString() {
         if (min > max) return "none";
         if (min == max) return String.valueOf(max);
         return min + " .. " + max;
      }

      public String toString(DecimalFormat formatter) {
         if (min > max) return "none";
         if (min == max) return formatter.format(max);
         return formatter.format(min) + " .. " + formatter.format(max);
      }
   }
}
