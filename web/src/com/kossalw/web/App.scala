package com.kossalw.web

import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.ReactiveHtmlElement
import com.raquo.laminar.codecs.StringAsIsCodec
import org.scalajs.dom
import dom.html

import com.kossalw.shared.Metabase.*
import com.kossalw.web.helper.EventStreamExt

import scala.scalajs.js
import scala.scalajs.js.annotation.*

import scala.util.{Failure, Success, Try}

import upickle.default.{Reader, read}

object TodoApp {

  // State management
  sealed trait Action
  case class UpdateQueryResult(result: QueryResult) extends Action
  case class UpdateQuery(query: String) extends Action
  case class UpdateRunningQuery(running: Boolean) extends Action

  case class UpdateSchemas(schemas: Seq[Schema]) extends Action
  case class UpdateTables(tables: Seq[Table], schema: Schema) extends Action
  case class UpdateColumns(columns: Seq[Column], table: Table, schema: Schema) extends Action

  case class State(
    result: QueryResult,
    query: String,
    runningQuery: Boolean,
    databaseNavigator: DatabaseNavigator
  )

  val queryKey = "query"
  val initialQuery = Option(dom.window.localStorage.getItem("query")).getOrElse("select * from \"Track\" limit 5")
  val initialState = State(
    result = QueryResult(rows = ujson.Arr(), cols = Seq.empty, error = None),
    query = initialQuery,
    runningQuery = false,
    databaseNavigator = DatabaseNavigator(schemas = Map.empty)
  )

  val actionBus = new EventBus[Action]
  val stateSignal: Signal[State] = actionBus.events.scanLeft(initialState) {
    case (currentState, UpdateQueryResult(result)) =>
      currentState.copy(result = result, runningQuery = false)

    case (currentState, UpdateQuery(query)) =>
      dom.window.localStorage.setItem("query", query)
      currentState.copy(query = query)

    case (currentState, UpdateRunningQuery(running)) =>
      currentState.copy(runningQuery = running)

    case (currentState, UpdateSchemas(schemas)) =>
      currentState.copy(
        databaseNavigator = DatabaseNavigator(
          schemas = schemas.map(_ -> Map.empty).toMap
        )
      )

    case (currentState, UpdateTables(tables, schema)) =>
      val tablesMap: Map[Table, Seq[Column]] = tables.map(_ -> Seq.empty).toMap

      val newSchemas = currentState.databaseNavigator.schemas + (schema -> tablesMap)

      val newDatabaseNavigator = currentState.databaseNavigator.copy(schemas = newSchemas)

      currentState.copy(databaseNavigator = newDatabaseNavigator)

    case (currentState, UpdateColumns(columns, table, schema)) =>
      val newTablesMap: Map[Table, Seq[Column]] = currentState.databaseNavigator
        .schemas(schema) + (table -> columns)

      val newSchemas: Map[Schema, Map[Table, Seq[Column]]] =
        currentState.databaseNavigator.schemas + (schema -> newTablesMap)

      val newDatabaseNavigator = currentState.databaseNavigator.copy(schemas = newSchemas)

      currentState.copy(databaseNavigator = newDatabaseNavigator)
  }

  // Query management
  val queryCancelBus = new EventBus[Unit]
  val queryRunEventBus = new EventBus[Unit]
  val fetchQueryResult = queryRunEventBus.events
    .sample(stateSignal)
    .flatMap { state =>
      FetchStream
        .post(
          "/api/query",
          _.body(state.query),
          _.abortStream(queryCancelBus.events)
        )
        .recoverToCollect {
          case Success(result) => Some(result)
          case Failure(error) if error.getMessage().contains("AbortError") =>
            dom.console.log("The user aborted a request")
            None
          case Failure(error) =>
            dom.console.log("Failed request due to:")
            dom.console.error(error)
            None
        }
    }
    .map { response =>
      val resultColumns: Try[Seq[Column]] =
        Try { read[QueryColumns](response).cols }

      val json = ujson.read(response)

      val result = QueryResult(
        rows = json.obj.get("rows").fold(ujson.Arr())(_.arr),
        cols = resultColumns.toOption.getOrElse(Seq.empty),
        error = json.obj.get("error").map(_.str)
      )

      UpdateQueryResult(result)
    }
    .recoverToCollect {
      case Success(result) => Some(result)
      case Failure(error) =>
        dom.console.log("Failed request due to:")
        dom.console.error(error)
        None
    }

  // Database navigator management
  sealed trait DatabaseNavigatorAction
  case object FetchSchemas extends DatabaseNavigatorAction
  case class FetchTables(schema: Schema) extends DatabaseNavigatorAction
  case class FetchColumns(table: Table, schema: Schema) extends DatabaseNavigatorAction

  def getJson[B: Reader](route: String): EventStream[B] =
    FetchStream
      .get(route)
      .recoverToTry
      .map {
        case Success(result) => Some(result)
        case Failure(error) =>
          dom.console.log(s"Failed $route request due to:")
          dom.console.error(error)
          None
      }
      .filter(_.isDefined)
      .map(_.get)
      .map { result =>
        Try { read[B](result) } match {
          case Success(schemas) => Some(schemas)
          case Failure(error) =>
            dom.console.log(s"Failed $route json reading due to:")
            dom.console.error(error)
            None
        }
      }
      .filter(_.isDefined)
      .map(_.get)

  val databaseNavigatorEventBus = new EventBus[DatabaseNavigatorAction]
  val fetchDatabaseNavigatorStream = databaseNavigatorEventBus.events
    // Avoid fetching the database structure again
    .withCurrentValueOf(stateSignal.map(_.databaseNavigator.schemas))
    .collect[DatabaseNavigatorAction] {
      case FetchSchemas -> schemas if schemas.isEmpty => FetchSchemas
      case (action @ FetchTables(schema: Schema)) -> schemas if schemas.get(schema).fold(true)(_.isEmpty) => action
      case (action @ FetchColumns(table: Table, schema: Schema)) -> schemas
          if schemas.get(schema).fold(true)(_.get(table).fold(true)(_.isEmpty)) =>
        action
    }
    .flatMap {
      case FetchSchemas => getJson[Seq[Schema]]("/api/schemas").map(UpdateSchemas(_))

      case FetchTables(schema: Schema) =>
        getJson[Seq[Table]](s"/api/schema/${schema.name}").map(UpdateTables(_, schema))

      case FetchColumns(table: Table, schema: Schema) =>
        getJson[Seq[Column]](s"/api/table/${table.id}").map(UpdateColumns(_, table, schema))
    }

  def separator = div(
    cls := "w-100",
    styleAttr := "height: 1rem; background-color: #181b1e; border-shadow: inset 0 0.5em 1.5em rgba(0, 0, 0, .1), inset 0 0.125em 0.5em rgba(0, 0, 0, .15)"
  )

  def chevronSvg =
    svg.svg(
      svg.cls := "icon-chevron",
      svg.xmlns := "http://www.w3.org/2000/svg",
      svg.width := "16",
      svg.height := "16",
      svg.fill := "currentColor",
      svg.viewBox := "0 0 16 16",
      svg.path(
        svg.svgAttr("fill-rule", StringAsIsCodec, None) := "evenodd",
        svg.d := "M1.646 4.646a.5.5 0 0 1 .708 0L8 10.293l5.646-5.647a.5.5 0 0 1 .708.708l-6 6a.5.5 0 0 1-.708 0l-6-6a.5.5 0 0 1 0-.708z"
      )
    )

  def playSvg =
    svg.svg(
      svg.xmlns := "http://www.w3.org/2000/svg",
      svg.width := "16",
      svg.height := "16",
      svg.fill := "currentColor",
      svg.cls := "bi bi-stop-fill",
      svg.viewBox := "0 0 16 16",
      svg.path(
        svg.d := "M10.804 8 5 4.633v6.734zm.792-.696a.802.802 0 0 1 0 1.392l-6.363 3.692C4.713 12.69 4 12.345 4 11.692V4.308c0-.653.713-.998 1.233-.696z"
      )
    )

  def stopSvg =
    svg.svg(
      svg.xmlns := "http://www.w3.org/2000/svg",
      svg.width := "16",
      svg.height := "16",
      svg.fill := "currentColor",
      svg.cls := "bi bi-play",
      svg.viewBox := "0 0 16 16",
      svg.path(
        svg.d := "M5 3.5h6A1.5 1.5 0 0 1 12.5 5v6a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 11V5A1.5 1.5 0 0 1 5 3.5"
      )
    )

  def databaseSvg =
    svg.svg(
      svg.xmlns := "http://www.w3.org/2000/svg",
      svg.width := "16",
      svg.height := "16",
      svg.fill := "currentColor",
      svg.cls := "bi bi-database-fill",
      svg.viewBox := "0 0 16 16",
      svg.path(
        svg.d := "M3.904 1.777C4.978 1.289 6.427 1 8 1s3.022.289 4.096.777C13.125 2.245 14 2.993 14 4s-.875 1.755-1.904 2.223C11.022 6.711 9.573 7 8 7s-3.022-.289-4.096-.777C2.875 5.755 2 5.007 2 4s.875-1.755 1.904-2.223"
      ),
      svg.path(
        svg.d := "M2 6.161V7c0 1.007.875 1.755 1.904 2.223C4.978 9.71 6.427 10 8 10s3.022-.289 4.096-.777C13.125 8.755 14 8.007 14 7v-.839c-.457.432-1.004.751-1.49.972C11.278 7.693 9.682 8 8 8s-3.278-.307-4.51-.867c-.486-.22-1.033-.54-1.49-.972"
      ),
      svg.path(
        svg.d := "M2 9.161V10c0 1.007.875 1.755 1.904 2.223C4.978 12.711 6.427 13 8 13s3.022-.289 4.096-.777C13.125 11.755 14 11.007 14 10v-.839c-.457.432-1.004.751-1.49.972-1.232.56-2.828.867-4.51.867s-3.278-.307-4.51-.867c-.486-.22-1.033-.54-1.49-.972"
      ),
      svg.path(
        svg.d := "M2 12.161V13c0 1.007.875 1.755 1.904 2.223C4.978 15.711 6.427 16 8 16s3.022-.289 4.096-.777C13.125 14.755 14 14.007 14 13v-.839c-.457.432-1.004.751-1.49.972-1.232.56-2.828.867-4.51.867s-3.278-.307-4.51-.867c-.486-.22-1.033-.54-1.49-.972"
      )
    )

  def runningQuerySplit[A](trueScenario: => A)(falseScenario: => A): Signal[A] =
    stateSignal.map(_.runningQuery).map(if (_) trueScenario else falseScenario)

  val header = headerTag(
    cls := "mt-2 mb-3 mx-2 py-3 px-2", // Box
    cls := "rounded shadow", // Decorators
    cls := "row justify-content-between align-items-center", // Flex
    styleAttr := "background-color: #0C134F;",
    h1(cls := "col-8 text-light", "PostgreSQL editor"),
    button(
      cls := "col-4 btn",
      cls <-- runningQuerySplit("btn-danger")("btn-primary"),
      child <-- runningQuerySplit(stopSvg)(playSvg),
      nbsp,
      child.text <-- runningQuerySplit("Cancel query")("Run query"),
      nbsp,
      span(
        cls := "spinner-grow spinner-grow-sm",
        cls <-- runningQuerySplit("")("d-none")
      ),
      // Run or cancel a query on click:
      inContext {
        _.events(onClick)
          .sample(
            stateSignal
              .map(_.runningQuery)
          )
          .filter(!_)
          .mapToUnit --> queryRunEventBus.writer
      },
      inContext {
        _.events(onClick)
          .sample(
            stateSignal
              .map(_.runningQuery)
          )
          .filter(identity)
          .mapToUnit --> queryCancelBus.writer
      },
      // Edit the state of running query
      inContext {
        _.events(onClick)
          .sample(
            stateSignal
              .map(_.runningQuery)
          )
          .map(isRunning => UpdateRunningQuery(!isRunning)) --> actionBus.writer
      }
    )
  )

  val databaseNavigator = div(
    cls := "col-3 ps-0 pe-2",
    styleAttr := "height: 100%;",
    div(
      cls := "p-3", // Box
      cls := "rounded shadow", // Decorator
      cls := "text-light overflow-auto",
      styleAttr := "background-color: #0C134F; height: 100%;",
      div(
        cls := "pb-3 mb-3 border-bottom",
        p(
          cls := "mb-1 fs-5 fw-semibold",
          databaseSvg,
          nbsp,
          "Database Navigator"
        ),
        span(cls := "text-success", "Schema"),
        nbsp,
        "/",
        nbsp,
        span(cls := "text-warning", "Table"),
        nbsp,
        "/",
        nbsp,
        span(cls := "", "Column"),
        nbsp,
        span(
          cls := "badge text-bg-info",
          "Type"
        )
      ),
      ul(
        cls := "list-unstyled",
        children <-- stateSignal.map(_.databaseNavigator.schemas.toSeq).split(_._1) { (_, _, schemaSignal) =>
          val schemaId = schemaSignal.map(schema => s"collapse-schema-${schema._1.name}")
          li(
            cls := "bg-transparent px-0 py-0 border-0",
            button(
              cls := "btn btn-toggle px-0 py-0 text-success",
              typ := "button",
              dataAttr("bs-toggle") := "collapse",
              dataAttr("bs-target") <-- schemaId.map("#" + _),
              aria.expanded := true,
              aria.controls <-- schemaId,
              inContext {
                _.events(onClick)
                  .sample(schemaSignal)
                  .map(schema => FetchTables(schema._1)) --> databaseNavigatorEventBus.writer
              },
              inContext { ctx =>
                ctx.events(onClick).mapTo(ctx.ref.classList.toggle("custom-collapsed")) --> Observer.empty
              },
              chevronSvg,
              nbsp,
              b(cls := "text-success", child.text <-- schemaSignal.map(_._1.name))
            ),
            div(
              cls := "collapse",
              idAttr <-- schemaId,
              ul(
                cls := "list-unstyled",
                children <-- schemaSignal.map(_._2.toSeq.sortBy(_._1.name)).split(_._1) { (_, _, tableSignal) =>
                  val tableId = tableSignal.map(table => s"collapse-schema-${table._1.name}")
                  li(
                    cls := "bg-transparent px-0 py-0 border-0",
                    button(
                      cls := "btn btn-toggle px-2 py-0 text-warning",
                      typ := "button",
                      dataAttr("bs-toggle") := "collapse",
                      dataAttr("bs-target") <-- tableId.map("#" + _),
                      inContext {
                        _.events(onClick)
                          .sample(tableSignal, schemaSignal)
                          .map((table, schema) =>
                            FetchColumns(table._1, schema._1)
                          ) --> databaseNavigatorEventBus.writer
                      },
                      inContext { ctx =>
                        ctx.events(onClick).mapTo(ctx.ref.classList.toggle("custom-collapsed")) --> Observer.empty
                      },
                      chevronSvg,
                      nbsp,
                      em(cls := "text-warning", child.text <-- tableSignal.map(_._1.name))
                    ),
                    div(
                      cls := "collapse",
                      idAttr <-- tableId,
                      ul(
                        cls := "list-unstyled",
                        children <-- tableSignal.map(_._2.toSeq).split(_._1) { (_, _, columnSignal) =>
                          li(
                            cls := "bg-transparent px-5 py-0 text-white border-0",
                            small(
                              child.text <-- columnSignal.map(_.name),
                              nbsp,
                              span(
                                cls := "badge text-bg-info",
                                child.text <-- columnSignal.map(_.baseType.replace("type/", ""))
                              )
                            )
                          )
                        }
                      )
                    )
                  )
                }
              )
            )
          )
        }
      )
    )
  )

  val queryEditor = div(
    idAttr := "editor",
    cls := "col-9 shadow",
    onMountCallback { ctx =>
      import scala.scalajs.js

      val ace: js.Dynamic = js.Dynamic.global.ace

      ace.require("ace/ext/language_tools");

      val editor: js.Dynamic = ace.edit("editor")
      js.Dynamic.global.document.editor = editor

      // Theme
      editor.setTheme("ace/theme/monokai")
      editor.getSession().setMode("ace/mode/pgsql")
      editor.setOptions(
        js.Dynamic.literal(
          enableBasicAutocompletion = true,
          enableSnippets = true,
          enableLiveAutocompletion = true
        )
      )

      // Send text change to state
      editor
        .getSession()
        .addEventListener(
          "change",
          { (e0: dom.Event) =>
            val query: String = editor.getSession().getValue().toString
            actionBus.writer.onNext(UpdateQuery(query))
          },
          false
        )

      // Run query on shortcut
      editor.commands.addCommand {
        js.Dynamic.literal(
          name = "runquery",
          exec = (_: js.Dynamic) => {
            queryRunEventBus.writer.onNext(())
            actionBus.writer.onNext(UpdateRunningQuery(true))
          },
          bindKey = js.Dynamic.literal(mac = "cmd-enter", win = "ctrl-enter")
        )
      }
    },
    // Update autocompleter after database navigator changes
    stateSignal.map(_.databaseNavigator.schemas).changes --> { schema =>
      import js.JSConverters.*

      def createAutocompleteItem(value: String, score: Int, meta: String): js.Dynamic =
        js.Dynamic.literal(name = value, value = value, score = score, meta = meta)

      val ace: js.Dynamic = js.Dynamic.global.ace
      val langTools: js.Dynamic = ace.require("ace/ext/language_tools");

      val schemas: js.Array[js.Dynamic] =
        schema.keys.map(schema => createAutocompleteItem(schema.name, 300, "Schema")).toJSArray
      val tables: js.Array[js.Dynamic] =
        schema.flatMap { case (schema, tables) =>
          tables.keys.map(table => createAutocompleteItem(table.name, 400, schema.name))
        }.toJSArray
      val columns: js.Array[js.Dynamic] =
        schema.flatMap { case (schema, tables) =>
          tables.flatMap { case (table, columns) =>
            columns.map(column => createAutocompleteItem(column.name, 500, table.name))
          }
        }.toJSArray

      val wordList: js.Array[js.Dynamic] = schemas ++ tables ++ columns

      val rhymeCompleter = js.Dynamic.literal(
        getCompletions =
          (editor: js.Dynamic, session: js.Dynamic, pos: js.Dynamic, prefix: js.Dynamic, callback: js.Dynamic) => {
            callback(null, wordList)
          }
      )

      langTools.addCompleter(rhymeCompleter);
    },
    initialQuery
  )

  val tableResult = div(
    cls := "overflow-auto",
    span(
      cls := "text-danger",
      cls.toggle("d-none") <-- stateSignal.map(_.result.error.isEmpty),
      child.text <-- stateSignal.map(_.result.error.getOrElse(""))
    ),
    table(
      cls := "w-100 table table-sm table-dark table-striped table-hover caption-top",
      caption(
        cls := "text-info bold mx-3",
        child.text <-- stateSignal.map(_.result.rows.value.size).map(rows => s"Result: $rows rows")
      ),
      thead(
        tr(
          children <-- stateSignal.map(_.result.cols).splitByIndex { (_, _, columnSignal) =>
            th(
              cls := "p-2",
              styleAttr := "min-width: 100px;",
              p(cls := "text-break mb-2 text-light", child.text <-- columnSignal.map(_.name)),
              span(
                cls := "badge text-bg-info",
                child.text <-- columnSignal.map(_.baseType.replace("type/", ""))
              )
            )
          }
        )
      ),
      tbody(
        children <-- stateSignal.map(_.result.rows.value.toSeq).splitByIndex { (_, _, rowSignal) =>
          tr(
            children <-- rowSignal.map(_.arr.value.toSeq).splitByIndex { (idx, _, fieldSignal) =>
              td(
                cls := "p-2",
                styleAttr := "min-width: 100px;",
                span(
                  cls := "text-break text-light",
                  cls <-- fieldSignal.map {
                    case row if row.isNull                   => "badge rounded-pill text-bg-secondary"
                    case row if row.boolOpt.getOrElse(false) => "badge rounded-pill text-bg-success"
                    case row if row.boolOpt.exists(!_)       => "badge rounded-pill text-bg-warning"
                    case _                                   => ""
                  },
                  child.text <-- fieldSignal.map {
                    case row if row.isNull || row.boolOpt.isDefined || row.numOpt.isDefined => row.toString
                    case row if row.strOpt.isDefined                                        => row.str
                    case row => row.toString.replace("^\"", "").replace("\"$", "")
                  }
                )
              )
            }
          )
        }
      )
    )
  )

  val app = div(
    // Subscribe streams
    fetchQueryResult --> actionBus.writer,
    fetchDatabaseNavigatorStream --> actionBus.writer,
    onMountBind { _ =>
      EventStream.fromValue(FetchSchemas) --> databaseNavigatorEventBus.writer
    },
    // Html
    header,
    div(
      cls := "m-2 mt-0",
      cls := "row justify-content-between",
      styleAttr := "height: 40%",
      databaseNavigator,
      queryEditor
    ),
    tableResult
  )

  @JSExportTopLevel("main")
  def main(args: Array[String]): Unit = {
    render(dom.document.getElementById("root"), app)
  }
}
