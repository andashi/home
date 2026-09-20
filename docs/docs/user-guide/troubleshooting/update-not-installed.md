# Launcher Cannot Be Updated

If you are trying to update the launcher, but the installation fails, you are most likely trying
to crossgrade from the F-Droid version to the GitHub version or vice versa. Both versions use
different signing keys, so you cannot update one with the other.

## Kvaesitso versions

There are two different release versions of Kvaesitso:

- **GitHub version**: This is the version that is released on GitHub. It includes all features and
  is also available on
  the [MM20 F-Droid repository](https://fdroid.mm20.de/app/de.mm20.launcher2.release) and on
  the [IzzyOnDroid F-Droid repository](https://apt.izzysoft.de/fdroid/index/apk/de.mm20.launcher2.release).
- **F-Droid version**: This version is built and signed, and distributed by the F-Droid maintainers.
  It is available on
  the [official F-Droid repository](https://f-droid.org/packages/de.mm20.launcher2.release). Some
  features disabled that depend on external APIs; most notably, there are fewer
  weather providers available. Furthermore, new versions are usually released with a delay (a few
  days up to a week).

## Check which version you have installed

Go to Settings > About. If the version number is something like `x.y.z`, you have the GitHub version
installed. If the version number ends in `-fdroid`, you have the F-Droid version installed.

## Switch between versions

You cannot switch versions without uninstalling the current version first, and uninstalling
removes the launcher's data.

::: warning
There is no backup and restore. Settings that the launcher manages through its `launcher.json`
configuration come back by applying that file again after reinstalling. Anything the configuration
does not cover — favorites, tags, custom labels and icons — is lost with the uninstall.
:::
