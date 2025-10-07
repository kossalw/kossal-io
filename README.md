# Project structure

This is the public repository for the online book Data Analysis in PostgreSQL. The project is structure in 4 sections:

1. The **static** folder is the [quarto](https://quarto.org/docs/websites/) website directory for [kossal.io](https://kossal.io/) and is probably where you want to start if you want to add/update any page.
2. The **web**, **shared** and **api** folders are [Scala](https://www.scala-lang.org/) modules that power the [query editor](https://kossal.io/query-editor.html) which is used to run queries against a sample database for exercise. The **web** module is a [Scala.js](https://www.scala-js.org/) project that uses [laminar](https://laminar.dev/) to create a reactive UI. The **api** module is a [ZIO http](https://zio.dev/zio-http/) web server that receives requests from **web**, communicates with a [Metabase](https://www.metabase.com/) instance and return results. **shared** defines traits and objects both used in **web** and **api**.
3. The **kubernetes** folder defines helm charts, values and container files for deployment into a kubernetes cluster.
4. The **bin** folder defines bash scripts used to deploy to the kubernetes cluster.

# Static

You'll need to install [quarto](https://quarto.org/docs/websites/), the easiest option is to use [VS Code quarto plugin](https://quarto.org/docs/tools/vscode.html) which will install quarto globally and allow preview.

# Query editor

We used [mill](https://mill-build.com/mill/Intro_to_Mill.html) as the build tool for [Scala 3](https://www.scala-lang.org/) modules. You'll also need to install java, we recommend to use [coursier](https://get-coursier.io/) to install and manage java versions.

Use `bin/devserver` to compile the modules and spin up a locahost web server at [http://localhost:8080/](http://localhost:8080/). Right now it does not have support for hot reload so feel free to contribute if you feel daring. (I'd recommend using [vite](https://www.scala-js.org/doc/tutorial/scalajs-vite.html)).

# Deploying

Check the [DEPLOYING.md](DEPLOYING.md) file to create a local version.