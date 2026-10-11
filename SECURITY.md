# Security

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's private vulnerability
reporting on this repository (Security tab, "Report a vulnerability"). You get an answer within
seven days.

## How dependencies are checked

- Every push to `main` sends GitHub the full list of dependencies of the published modules,
  including the dependencies of dependencies. So Dependabot alerts cover all of them.
- Dependabot opens update pull requests once a month, and security updates as soon as they appear.
- Alerts are reviewed before every release. A finding that affects only the build (a library that
  the Gradle plugins use and that never ships in an AAR) is dismissed with a note saying so.
- There is no dependency review on each pull request. GitHub shows a pull request's dependencies
  to the review only after the review has already timed out, so the check compared nothing.
  Dependabot catches vulnerabilities after a merge, and the review before each release catches
  them before they ship.

## What the library protects

Renovo installs an APK only when its package name and signing certificate match the installed
app, its version code is higher, and its SHA-256 hash matches the one in the version file, when
the version file gives one. The version file and the APK are fetched over HTTPS only. Details are
in the README.
