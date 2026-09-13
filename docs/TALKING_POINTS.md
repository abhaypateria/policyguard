# Interview Talking Points

> My interview script. Short, plain-English answers I can say out loud.

## Day 1 — Foundation

**What Spring Boot is and why we use it**
Spring Boot lets me build a running Java web service without wiring up a server or
tons of config by hand. It bundles a web server (Tomcat) inside the app and
auto-configures the common stuff, so I focus on my code, not setup.

**What the Controller layer does**
It's the front desk of the backend. It receives HTTP requests, calls the right logic,
and returns a response. In my app, `HealthController` handles `GET /health`.

**What `/health` does**
It returns `"PolicyGuard is running"`. It's a cheap way for me, a load balancer, or a
monitoring tool to confirm the service is alive — without touching the database or any
business logic. Real systems always have one.

**What Maven does**
Maven is my build tool. `pom.xml` lists my Java version, Spring Boot version, and
libraries; Maven downloads them and compiles/packages the app. I use the Maven
*wrapper* (`mvnw.cmd`) so I don't need Maven installed globally and anyone can build it.

**What Docker does**
Docker runs my PostgreSQL database in a ready-made, isolated package (a container) so I
don't have to install and configure Postgres on my machine. `docker-compose.yml` is the
one-file recipe that starts it.

**Why PostgreSQL**
It's a rock-solid, widely-used relational database — the kind banks actually run. It
also has **pgvector**, an extension that lets it store and search the vectors I'll need
for AI similarity search later, so one database covers both normal data and vectors.

**Why the pgvector image already (on Day 1)**
Vector search doesn't arrive until Section 3, but I use the pgvector image from the
start so I never have to swap or migrate databases mid-project. Cheap now, saves pain
later.

**What I'd improve for production**
Externalize secrets (no plaintext DB password in properties — use environment variables
or a secrets manager), add a proper `/actuator/health` with DB checks, use Flyway for
DB migrations instead of `ddl-auto=update`, and add profiles for dev vs prod config.
