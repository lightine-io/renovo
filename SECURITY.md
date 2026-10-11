# Security

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's private vulnerability
reporting on this repository (Security tab, "Report a vulnerability"). You get an answer within
seven days.

## How dependencies are checked

- Every push to `main` submits the full resolved dependency graph of the published modules to
  GitHub, so Dependabot alerts cover transitive dependencies too.
- Dependabot opens update pull requests monthly and security updates as they appear.
- Alerts are reviewed before every release. Build-time-only findings (libraries that reach the
  build through the Gradle plugin classpath and never ship in an AAR) are dismissed with a note
  saying so.
- There is no per-pull-request dependency review: GitHub's snapshot of a pull request becomes
  visible to the review API only after the review's timeout, so the check compared nothing.
  Vulnerabilities are caught after merge by Dependabot and before release by the release gate.

## What the library protects

Renovo installs only an APK whose package name and signing certificate match the installed app,
whose version code is higher, and whose SHA-256 matches the version file when one is given. The
version file and the APK are fetched over HTTPS only. Details are in the README.
