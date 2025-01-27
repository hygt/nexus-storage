package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.{ContentType, Uri}
import ch.epfl.bluebrain.nexus.rdf.syntax._
import ch.epfl.bluebrain.nexus.storage.config.Contexts.resourceCtxUri
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{Decoder, Encoder}

// $COVERAGE-OFF$
object File {

  private implicit val config: Configuration = Configuration.default
    .copy(transformMemberNames = {
      case "@context" => "@context"
      case key        => s"_$key"
    })

  /**
    * Holds some of the metadata information related to a file.
    *
    * @param filename  the original filename of the file
    * @param mediaType the media type of the file
    */
  final case class FileDescription(filename: String, mediaType: ContentType)

  /**
    * Holds all the metadata information related to the file.
    *
    * @param location  the file location
    * @param bytes     the size of the file file in bytes
    * @param digest    the digest information of the file
    */
  final case class FileAttributes(location: Uri, bytes: Long, digest: Digest)
  object FileAttributes {
    implicit val fileAttrEncoder: Encoder[FileAttributes] =
      deriveEncoder[FileAttributes].mapJson(_.addContext(resourceCtxUri))
    implicit val fileAttrDecoder: Decoder[FileAttributes] = deriveDecoder[FileAttributes]
  }

  /**
    * Digest related information of the file
    *
    * @param algorithm the algorithm used in order to compute the digest
    * @param value     the actual value of the digest of the file
    */
  final case class Digest(algorithm: String, value: String)

  object Digest {
    val empty: Digest                           = Digest("", "")
    implicit val digestEncoder: Encoder[Digest] = deriveEncoder[Digest]
    implicit val digestDecoder: Decoder[Digest] = deriveDecoder[Digest]
  }

}
// $COVERAGE-ON$
