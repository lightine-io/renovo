# test-server

A small HTTPS server for the acceptance tests. It serves a version file and an APK from a folder
on your machine, so the sample app on an emulator can update itself. It is not published.

## Start it

```
./gradlew :test-server:run --args="--dir sample/server"
```

Put `<name>.json` (the version file) and `<name>.apk` in that folder. The server answers
`GET` and `HEAD` for `/<name>.json` and `/<name>.apk`. Everything else is 404. The APK route
supports `Range` and `If-Range`, so downloads can resume.

Options: `--port <n>` (default 8443), `--bind <address>` (default 0.0.0.0),
`--cert-dir <path>` (default `<dir>/.cert`), `--cut-after <bytes>`.

An emulator reaches this machine as `10.0.2.2`, so the version file URL is
`https://10.0.2.2:8443/<name>.json`.

## The certificate

On the first start the server creates a self-signed certificate with `keytool`. It is valid for
`10.0.2.2`, `127.0.0.1`, and `localhost`. It prints the path of `server.pem` and its SHA-256
fingerprint. The debug build of the sample app trusts that file through its network security
config, so copy `server.pem` into the sample's debug resources. Never commit it.

## Cut a download

`--cut-after 4000000` closes the connection of the first APK download after 4000000 bytes. The
download that follows is served normally. The library must resume it with `Range` (AC-3).
