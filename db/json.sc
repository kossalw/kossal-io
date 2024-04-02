import $ivy.`com.lihaoyi::requests:0.8.0`

case class Row(
    gutenberId: Int,
    title: String,
    bookData: String
)

def escapeSingleQuote(str: String): String =
    str.replaceAll("'", "''")

def loop(max: Int = 10, counter: Int = 0, next: Option[String] = None, acc: Seq[Row] = Nil): Seq[Row] = {
    println(s"Starting loop: ${counter + 1}-$max")

    val gutenberUrl: String = next.getOrElse("https://gutendex.com/books/?page=1")
    val response = requests.get(gutenberUrl)

    val json = ujson.read(response.text())
    val newNext: Option[String] = Some(json("next").str)
    val results = json("results").arr

    val rows = results.map { result => 
        Row(
            gutenberId = result("id").num.toInt,
            title = result("title").str,
            bookData = result.render()
        )
    }

    val newRows = acc ++ rows

    if (counter + 1 >= max) newRows
    else loop(max, counter + 1, newNext, newRows)
}

val rows = loop(100)
val insertRows = rows.distinct.map { row =>
        "INSERT INTO BOOK(gutenberg_id, title, book_data) VALUES (" +
        row.gutenberId + "," +
        "'" + escapeSingleQuote(row.title) + "'" + "," +
        "'" + escapeSingleQuote(row.bookData) + "'" +
        ");"
    }.mkString("\n")
os.write.over(os.pwd / "json-dump.sql", insertRows)