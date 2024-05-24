/*
 * Copyright (c) 2014 Contributor. All rights reserved.
 */
package dotty.tools.dotc.classpath

import scala.language.unsafeNulls
import java.net.URL
import java.nio.file.{FileSystems, Files}
import dotty.tools.dotc.classpath.PackageNameUtils.{
  packageContains,
  separatePkgAndClassNames
}
import dotty.tools.io.{
  AbstractFile,
  ClassPath,
  ClassRepresentation,
  EfficientClassPath,
  JDK9Reflectors,
  JFile,
  JPath,
  PlainFile,
  PlatformFile,
  PlatformFileSystem,
  PlatformFileSystems,
  PlatformFiles,
  PlatformPath,
  PlatformPaths,
  PlatformURI,
  PlatformURL
}
import FileUtils.*
import PlainFile.toPlainFile

import java.io.File
import scala.jdk.CollectionConverters.*
import scala.collection.immutable.ArraySeq
import scala.util.control.NonFatal

/** A trait allowing to look for classpath entries in directories. It provides common logic for
  * classes handling class and source files.
  * It makes use of the fact that in the case of nested directories it's easy to find a file
  * when we have a name of a package.
  * It abstracts over the file representation to work with both JFile and AbstractFile.
  */
trait DirectoryLookup[FileEntryType <: ClassRepresentation]
    extends EfficientClassPath {
  type F

  val dir: F

  protected def emptyFiles: Array[F] // avoids reifying ClassTag[F]
  protected def getSubDir(dirName: String): Option[F]
  protected def listChildren(
      dir: F,
      filter: Option[F => Boolean] = None
  ): Array[F]
  protected def getName(f: F): String
  protected def toAbstractFile(f: F): AbstractFile
  protected def isPackage(f: F): Boolean

  protected def createFileEntry(file: AbstractFile): FileEntryType
  protected def isMatchingFile(f: F): Boolean

  private def getDirectory(forPackage: PackageName): Option[F] =
    if (forPackage.isRoot)
      Some(dir)
    else
      getSubDir(forPackage.dirPathTrailingSlash)

  override private[dotty] def hasPackage(pkg: PackageName): Boolean =
    getDirectory(pkg).isDefined

  private[dotty] def packages(inPackage: PackageName): Seq[PackageEntry] = {
    val dirForPackage = getDirectory(inPackage)
    val nestedDirs: Array[F] = dirForPackage match {
      case None            => emptyFiles
      case Some(directory) => listChildren(directory, Some(isPackage))
    }
    ArraySeq
      .unsafeWrapArray(nestedDirs)
      .map(f => PackageEntryImpl(inPackage.entryName(getName(f))))
  }

  protected def files(inPackage: PackageName): Seq[FileEntryType] = {
    val dirForPackage = getDirectory(inPackage)
    val files: Array[F] = dirForPackage match {
      case None            => emptyFiles
      case Some(directory) => listChildren(directory, Some(isMatchingFile))
    }
    files.iterator.map(f => createFileEntry(toAbstractFile(f))).toSeq
  }

  override def list(
      inPackage: PackageName,
      onPackageEntry: PackageEntry => Unit,
      onClassesAndSources: ClassRepresentation => Unit
  ): Unit = {
    val dirForPackage = getDirectory(inPackage)
    dirForPackage match {
      case None =>
      case Some(directory) =>
        for (file <- listChildren(directory)) {
          if (isPackage(file))
            onPackageEntry(PackageEntryImpl(inPackage.entryName(getName(file))))
          else if (isMatchingFile(file))
            onClassesAndSources(createFileEntry(toAbstractFile(file)))
        }
    }
  }
}

trait PlatformFileDirectoryLookup[FileEntryType <: ClassRepresentation]
    extends DirectoryLookup[FileEntryType] {
  type F = PlatformFile

  protected def emptyFiles: Array[PlatformFile] = Array.empty
  protected def getSubDir(packageDirName: String): Option[PlatformFile] = {
    val packageDir = PlatformFile(dir, packageDirName)
    if (packageDir.exists && packageDir.isDirectory) Some(packageDir)
    else None
  }
  protected def listChildren(
      dir: PlatformFile,
      filter: Option[PlatformFile => Boolean]
  ): Array[PlatformFile] = {
    val listing = filter match {
      case Some(f) => dir.listFiles(f)
      case None    => dir.listFiles()
    }

    if (listing != null) {
      // Sort by file name for stable order of directory .class entries in package scope.
      // This gives stable results ordering of base type sequences for unrelated classes
      // with the same base type depth.
      //
      // Notably, this will stably infer`Product with Serializable`
      // as the type of `case class C(); case class D(); List(C(), D()).head`, rather than the opposite order.
      // On Mac, the HFS performs this sorting transparently, but on Linux the order is unspecified.
      //
      // Note this behaviour can be enabled in javac with `javac -XDsortfiles`, but that's only
      // intended to improve determinism of the compiler for compiler hackers.
      java.util.Arrays.sort(
        listing,
        new java.util.Comparator[PlatformFile] {
          def compare(o1: PlatformFile, o2: PlatformFile) =
            o1.getName.compareTo(o2.getName)
        }
      )
      listing
    } else Array()
  }
  protected def getName(f: PlatformFile): String = f.getName
  protected def toAbstractFile(f: PlatformFile): AbstractFile =
    f.toPath.toPlainFile
  protected def isPackage(f: PlatformFile): Boolean = f.isPackage

  assert(dir != null, "Directory file in DirectoryFileLookup cannot be null")

  def asURLs: Seq[PlatformURL] = Seq(dir.toURI.toURL)
  def asClassPathStrings: Seq[String] = Seq(dir.getPath)
}

object JrtClassPath {
  import java.nio.file._, java.net.URI
  def apply(release: Option[String]): Option[ClassPath] = {
    import scala.util.Properties._
    if (!isJavaAtLeast("9")) None
    else {
      // Longer term we'd like an official API for this in the JDK
      // Discussion: http://mail.openjdk.java.net/pipermail/compiler-dev/2018-March/thread.html#11738

      val currentMajorVersion: Int = JDK9Reflectors
        .runtimeVersionMajor(JDK9Reflectors.runtimeVersion())
        .intValue()
      release match {
        case Some(v) if v.toInt < currentMajorVersion =>
          try {
            val ctSym =
              PlatformPaths.get(javaHome).resolve("lib").resolve("ct.sym")
            if (PlatformFiles.notExists(ctSym)) None
            else Some(new CtSymClassPath(ctSym, v.toInt))
          } catch {
            case NonFatal(_) => None
          }
        case _ =>
          try {
            val fs =
              PlatformFileSystems.getFileSystem(PlatformURI.create("jrt:/"))
            Some(new JrtClassPath(fs))
          } catch {
            case _: ProviderNotFoundException |
                _: FileSystemNotFoundException =>
              None
          }
      }
    }
  }
}

/** Implementation `ClassPath` based on the JDK 9 encapsulated runtime modules (JEP-220)
  *
  * https://bugs.openjdk.java.net/browse/JDK-8066492 is the most up to date reference
  * for the structure of the jrt:// filesystem.
  *
  * The implementation assumes that no classes exist in the empty package.
  */
final class JrtClassPath(fs: PlatformFileSystem)
    extends ClassPath
    with NoSourcePaths {
  import java.nio.file._
  type F = Path
  private val dir: PlatformPath = fs.getPath("/packages")

  // e.g. "java.lang" -> Seq("/modules/java.base")
  private val packageToModuleBases: Map[String, Seq[PlatformPath]] = {
    val ps = PlatformFiles.newDirectoryStream(dir).iterator.asScala
    def lookup(pack: PlatformPath): Seq[PlatformPath] =
      PlatformFiles
        .list(pack)
        // .iterator
        // .asScala
        .map(l =>
          if (PlatformFiles.isSymbolicLink(l)) PlatformFiles.readSymbolicLink(l)
          else l
        )
        // .toList
    ps.map(p => (p.toString.stripPrefix("/packages/"), lookup(p))).toMap
  }

  /** Empty string represents root package */
  override private[dotty] def hasPackage(pkg: PackageName): Boolean =
    packageToModuleBases.contains(pkg.dottedString)

  override private[dotty] def packages(
      inPackage: PackageName
  ): Seq[PackageEntry] =
    packageToModuleBases.keysIterator
      .filter(pack => packageContains(inPackage.dottedString, pack))
      .map(PackageEntryImpl(_))
      .toVector

  private[dotty] def classes(inPackage: PackageName): Seq[ClassFileEntry] =
    if (inPackage.isRoot) Nil
    else
      packageToModuleBases
        .getOrElse(inPackage.dottedString, Nil)
        .flatMap(x =>
          PlatformFiles
            .list(x.resolve(inPackage.dirPathTrailingSlash))
            // .iterator
            // .asScala
            .filter(_.getFileName.toString.endsWith(".class"))
        )
        .map(x => ClassFileEntryImpl(x.toPlainFile))
        .toVector

  override private[dotty] def list(inPackage: PackageName): ClassPathEntries =
    if (inPackage.isRoot) ClassPathEntries(packages(inPackage), Nil)
    else ClassPathEntries(packages(inPackage), classes(inPackage))

  def asURLs: Seq[PlatformURL] = Seq(PlatformURL("jrt:/"))
  // We don't yet have a scheme to represent the JDK modules in our `-classpath`.
  // java models them as entries in the new "module path", we'll probably need to follow this.
  def asClassPathStrings: Seq[String] = Nil

  def findClassFile(className: String): Option[AbstractFile] =
    if (!className.contains(".")) None
    else {
      val (inPackage, _) = separatePkgAndClassNames(className)
      packageToModuleBases
        .getOrElse(inPackage, Nil)
        .iterator
        .flatMap { x =>
          val file = x.resolve(FileUtils.dirPath(className) + ".class")
          if (PlatformFiles.exists(file)) file.toPlainFile :: Nil else Nil
        }
        .take(1)
        .toList
        .headOption
    }
}

/** Implementation `ClassPath` based on the \$JAVA_HOME/lib/ct.sym backing http://openjdk.java.net/jeps/247
  */
final class CtSymClassPath(ctSym: PlatformPath, release: Int)
    extends ClassPath
    with NoSourcePaths {
  import java.nio.file._

  private val fileSystem: PlatformFileSystem =
    PlatformFileSystems.newFileSystem(ctSym, null: ClassLoader)
  private val root: PlatformPath = fileSystem.getRootDirectories.iterator.next
  private val roots =
    PlatformFiles.newDirectoryStream(root).iterator.asScala.toList

  // http://mail.openjdk.java.net/pipermail/compiler-dev/2018-March/011737.html
  private def codeFor(major: Int): String =
    if (major < 10) major.toString else ('A' + (major - 10)).toChar.toString

  private val releaseCode: String = codeFor(release)
  private def fileNameMatchesRelease(fileName: String) = !fileName.contains(
    "-"
  ) && fileName.contains(releaseCode) // exclude `9-modules`
  private val rootsForRelease: List[PlatformPath] =
    roots.filter(root => fileNameMatchesRelease(root.getFileName.toString))

  // e.g. "java.lang" -> Seq(/876/java/lang, /87/java/lang, /8/java/lang))
  private val packageIndex
      : scala.collection.Map[String, scala.collection.Seq[PlatformPath]] = {
    val index = collection.mutable
      .AnyRefMap[String, collection.mutable.ListBuffer[PlatformPath]]()
    val isJava12OrHigher = scala.util.Properties.isJavaAtLeast("12")
    rootsForRelease.foreach(root =>
      PlatformFiles
        .walk(root)
        .iterator
        .asScala
        .filter(PlatformFiles.isDirectory(_))
        .foreach { p =>
          val moduleNamePathElementCount = if (isJava12OrHigher) 1 else 0
          if (p.getNameCount > root.getNameCount + moduleNamePathElementCount) {
            val packageDotted = p
              .subpath(
                moduleNamePathElementCount + root.getNameCount,
                p.getNameCount
              )
              .toString
              .replace('/', '.')
            index.getOrElseUpdate(
              packageDotted,
              new collection.mutable.ListBuffer
            ) += p
          }
        }
    )
    index
  }

  /** Empty string represents root package */
  override private[dotty] def hasPackage(pkg: PackageName) =
    packageIndex.contains(pkg.dottedString)
  override private[dotty] def packages(
      inPackage: PackageName
  ): Seq[PackageEntry] = {
    packageIndex.keysIterator
      .filter(pack => packageContains(inPackage.dottedString, pack))
      .map(PackageEntryImpl(_))
      .toVector
  }
  private[dotty] def classes(inPackage: PackageName): Seq[ClassFileEntry] = {
    if (inPackage.isRoot) Nil
    else {
      val sigFiles = packageIndex
        .getOrElse(inPackage.dottedString, Nil)
        .iterator
        .flatMap(p =>
          PlatformFiles
            .list(p)
            // .iterator
            // .asScala
            .filter(_.getFileName.toString.endsWith(".sig"))
        )
      sigFiles.map(f => ClassFileEntryImpl(f.toPlainFile)).toVector
    }
  }

  override private[dotty] def list(inPackage: PackageName): ClassPathEntries =
    if (inPackage.isRoot) ClassPathEntries(packages(inPackage), Nil)
    else ClassPathEntries(packages(inPackage), classes(inPackage))

  def asURLs: Seq[PlatformURL] = Nil
  def asClassPathStrings: Seq[String] = Nil
  def findClassFile(className: String): Option[AbstractFile] = {
    if (!className.contains(".")) None
    else {
      val (inPackage, classSimpleName) = separatePkgAndClassNames(className)
      packageIndex
        .getOrElse(inPackage, Nil)
        .iterator
        .flatMap { p =>
          val path = p.resolve(classSimpleName + ".sig")
          if (PlatformFiles.exists(path)) path.toPlainFile :: Nil else Nil
        }
        .take(1)
        .toList
        .headOption
    }
  }
}

case class DirectoryClassPath(dir: PlatformFile)
    extends PlatformFileDirectoryLookup[ClassFileEntryImpl]
    with NoSourcePaths {
  override def findClass(className: String): Option[ClassRepresentation] =
    findClassFile(className) map ClassFileEntryImpl.apply

  def findClassFile(className: String): Option[AbstractFile] = {
    val relativePath = FileUtils.dirPath(className)
    val classFile = PlatformFile(dir, relativePath + ".class")
    if (classFile.exists) {
      Some(classFile.toPath.toPlainFile)
    } else None
  }

  protected def createFileEntry(file: AbstractFile): ClassFileEntryImpl =
    ClassFileEntryImpl(file)
  protected def isMatchingFile(f: PlatformFile): Boolean = f.isClass

  private[dotty] def classes(inPackage: PackageName): Seq[ClassFileEntry] =
    files(inPackage)
}

case class DirectorySourcePath(dir: PlatformFile)
    extends PlatformFileDirectoryLookup[SourceFileEntryImpl]
    with NoClassPaths {
  def asSourcePathString: String = asClassPathString

  protected def createFileEntry(file: AbstractFile): SourceFileEntryImpl =
    SourceFileEntryImpl(file)
  protected def isMatchingFile(f: PlatformFile): Boolean = endsScalaOrJava(
    f.getName
  )

  override def findClass(className: String): Option[ClassRepresentation] =
    findSourceFile(className) map SourceFileEntryImpl.apply

  private def findSourceFile(className: String): Option[AbstractFile] = {
    val relativePath = FileUtils.dirPath(className)
    val sourceFile = LazyList("scala", "java")
      .map(ext => PlatformFile(dir, relativePath + "." + ext))
      .collectFirst { case file if file.exists => file }

    sourceFile.map(_.toPath.toPlainFile)
  }

  private[dotty] def sources(inPackage: PackageName): Seq[SourceFileEntry] =
    files(inPackage)
}
