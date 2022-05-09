package dotty.tools.dotc.core.classfile

import dotty.tools.io
import dotty.tools.io._
import java.io.ByteArrayOutputStream
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.tasty.TastyReader
import java.util.UUID
import dotty.tools.tasty.TastyHeaderUnpickler
import dotty.tools.dotc.core.tasty.DottyUnpickler
import dotty.tools.dotc.core.classfile.ClassfileParser.Embedded
import dotty.tools.dotc.core.SymDenotations.ClassDenotation

class TastyParser(
  tastyFile: AbstractFile,
  classRoot: ClassDenotation,
  moduleRoot: ClassDenotation
)(ictx: Context):

  def readFile(file: AbstractFile): Array[Byte] =
    file match 
      case zipEntry: io.ZipArchive#Entry => // We are in a jar
        val stream = file.input
        try
          val tastyOutStream = new ByteArrayOutputStream()
          val buffer = new Array[Byte](1024)
          var read = stream.read(buffer, 0, buffer.length)
          while read != -1 do 
            tastyOutStream.write(buffer, 0, read)
            read = stream.read(buffer, 0, buffer.length)
          
          tastyOutStream.flush()
          tastyOutStream.toByteArray.nn
        finally
          stream.close()
      case _ =>
        file.toByteArray


  def run()(using Context): Option[Embedded] =
    val bytes = readFile(tastyFile)
    if bytes.nonEmpty then
      unpickleTASTY(bytes)
    else
      None

  def unpickleTASTY(bytes: Array[Byte])(using ctx: Context): Some[Embedded]  = {
    val unpickler = new DottyUnpickler(bytes)
    unpickler.enter(roots = Set(classRoot, moduleRoot, moduleRoot.sourceModule))(using ctx.withSource(dotty.tools.dotc.util.NoSource))
    Some(unpickler)
  }
