## Summary

<!-- One to three sentences: what this change does and why. -->

## Tests

<!-- Which tests were added or changed: unit, Robolectric, screenshot. Name any test that fails at random or only passes on some machines. -->

## Changelog

<!--
Every change a host app developer could notice gets an entry under `[Unreleased]` in CHANGELOG.md.
A trivial change, or one that only touches the build or CI, gets the `no-changelog` label instead.
The CI check "changelog · entry present" looks at the label, not at this description.
-->

- [ ] `CHANGELOG.md` updated, or the `no-changelog` label applied because: <!-- one line -->

## Checks

- [ ] `./gradlew spotlessCheck build` passes locally
- [ ] `./gradlew publishToMavenLocal -Prenovo.skipSigning=true` passes locally
- [ ] No organization, customer, or location names, and no secrets, in the diff
- [ ] Every new or changed Azerbaijani string is listed above
