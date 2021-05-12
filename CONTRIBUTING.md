# Contributing

The Zeebe Debug Exporter is a community driven project, and as such contributions are more than
welcome.

To get started, it's recommended that you first [setup the project](#setup), then read through the
[architecture](/architecture.md) overview.

After that, you can look through the project's board for planned issues, or suggest new ones
yourself. Make sure to read the [contributor workflow](#workflow) before opening a pull-request -
this will save time for everyone involved.

## Setup

To contribute to the project, first
[create a fork](https://docs.github.com/en/github/getting-started-with-github/fork-a-repo) of the
project, or if you have write access,
[clone the project locally](https://docs.github.com/en/github/creating-cloning-and-archiving-repositories/cloning-a-repository).
Once that's done, you will need to install the following on your local machine:

> We recommend you use [SDKMAN](https://sdkman.io/) as the simplest way to install Java, maven,
> etc., versions, but it's  not necessary to do so

- JDK 11+, e.g. [OpenJDK](https://adoptopenjdk.net/)
- [Maven](https://maven.apache.org/) 3.6+, as the build system
- [Docker](https://www.docker.com/) 20+, primarily for acceptance tests

> Optionally, if you want to build a native image of the agent, you may want to install Mandrel or
GraalVM 21+.

Once done, navigate to the project and build the project to verify everything works.

```shell
cd /path/to/zeebe-debug-exporter
mvn clean install -T1C
```

### IDE

While you don't have to use a particular IDE - or even any IDE - to contribute, it will definitely
make your life easier.

As the project adheres to the same style guide as the
[Zeebe](https://github.com/camunda-cloud/zeebe/wiki/Code-Style), you may find it easier to use
IntelliJ and set it up as documented in the
[Zeebe Wiki](https://github.com/camunda-cloud/zeebe/wiki/Code-Style#integration-with-intellij).

### Troubleshooting

First, install the required dependencies one at a time, verifying the installation each time. This
means, for example, that after installing Docker, verify its installation by running a sample
container, e.g.:

```shell
docker run --rm hello-world
```

Consult each dependency's documentation on how to do this, and how to troubleshoot it if you're
having issues.

## Repository structure

Here is a quick rundown of how the repository is structured.

- [root](/): the root of the project contains the root [zeebe-debug-exporter-root](/ARCHITECTURE.md#zeebe-debug-exporter-root)
  module, as well as a handful of documentation and configuration files.

  Any configuration which is external to Maven but applies to the whole project (e.g. Editor Config,
  gitignore) should go here.

  Any files which integrates automatically with Github (e.g. OWNERS, LICENSE, README.md) should also
  go here.

  For now, all documentation also lives in the root - once it grows, it can be migrated to a `docs/`
  folder (minus `README.md` and `SECURITY.md`).

- [.github](/.github): contains Github integration specific files. You will find here the complete CI pipeline (using
  Github Actions), as well as pull request and issue templates.
- [agent](/agent): code for the [zeebe-debug-exporter-agent](/ARCHITECTURE.md#zeebe-debug-exporter-agent) module.
- [common](/common): code for the [zeebe-debug-exporter-common](/ARCHITECTURE.md#zeebe-debug-exporter-common) module.
- [exporter](/exporter): code for the [zeebe-debug-exporter](/ARCHITECTURE.md#zeebe-debug-exporte) module.
- [protocol](/protocol): code for the [zeebe-debug-exporter-protocol](/ARCHITECTURE.md#zeebe-debug-exporter-protocol) module.
- [server](/server): code for the [zeebe-debug-exporter-server](/ARCHITECTURE.md#zeebe-debug-exporter-server) module.
- [tests](/tests): code for the [zeebe-debug-exporter-tests](/ARCHITECTURE.md#zeebe-debug-exporter-tests) module.

## Workflow


