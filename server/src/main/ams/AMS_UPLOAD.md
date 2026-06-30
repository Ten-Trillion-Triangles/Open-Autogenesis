# AMS Upload Runbook

This folder is produced by `./gradlew :server:stageAmsUpload` and is the
artifact consumed by the `ams upload` CLI. It contains a Beryx-built Linux
runtime for the Autogenesis dedicated server plus a validated
`accelbyte.properties`.

## Upload command

From inside this folder, run:

```bash
ams upload \
    -H <ams_host> \
    -c <ams_client_id> \
    -s <ams_client_secret> \
    -n <image_name> \
    -p . \
    -e bin/autogenesis-server
```

| Flag | Description |
|------|-------------|
| `-H` | AMS API host (e.g. `https://ams.accelbyte.io`). |
| `-c` | AMS client id (a service-account client with `NAMESPACE=<your namespace>:AMS` permission). |
| `-s` | AMS client secret paired with `-c`. |
| `-n` | Image name registered in AMS (e.g. `autogenesis-server`). |
| `-p` | Upload path. The literal `.` uploads the contents of this folder. |
| `-e` | Entrypoint script that AMS runs on the dedicated server VM. |

> UBuild invokes this command for you; the runbook only documents the shape so
> operators can re-run it manually for debugging.

## Runtime env vars AMS injects

AMS sets the following env vars on the dedicated server VM at runtime. They
override anything in `accelbyte.properties` (the staged file contains only
`AB_NAMESPACE`, `AB_CLIENT_ID`, `AB_CLIENT_SECRET`, `AB_BASE_URL`):

| Env var | Source | Read by |
|---------|--------|---------|
| `AB_DS_ID` | AMS fleet assignment | `AccelByteConfig.getDsId()` |
| `AB_DS_HUB_URL` | AMS namespace config | `AccelByteConfig.getDsHubUrl()` |
| `AB_WATCHDOG_URL` | AMS namespace config | `AccelByteConfig.getWatchdogUrl()` |
| `AB_REGION` | AMS region placement | `AccelByteConfig.getRegion()` |

> Do NOT add `AB_DS_*` to `accelbyte.properties`. AMS injects them; if you
> also set them in the file, the runtime env var wins anyway and the staged
> file becomes a footgun for future maintainers.

## References

- AMS upload docs: https://docs.accelbyte.io/guides/ams/ams-quickstart.html
- Beryx runtime output structure: `server/build/server-runtime/server-linux-x64/`
- Production staging: `./gradlew :server:stageAmsUpload` (hermetic, idempotent)
