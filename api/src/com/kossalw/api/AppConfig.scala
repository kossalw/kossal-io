package com.kossalw.api

import zio.*

import sttp.client3.httpclient.zio.{HttpClientZioBackend, SttpClient}
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.WebSockets

case class EnvironmentConfig(environment: String)

object AppConfig {
  private val environmentLayer: TaskLayer[EnvironmentConfig] =
    ZLayer {
      ZIO.attemptUnsafe(_ => EnvironmentConfig(sys.env("ENVIRONMENT")))
    }

  // ** Metabase **
  // To create a MetabaseService we need to follow this steps:
  // 1. Import metabase config from env
  // 2. Create a ZIO Sttp backend
  // 3. Create ZPool of MetabaseTokens
  // 4. Create an MetabaseEnv that we'll use to create a MetabaseServiceImpl
  // 5. Convert the MetabaseServiceImpl to MetabaseService
  private val metabaseConfigLayer: TaskLayer[MetabaseConfig] =
    ZLayer {
      ZIO.attemptUnsafe { _ =>
        MetabaseConfig(
          host = sys.env("METABASE_API_HOST"),
          user = sys.env("METABASE_USER"),
          password = sys.env("METABASE_PASSWORD")
        )
      }
    }

  private val metabaseBackend: TaskLayer[SttpClient] =
    HttpClientZioBackend.layer()

  private val metabasePoolLayer: ZLayer[
    Scope & MetabaseConfig & SttpClient,
    Nothing,
    MetabasePool
  ] =
    ZLayer {
      for {
        tokens <- Ref.make(Set.empty[MetabaseToken])

        loginEffect = Metabase.login.flatMap(token => tokens.update(_ + token).as(token))
        pool <- ZPool.make(loginEffect, 0 to 10, 14.day)

        // Logout all metabase tokens on exit
        logoutAllTokens = tokens.get.map(_.map(Metabase.logout)).flatMap(ZIO.collectAllPar).ignoreLogged
        _ <- ZIO.addFinalizer(logoutAllTokens)

        env <- ZIO.environment[MetabaseConfig & SttpClient]
        _ = scala.sys.addShutdownHook {
          Unsafe.unsafe { implicit unsafe =>
            for {
              _ <- Runtime.default.unsafe.run(logoutAllTokens.provideEnvironment(env))
            } yield ()
          }
        }
      } yield pool
    }

  // MetabasePool will shutdown after the Scoped is finished
  private val metabaseLayers: RLayer[Scope, MetabaseEnv] = metabaseConfigLayer ++ metabaseBackend >+> metabasePoolLayer
  private val metabaseServiceLayer: RLayer[Scope, MetabaseService] =
    metabaseLayers.map { env =>
      ZEnvironment {
        new MetabaseServiceImpl {
          val metabaseEnv = env
        }
      }
    }

  val layers: RLayer[Scope, EnvironmentConfig & MetabaseService] =
    environmentLayer ++ metabaseServiceLayer
}
