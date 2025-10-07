# Introduction

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
- You should set up a managed database that has your sample database and [metabase database](https://www.metabase.com/docs/latest/installation-and-operation/migrating-from-h2) that your able to point through your kubernetes cluster (there is a section for creating your own postgres database in your kubernetes cluster)
- Create a private github repo where you will store the static files created by [quarto](https://quarto.org/docs/websites/)

## 1. Create your kubernetes cluster

My recommendation would be to use a managed kubernetes cluster by the common cloud providers, but if you want to do it yourself in your own VPS, you can use [Canonical Kubernetes](https://ubuntu.com/kubernetes).

```bash
sudo apt update
sudo apt upgrade -y
sudo snap install k8s --classic --channel=1.34-classic/stable
sudo k8s bootstrap
k8s status # Wait until the cluster is ready before proceeding
sudo k8s enable load-balancer
```

You can test your new cluster creating a busybox pod:

```bash
k8s kubectl run -i --tty --rm busybox --image=busybox --restart=Never -- nslookup google.com
```

You should received an answer like this (it also tells you that your pods are able to send requests to the internet):

```bash
Server:		10.152.183.98
Address:	10.152.183.98:53

Non-authoritative answer:
Name:	google.com
Address: 2607:f8b0:402a:80a::200e

Non-authoritative answer:
Name:	google.com
Address: 192.178.164.100
Name:	google.com
Address: 192.178.164.139
Name:	google.com
Address: 192.178.164.101
Name:	google.com
Address: 192.178.164.102
Name:	google.com
Address: 192.178.164.113
Name:	google.com
Address: 192.178.164.138
```

To be able to connect to your k8s cluster in a remote client you'll need to ensure that port 6443 is open (`ufw status`), and then make sure that you're remote client has a ~/.kube/config with the certificates of your cluster. To do that first get your certificates in your cluster:

```bash
k8s kubectl config view --raw
```

Then on your remote client create a ~/.kube/config file copying the result of the past command, change the server IP address to your clusters IP. If you already had a ~/.kube/config then add a new entry (only copy the cluster, context and user list elements). After doing this you should be able to do a `kubectl get pods` and obtain a response.

## 2. Database

Again I recommend using a managed database through a cloud provider, but if you want to create your own postgres database in your cluster you can use [PG Cloud Native operator](https://cloudnative-pg.io/).

```bash
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm upgrade --install cnpg cnpg/cloudnative-pg
```

Now you can create your cluster:

```bash
helm upgrade --install postgres-db \
  --values kubernetes/values/postgres-cluster.yaml \
  cnpg/cluster
```

With your cluster created, you can now import the sql dump to your local database.

```bash
mkdir ~/db # Run this in server
scp db-dump.sql.zip user@server-ip:~/db # Run this on client with db-dump
unzip db/db-dump.sql.zip # Run this in server, also use `sudo apt install unzip` if your server does not have it installed
```

To import the dump file, you first need to port-forward your postgres cluster (run this on your server):

```bash
k8s kubectl port-forward service/postgres-db-cluster-rw 5432:5432
```

In another shell tab, get your

```bash
sudo apt install postgresql-client # Only if you don't have pg_restore installed in your server
# Before using pg_restore, it will prompt you for a password, you can use this to get the password
k8s kubectl get secrets postgres-db-cluster-superuser -o jsonpath="{.data.username}" | base64 -d
pg_restore -h 127.0.0.1 -p 5432 -W \
  -U $(k8s kubectl get secrets postgres-db-cluster-superuser -o jsonpath="{.data.username}" | base64 -d) \
  -d $(k8s kubectl get cluster postgres-db-cluster -o jsonpath="{.spec.bootstrap.initdb.database}") \
  db/db-dump.sql
```

## 3. Cert manager

To manage TLS file we used [cert-manager bitnami chart](https://artifacthub.io/packages/helm/bitnami/cert-manager). I'm using DigitalOcean domains so I need to upload the API key before proceeding.

```bash
kubectl delete secrets digitalocean-dns || true
kubectl create secret generic digitalocean-dns --from-literal=access-token="your-token"

# Install cert manager
helm install cert-manager -f kubernetes/values/cert-manager.yaml oci://registry-1.docker.io/bitnamicharts/cert-manager

# Apply issuer, be sure to check the values file to change it to your server
kubectl apply -f kubernetes/tls/issuer.yaml

# Apply certificate, be sure to check the values file to change it to your server
kubectl apply -f kubernetes/tls/certificate.yaml
```

Wait 1 minute until the certificate in in a READY status:

```bash
kubectl get certificate
```

## 4. Install Nginx ingress controller

An [Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/) is a kubernetes object that manages external access to services in a cluster, and specifically we use Nginx ingress controller to manage it.

```bash
# Install the bitnami chart for nginx-ingress-controller using values from the repository
helm install nginx-ingress-controller -f kubernetes/values/nginx-ingress-controller.yaml oci://registry-1.docker.io/bitnamicharts/nginx-ingress-controller
```

## 5. Deploy metabase

To deploy metabase make sure that you have run bin/upload-config with your .env file filled. The second step is to create the metabase database and roles:

```bash
kubectl port-forward service/postgres-db-cluster-rw 5432:5433
bin/initdb-metabase
helm upgrade --install metabase kubernetes/charts/metabase
```

After this, you can port-forward to your metabase instance and set up a robotic user and a connection to the db.

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

# Upload configmaps with server blocks for nginx
kubectl apply -f kubernetes/configmaps/nginx-configmaps.yaml

# Create a web server that serves content from your private github repo and automatically syncs every 60 seconds
helm install public -f kubernetes/values/nginx-web-server-values.yaml oci://registry-1.docker.io/bitnamicharts/nginx
```

## Prometheus Stack

This installs prometheus, grafana and altermanager.

> (Helm Chart)[https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack]

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm upgrade --install prometheus-stack \
    --values kubernetes/values/prometheus.yaml \
    --set grafana.adminUser=(kubectl get secrets grafana-secrets -o jsonpath="{.data.ADMIN_USER}" | base64 -d) \
    --set grafana.adminPassword=(kubectl get secrets grafana-secrets -o jsonpath="{.data.ADMIN_PASSWORD}" | base64 -d) \
    prometheus-community/kube-prometheus-stack
```

## Network

Lastly, to be able to reach your nginx controller you'll need to add your public IP to the [metallb](kubernetes/metallb/metallb-configmaps.yaml) config and then apply it.

```bash
kubectl apply -f kubernetes/metallb/metallb-configmaps.yaml
```

> Ensure that your DNS A records points to your kubernetes load balancer IP.