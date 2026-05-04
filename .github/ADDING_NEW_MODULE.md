# Adding a New Module to the CI/CD System

Follow these steps to wire up a new module (Gradle-based Java mod/plugin or Python service).

---

## 1. Identify your module type

| Type | Example | Build tool | Deploy target |
|------|---------|------------|---------------|
| Java mod / plugin | `RealMarket/`, `sales-addon/` | Gradle | LXC container |
| Python service | `api/` | pytest + pip | systemd on host |

---

## 2. Register the module in `pr-checks.yml`

Add an entry to the `changes` job filter **and** a new build/check job:

```yaml
# In the `changes` job:
    filters: |
      # ... existing entries ...
      my-module:
        - 'my-module/**'

# New job (after the changes job):
  build-my-module:
    needs: changes
    if: needs.changes.outputs.my-module == 'true'
    uses: ./.github/workflows/_build-java.yml   # or _test-python.yml
    with:
      module_path:   my-module
      artifact_name: pr-my-module-jar
      jar_pattern:   "my-module-*.jar"           # adjust as needed

# Add to the `all-checks-passed` job's `needs:` list:
    needs:
      - ...
      - build-my-module
```

---

## 3. Register the module in `ci-dev.yml`

Add the same `changes` filter entry, then two jobs — build and deploy:

```yaml
# In the `changes` job — same filter as pr-checks.yml
      my-module:
        - 'my-module/**'

# Build job:
  build-my-module:
    needs: changes
    if: needs.changes.outputs.my-module == 'true'
    uses: ./.github/workflows/_build-java.yml
    with:
      module_path:   my-module
      artifact_name: dev-my-module-jar
      jar_pattern:   "my-module-*.jar"

# Deploy-to-DEV job:
  deploy-my-module-dev:
    needs: build-my-module
    if: needs.build-my-module.result == 'success'
    uses: ./.github/workflows/_deploy-lxc-jar.yml
    with:
      artifact_name:          dev-my-module-jar
      jar_name:               ${{ needs.build-my-module.outputs.jar_name }}
      lxc_container:          ${{ vars.DEV_MY_MODULE_CONTAINER }}   # see step 5
      lxc_project:            ${{ vars.DEV_LXD_PROJECT_NAME }}
      dest_path:              ${{ vars.DEV_MY_MODULE_DEST_PATH }}
      restart_command:        ${{ vars.DEV_MY_MODULE_RESTART_CMD }}
      health_type:            minecraft                              # or http / none
      health_target:          ${{ vars.DEV_MY_MODULE_HEALTH_TARGET }}
      health_timeout_minutes: 10
    secrets:
      SSH_PRIVATE_KEY: ${{ secrets.DEV_SSH_PRIVATE_KEY }}
      SERVER_HOST:     ${{ secrets.DEV_SERVER_HOST }}
      SERVER_USER:     ${{ secrets.DEV_SERVER_USER }}
      SERVER_PORT:     ${{ secrets.DEV_SERVER_PORT }}

# Add to `dev-summary`'s `needs:` list too.
```

---

## 4. Create a production deploy workflow

Copy `.github/workflows/deploy-mod.yml` as `deploy-my-module.yml` and adjust:

```yaml
name: Deploy My Module to Production
on:
  push:
    tags:
      - 'my-module-v*'     # <-- choose a unique tag prefix
  workflow_dispatch:
    inputs:
      container_name: { required: false, default: '' }

jobs:
  build:
    uses: ./.github/workflows/_build-java.yml
    with:
      module_path:   my-module
      artifact_name: prod-my-module-jar
      jar_pattern:   "my-module-*.jar"

  deploy:
    needs: build
    uses: ./.github/workflows/_deploy-lxc-jar.yml
    with:
      artifact_name:   prod-my-module-jar
      jar_name:        ${{ needs.build.outputs.jar_name }}
      lxc_container:   ${{ github.event.inputs.container_name || vars.MY_MODULE_CONTAINER_NAME }}
      lxc_project:     ${{ vars.LXD_PROJECT_NAME }}
      dest_path:       ${{ vars.MY_MODULE_DEST_PATH }}
      restart_command: ${{ vars.MY_MODULE_RESTART_CMD }}
      health_type:     minecraft
      health_target:   ${{ vars.MY_MODULE_HEALTH_TARGET }}
    secrets:
      SSH_PRIVATE_KEY: ${{ secrets.SSH_PRIVATE_KEY }}
      SERVER_HOST:     ${{ secrets.SERVER_HOST }}
      SERVER_USER:     ${{ secrets.SERVER_USER }}
      SERVER_PORT:     ${{ secrets.SERVER_PORT }}
```

---

## 5. Add GitHub secrets and variables

Go to **Settings → Secrets and variables → Actions**.

### Secrets (sensitive — passwords, keys)
| Secret | Description |
|--------|-------------|
| `DEV_SSH_PRIVATE_KEY` | Already shared with other dev jobs |
| `DEV_SERVER_HOST` | Already shared |
| `DEV_SERVER_USER` | Already shared |
| `DEV_SERVER_PORT` | Already shared |

### Variables (non-sensitive config)
| Variable | Example value | Description |
|----------|---------------|-------------|
| `DEV_MY_MODULE_CONTAINER` | `dev-skyblock-01` | DEV LXC container |
| `DEV_MY_MODULE_DEST_PATH` | `/server/mods` | Path inside container |
| `DEV_MY_MODULE_RESTART_CMD` | `systemctl restart mc` | Restart command |
| `DEV_MY_MODULE_HEALTH_TARGET` | `10.0.0.5:25565` | host:port for mcstatus |
| `MY_MODULE_CONTAINER_NAME` | `skyblock-template` | PROD LXC container |
| `MY_MODULE_DEST_PATH` | `/server/mods` | PROD path inside container |
| `MY_MODULE_RESTART_CMD` | `systemctl restart mc` | PROD restart command |
| `MY_MODULE_HEALTH_TARGET` | `10.0.0.10:25565` | PROD health target |

---

## 6. Full pipeline summary

```
feature branch
    │
    ▼  Pull Request → main
pr-checks.yml
    path-filtered build / test for changed modules
    └─ all-checks-passed (required status check)
    │
    ▼  Merge to main
ci-dev.yml
    detect changes → build → deploy to DEV → health check
    └─ dev-summary (fails loud if any DEV deploy is unhealthy)
    │
    ▼  Tag: my-module-v1.2.3  (only after DEV is green)
deploy-my-module.yml
    build → deploy to PROD → health check → auto-rollback on failure
```

---

## Reusable workflows reference

| Workflow | Purpose | Key inputs |
|----------|---------|------------|
| `_build-java.yml` | Build any Gradle module | `module_path`, `jar_pattern`, `artifact_name` |
| `_test-python.yml` | Run pytest | `test_path`, `requirements_file` |
| `_deploy-lxc-jar.yml` | Deploy JAR to LXC | `artifact_name`, `jar_name`, `lxc_container`, `health_type` |
| `_deploy-api-ssh.yml` | Deploy Python API | `deploy_sha`, `api_path`, `service_name`, `health_url` |
