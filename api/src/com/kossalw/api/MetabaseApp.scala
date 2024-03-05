package com.kossalw.api

import zio.*
import Console.*

import sttp.client3.*
import httpclient.zio.HttpClientZioBackend
import upicklejson.*
import upickle.default.*
import sttp.client3.ResponseException
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.WebSockets

object MetabaseApp extends ZIOAppDefault {

  def manageResponse[A, E](response: Response[Either[E, A]]): Task[A] =
    response.body match {
      case Right(result) => ZIO.succeed(result)
      case Left(error)   => ZIO.fail(throw new Throwable(response.show()))
      // case Left(error: ResponseException[String, String]) => ZIO.fail(throw new Throwable(error.getMessage))
    }

  def login(backend: SttpBackend[Task, ZioStreams & WebSockets]): Task[MetabaseToken] =
    for {
      username <- ZIO.attemptUnsafe(_ => sys.env("METABASE_USER"))
      password <- ZIO.attemptUnsafe(_ => sys.env("METABASE_PASSWORD"))
      body = MetabaseLoginBody(username, password)

      token <- basicRequest
        .contentType("application/json")
        .post(uri"https://metabase.kossal.io/api/session")
        .body(body)
        .response(asJson[MetabaseToken])
        .send(backend)
        .flatMap(manageResponse)

      _ <- ZIO.attemptBlockingIOUnsafe(_ =>
        os.write(os.pwd / "cache" / "metabase-token.json", write[MetabaseToken](token))
      )
    } yield token

  // 1. Check if we have a metabase token
  // 2. If not then login and set token
  // 3. Try to make query
  // 4. If query fails due to bad token then do steps 2 and 3
  // 5. If it does not work, then die
  def useSession[A, E](
    request: RequestT[Empty, Either[String, String], Any] => RequestT[Identity, Either[E, A], Any]
  ): Task[A] =
    for {
      backend <- HttpClientZioBackend()
      token <- ZIO
        .attemptBlockingIOUnsafe(_ => os.read(os.pwd / "cache" / "metabase-token.json"))
        .either
        .flatMap {
          case Right(fileText) => ZIO.attempt(read[MetabaseToken](fileText))
          case Left(_) =>
            printLine("No cached metabase token found, trying to log in:") *> login(backend)
        }
      authenticatedRequest = request(
        basicRequest.contentType("application/json").header("X-Metabase-Session", token.id)
      )
      result <- authenticatedRequest
        .send(backend)
        .flatMap {
          case response if response.code.code == 401 =>
            printLine("Received 401, try to login again:") *>
              login(backend).flatMap { token =>
                authenticatedRequest
                  .send(backend)
                  .flatMap(manageResponse)
              }
          case response => manageResponse(response)
        }
    } yield result

  val appEffect = useSession { req =>
    req
      .post(uri"https://metabase.kossal.io/api/dataset")
      .body(
        """{"database":2,"native":{"query":"select * from \"Track\"","template-tags":{}},"type":"native","parameters":[]}"""
      )
  }.flatMap(sss => printLine(sss))

  def run = appEffect
}
