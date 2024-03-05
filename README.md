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

We use [kubernetes](https://kubernetes.io/) and [helm](https://helm.sh/) to manage three deployments:

1. **metabase**: uses the Metabase container to expose the BI frontend and API to execute queries
2. **public**: nginx server that serves a static git repository
3. **query-editor-api**: interacts with the metabase API to power the PostgreSQL editor

> You can use [docker-desktop](https://docs.docker.com/desktop/kubernetes/) to reproduce the kubernetes cluster locally:

The steps to deploy on production are:

## Prerequisites

- Install [kubectl](https://kubernetes.io/docs/tasks/tools/#kubectl), [helm](https://helm.sh/) and [quarto](https://quarto.org/docs/websites/)
- Install [docker desktop](https://www.docker.com/products/docker-desktop/) with [multiplatform support](https://docs.docker.com/desktop/containerd/])
- (OPTIONAL for local clusters) Enable [kubernetes](https://docs.docker.com/desktop/kubernetes/) in docker desktop
- You should set up a managed database that has your sample database and [metabase database](https://www.metabase.com/docs/latest/installation-and-operation/migrating-from-h2) that your able to point through your kubernetes cluster
- Create a private github repo where you will store the static files created by [quarto](https://quarto.org/docs/websites/)

## 1. Authenticate to kubernetes (ONLY PRODUCTION)

This step depends on your cloud provider. For this project we used Digital Ocean so we needed to install [doctl](https://formulae.brew.sh/formula/doctl) and run the following scripts:

```bash
doctl auth login
doctl kubernetes cluster kubeconfig save XXX # Digital Ocean gives a command to get the kubernetes context
kubectl config get-contexts # Ensure that the context points to your kubernetes cluster
```

## 2. Upload secrets and configs

You should create an `.env` file based on the `.env.sample` file, filed it with your tokens and then run `bin/upload-config`.

## 3. Cert manager

To manage TLS file we used [cert-manager bitnami chart](https://artifacthub.io/packages/helm/bitnami/cert-manager):

```bash
# Install cert manager
helm install cert-manager -f kubernetes/values/cert-manager.yaml oci://registry-1.docker.io/bitnamicharts/cert-manager

# Apply issuer, be sure to check the values file to change it to your server
kubectl apply -f kubernetes/tls/issuer.yaml

# Apply certificate, be sure to check the values file to change it to your server
kubectl apply -f kubernetes/tls/certificate.yaml
```

## 4. Install Nginx ingress controller

An [Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/) is a kubernetes object that manages external access to services in a cluster, and specifically we use Nginx ingress controller to manage it.

```bash
# Install the bitnami chart for nginx-ingress-controller using values from the repository
helm install nginx-ingress-controller -f kubernetes/values/nginx-ingress-controller.yaml oci://registry-1.docker.io/bitnamicharts/nginx-ingress-controller
```

> Ensure that your DNS A records points to your kubernetes load balancer IP.

## 5. Deploy metabase

Run the following query and follow the logs until it finishes.

```bash
helm install metabase kubernetes/charts/metabase

# You should see a metabase pod been created
kubectl get pods

# Change the XXX for your metabase pod to follow logs, it should take 5 minutes the first time
kubectl logs -f metabase-XXX 
```

## 6. Deploy query-editor-api

We'll need to build a Docker image and store the image in a container registry, so follow the instructions of your cloud provider on how to access private containers. In DigitalOcean you have to use:

```bash
doctl registry login
```

Then use the UI to link the image registry to the k8 cluster. After that follow the steps:

1. Edit the `bin/deploy-api` script so that you use `helm install` instead of `helm upgrade`, this is only necesary for the first time.
2. Run `bin/deploy-api`.
3. Change `bin/deploy-api` to use `helm upgrade`.

If you use `kubectl get pods` you should see a `query-editor-api` pod been created.

## 7. Deploy static server public

We use a [Nginx bitnami chart](https://artifacthub.io/packages/helm/bitnami/nginx) to serve static files from a private git repository using [personal tokens](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens). Run the following commands:

```bash
# To render the quarto webpage and query editor UI, then upload it to the private github repo
bin/deploy-static

# Create a web server that serves content from your private github repo and automatically syncs every 60 seconds
helm install public -f kubernetes/values/nginx-web-server-values.yaml oci://registry-1.docker.io/bitnamicharts/nginx
```

If you use `kubectl get pods` you should see a `public-nginx` pod been created.