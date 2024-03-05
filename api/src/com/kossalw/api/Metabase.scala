package com.kossalw.api

import sttp.client3.*
import sttp.client3.ResponseException

import zio.*
import Console.*
import ZPool.*
import Duration.*

import httpclient.zio.{HttpClientZioBackend, SttpClient}
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.WebSockets

import upicklejson.*
import upickle.default.{ReadWriter => RW, macroRW, read, write}

// Shared classes with web
import com.kossalw.shared.Metabase.*

// API specific classes
case class MetabaseLoginBody(username: String, password: String) derives RW

case class MetabaseLogoutBody(
  @upickle.implicits.key("metabase-session-id")
  id: String
) derives RW

case class MetabaseToken(id: String) derives RW

// Exceptions
case class MetabaseInvalidToken(message: String) extends Exception(message: String)

// Config
case class MetabaseConfig(
  host: String,
  user: String,
  password: String
)

type SttpRequest[A, E] = RequestT[Empty, Either[String, String], Any] => RequestT[Identity, Either[E, A], Any]

type MetabasePool = ZPool[Throwable, MetabaseToken]
type MetabaseEnv = MetabaseConfig & SttpClient & MetabasePool

// The Metabase object holds helper functions
object Metabase {
  val extractConfig = ZIO.environment[MetabaseConfig].map(_.get[MetabaseConfig])
  val extractHost = extractConfig.map(_.host)
  private val extractUser = extractConfig.map(_.user)
  private val extractPassword = extractConfig.map(_.password)

  val extractBackend = ZIO.environment[SttpClient].map(_.get[SttpClient])
  val extractPool = ZIO.environment[MetabasePool].map(_.get[MetabasePool])

  def manageResponse[A, E](response: Response[Either[E, A]]): Task[A] =
    if (response.code.code == 401)
      ZIO.fail(new MetabaseInvalidToken("Invalid metabase token, should try to get new token"))
    else
      response.body match {
        case Right(result) => ZIO.succeed(result)
        // case Left(error) if error.isInstanceOf[ResponseException[String, String]] =>
        //   ZIO.fail(new Throwable(error.asInstanceOf[ResponseException[String, String]].getMessage))
        case Left(error) => ZIO.fail(new Throwable(response.show()))
      }

  def login: RIO[MetabaseConfig & SttpClient, MetabaseToken] =
    for {
      config <- extractConfig
      backend <- extractBackend
      token <- basicRequest
        .contentType("application/json")
        .post(uri"${config.host}/session")
        .body(MetabaseLoginBody(config.user, config.password))
        .response(asJson[MetabaseToken])
        .send(backend)
        .flatMap(manageResponse)
    } yield token

  def logout(token: MetabaseToken): RIO[MetabaseConfig & SttpClient, Unit] =
    for {
      host <- extractHost
      backend <- extractBackend
      _ <- basicRequest
        .contentType("application/json")
        .delete(uri"$host/session")
        .body(MetabaseLogoutBody(token.id))
        .send(backend)
        .either
      _ = println("Tried to logout Metabase token")
    } yield ()

  def useSession[A, E](request: SttpRequest[A, E]): RIO[SttpClient & MetabasePool, A] = {
    val effect = ZIO.scoped {
      for {
        token <- extractPool.flatMap(_.get)
        authenticatedRequest = request(
          basicRequest.contentType("application/json").header("X-Metabase-Session", token.id)
        )
        backend <- extractBackend
        result <- authenticatedRequest
          .send(backend)
          .flatMap(manageResponse)
      } yield result
    }

    effect.either.flatMap {
      case Right(value) => ZIO.succeed(value)
      case Left(e: MetabaseInvalidToken) =>
        println("Received 401, try to login again:")
        effect
      case Left(e) => ZIO.fail(e)
    }
  }

  def useSessionWithHost[A, E](requestWithHost: String => SttpRequest[A, E]): RIO[MetabaseEnv, A] =
    extractHost.flatMap(host => useSession(requestWithHost(host)))
}

// We want to expose an implementation of the MetabaseService
trait MetabaseService {
  def getAvailableSchemas: Task[Seq[Schema]]
  def getSchemaTables(schemaName: String): Task[Seq[Table]]
  def getTableColumns(tableId: Int): Task[Seq[Column]]
  def runQuery(query: String): Task[String]
}

// This trait has the internals of how me make the service works but we won't expose it
trait MetabaseServiceImpl extends MetabaseService {
  import Metabase.*

  val metabaseEnv: ZEnvironment[MetabaseEnv]

  override def getAvailableSchemas: Task[Seq[Schema]] =
    useSessionWithHost { host => req => req.get(uri"$host/database/2/schemas").response(asJson[Seq[String]]) }
      .map(_.map(Schema(_)))
      .provideEnvironment(metabaseEnv)

  override def getSchemaTables(schemaName: String): Task[Seq[Table]] =
    useSessionWithHost { host => req => req.get(uri"$host/database/2/schema/$schemaName").response(asJson[Seq[Table]]) }
      .provideEnvironment(metabaseEnv)

  override def getTableColumns(tableId: Int): Task[Seq[Column]] =
    useSessionWithHost { host => req =>
      req.get(uri"$host/table/$tableId/query_metadata").response(asJson[MetabaseColumns])
    }.map(_.fields).provideEnvironment(metabaseEnv)

  override def runQuery(query: String): Task[String] = useSessionWithHost { host => req =>
    req
      .post(uri"$host/dataset")
      .body(s"""{"database":2,"native":{"query":"${query
          .replaceAll("\"", "\\\\\"")}","template-tags":{}},"type":"native","parameters":[]}""")
  }.flatMap { resp =>
    ZIO.attemptBlocking {
      val json = ujson.read(resp)

      val newJson = json.obj.get("error") match {
        case Some(error) => ujson.Obj("error" -> error.str)
        case None =>
          val rows = json.obj("data").obj("rows").arr
          val cols = json.obj("data").obj("cols").arr
          ujson.Obj(
            "rows" -> rows,
            "cols" -> cols
          )
      }

      ujson.write(newJson)
    }
  }.provideEnvironment(metabaseEnv)
}
