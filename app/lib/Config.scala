package lib

import com.madgag.git._
import com.netaporter.uri.Uri
import lib.Config.{Checkpoint, CheckpointDetails}
import org.eclipse.jgit.lib.{ObjectId, ObjectReader}
import org.joda.time.Period
import play.api.data.validation.ValidationError
import play.api.libs.json.{Json, _}

case class ConfigFile(checkpoints: Map[String, CheckpointDetails]) {

  lazy val checkpointsByName: Map[String, Checkpoint] = checkpoints.map {
    case (name, details) => name -> Checkpoint(name, details)
  }

  lazy val checkpointSet = checkpointsByName.values.toSet
}

object Config {

  def readsParseableString[T](parser: String => T): Reads[T] = new Reads[T] {
    def reads(json: JsValue): JsResult[T] = json match {
      case JsString(s) => parse(s) match {
        case Some(d) => JsSuccess(d)
        case None => JsError(Seq(JsPath() -> Seq(ValidationError("Error parsing string"))))
      }
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError("Expected string"))))
    }

    private def parse(input: String): Option[T] =
    scala.util.control.Exception.allCatch[T] opt (parser(input))

  }

  implicit val readsPeriod: Reads[Period] = readsParseableString(input => Period.parse("PT"+input))

  implicit val readsUri: Reads[Uri] = readsParseableString(input => Uri.parse(input))

  implicit val readsCheckpointDetails = Json.reads[CheckpointDetails]

  implicit val readsConfig = Json.reads[ConfigFile]

  def readConfigFrom(configFileObjectId: ObjectId)(implicit objectReader : ObjectReader) = {
    val fileJson = Json.parse(configFileObjectId.open.getCachedBytes(4096))
    Json.fromJson[ConfigFile](fileJson)
  }

  case class CheckpointDetails(url: Uri, overdue: Period, disableSSLVerification: Option[Boolean] = None) {
    val sslVerification = !disableSSLVerification.contains(true)
  }

  object Checkpoint {
    implicit def checkpointToDetails(c: Checkpoint) = c.details
  }

  case class Checkpoint(name: String, details: CheckpointDetails) {
    lazy val nameMarkdown = s"[$name](${details.url})"
  }

  case class RepoConfig(checkpointsByFolder: Map[String, JsResult[ConfigFile]]) {
    val validConfigByFolder: Map[String, ConfigFile] = checkpointsByFolder.collect {
      case (folder, JsSuccess(config, _)) => folder -> config
    }
    
    val foldersWithValidConfig: Set[String] = validConfigByFolder.keySet

    val foldersByCheckpointName: Map[String, Seq[String]] = (for {
      (folder, checkpointNames) <- validConfigByFolder.mapValues(_.checkpoints.keySet).toSeq
      checkpointName <- checkpointNames
    } yield checkpointName -> folder).groupBy(_._1).mapValues(_.map(_._2))

    val checkpointsNamedInMultipleFolders: Map[String, Seq[String]] = foldersByCheckpointName.filter(_._2.size > 1)

    require(checkpointsNamedInMultipleFolders.isEmpty, s"Duplicate checkpoints defined in multiple config files: $checkpointsNamedInMultipleFolders")

    val checkpointsByName: Map[String, Checkpoint] = validConfigByFolder.values.map(_.checkpointsByName).reduce(_ ++ _)
  }
}
