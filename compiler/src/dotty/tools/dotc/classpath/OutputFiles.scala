package dotty.tools.dotc.classpath

import scala.language.unsafeNulls
import dotty.tools.io.AbstractFile
import dotty.tools.backend.jvm.FileConflictException
import dotty.tools.dotc.core.Contexts.Context

object OutputFiles:

  /**
   * @param clsName cls.getName
   */
  def getFile(base: AbstractFile, clsName: String, suffix: String): AbstractFile = {
    def ensureDirectory(dir: AbstractFile): AbstractFile =
      if (dir.isDirectory) dir
      else throw new FileConflictException(s"${base.path}/$clsName$suffix: ${dir.path} is not a directory", dir)
    
    var dir = base
    val pathParts = clsName.split("[./]").toList
    for (part <- pathParts.init) dir = ensureDirectory(dir) subdirectoryNamed part
    ensureDirectory(dir) fileNamed pathParts.last + suffix
  }
