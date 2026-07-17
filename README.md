# lpg-runtime

Java runtime for [LPG2](https://github.com/A-LPG/LPG2).

## Install / coordinates

| Field | Value |
|-------|-------|
| Package | Maven `org.lpg2:lpg.runtime` |
| Version | 1.9.0 (Maven; Java 8+) |
| Compatible generator | LPG2 ≥ 2.3.0 — see [`ecosystem/compat.json`](https://github.com/A-LPG/LPG2/blob/main/ecosystem/compat.json) |

```xml
<dependency>
  <groupId>org.lpg2</groupId>
  <artifactId>lpg.runtime</artifactId>
  <version>1.9.0</version>
</dependency>
```

Or compile `src/` directly (as LPG2 CI / calculator sample do).

## Minimum toolchain

JDK 8+ (CI uses 17; `maven.compiler.release=8`).

## Build and test

```bash
# Maven (if configured)
mvn -B package
# or
mkdir -p out && javac -encoding UTF-8 -d out $(find src -name '*.java')
```

## Wiring generated files

1. Generate with `-programming_language=java -table` and `dtParserTemplateF.gi`
2. Put generated sources on the classpath with this runtime
3. Sample: https://github.com/A-LPG/LPG2/tree/main/examples/calculator/java

## Features

| Feature | Status |
|---------|--------|
| Deterministic parser | yes |
| Backtracking | yes |
| Nested automatic AST | yes |
| `%Recover` prosthetic AST | yes |

## Publish status

- Channel: Maven Central (version `1.9.0`; see `.github/workflows/publish.yml`)
- Historical binary: SourceForge LPG 2.0.23 jar (legacy)

## Links

- Generator: https://github.com/A-LPG/LPG2
- Ecosystem: https://github.com/A-LPG/LPG2/blob/main/docs/ECOSYSTEM.md
