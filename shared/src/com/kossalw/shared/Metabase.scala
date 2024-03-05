package com.kossalw.shared

import upickle.default.{ReadWriter => RW, macroRW, read, write}

object Metabase {
  sealed trait KeyType derives RW
  case object PrimaryKey extends KeyType
  case class ForeignKey(targetTable: String, targetColumn: Column) extends KeyType

  case class Column(
    name: String,
    @upickle.implicits.key("base_type")
    baseType: String
  ) derives RW

  case class MetabaseColumns(fields: Seq[Column]) derives RW

  case class Table(
    id: Int,
    name: String
  ) derives RW

  case class Schema(name: String) derives RW

  case class QueryResult(
    rows: ujson.Arr,
    cols: Seq[Column],
    error: Option[String]
  ) derives RW {
    require(
      rows.value.headOption.fold(0)(_.arr.size) == cols.size,
      s"Query results rows and cols do not match, Row: ${rows.value.headOption.fold(0)(_.arr.size)}, Col: ${cols.size}"
    )
    require((0 to 1).contains(rows.value.map(_.arr.size).distinct.size), "Rows don't have the same size")
  }

  case class QueryColumns(cols: Seq[Column]) derives RW

  case class DatabaseNavigator(
    schemas: Map[Schema, Map[Table, Seq[Column]]]
  )
}
