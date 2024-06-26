package com.kossalw.api

import zio.*
import http.*
import template.Html

import upickle.default.{Writer, write}

// Shared classes with web
import com.kossalw.shared.Metabase.*

object MainServer extends ZIOAppDefault {

  private def manageMetabaseResponse[B: Writer](
    eff: MetabaseService => Task[B]
  ): URIO[EnvironmentConfig & MetabaseService, Response] =
    for {
      env <- ZIO.service[EnvironmentConfig]
      metabaseService <- ZIO.service[MetabaseService]
      response <- ZIO.unit.flatMap(_ => eff(metabaseService)).either.flatMap {
        case Right(result)                           => ZIO.succeed(Response.json(write[B](result)))
        case Left(error) if env.environment == "DEV" => ZIO.succeed(Response.text(error.getMessage))
        case Left(error) =>
          Console.printError(error).ignoreLogged *>
            ZIO.succeed(Response.error(Status.InternalServerError))
      }
    } yield response

  private val apiRoutes: Routes[EnvironmentConfig & MetabaseService, Nothing] =
    Routes(
      Method.GET / "api" / "health" -> handler(Response.ok),
      Method.GET / "api" / "schemas" -> handler(manageMetabaseResponse[Seq[Schema]](_.getAvailableSchemas)),
      Method.GET / "api" / "schema" / string("schema") -> handler { (schema: String, req: Request) =>
        manageMetabaseResponse[Seq[Table]](_.getSchemaTables(schema))
      },
      Method.GET / "api" / "table" / int("tableId") -> handler { (tableId: Int, req: Request) =>
        manageMetabaseResponse[Seq[Column]](_.getTableColumns(tableId))
      },
      Method.POST / "api" / "query" -> Handler.fromFunctionZIO { (req: Request) =>
        for {
          metabaseService <- ZIO.service[MetabaseService]
          response <- req.body.asString.flatMap(metabaseService.runQuery).either.flatMap {
            case Right(result) => ZIO.succeed(Response.json(result))
            case Left(error) =>
              Console.printError(error).ignoreLogged *>
                ZIO.succeed(Response.error(Status.InternalServerError))
          }
        } yield response
      }
    )

  def app = apiRoutes.toHttpApp @@ Middleware.debug

  def run =
    Console.printLine("Started server at http://localhost:8080/") <*
      // MetabasePool will shutdown after the Scoped is finished
      ZIO.scoped {
        for {
          scope <- ZIO.scope
          scopeLayer = ZLayer.fromZIO(ZIO.succeed(scope))
          configLayers = scopeLayer >>> AppConfig.layers
          _ <- Server.serve(app).provide(Server.default, configLayers)
        } yield ()
      }
}
