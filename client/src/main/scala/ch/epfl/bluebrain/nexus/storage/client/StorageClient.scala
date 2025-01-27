package ch.epfl.bluebrain.nexus.storage.client

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.Uri.Path.{Segment, Slash}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import cats.effect.{Effect, IO, LiftIO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.commons.rdf.syntax._
import ch.epfl.bluebrain.nexus.iam.client.IamClientError.{Forbidden, Unauthorized}
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.storage.client.StorageClient._
import ch.epfl.bluebrain.nexus.storage.client.StorageClientError._
import ch.epfl.bluebrain.nexus.storage.client.config.StorageClientConfig
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.Digest
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes, LinkFile, ServiceDescription}
import io.circe
import io.circe.parser.parse
import io.circe.{DecodingFailure, ParsingFailure}
import journal.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class StorageClient[F[_]] private[client] (
    config: StorageClientConfig,
    attributes: HttpClient[F, FileAttributes],
    digest: HttpClient[F, Digest],
    source: HttpClient[F, AkkaSource],
    serviceDesc: HttpClient[F, ServiceDescription],
    emptyBody: HttpClient[F, NotUsed]
)(implicit F: Effect[F], ec: ExecutionContext) {

  private val emptyChunk = "An HttpEntity.Chunk must have non-empty data"

  def serviceDescription: F[ServiceDescription] =
    serviceDesc(Get(config.iri.toAkkaUri))

  /**
    * Checks that the provided storage bucket exists and it is readable/writable.
    *
    * @param name the storage bucket name
    */
  def exists(name: String)(implicit cred: Option[AuthToken]): F[Boolean] = {
    val endpoint = config.buckets + name
    emptyBody(Head(endpoint.toAkkaUri).withCredentials).map(_ => true).recoverWith {
      case _: NotFound => F.pure(false)
    }
  }

  /**
    * Creates a file with the provided metadata  and ''source'' on the provided ''filePath''.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path location
    * @param source       the file content
    * @return The file attributes wrapped on the effect type F[] containing the metadata plus the file bytes, digest and location
    */
  def createFile(name: String, relativePath: Uri.Path, source: AkkaSource)(
      implicit cred: Option[AuthToken]): F[FileAttributes] = {
    val endpoint       = config.files(name) + slashIfNone(relativePath).toIriPath
    val bodyPartEntity = HttpEntity.IndefiniteLength(ContentTypes.`application/octet-stream`, source)
    val filename       = extractName(relativePath).getOrElse("myfile")
    val multipartForm  = FormData(BodyPart("file", bodyPartEntity, Map("filename" -> filename))).toEntity()
    attributes(Put(endpoint.toAkkaUri, multipartForm).withCredentials).recoverWith {
      case ex: IllegalArgumentException if ex.getMessage != null && ex.getMessage.endsWith(emptyChunk) =>
        createFile(name, relativePath, Source.empty)
      case ex => F.raiseError(ex)
    }
  }

  private def extractName(path: Uri.Path): Option[String] =
    path.reverse match {
      case Segment(name, _) => Some(name)
      case _                => None
    }

  /**
    * Retrieves the file as a Source.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path to the file location
    * @return The source wrapped on the effect type F[]
    */
  def getFile(name: String, relativePath: Uri.Path)(implicit cred: Option[AuthToken]): F[AkkaSource] = {
    val endpoint = config.files(name) + slashIfNone(relativePath).toIriPath
    source(Get(endpoint.toAkkaUri).withCredentials)
  }

  /**
    * Retrieves the file digest.
    *
    * @param name         the storage bucket name
    * @param relativePath the relative path to the file location
    */
  def getDigest(name: String, relativePath: Uri.Path)(implicit cred: Option[AuthToken]): F[Digest] = {
    val endpoint = config.digests(name) + slashIfNone(relativePath).toIriPath
    digest(Get(endpoint.toAkkaUri).withCredentials)
  }

  /**
    * Moves a path from the provided ''sourceRelativePath'' to ''destRelativePath'' inside the nexus folder.
    *
    * @param name               the storage bucket name
    * @param sourceRelativePath the source relative path location
    * @param destRelativePath   the destination relative path location inside the nexus folder
    * @return The file attributes wrapped on the effect type F[] containing the metadata plus the file bytes, digest and location
    */
  def moveFile(name: String, sourceRelativePath: Uri.Path, destRelativePath: Uri.Path)(
      implicit cred: Option[AuthToken]): F[FileAttributes] = {
    val endpoint = (config.files(name) + slashIfNone(destRelativePath).toIriPath).toAkkaUri
    attributes(Put(endpoint, LinkFile(sourceRelativePath)).withCredentials)
  }

  private def slashIfNone(path: Uri.Path): Uri.Path =
    if (path.startsWithSlash) path else Slash(path)

}

// $COVERAGE-OFF$
object StorageClient {

  private[client] implicit class RequestCredentialsSyntax(private val req: HttpRequest) extends AnyVal {
    def withCredentials(implicit cred: Option[AuthToken]): HttpRequest =
      cred.map(token => req.addCredentials(OAuth2BearerToken(token.value))).getOrElse(req)
  }

  /**
    * Source where the Output is ByteString and the Materialization is Any
    */
  type AkkaSource = Source[ByteString, Any]

  private implicit val sourceUnmarshaller: FromEntityUnmarshaller[AkkaSource] =
    Unmarshaller { _ => entity =>
      Future.successful(entity.dataBytes)
    }

  private implicit val emptyBodyUnmarshaller: FromEntityUnmarshaller[NotUsed] =
    Unmarshaller.withMaterializer { implicit ec => implicit mt => entity =>
      entity.dataBytes.runFold("")(_ ++ _.utf8String).flatMap {
        case ""      => Future.successful(NotUsed.notUsed())
        case payload => Future.failed(UnmarshallingError(s"Payload '$payload'not expected"))
      }
    }

  private def httpClient[F[_], A: ClassTag](
      implicit L: LiftIO[F],
      F: Effect[F],
      ec: ExecutionContext,
      mt: Materializer,
      cl: UntypedHttpClient[F],
      um: FromEntityUnmarshaller[A]
  ): HttpClient[F, A] = new HttpClient[F, A] {
    private val logger = Logger(s"IamHttpClient[${implicitly[ClassTag[A]]}]")

    private def typeAndReason(string: String): Either[circe.Error, (String, String)] =
      parse(string).flatMap { json =>
        (json.hcursor.get[String]("@type"), json.hcursor.get[String]("reason")).mapN {
          case (tpe, reason) => (tpe, reason)
        }
      }

    override def apply(req: HttpRequest): F[A] =
      cl.apply(req).flatMap { resp =>
        resp.status match {
          case StatusCodes.Unauthorized =>
            cl.toString(resp.entity).flatMap { entityAsString =>
              F.raiseError[A](Unauthorized(entityAsString))
            }
          case StatusCodes.Forbidden =>
            logger.error(s"Received Forbidden when accessing '${req.method.name()} ${req.uri.toString()}'.")
            cl.toString(resp.entity).flatMap { entityAsString =>
              F.raiseError[A](Forbidden(entityAsString))
            }
          case StatusCodes.NotFound =>
            cl.toString(resp.entity).flatMap { entityAsString =>
              val (_, msg) = typeAndReason(entityAsString).getOrElse(("", entityAsString))
              F.raiseError[A](NotFound(msg))
            }
          case other if other.isSuccess() =>
            val value = L.liftIO(IO.fromFuture(IO(um(resp.entity))))
            value.recoverWith {
              case pf: ParsingFailure =>
                logger.error(
                  s"Failed to parse a successful response of '${req.method.name()} ${req.getUri().toString}'.")
                F.raiseError[A](UnmarshallingError(pf.getMessage()))
              case df: DecodingFailure =>
                logger.error(
                  s"Failed to decode a successful response of '${req.method.name()} ${req.getUri().toString}'.")
                F.raiseError(UnmarshallingError(df.getMessage()))
            }

          case other =>
            cl.toString(resp.entity).flatMap { entityAsString =>
              val (tpe, msg) = typeAndReason(entityAsString).getOrElse(("", entityAsString))
              tpe match {
                case "PathContainsLinks" => F.raiseError(InvalidPath(msg))
                case "PathInvalid"       => F.raiseError(InvalidPath(msg))
                case "PathAlreadyExists" => F.raiseError(InvalidPath(msg))
                case _ =>
                  logger.error(
                    s"Received '${other.value}' when accessing '${req.method.name()} ${req.uri.toString()}', response entity as string: '$msg'")
                  F.raiseError[A](UnknownError(other, msg))

              }
            }
        }
      }

    override def discardBytes(entity: HttpEntity): F[HttpMessage.DiscardedEntity] =
      cl.discardBytes(entity)

    override def toString(entity: HttpEntity): F[String] =
      cl.toString(entity)
  }

  /**
    * Constructs an ''StorageClient[F]'' from implicitly available instances of [[StorageClientConfig]], [[ActorSystem]] and [[Effect]].
    *
    * @tparam F the effect type
    * @return a new [[StorageClient]]
    */
  final def apply[F[_]: Effect](implicit config: StorageClientConfig, as: ActorSystem): StorageClient[F] = {
    implicit val mt: ActorMaterializer     = ActorMaterializer()
    implicit val ec: ExecutionContext      = as.dispatcher
    implicit val ucl: UntypedHttpClient[F] = HttpClient.untyped[F]
    new StorageClient(config,
                      httpClient[F, FileAttributes],
                      httpClient[F, Digest],
                      httpClient[F, AkkaSource],
                      httpClient[F, ServiceDescription],
                      httpClient[F, NotUsed])
  }
}
// $COVERAGE-ON$
