import mill._, scalalib._, scalajslib._

object DotEnvModule {

  def parse(pathRef: PathRef): Map[String, String] = {
    parse(os.read(pathRef.path))
  }

  def parse(source: String): Map[String, String] =
    LINE_REGEX
      .findAllMatchIn(source)
      .map(keyValue => (keyValue.group(1), unescapeCharacters(removeQuotes(keyValue.group(2)))))
      .toMap

  private def removeQuotes(value: String): String = {
    value.trim match {
      case quoted if quoted.startsWith("'") && quoted.endsWith("'")   => quoted.substring(1, quoted.length - 1)
      case quoted if quoted.startsWith("\"") && quoted.endsWith("\"") => quoted.substring(1, quoted.length - 1)
      case unquoted                                                   => unquoted
    }
  }

  private def unescapeCharacters(value: String): String = {
    value.replaceAll("""\\([^$])""", "$1")
  }

  // shamefuly copied from SbtDotenv
  // https://github.com/mefellows/sbt-dotenv/blob/master/src/main/scala/au/com/onegeek/sbtdotenv/SbtDotenv.scala

  private val LINE_REGEX =
    """(?xms)
       (?:^|\A)           # start of line
       \s*                # leading whitespace
       (?:export\s+)?     # export (optional)
       (                  # start variable name (captured)
         [a-zA-Z_]          # single alphabetic or underscore character
         [a-zA-Z0-9_.-]*    # zero or more alphnumeric, underscore, period or hyphen
       )                  # end variable name (captured)
       (?:\s*=\s*?)       # assignment with whitespace
       (                  # start variable value (captured)
         '(?:\\'|[^'])*'    # single quoted variable
         |                  # or
         "(?:\\"|[^"])*"    # double quoted variable
         |                  # or
         [^\#\r\n]*         # unquoted variable
       )                  # end variable value (captured)
       \s*                # trailing whitespace
       (?:                # start trailing comment (optional)
         \#                 # begin comment
         (?:(?!$).)*        # any character up to end-of-line
       )?                 # end trailing comment (optional)
       (?:$|\z)           # end of line
    """.r

}

trait DotEnvModule extends JavaModule {

  def dotenvSources = T.sources { os.pwd / ".env" }

  def dotenv = T.input {
    dotenvSources().map(DotEnvModule.parse).foldLeft(Map[String, String]()) { _ ++ _ }
  }

  override def forkEnv = super.forkEnv() ++ dotenv()
}

trait AppScalaModule extends ScalaModule {
  def scalaVersion = "3.3.1"
}

trait AppScalaJSModule extends AppScalaModule with ScalaJSModule {
  def scalaJSVersion = "1.15.0"
}

object shared extends Module {
  trait SharedModule extends AppScalaModule with PlatformScalaModule {
    def ivyDeps = Agg(
      ivy"com.lihaoyi::upickle::3.2.0",
      ivy"com.lihaoyi::ujson::3.2.0"
    )
  }

  object jvm extends SharedModule
  object js extends SharedModule with AppScalaJSModule
}

object web extends AppScalaJSModule {
  def scalaJSVersion = "1.15.0"

  def moduleDeps = Seq(shared.js)

  override def ivyDeps = Agg(
    ivy"org.scala-js::scalajs-dom::2.8.0",
    ivy"com.raquo::laminar::16.0.0",
    ivy"com.raquo::airstream::16.0.0",
    ivy"io.laminext::fetch::0.16.2",
    ivy"com.lihaoyi::upickle::3.2.0",
    ivy"com.lihaoyi::ujson::3.2.0"
  )
}

object api extends AppScalaModule with DotEnvModule {
  override def generatedSources = super.generatedSources() ++ Seq(web.fastOpt())

  def moduleDeps = Seq(shared.jvm)

  override def ivyDeps = Agg(
    ivy"com.lihaoyi::os-lib:0.9.3",
    ivy"dev.zio::zio:2.1-RC1",
    ivy"dev.zio::zio-http:3.0.0-RC4",
    ivy"com.lihaoyi::upickle:3.2.0",
    ivy"com.lihaoyi::ujson:3.2.0",
    ivy"com.softwaremill.sttp.client3::zio:3.9.3",
    ivy"com.softwaremill.sttp.client3::upickle:3.9.3",
    ivy"com.lihaoyi::pprint:0.7.0"
  )
}
