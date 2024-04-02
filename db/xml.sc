import scala.xml.XML

case class Country(
    mondialId: String,
    countryName: String,
    countryData: String
)

def escapeSingleQuote(str: String): String =
    str.replaceAll("'", "''")

val mondial = XML.loadFile("mondial-3.0.xml")

val countries = (mondial \ "country").map { country =>
    Country(
        mondialId = (country \ "@id").text,
        countryName = (country \ "@name").text,
        countryData = country.toString
    )
}.distinct.map { country =>
    "INSERT INTO COUNTRY(mondial_id, country_name, country_data) VALUES (" +
    "'" + escapeSingleQuote(country.mondialId) + "'" + "," +
    "'" + escapeSingleQuote(country.countryName) + "'" + "," +
    "'" + escapeSingleQuote(country.countryData) + "'" +
    ");"
}.mkString("\n")

os.write.over(os.pwd / "xml-dump.sql", countries)