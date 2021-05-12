# Architecture

This document describes the high-level architecture of the Zeebe Debug Exporter. If you want to
start contributing, this is the best place to start!

## Mission statement

The goal of the exporter is to provide a fast, low overhead exporter which can be used to test and
debug staging Zeebe clusters, paired with a minimal server implementation.

By fast, it means that the exporter, barring any server unavailability, should not cause back
pressure on the broker side.

Low overhead here applies only to the exporter, and means three things:

1. Tiny "fat" JAR
    - always try to use libraries provided by the Zeebe broker
2. Minimal memory overhead:
   - use only bounded memory structures (directly or indirectly)
   - avoid allocating objects unless necessary
3. Minimal CPU overhead
   - avoid repeating expensive operations (e.g. serialization)
   - keep the number of threads low
   - avoid blocking where possible

## High level view

![High level view](/docs/high-level-view.svg)

Records are exported via gRPC using
[a simple protocol](/protocol/src/main/resources/exporter.proto), with a single RPC, `Export`.
Simply put, there is one instance of the exporter per partition, with each instance opening a
bidirectional stream to the server. Records piped through as they come in, with some buffering/retry
logic in case of error. Eventually, the server may acknowledge records, either immediately or only
occasionally to reduce traffic.

> Note that the server relies on Zeebe ensuring there is no split brain situation, and there is
> always at most one live exporting client per partition.

## Modules

Let's start with a high level overview of the code base. At the time of writing (2021-05-02), the
project is made of 6 modules:

- [zeebe-debug-exporter-root](/pom.xml), the root modules, which acts as a parent POM for the other
  modules.
- [zeebe-debug-exporter-common](/common/pom.xml), a set of common components which are used by at
  least 2 modules in the project, e.g. parsing of the Netty transport settings.
- [zeebe-debug-exporter](/exporter/pom.xml), the exporter implementation. This is the core module
  which builds the exporter "fat" JAR, and the core of the project.
- [zeebe-debug-exporter-protocol](/protocol/pom.xml), the gRPC protocol used by the server/exporter
  to communicate.
- [zeebe-debug-exporter-server](/server/pom.xml), a minimal server implementation of the protocol,
  meant primarily for testing and demo purposes.
- [zeebe-debug-exporter-tests](/tests/pom.xml), a set of acceptance tests for the exporter and
  server.
- [zeebe-debug-exporter-agent](/agent/pom.xml), an experimental standalone CLI wrapper of the server
  module.

### zeebe-debug-exporter-root

The root module serves as a parent for all other modules, and defines any shared properties,
plugins, dependencies, etc., between them.

All dependencies and plugins should be defined here, with versions and configurations
being reused as much as possible. Default plugins which should apply to all modules are also
specified there (e.g. formatting, checkstyle, etc.).

### zeebe-debug-exporter-common

Any utility type and/or functionality shared by at least two modules should be placed here.

> As it's included in the exporter, it's important to keep the number of dependencies to a minimum.

### zeebe-debug-exporter

The core of the project, this module is in charge of implementing the
`io.zeebe.exporter.api.Exporter` interface and producing the exporter "fat" JAR. Any code committed
to this module should adhere to the low overhead goals mentioned in the mission statement.

> TODO: add a lifecycle diagram, and sequence diagrams representing the normal export path as well
> as the error handling paths.

The starting point of this module is the `ZeebeDebugExporter` class. This is the implementation of the
`io.zeebe.exporter.api.Exporter` interface, and the class which will get instantiated by the Zeebe
broker at runtime. This class is responsible for parsing the configuration of the exporter and
managing the lifecycle of all the other components.

Configuration of the exporter is handled by instantiating an instance of the
`ZeebeDebugExporterConfig` bean from the arguments map provided by the broker. Refer to
[the configuration section of the readme for more](/README.md#configuration). One important thing to
note is that only standard Java types or beans should be used for configuration field types. This is
simply to keep the configuration/parsing as simple as possible.

On open, the exporter will create and manage the lifecycle of all the other components - it's also
the one ensuring that everything is cleaned up when closing.

Once opened, the normal flow is as follows:

- A record is given to the exporter via `Exporter#export(Record)`
- The record is serialized; an exception is thrown if this is not possible, and the record will be
  retried (with back off).
- The record is offered to the `RecordBuffer`; an exception is thrown if it's full, which will
  cause the record to be retried (with back off).
- If the record is successfully offered, then the `RecordStreamer` is notified that there are new
  records available in the buffer.
