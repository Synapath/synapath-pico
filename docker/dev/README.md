# Android build environment

`docker compose build dev` creates a Java 17 / Android SDK 35 Linux amd64 image.
`make build` uses this image automatically, with source bind-mounted at
`/workspace/synapath-pico`. Gradle caches and the debug signing key use persistent
Docker volumes. Do not delete the signing volume if you need future debug APKs
to update installations signed with that key.

An existing compatible Android build image can be selected with
`PICO_DEV_IMAGE=operator-origin-dev:local make build`. This is the image used
for the initial migrated-source compile verification. The native Android
project needs no Godot or OpenXR toolchain. Run USB ADB commands on the Mac.
