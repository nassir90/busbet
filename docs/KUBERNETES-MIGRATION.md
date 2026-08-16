# Kubernetes migration plan

This document is in Simplified Technical English (ASD-STE100).
Refer to https://www.asd-ste100.org/.

**A note about compliance.** The official STE Dictionary is in the standard
document. You must request that document from ASD. I applied the writing rules
of STE: short sentences, the active voice, one meaning for each word, the same
word for the same idea, and simple verb tenses. I did not compare each word to
the official Dictionary, because I do not have it. Thus this document follows
the rules of STE, but it is not a verified STE document. Use a checker tool
such as HyperSTE to verify it.

STE permits technical names and technical verbs. In this document, "Kubernetes",
"k3s", "Traefik", "cloudflared", "SQLite", "Helm" and "Flux" are technical
names. "Deploy", "build", "push" and "pull" are technical verbs for this
subject.

Date of the survey: 2026-08-15. I read the data in section 1 from the two
hosts. I did not calculate it from the repository.

---

## 1. The present condition

### 1.1 The hetzner host

Name: `ubuntu-2gb-fsn1-1`. Address: 167.233.233.38.

| Item | Value |
| --- | --- |
| Operating system | Ubuntu 26.04 LTS, kernel 7.0.0-15 |
| Architecture | x86_64 |
| Processor | **1 vCPU** |
| Memory | **1.9 GiB. There is no swap space.** 869 MiB was available. |
| Disk | 38 GB on one volume (`/dev/sda1`). 25 GB is free. |
| Containers | **None.** Docker is not installed. Podman is not installed. |

The services operate as systemd user units. The user is `lab` (uid 1000). The
code is in a git clone at `/home/lab/Projects/busbet`. The services run
TypeScript directly with `npx tsx`. There is no build step.

| Unit | Port | Memory (with the tsx and esbuild child processes) |
| --- | --- | --- |
| `tfi.service` | 8108 | 52 MB (`node server.js`, no dependencies) |
| `gtfsr-stop-times.service` | 8110 | **364 MB** |
| `tfi-tenant-api.service` | 8120 | 123 MB |
| `gtfsr-collector.service` | none (it writes data only) | 162 MB |
| `caddy.service` | 8080 | 16 MB |
| `cloudflared.service` | none | 33 MB |
| | **Total** | **approximately 750 MB** |

The Caddy configuration has a route for `gtfsr-historical-view` on port 8130.
This service does not operate.

### 1.2 The route from the internet

```
internet -> Cloudflare edge -> cloudflared tunnel (9bb14599...)
         -> 127.0.0.1:8080 Caddy -> the service ports on the loopback address
```

These host names use the tunnel: `cattle.uzoukwu.net`, `pet.uzoukwu.net` and
`iompar.mpts.ie`.

Only SSH has an open public port. Caddy listens on the address 127.0.0.1 only.
The configuration contains `auto_https off` and `admin off`. Caddy discards the
access logs. This is deliberate, for privacy.

Caddy removes a path prefix for each service. For example, `handle_path
/gtfsr-stop-times/*` sends the request to 127.0.0.1:8110. Thus the Android
clients change the host name only. Caddy also supplies the static privacy
policy pages from `/srv/www/`.

### 1.3 The data

| Path | Size | Note |
| --- | --- | --- |
| `data/busbet.db` | 896 MB | The static GTFS data, in SQLite |
| `tfi-tenant-api/data/reports.db` | 28 KB | **The only data from users** |
| `gtfsr-collector/data/feeds/` | 4.4 GB | 33,457 `.pb.gz` files |
| `gtfsr-collector/data/vehicles/` | 701 MB | 33,457 files |

The archive contains data from 2026-07-23 to 2026-08-15. This is 23 days and
5.1 GB. Thus the archive increases by approximately 222 MB each day. There are
25 GB of free disk space. Thus the disk will become full at approximately the
start of December 2026. This problem is not related to Kubernetes. Refer to
section 7.

### 1.4 The lab host

**I could not survey the lab host. The host is offline.** SSH stops after the
time-out. Tailscale made the last connection 5 days ago. The address is
100.87.20.22. Thus all the data about the lab host in this document is
provisional.

The repository shows that the hetzner host is a copy of the lab host:

- The file `ports.env` contains this comment: "the real file lives at
  /home/lab/Projects/ports.env on lab, which is currently unreachable".
- The unit files on the hetzner host use the paths of the lab host. They also
  use `lab.slice`.
- The file `reference_grafana.md` describes an exporter. The exporter connects
  to `lab@lab` with SSH each 60 seconds. It examines the same archive.

Thus the lab host was the primary host for the collector. The hetzner host is
the alternative host.

You supplied these facts about the lab host: it is a Raspberry Pi, the
architecture is arm64, it has one disk, and Tailscale is the only route to it.

### 1.5 The present deploy procedure

The script `scripts/deploy-backend.sh` does these steps:

1. It copies the code with rsync. It does not copy `node_modules`, `data/` or
   `.env`.
2. It makes a snapshot of the live directory.
3. It runs `npm ci`.
4. It restarts the systemd unit.
5. It sends `GET /health` on the loopback address.
6. If the health test fails, it installs the snapshot again.

The GitHub workflow "Backend Deploy" uses this script. The workflow starts with
`workflow_dispatch` only. The operator must type "deploy". Five secrets must
exist. The workflow uses a GitHub Environment with the name "production". The
workflow controls `gtfsr-stop-times` and `tfi-tenant-api` only.

This deploy procedure is safe. The problem is different: a person or an agent
must start it.

---

## 2. The primary problem

**k3s will not operate on the hetzner host with the present services.**

A k3s server node uses approximately 500 MB to 700 MB of memory. This includes
the server process, containerd, kubelet, CoreDNS, Traefik and
local-path-provisioner. The services use approximately 750 MB. The host has
1.9 GB of memory and no swap space. Only 869 MB is available.

750 MB plus 600 MB is more than 869 MB. No configuration can correct this.

These decreases are possible, but they are not sufficient:

- `--disable servicelb,metrics-server` saves approximately 80 MB.
- A build step with `tsc` removes the tsx loader and the esbuild child process.
  This saves approximately 60 MB for the three services together.

One vCPU is also too small for k3s. The control plane, an image pull and a
service with a high load operate on one processor core. This causes a long
delay. The delay looks like a defective cluster.

**Therefore, increase the size of the hetzner host first.**

- Hetzner CX32 has 4 vCPU, 8 GB of memory and 80 GB of disk space. The cost is
  approximately 7 euros each month. This also corrects the disk space problem.
- Hetzner CX22 has 2 vCPU, 4 GB of memory and 40 GB of disk space. The cost is
  approximately 4 euros each month. This is the minimum size.

Examine the current prices, because prices change. To increase the size of a
Hetzner host, you must restart the host. The disk becomes larger in the same
volume.

If you cannot increase the size of the host, do not do this migration. Do phase
1 only. Phase 1 gives you images that you can build again. It also gives you a
quick method to install an older version. Phase 1 does not have the memory cost
of a control plane.

---

## 3. The recommended components

| Item | Recommendation | Reason |
| --- | --- | --- |
| Distribution | **k3s** | One binary file and one process. It uses SQLite and not etcd. It is the only possible choice for a Raspberry Pi and one small host. MicroK8s needs snap and more memory. k0s is satisfactory, but it has a smaller ecosystem. Talos is very good, but it needs immutable machines and it is difficult on a Raspberry Pi. |
| Datastore | The default k3s datastore (SQLite) | Do not use etcd. etcd needs three nodes. It also operates badly with the slow disk of a Raspberry Pi. One server means that the hetzner host is a single point of failure for the control plane. This is satisfactory here, because the workloads continue to operate when the control plane stops. |
| Ingress | **Traefik** (supplied with k3s) | It is already in k3s. It uses approximately 50 MB. The `handle_path` function of Caddy is the same as the `StripPrefix` middleware of Traefik. When you remove Caddy, you get memory again. |
| Public entry | **cloudflared as a Deployment in the cluster** | This keeps the present condition: no open public ports. Send the tunnel to the Traefik Service and not to 127.0.0.1:8080. Put the tunnel credentials in a Secret. |
| Storage | **local-path-provisioner** (supplied with k3s) | Each node has one disk. There is no shared storage. Thus each PV is local to one node. Longhorn and Rook are not possible with two nodes on a wide-area network. |
| Registry | **GHCR** (`ghcr.io/nassir90/busbet-*`) | There is no cost. The `GITHUB_TOKEN` in Actions already gives access. Private repositories operate with an `imagePullSecret`. |
| Network between the hosts | **Tailscale** | Tailscale is the only route to the lab host. Start k3s with `--node-ip` and `--flannel-iface` on `tailscale0`. |
| Deploy trigger | **Flux** (refer to section 6) | A controller in the cluster that pulls the configuration is the only method that removes the agent from the deploy. |

### The functions of the nodes

- **The hetzner host: the server (the control plane) and the workloads.** It
  has the public address, the tunnel and the data.
- **The lab host: an agent (a worker) only, with a taint.** A node that is
  unavailable for five days must not have the API server.

---

## 4. Your proposed directory structure

You proposed this structure:

```
charts/
deployments/app/default/
deployments/app/0.1.3.<hash>/{values-inherited.yml, values-override.yml, values-full.yml}
```

I recommend a different structure, for three reasons.

**Reason 1. The structure does the same work as Helm.** The three files
`values-inherited.yml`, `values-override.yml` and `values-full.yml` are the
same as the values layers of Helm. Helm does this with `-f base.yaml -f
env.yaml`. If you keep the layers and their result in git, you must maintain
the merge algorithm. The files will also become different from the true
condition.

**Reason 2. A directory for each version causes the removal problem.** You
identified this problem. Each deploy adds a directory. No data shows when you
can remove the oldest directory. The git history makes the removal feel
dangerous.

**Reason 3. Two versions of these services cannot operate together.**
`gtfsr-stop-times` and `tfi-tenant-api` use SQLite files. SQLite permits one
writer only. The files are on a disk that is local to one node. Two versions
with one `reports.db` file will cause damage to the data. Only `tfi` can
operate in two versions, because `tfi` has no data and no dependencies.

### The recommended structure

```
charts/
  busbet-service/            # ONE chart for all four services. Each service is
    Chart.yaml               # a Node process, a port, an optional PVC and an
    values.yaml              # ingress path.
    templates/
  cloudflared/

deploy/
  staging/
    kustomization.yaml
    gtfsr-stop-times.yaml    # A HelmRelease: the image tag, the environment
    tfi-tenant-api.yaml      # variables and the resource limits.
    tfi.yaml
  production/
    kustomization.yaml
    gtfsr-stop-times.yaml
    tfi-tenant-api.yaml
    tfi.yaml
    gtfsr-collector.yaml     # In production only. There must be one writer.
```

Use two namespaces: `busbet-staging` and `busbet-production`. The image tag is
the only item that changes for each release. The image tag is one line in one
file. The image automation of Flux writes this line.

To install an older version, use `git revert`. To remove a service, delete a
file. There are no version directories to remove.

If you want a preview for each pull request later, do this for `tfi` only. Use
a namespace that the cluster removes after a time limit. Do not use directories
in git.

### Your question: is there a Vercel for backends?

Yes. **Fly.io** is the most similar. Fly.io gives you many backends, a preview
environment for each branch, disk volumes, and a deploy after each push.
**Railway** and **Render** also do this. Fly.io can operate this stack. Fly.io
would make the largest part of this document unnecessary.

You must know which goal you have:

- If the goal is "deploys must occur without an agent", Fly.io gives you this
  quickly.
- If the goal is "I want to operate Kubernetes on my own nodes", this is a
  different goal. It is a correct goal: you learn Kubernetes, you have control,
  and you use the Raspberry Pi that you own. This document is for that goal.

The Kubernetes procedure needs approximately two days of work. It also uses
approximately 600 MB of memory permanently.

---

## 5. The phases

### Phase 0. The necessary steps before the migration

**WARNING: The file `reports.db` has one copy only. It is the only data that
you cannot make again. Make a backup before you change the hosts.**

- [ ] Increase the size of the hetzner host to 2 vCPU and 4 GB of memory, or
      more. Refer to section 2. All the subsequent steps need this.
- [ ] Copy the data to a different location. `reports.db` contains data from
      users. You can make `busbet.db` again from the GTFS static feed. You
      cannot make the 5.1 GB archive again.
- [ ] Start the lab host. Record the architecture, the memory, the disk space
      and the Raspberry Pi model. Compare its `ports.env` file and its data
      with the copies on the hetzner host.
- [ ] Select the correct archive. The two hosts operated collectors for the
      same routes. Thus the two archives can be different.
- [ ] Add 2 GB of swap space on the hetzner host. This gives protection against
      an out-of-memory condition during an image pull.

### Phase 1. Make the containers. Do not install Kubernetes.

Phase 1 is useful without the subsequent phases. If you decide not to use
Kubernetes, stop after phase 1.

- [ ] Write a Dockerfile for each service. The four services have the same
      structure: `node:22-slim`, then `npm ci --omit=dev`, then copy the source
      code, then `CMD`.
- [ ] Add a build step. Use `tsc`, then `node dist/server.js`. Do this for
      `gtfsr-stop-times`, `tfi-tenant-api` and `gtfsr-collector`. This removes
      the tsx loader and one esbuild child process for each service. It also
      changes a type error into a build failure. Without this step, a type
      error causes a repeated restart at run time. `tfi` does not need a build
      step, because it is JavaScript and it has no dependencies.
- [ ] Add `/health` to each service that does not have it. The deploy script
      uses this path.
- [ ] Build the images for two architectures with `docker buildx`:
      `linux/amd64` and `linux/arm64`. The lab host is a Raspberry Pi. Do this
      from the start. If you add arm64 later, you must build each tag again.
- [ ] Write a GitHub Actions workflow. After each push to `master`, build the
      image and push it to GHCR. Use two tags: `sha-<short>` and `master`.
- [ ] Test the containers with `docker compose up` on the hetzner host. Use a
      copy of the data. Continue to phase 2 only when all the services operate
      correctly.

### Phase 2. Install k3s on the hetzner host only

- [ ] Install k3s with this command. Keep Traefik.
      ```
      curl -sfL https://get.k3s.io | sh -s - --disable servicelb \
        --disable metrics-server --write-kubeconfig-mode 644
      ```
- [ ] Make the two namespaces: `busbet-staging` and `busbet-production`.
- [ ] Write the chart `charts/busbet-service`. Refer to section 4. One chart
      has four values files.
- [ ] Make the PVCs on `local-path`: `busbet-db` (2 Gi), `reports-db` (1 Gi)
      and `collector-archive`. Refer to section 7 for the size of
      `collector-archive`.
- [ ] Move the data. The collector must be stopped, because the data must not
      change during the copy. Do these steps:
      1. Stop each unit with `systemctl --user stop <unit>`.
      2. Copy the data with rsync to `/var/lib/rancher/k3s/storage/<pvc>/`.
      3. Start the pods.
      4. Examine the data.
      The archive is 5.1 GB. Thus this copy is slow.
- [ ] Move cloudflared into the cluster. Put the credentials file in a Secret.
      Put the configuration in a ConfigMap. Change the destination to
      `http://traefik.kube-system:80`.
- [ ] Change each Caddy `handle_path` route into an IngressRoute and a
      `StripPrefix` middleware.
- [ ] **WARNING: The privacy policy pages must continue to operate. These URLs
      are in the Google Play listing.** Use a small nginx pod with the files in
      a ConfigMap. Alternatively, keep `/srv/www` on the host and use a
      hostPath.
- [ ] Keep the systemd units on the host for two weeks. Disable them, but do
      not delete them. To go back to the old configuration, start the units and
      send cloudflared to the host again. Delete the units only after the
      system operates correctly through a restart.

### Phase 3. Install GitOps, so that no person starts a deploy

- [ ] Start Flux:
      ```
      flux bootstrap github --owner=nassir90 --repo=busbet --path=deploy/production
      ```
- [ ] Install four controllers only: `source-controller`, `helm-controller`,
      `image-reflector-controller` and `image-automation-controller`. Do not
      install `kustomize-controller` and `notification-controller`. This saves
      approximately 100 MB.
- [ ] Write an ImagePolicy for each service. The policy monitors GHCR.
      - For staging, use `semver: '>=0.0.0'`. Each new image deploys
        automatically.
      - For production, use a fixed tag. Flux makes a pull request that changes
        the tag. You approve the pull request.
- [ ] The approval of the pull request is the deploy. There is no dispatch, no
      confirmation text and no agent.
- [ ] Delete `backend-deploy.yml` and `deploy-backend.sh`. Do this only after
      the two weeks of phase 2.

The two environments make the automatic deploy safe. Staging accepts each
commit without a person. Production accepts a change when a person approves a
pull request that has one line.

### Phase 4. Add the lab host as a worker node

- [ ] Start Tailscale on the two hosts. Make sure that the connection is stable
      first. Do not add an unstable node to a cluster that operates correctly.
- [ ] Install the k3s agent on the lab host. Use `--node-ip <tailscale
      address>` and `--flannel-iface tailscale0`.
- [ ] Add the label `node-role=edge`. Add the taint `edge=true:NoSchedule`.
- [ ] Put only these workloads on the lab host: workloads that accept the taint
      and have no local data. For example, a second copy of `tfi`. A second
      collector that writes a different archive is also possible.
- [ ] Keep each workload that has a PVC on the hetzner host. Use
      `nodeSelector`. Write this in the values files, because a future operator
      can try to move them.

**Know this about phase 4.** The data is on a local disk. Thus two nodes do not
give you high availability. Two nodes give you two locations for the pods that
have no data. Your data stays in one location. If this is not sufficient value
for the complexity, stop after phase 3. Phase 3 is an acceptable final
condition.

---

## 6. The deploy trigger: the options that I examined

| Option | Memory | Result |
| --- | --- | --- |
| **Flux** (four controllers only) | approximately 250 MB | **Recommended.** It has a true reconcile loop. The image automation writes the tags into git. It continues to operate when you are not present. |
| Argo CD | approximately 700 MB | No. It uses more memory than the workloads. It gives you a user interface that you will open infrequently. |
| Actions runs `helm upgrade` through Tailscale | approximately 0 | This is the most simple option. It is satisfactory for phase 3 if Flux is too large. But it pushes the configuration: it puts the cluster credentials in GitHub, and it does not correct drift. |
| The automatic manifest directory of k3s (`/var/lib/rancher/k3s/server/manifests`) | 0 | It is already in k3s. But it cannot monitor a registry for a new tag. Thus it is not sufficient. |

### Your question: must this be independent of GitHub?

**No. Do not do this.** To be independent, you must operate your own build
system. For example, Gitea with Woodpecker, or Forgejo Actions. This uses
approximately 400 MB more memory. The hosts do not have this memory.

Use GitHub with a Flux controller in the cluster. This is the correct quantity
of dependency. If GitHub stops, your cluster continues to operate. You cannot
deploy new code, but nothing stops.

---

## 7. Correct these problems during the migration

### 7.1 The disk will become full at approximately the start of December 2026

The archive increases by 222 MB each day. There are 25 GB of free disk space.
This problem occurs with Kubernetes and without Kubernetes. A full disk on the
control plane node is worse than a full disk on a systemd host.

Select one of these methods:

- Keep the data for 30 days only. Delete the older data.
- Move the old data to a Hetzner Storage Box or to S3.
- Make the archive smaller.

Each directory contains 33,457 files. This quantity of files is also difficult
for the file system. It is also difficult for the Grafana exporter, which
examines the files each 60 seconds.

Make this decision in phase 0, because the decision gives you the size of the
collector PVC.

### 7.2 The file `reports.db` has no backup

The file is 28 KB. There is one copy. It is the only data in this stack that
you cannot make again. Write a CronJob that copies the file to object storage.
This is a small task.

### 7.3 The two hosts operated collectors

The two hosts collected data from the same feed. Thus the archives can be
different. Select the correct archive before you move 5.1 GB of data into a PV.

### 7.4 The service `gtfsr-stop-times` uses 364 MB

This is more memory than the other services together. Examine this service
before you write the resource limits. The service can read the 896 MB SQLite
file into memory. If this is correct, this service has more effect on the node
size than k3s.

---

## 8. Questions

1. **Can you increase the size of the hetzner host?** All the subsequent steps
   depend on this.
2. **What is the lab host, and is it sufficiently reliable to be a node?** The
   host was offline for 5 days. Such a node is frequently in the NotReady
   condition. It removes pods and it causes alerts. If the host is not
   reliable, do not put it in the cluster. Operate the collector on it as an
   independent systemd host. It can supply data to the same archive.
3. **Which archive is correct?** Refer to section 7.
4. **Do you want staging on the same node?** Two environments in 4 GB of memory
   is not much. Alternatively, operate staging with `docker compose` on your
   workstation. Then the cluster operates production only.
5. **What is the function of `gtfsr-historical-view`?** It has a Caddy route on
   port 8130, and it does not operate. Move it or delete it. Do not move
   configuration that you do not use.
