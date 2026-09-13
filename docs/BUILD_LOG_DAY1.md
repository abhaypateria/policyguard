# BUILD LOG — Day 1: Foundation

> Self-contained handoff document. Read this top-to-bottom and you can be grilled on
> everything we built on Day 1 without seeing the rest of the project.

---

## a. WHAT WE BUILT TODAY

**Goal:** Stand up the empty-but-running skeleton of the PolicyGuard backend and its
database — no features yet, just proof that the plumbing works.

**Outcome:** A Spring Boot app (Java 21, Maven) that starts an embedded web server on
port 8080 and answers `GET /health` with `"PolicyGuard is running"`. Alongside it, a
PostgreSQL database (using the pgvector image) running in Docker on host port 5433,
which Spring connects to on startup.

Where we are in the overall architecture:

```
TARGET:  React → Spring Boot (Controller → Service → Repository) → PostgreSQL + pgvector
TODAY:   Client → Spring Boot → HealthController         (+ PostgreSQL running & connected)
```

We built only the Controller layer + the running database. Service, Repository,
Entities, PDF upload, embeddings, vector search, and RAG are all still to come.

---

## b. COMPONENT LIST

| File | Problem it solves | What it does | Architecture position | Calls / called by |
|------|-------------------|--------------|-----------------------|-------------------|
| `pom.xml` | How does the build know what to download/compile? | Declares Java 21, Spring Boot 3.4.13, and the 4 starters + Postgres driver | Build config (outside the app) | Read by Maven, not by our code |
| `docker-compose.yml` | We need Postgres without installing it manually | Runs the pgvector Postgres image as a container on host port 5433 | External datastore | Started by `docker compose`; Spring connects to it |
| `application.properties` | Where is the DB and how should JPA behave? | Gives Spring the JDBC URL, credentials, and JPA settings | Config read at startup | Read by Spring Boot auto-config |
| `PolicyGuardApplication.java` | Something has to start the whole app | `main()` that boots Spring and starts Tomcat; triggers component scanning | Entry point (top of everything) | Called by the JVM; starts all beans |
| `controller/HealthController.java` | We need to prove the server is up | Maps `GET /health` to a method returning a status string | Controller layer | Called by Spring MVC when a request hits `/health` |
| `.mvn/`, `mvnw`, `mvnw.cmd` | Maven isn't installed globally | The Maven wrapper — downloads and runs the right Maven version for us | Build tooling | Invoked as `.\mvnw.cmd ...` |

---

## c. KEY CODE (full contents)

### pom.xml
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.13</version>
</parent>
<groupId>com.policyguard</groupId>
<artifactId>policyguard</artifactId>
<properties><java.version>21</java.version></properties>
<dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
</dependencies>
```
(Full file in `pom.xml`.)

### PolicyGuardApplication.java
```java
package com.policyguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PolicyGuardApplication {
    public static void main(String[] args) {
        SpringApplication.run(PolicyGuardApplication.class, args);
    }
}
```

### controller/HealthController.java
```java
package com.policyguard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/health")
    public String health() {
        return "PolicyGuard is running";
    }
}
```

### application.properties
```properties
spring.application.name=policyguard
server.port=8080

spring.datasource.url=jdbc:postgresql://localhost:5433/policyguard
spring.datasource.username=policyguard
spring.datasource.password=policyguard
spring.datasource.driver-class-name=org.postgresql.Driver

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
```

### docker-compose.yml
```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: policyguard-postgres
    environment:
      POSTGRES_DB: policyguard
      POSTGRES_USER: policyguard
      POSTGRES_PASSWORD: policyguard
    ports:
      - "5433:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U policyguard -d policyguard"]
      interval: 5s
      timeout: 5s
      retries: 5
volumes:
  pgdata:
```

---

## d. HOW IT ALL CONNECTS — one `/health` request, end to end

1. **Browser** sends `GET http://localhost:8080/health`.
2. **Embedded Tomcat** (the web server bundled inside our app by `spring-boot-starter-web`)
   accepts the TCP connection on port 8080.
3. Tomcat hands the request to the **DispatcherServlet** — Spring MVC's single "front
   controller" that receives every HTTP request.
4. The DispatcherServlet looks at its **handler mappings** and sees that `GET /health`
   is mapped to `HealthController.health()` (because of `@GetMapping("/health")`).
5. It **invokes the method**. The method returns the Java String `"PolicyGuard is running"`.
6. Because the class is `@RestController`, Spring writes that String straight into the
   **HTTP response body** (status 200, content-type text/plain).
7. Tomcat sends the response back; the **browser** displays `PolicyGuard is running`.

Note: this path never touches the database. The DB is connected at **startup** (Spring
opens a connection pool to Postgres), but `/health` itself uses only the Controller layer.

---

## e. DESIGN DECISIONS

| Decision | Chosen | Rejected | Why |
|----------|--------|----------|-----|
| Boot version | **3.4.13** | 4.1.1 (Initializr default) | v4 renamed starters (`-webmvc`) and wouldn't match the video; 3.4.x is proven-compatible with Spring AI 1.0 which we need in Section 3 |
| DB image | **pgvector/pgvector:pg16** | plain `postgres:16` | Avoids swapping databases in Section 3 when we add vector search — same image the whole way |
| Host DB port | **5433** | 5432 | 5432 may already be taken by a local Postgres install; 5433 sidesteps the clash |
| Maven | **wrapper (`mvnw.cmd`)** | global `mvn` | Maven isn't installed globally; the wrapper downloads the correct version automatically and is reproducible on any machine |
| Layers today | **Controller only** | pre-making empty service/repo | We only create what's actually needed; empty placeholder classes are noise |
| Lombok | **not used** | Lombok | Keep every line explicit and understandable for a first project |

---

## f. NEW TERMS (one sentence each)

- **Spring Boot** — a framework that lets you build a runnable Java web app with almost
  no manual setup (server, config, wiring all handled for you).
- **@SpringBootApplication** — one annotation that switches on component scanning +
  auto-configuration and marks the app's entry point.
- **@RestController** — marks a class whose methods handle web requests and return data
  directly in the HTTP response (not HTML pages).
- **@GetMapping** — maps an HTTP GET request for a given URL path to a specific method.
- **Embedded Tomcat** — the web server that runs *inside* our app, so we don't install a
  separate server.
- **Spring MVC** — Spring's web layer that routes each incoming request to the right
  controller method.
- **Dependency injection** — instead of creating objects yourself, Spring creates them
  and hands (injects) them where needed.
- **Maven** — the build tool that downloads our libraries and compiles/packages the app.
- **pom.xml** — Maven's configuration file listing the Java version, Spring Boot version,
  and libraries.
- **Docker** — a tool that runs software in isolated, ready-made packages called containers.
- **Image vs container** — an image is the frozen template (like a class); a container is
  a running instance of it (like an object).
- **Docker Compose** — a tool that starts one or more containers from a single YAML recipe.
- **Port mapping "5433:5432"** — traffic to port 5433 on our machine is forwarded to port
  5432 inside the container (host:container).
- **JDBC** — the standard Java API for talking to a relational database.
- **datasource** — the configured connection (URL + credentials) Spring uses to reach the DB.
- **JPA** — a Java standard for mapping Java objects to database tables so you write less SQL.
- **Hibernate** — the library that actually implements JPA under the hood.
- **ddl-auto** — a setting that tells Hibernate whether/how to create or update database
  tables automatically from your entities.

---

## g. GRILLING QUESTIONS (no answers — figure these out and defend them)

1. What exactly does `@SpringBootApplication` do, and what would break if you removed it?
2. Trace a `GET /health` request through every layer from the browser to the response.
3. Why is `spring-boot-starter-web` enough to serve HTTP without installing Tomcat?
4. What's the difference between a Docker image and a container? Give an analogy.
5. Why did we map the DB to host port 5433 instead of 5432? What error would 5432 risk?
6. Why did we choose the pgvector image on Day 1 even though we don't do vector search yet?
7. Your app has `spring-boot-starter-data-jpa` on the classpath. Why must Postgres be
   running *before* you start the app, and what happens if it isn't?
8. What is dependency injection, and where (if anywhere) is it happening in today's code?
9. Why use the Maven wrapper (`mvnw.cmd`) instead of installing Maven globally?
10. `@RestController` vs `@Controller` — what's the difference, and why does `/health`
    return plain text instead of trying to render a web page?

---

## h. CURRENT STATE

**Works:**
- Project compiles (`.\mvnw.cmd compile` → BUILD SUCCESS).
- App starts on port 8080; `GET /health` returns `PolicyGuard is running`.
- Postgres (pgvector image) runs in Docker on 5433 with a healthcheck.
- Spring connects to Postgres at startup.

**Missing (by design — later sections):**
- No entities, repositories, or services yet.
- No PDF upload/parsing, chunking, embeddings, vector search, or RAG.
- No frontend, auth, audit logging, or versioning.

**Next (Day 2):** finish §2 (document upload + parsing + DocumentMetadata entity/repo)
and start §3 (chunking + embedding + storing vectors in pgvector).

---

## i. DIFFERENCES FROM DURGESH'S VERSION (Day 1)

### 1. WHAT IS THE SAME
- Layered package layout under `com.<project>` with `controller/` (and `service/`,
  `repository/`, `entity/`, `dto/` to come) — mirrors the reference's structure.
- Maven + Spring Boot + Spring Web + Spring Data JPA + PostgreSQL driver — same base
  stack as the reference's `pom.xml`.
- Generated from Spring Initializr, same as the reference.
- A simple REST controller returning a string — equivalent to any basic controller the
  reference introduces early (e.g., its first test/hello endpoint).

### 2. WHAT IS DIFFERENT
- **Spring Boot 3.4.13 pinned** (reference uses a 3.x version too, but we explicitly
  pinned instead of taking Initializr's current default of 4.1.1). *Reason B* — the new
  default is too advanced/risky for a beginner following a 3.x video, and *Reason A* —
  we need Spring AI 1.0 compatibility later.
- **pgvector image from Day 1** rather than adding vector support later. *Reason A* —
  our domain needs vector search, and using it from the start avoids a disruptive DB
  swap mid-project.
- **DB on host port 5433**, not 5432. *Reason B/environment* — avoids clashing with a
  local Postgres on this specific machine.
- **A dedicated `/health` endpoint** as the first thing. *Reason A* — a compliance/prod
  mindset values an explicit health check (monitoring, load balancers); it also keeps
  Day 1 focused on plumbing, not features.

### 3. WHAT WE SKIPPED / DEFERRED
- **Service / Repository / Entity / DTO / config / exception classes** — deferred until
  the day each is actually needed (Day 2 onward). We don't create empty shells.
- **Spring AI, VectorStore, ChatClient, PDF readers, TokenTextSplitter** — these are the
  reference's core, added in our Sections 3–5.
- **Any security/JWT, ChatMemory, streaming** — the reference's advanced pieces; deferred
  or intentionally simplified (Reason B). We'll note if/when we add a simple version.
