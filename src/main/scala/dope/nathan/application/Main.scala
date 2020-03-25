package dope.nathan.application

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.stream.scaladsl.{Sink, Source}
import com.datastax.oss.driver.api.core.uuid.Uuids

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global


object Main extends App {

  implicit val system: ActorSystem = ActorSystem()

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val sessionSettings = CassandraSessionSettings()

  implicit val cassandraSession: CassandraSession =
    CassandraSessionRegistry.get(system).sessionFor(sessionSettings)

  // how to get Cassandra version
  val version: Future[String] =
    cassandraSession
      .select("SELECT release_version FROM system.local;")
      .map(_.getString("release_version"))
      .runWith(Sink.head)

  println(Await.result(version, 15 seconds))

  // values
  val keyspace = "videodb0"
  val videosTable = s"$keyspace.videos"
  val value = UUID.fromString("99051fe9-6a9c-46c2-b949-38ef78858dd0") // add some uuid from your videos table

  // how to read
  import akka.stream.alpakka.cassandra.scaladsl.CassandraSource

  val ids: Future[immutable.Seq[UUID]] =
    CassandraSource(s"SELECT videoid FROM $videosTable").map(row => row.getUuid("videoid")).runWith(Sink.seq)

  val idsWhere: Future[UUID] =
    CassandraSource(s"SELECT * FROM $videosTable WHERE videoid = ?", value).map(_.getUuid("videoid")).runWith(Sink.head)

  println(Await.result(ids, 15 seconds))
  println(Await.result(idsWhere, 15 seconds))

  // how to read with specific settings
  import com.datastax.oss.driver.api.core.cql.{Row, SimpleStatement}

  val stmt = SimpleStatement.newInstance(s"SELECT * FROM $videosTable").setPageSize(2)

  val rows: Future[immutable.Seq[Row]] = CassandraSource(stmt).runWith(Sink.seq)

  val eventualStrings: Future[immutable.Seq[String]] = rows.map(_.map(_.getFormattedContents))

  println(Await.result(eventualStrings, 15 seconds))

  //how to write

  import akka.stream.alpakka.cassandra.CassandraWriteSettings
  import akka.stream.alpakka.cassandra.scaladsl.CassandraFlow
  import com.datastax.oss.driver.api.core.cql.{BoundStatement, PreparedStatement}

  import java.lang.{Long => JLong}

  case class VideoEvent(videoId: UUID, userName: String, eventTimestamp: UUID, event: String, videoTimestamp: JLong)

  import scala.util.Random

  def uuidGen: UUID = UUID.randomUUID()
  def timeUuidGen: UUID = Uuids.timeBased()
  def randomBigInt: JLong = scala.math.abs(Random.nextLong).asInstanceOf[JLong]

  val videoEventTable = s"$keyspace.video_event"

  val videoEvents = immutable.Seq(
    VideoEvent(uuidGen, "John", timeUuidGen, "event1", randomBigInt),
    VideoEvent(uuidGen, "Umberto", timeUuidGen, "event2", randomBigInt),
    VideoEvent(uuidGen, "James", timeUuidGen, "event3", randomBigInt)
  )

  val statementBinder: (VideoEvent, PreparedStatement) => BoundStatement =
    (videoEvent, preparedStatement) => preparedStatement.bind(videoEvent.videoId, videoEvent.userName, videoEvent.eventTimestamp, videoEvent.event, videoEvent.videoTimestamp)

  val written: Future[immutable.Seq[VideoEvent]] = Source(videoEvents)
    .via(
      CassandraFlow.create(CassandraWriteSettings.defaults,
        s"INSERT INTO $videoEventTable(videoid, username, event_timestamp, event, video_timestamp) VALUES (?, ?, ?, ?, ?)",
        statementBinder)
    )
    .runWith(Sink.seq)

  println(Await.result(written, 15 seconds))

}
