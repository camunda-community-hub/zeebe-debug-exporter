# Zeebe Debug Exporter

[![](https://img.shields.io/badge/Community%20Extension-An%20open%20source%20community%20maintained%20project-FF4700)](https://github.com/camunda-community-hub/community)
[![](https://img.shields.io/badge/Lifecycle-Proof%20of%20Concept-blueviolet)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#proof-of-concept-)
[![](https://img.shields.io/github/v/release/zeebe-io/zeebe-debug-exporter?sort=semver)](https://github.com/zeebe-io/zeebe-protocol-immutables/releases/latest)
[![Java CI](https://github.com/camunda-community-hub/zeebe-debug-exporter/actions/workflows/ci.yml/badge.svg)](https://github.com/camunda-community-hub/zeebe-debug-exporter/actions/workflows/ci.yml)

An exporter to easily debug containerized instances of Zeebe.

## Supported Zeebe versions

Release versions follow the same versioning as Zeebe versions, with the same compatibility
guarantees.

## Quickstart

Add the project to your dependencies:

```xml
<dependency>
  <groupId>io.zeebe</groupId>
  <artifactId>zeebe-debug-exporter</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Injecting & configuring

### Lossy mode

As mentioned, one of the goals is to be able to debug staged Zeebe clusters. In some cases, it can
be useful to see what a cluster is currently doing by hooking into its log. However, we still want
to have low overhead, and potentially having the exporter/server running all the time may be
counter-productive and/or cost prohibitive.

One potential solution is to have the exporter on all the time, but in lossy mode, and only start
the server when needed. Lossy mode in this sense means that when the server is unavailable, the
exporter immediately acknowledges records so as not to block normal usage of the cluster. In other
words, records are "lost", i.e. will not be re-exported.

This allows us to start the server whenever we want to hook into it, at which points records will
start being exported again.
