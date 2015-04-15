package org.radargun.reporting.html;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.jfree.chart.axis.LogAxis;
import org.jfree.chart.axis.NumberTick;
import org.jfree.chart.axis.Tick;
import org.jfree.chart.axis.TickType;
import org.jfree.data.Range;
import org.jfree.text.TextUtilities;
import org.jfree.ui.RectangleEdge;
import org.jfree.ui.RectangleInsets;
import org.jfree.ui.TextAnchor;

/**
 * Logarithmic axis that shows time
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
class LogTimeAxis extends LogAxis {

   protected final double tickLabelAngle;
   protected final int[] minorTicks;

   public LogTimeAxis(String label) {
      this(label, Math.PI / 4, new int[] { 2, 3, 4, 5, 6, 7, 8, 9 });
   }

   public LogTimeAxis(String label, double tickLabelAngle, int[] minorTicks) {
      super(label);
      this.tickLabelAngle = tickLabelAngle;
      this.minorTicks = minorTicks;
   }

   @Override
   protected List refreshTicksHorizontal(Graphics2D g2, Rectangle2D dataArea, RectangleEdge edge) {
      return refreshTicks(g2, edge == RectangleEdge.TOP ? TextAnchor.BOTTOM_RIGHT : TextAnchor.TOP_LEFT);
   }

   @Override
   protected List refreshTicksVertical(Graphics2D g2, Rectangle2D dataArea, RectangleEdge edge) {
      return refreshTicks(g2, edge == RectangleEdge.RIGHT ? TextAnchor.CENTER_LEFT : TextAnchor.CENTER_RIGHT);
   }

   private List refreshTicks(Graphics2D g2, TextAnchor textAnchor) {
      Range range = getRange();
      List ticks = new ArrayList();
      Font tickLabelFont = getTickLabelFont();
      g2.setFont(tickLabelFont);

      double start = Math.pow(10, Math.floor(Math.log10(range.getLowerBound())));
      double end = Math.pow(10, Math.floor(Math.log10(range.getUpperBound())));
      double current = start;
      while (current <= end) {
         if (range.contains(current)) {
            ticks.add(new NumberTick(TickType.MAJOR, current, tickLabel(current), textAnchor, textAnchor, tickLabelAngle));
         }
         for (int i : minorTicks) {
            if (range.contains(current * i)) {
               ticks.add(new NumberTick(TickType.MINOR, current * i, tickLabel(current * i), textAnchor, TextAnchor.TOP_LEFT, tickLabelAngle));
            }
         }
         current *= 10;
      }
      return ticks;
   }

   protected double findMaximumTickLabelHeight(List ticks, Graphics2D g2, Rectangle2D drawArea, boolean vertical) {
      RectangleInsets insets = getTickLabelInsets();
      Font font = getTickLabelFont();
      g2.setFont(font);
      double maxHeight = 0.0;
      Iterator iterator = ticks.iterator();
      while (iterator.hasNext()) {
         Tick tick = (Tick) iterator.next();
         Rectangle2D labelBounds = null;
         if (tick.getText() != null) {
            labelBounds = TextUtilities.calculateRotatedStringBounds(tick.getText(), g2, 0, 0, TextAnchor.TOP_LEFT, tickLabelAngle, TextAnchor.TOP_LEFT).getBounds2D();
         }
         if (labelBounds != null && labelBounds.getWidth()
               + insets.getTop() + insets.getBottom() > maxHeight) {
            maxHeight = labelBounds.getWidth()
                  + insets.getTop() + insets.getBottom();
         }
      }
      return maxHeight;
   }

   protected double findMaximumTickLabelWidth(List ticks, Graphics2D g2, Rectangle2D drawArea, boolean vertical) {
      RectangleInsets insets = getTickLabelInsets();
      double maxWidth = 0.0;
      Iterator iterator = ticks.iterator();
      while (iterator.hasNext()) {
         Tick tick = (Tick) iterator.next();
         Rectangle2D labelBounds = null;
         if (tick.getText() != null) {
            labelBounds = TextUtilities.calculateRotatedStringBounds(tick.getText(), g2, 0, 0, TextAnchor.TOP_LEFT, tickLabelAngle, TextAnchor.TOP_LEFT).getBounds2D();
         }
         if (labelBounds != null
               && labelBounds.getWidth() + insets.getLeft()
               + insets.getRight() > maxWidth) {
            maxWidth = labelBounds.getWidth()
                  + insets.getLeft() + insets.getRight();
         }
      }
      return maxWidth;
   }

   protected String tickLabel(double value) {
      String unit;
      if (value < 1000d) {
         unit = "ns";
      } else if (value < 1000000d) {
         unit = "us";
         value /= 1000d;
      } else if (value < 1000000000d){
         unit = "ms";
         value /= 1000000d;
      } else {
         unit = "s";
         value /= 1000000000d;
      }
      return String.format("%.0f %s", value, unit);
   }
}
