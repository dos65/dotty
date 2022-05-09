package dotty
package tools.dotc

import dotty.tools.vulpix.ParallelTesting
import scala.concurrent.duration._
import dotty.tools.vulpix.SummaryReport
import org.junit.Test

class IntTreesTests extends ParallelTesting {

  def maxDuration = 45.seconds
  def numberOfSlaves = Runtime.getRuntime.availableProcessors()
  def safeMode = Properties.testsSafeMode
  def isInteractive = SummaryReport.isInteractive
  def testFilter = Properties.testsFilter
  def updateCheckFiles: Boolean = Properties.testsUpdateCheckfile

  @Test def intTrees: Unit = {
    ???
  }
}


