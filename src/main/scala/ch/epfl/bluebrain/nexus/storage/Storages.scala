package ch.epfl.bluebrain.nexus.storage

import java.nio.file.StandardCopyOption._
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.{FileIO, Keep, Sink}
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.File._
import ch.epfl.bluebrain.nexus.storage.Rejection.{PathAlreadyExists, PathContainsLinks, PathNotFound}
import ch.epfl.bluebrain.nexus.storage.StorageError.{InternalError, PathInvalid}
import ch.epfl.bluebrain.nexus.storage.Storages.BucketExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.PathExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.{BucketExistence, PathExistence}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.{DigestConfig, StorageConfig}
import ch.epfl.bluebrain.nexus.storage.digest.DigestCache
import com.github.ghik.silencer.silent
import ch.epfl.bluebrain.nexus.storage.digest.DigestComputation.sink

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

trait Storages[F[_], Source] {

  /**
    * Checks that the provided bucket name exists and it is readable/writable.
    *
    * @param name the storage bucket name
    */
  def exists(name: String): BucketExistence

  /**
    * Check whether the provided path already exists.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path location
    */
  def pathExists(name: String, relativePath: Uri.Path): PathExistence

  /**
    * Creates a file with the provided ''metadata'' and ''source'' on the provided ''filePath''.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path location
    * @param source       the file content
    * @return The file attributes containing the metadata (bytes and location) wrapped in an F effect type
    */
  def createFile(name: String, relativePath: Uri.Path, source: Source)(
      implicit @silent bucketEv: BucketExists,
      @silent pathEv: PathDoesNotExist): F[FileAttributes]

  /**
    * Moves a path from the provided ''sourceRelativePath'' to ''destRelativePath'' inside the nexus folder.
    *
    * @param name               the storage bucket name
    * @param sourceRelativePath the source relative path location
    * @param destRelativePath   the destination relative path location inside the nexus folder
    * @return Left(rejection) or Right(fileAttributes).
    *         The file attributes contain the metadata (bytes and location) wrapped in an F effect type
    */
  def moveFile(name: String, sourceRelativePath: Uri.Path, destRelativePath: Uri.Path)(
      implicit @silent bucketEv: BucketExists): F[RejOrAttributes]

  /**
    * Retrieves the file as a Source.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path to the file location
    * @return Left(rejection),  Right(source, Some(filename)) when the path is a file and Right(source, None) when the path is a directory
    */
  def getFile(name: String, relativePath: Uri.Path)(implicit @silent bucketEv: BucketExists,
                                                    @silent pathEv: PathExists): RejOr[(Source, Option[String])]

  /**
    * Retrieves the digest of the file.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path to the file location
    */
  def getDigest(name: String, relativePath: Uri.Path)(implicit @silent bucketEv: BucketExists,
                                                      @silent pathEv: PathExists): F[Digest]

}

object Storages {

  sealed trait BucketExistence
  sealed trait PathExistence

  object BucketExistence {
    final case object BucketExists       extends BucketExistence
    final case object BucketDoesNotExist extends BucketExistence
    type BucketExists       = BucketExists.type
    type BucketDoesNotExist = BucketDoesNotExist.type
  }

  object PathExistence {
    final case object PathExists       extends PathExistence
    final case object PathDoesNotExist extends PathExistence
    type PathExists       = PathExists.type
    type PathDoesNotExist = PathDoesNotExist.type
  }

  /**
    * An Disk implementation of Storage interface.
    */
  final class DiskStorage[F[_]](config: StorageConfig, digestConfig: DigestConfig, cache: DigestCache[F])(
      implicit ec: ExecutionContext,
      mt: Materializer,
      F: Effect[F])
      extends Storages[F, AkkaSource] {

    private def basePath(name: String, protectedDir: Boolean = true): Path = {
      val path = config.rootVolume.resolve(name).normalize()
      if (protectedDir) path.resolve(config.protectedDirectory).normalize() else path
    }

    private def filePath(name: String, relativePath: Uri.Path, protectedDir: Boolean = true): Path =
      basePath(name, protectedDir).resolve(Paths.get(relativePath.toString())).normalize()

    def exists(name: String): BucketExistence = {
      val path = basePath(name)
      if (path.getParent.getParent != config.rootVolume) BucketDoesNotExist
      else if (Files.isDirectory(path) && Files.isReadable(path)) BucketExists
      else BucketDoesNotExist
    }

    def pathExists(name: String, relativeFilePath: Uri.Path): PathExistence = {
      val path = filePath(name, relativeFilePath)
      if (Files.exists(path) && Files.isReadable(path) && path.descendantOf(basePath(name))) PathExists
      else PathDoesNotExist
    }

    def createFile(name: String, relativeFilePath: Uri.Path, source: AkkaSource)(
        implicit @silent bucketEv: BucketExists,
        @silent pathEv: PathDoesNotExist): F[FileAttributes] = {
      val absFilePath = filePath(name, relativeFilePath)
      if (absFilePath.descendantOf(basePath(name)))
        F.fromTry(Try(Files.createDirectories(absFilePath.getParent))) >>
          F.fromTry(Try(MessageDigest.getInstance(digestConfig.algorithm))).flatMap { msgDigest =>
            source
              .alsoToMat(sink(msgDigest))(Keep.right)
              .toMat(FileIO.toPath(absFilePath)) {
                case (digFuture, ioFuture) =>
                  digFuture.zipWith(ioFuture) {
                    case (digest, io) if io.wasSuccessful && absFilePath.toFile.exists() =>
                      Future(FileAttributes(s"file://$absFilePath", io.count, digest))
                    case _ =>
                      Future.failed(InternalError(s"I/O error writing file to path '$relativeFilePath'"))
                  }
              }
              .run()
              .flatten
              .to[F]
          } else
        F.raiseError(PathInvalid(name, relativeFilePath))
    }

    def moveFile(name: String, sourceRelativePath: Uri.Path, destRelativePath: Uri.Path)(
        implicit bucketEv: BucketExists): F[RejOrAttributes] = {

      val bucketPath          = basePath(name, protectedDir = false)
      val bucketProtectedPath = basePath(name)
      val absSourcePath       = filePath(name, sourceRelativePath, protectedDir = false)
      val absDestPath         = filePath(name, destRelativePath)

      //TODO: This method should first call the binary to change the ownership + permissions before doing the move
      def computeSizeAndMove(): F[RejOrAttributes] =
        size(absSourcePath).flatMap { computedSize =>
          F.fromTry(Try(Files.createDirectories(absDestPath.getParent))) >>
            F.fromTry(Try(Files.move(absSourcePath, absDestPath, ATOMIC_MOVE))) >>
            F.pure(cache.asyncComputePut(absDestPath, digestConfig.algorithm)) >>
            F.pure(Right(FileAttributes(s"file://$absDestPath", computedSize, Digest.empty)))
        }

      def dirContainsLink(path: Path): F[Boolean] =
        Directory
          .walk(path)
          .map(p => Files.isSymbolicLink(p) || containsHardLink(p))
          .takeWhile(_ == false, inclusive = true)
          .runWith(Sink.last)
          .to[F]

      if (!Files.exists(absSourcePath))
        F.pure(Left(PathNotFound(name, sourceRelativePath)))
      else if (!absSourcePath.descendantOf(bucketPath) || absSourcePath.descendantOf(bucketProtectedPath))
        F.pure(Left(PathNotFound(name, sourceRelativePath)))
      else if (!absDestPath.descendantOf(bucketProtectedPath))
        F.raiseError(PathInvalid(name, destRelativePath))
      else if (Files.exists(absDestPath))
        F.pure(Left(PathAlreadyExists(name, destRelativePath)))
      else if (Files.isSymbolicLink(absSourcePath) || containsHardLink(absSourcePath))
        F.pure(Left(PathContainsLinks(name, sourceRelativePath)))
      else if (Files.isRegularFile(absSourcePath))
        computeSizeAndMove()
      else if (Files.isDirectory(absSourcePath))
        dirContainsLink(absSourcePath).flatMap {
          case true  => F.pure(Left(PathContainsLinks(name, sourceRelativePath)))
          case false => computeSizeAndMove()
        } else F.pure(Left(PathNotFound(name, sourceRelativePath)))
    }

    def getFile(name: String, relativePath: Uri.Path)(
        implicit @silent bucketEv: BucketExists,
        @silent pathEv: PathExists): RejOr[(AkkaSource, Option[String])] = {
      val absPath = filePath(name, relativePath)
      if (Files.isRegularFile(absPath)) Right(fileSource(absPath) -> Some(absPath.getFileName.toString))
      else if (Files.isDirectory(absPath)) Right(folderSource(absPath) -> None)
      else Left(PathNotFound(name, relativePath))
    }

    def getDigest(name: String, relativePath: Uri.Path)(implicit bucketEv: BucketExists,
                                                        pathEv: PathExists): F[Digest] =
      cache.get(filePath(name, relativePath))

    private def containsHardLink(absPath: Path): Boolean =
      if (Files.isDirectory(absPath)) false
      else
        Try(Files.getAttribute(absPath, "unix:nlink").asInstanceOf[Int]) match {
          case Success(value) => value > 1
          case _              => false
        }

    private def size(absPath: Path): F[Long] =
      if (Files.isDirectory(absPath))
        Directory.walk(absPath).filter(Files.isRegularFile(_)).runFold(0L)(_ + Files.size(_)).to[F]
      else if (Files.isRegularFile(absPath))
        F.pure(Files.size(absPath))
      else
        F.raiseError(InternalError(s"Path '$absPath' is not a file nor a directory"))
  }

}
