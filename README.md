# DocuWare

**Upload, organize, summarize, and ask questions about your documents.**

DocuWare is a document intelligence application built with Java 21 and Spring Boot 3.5. It includes a responsive browser frontend, authenticated document management, asynchronous text extraction, and optional local AI through Ollama. PostgreSQL with pgvector stores structured data and embeddings; the original uploaded files live on the local filesystem.

## Features

- Register and sign in with BCrypt password hashing and expiring JWT access tokens.
- Upload PDF, DOCX, TXT, CSV, and Markdown files up to 20 MB.
- Browse, filter, sort, download, edit metadata, and delete documents in a private workspace.
- Track background processing and retry failed jobs.
- Generate document summaries, cached for the configured chat model.
- Ask document-specific questions and inspect the retrieved source excerpts.
- Search documents by meaning using owner-scoped pgvector similarity queries.
- Run document management and extraction with AI disabled.

## Technology stack

| Layer | Implementation |
| --- | --- |
| Frontend | Native HTML, CSS, and JavaScript served by Spring Boot |
| Backend | Java 21, Spring Boot 3.5, Spring Web, Spring Data JPA |
| Authentication | Spring Security, BCrypt, signed JWT bearer tokens |
| Database | PostgreSQL 16 with pgvector; Flyway migrations |
| File storage | Local filesystem; persistent named volume in Docker |
| Text extraction | Apache PDFBox, Apache POI, UTF-8 text reader |
| Local AI | Ollama; `nomic-embed-text` embeddings and `llama3.2` generation by default |
| Build and testing | Maven, JUnit, MockMvc, H2, Testcontainers |
| CI | GitHub Actions runs `mvn verify` on pushes and pull requests |

## Architecture and user flow

1. A user registers or logs in through the UI and receives a JWT.
2. Uploads are validated; original files are saved to disk, and metadata is saved to PostgreSQL with status `UPLOADED`.
3. A scheduled worker claims the job, extracts text, splits it into overlapping chunks, and optionally requests embeddings from Ollama.
4. PostgreSQL stores extracted text, chunks, vectors, and the final processing state.
5. A summary request reads extracted text, generates a summary with Ollama, caches it in PostgreSQL, and returns it to the UI.
6. A question request embeds the question, retrieves relevant document chunks through pgvector, and sends the question and sources to Ollama. The UI displays the answer and source references.

| Stored data | Location |
| --- | --- |
| Accounts and password hashes | PostgreSQL `users` table |
| Metadata, ownership, status, extracted text, cached summaries | PostgreSQL `documents` table |
| Document tags | PostgreSQL `document_tags` table |
| Text chunks and embedding vectors | PostgreSQL `document_chunks` table with pgvector |
| Original uploaded file bytes | `data/documents` locally, or the document volume in Docker |
| Browser access token | Per-tab session storage until sign-out or expiration |


<details>
<summary>Expand the full user-flow diagram</summary>


</details>

## Quick start

For a fresh installation, use the Docker setup below. You need Docker with Compose and Linux containers. Ollama is optional for basic document management and required for AI features.

```sh
# From the repository root; use Copy-Item on PowerShell instead of cp
cp .env.example .env
```

Set `DATABASE_PASSWORD` and `JWT_SECRET` in `.env` before starting the app. Keep `.env` private. `JWT_SECRET` must contain at least 32 UTF-8 bytes.

```sh
docker compose up --build -d
```

Open [DocuWare](http://localhost:8080/) to create an account and upload your first document. API documentation is available at [Swagger UI](http://localhost:8080/swagger-ui), and [health](http://localhost:8080/actuator/health) reports application readiness.

For an existing installation, read [Upgrading an existing installation](#upgrading-an-existing-installation) before starting services with renamed database or volume defaults.

## Browser workspace

Open [DocuWare](http://localhost:8080/) after starting the application. Create an account or sign in, upload documents, filter by file type or tag, sort and page through your library, edit metadata, download files, and track processing. Open a document to reprocess it, generate a summary, or ask questions with expandable source excerpts. **Discover with AI** searches indexed content across your documents. AI features display provider errors when AI is disabled or unavailable.

The frontend is in `src/main/resources/static`, uses native HTML/CSS/JavaScript, and ships inside the application JAR and Docker image. No Node build, extra server, or CORS configuration is required. JWTs are kept in per-tab session storage and cleared on sign-out or expiration. Public UI resources do not expose protected document endpoints. Document titles, metadata, and AI responses are rendered as text or escaped HTML.

## Run with Docker

Requires Docker Desktop with Linux containers.

1. Copy `.env.example` to `.env` and replace `DATABASE_PASSWORD` and `JWT_SECRET`. Use a random JWT secret of at least 32 UTF-8 bytes. `.env` is ignored by Git.
2. Run `docker compose up --build -d` from this directory.
3. Open [DocuWare](http://localhost:8080/), [Swagger UI](http://localhost:8080/swagger-ui), or [health](http://localhost:8080/actuator/health).

Compose binds the API and PostgreSQL to localhost and persists both database and document files in named volumes. Do not remove those volumes unless you intend to erase the stored documents.

## Run Java locally

Requires JDK 21, Maven 3.9+, and PostgreSQL 16 with the pgvector extension. You can start only the database with `docker compose up -d postgres` after configuring `.env`.

PowerShell:

```powershell
$env:DATABASE_PASSWORD = 'your-database-password'
$env:JWT_SECRET = 'your-random-secret-at-least-32-bytes-long'
# Optional when using a different database:
$env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/docuware'
$env:DATABASE_USER = 'docuware'
mvn spring-boot:run
```

The application imports the project root `.env` file for local Maven and IntelliJ runs. Keep the working directory set to the project root. Environment variables override values from `.env`. This file uses plain `KEY=value` entries without shell quoting or variable expansion. Flyway creates tables and the vector extension; the database user must have extension creation privileges. Hibernate validates the schema at startup.

When switching from the Docker API to IntelliJ, run `docker compose stop app` to free port 8080; keep PostgreSQL running. The Docker API stores files in its named volume, while local Java uses `./data/documents`, so download files through the Docker API or migrate their storage before switching runtimes.

## API quick start

The examples below use `curl`; on Windows use `curl.exe` and adapt JSON quoting, or use Swagger's interactive request forms.

```sh
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com","password":"a-long-secure-password"}'
```

Copy the `accessToken` from the response into `TOKEN`. Tokens expire after one hour; log in again to obtain another token. In Swagger, select **Authorize** and paste the token.

```sh
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"a-long-secure-password"}'

curl -X POST http://localhost:8080/api/v1/documents \
  -H "Authorization: Bearer $TOKEN" \
  -F 'file=@notes.txt;type=text/plain' \
  -F 'metadata={"title":"Spring Notes","tags":["java"]};type=application/json'

curl 'http://localhost:8080/api/v1/documents?page=0&size=20&sort=createdAt,desc&tag=java' \
  -H "Authorization: Bearer $TOKEN"

curl http://localhost:8080/api/v1/documents/DOCUMENT_ID/download \
  -H "Authorization: Bearer $TOKEN" -o downloaded.txt
```

The `metadata` multipart part is optional. When included, it must have `Content-Type: application/json`.

| Method | Endpoint | Behavior |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | Register and receive a JWT; 201 |
| POST | `/api/v1/auth/login` | Authenticate and receive a JWT |
| POST | `/api/v1/documents` | Multipart upload; 201 with Location |
| GET | `/api/v1/documents` | Owner-scoped pagination and filters |
| GET | `/api/v1/documents/{id}` | Metadata and processing state |
| PATCH | `/api/v1/documents/{id}` | Edit title, description, tags |
| GET | `/api/v1/documents/{id}/download` | Stream original bytes as attachment |
| DELETE | `/api/v1/documents/{id}` | Remove file and related records; 204 |
| POST | `/api/v1/documents/{id}/retry-processing` | Retry failed processing or reindex completed content; 202 |
| POST | `/api/v1/documents/{id}/summary` | Generate and persist a model-specific cached summary |
| POST | `/api/v1/documents/{id}/ask` | Retrieve document chunks and answer with source references |
| GET | `/api/v1/search?q=...&limit=5` | Owner-scoped cosine similarity search |

List parameters: `page` >= 0, `size` 1–100, `sort` one of `createdAt`, `updatedAt`, `title`, `fileSize` with `asc`/`desc`; optional `fileType`, `tag`, `createdAfter` (UTC ISO instant or `YYYY-MM-DD`). Tags are normalized to lowercase. PATCH leaves omitted/null fields untouched; send `"description":""` or `"tags":[]` to clear them. Blank titles are rejected.

Uploads support PDF, DOCX, and UTF-8 TXT/CSV/Markdown, up to 20 MB. Validation checks extensions, declared MIME types, PDF signatures, DOCX ZIP structure/expansion limits, and text encoding. Files are stored under generated UUID keys. Filenames and storage paths are never used as authorization evidence. Other users' document IDs return 404 across all document endpoints.

## Background processing

Uploads return `UPLOADED` immediately after durable storage. A scheduled worker polls PostgreSQL every three seconds, atomically claims jobs, and transitions them through `PROCESSING` to `COMPLETED` or `FAILED`. PDFBox and Apache POI extract text. Content is limited to one million characters and PDFs to 1,000 pages; scanned PDFs with no text fail until OCR is added. Text is split into overlapping chunks.

Processing uses claim tokens and renewable leases. Jobs left in `PROCESSING` for ten minutes are requeued. Only the current claim can publish results. Metadata edits can occur during processing. AI calls occur outside database transactions; the chunks and final state are saved together after processing succeeds.

Deletion first commits a `DELETING` state, hiding the document from retrieval. The file is removed before metadata and cascading chunks/tags. If storage deletion fails, the API returns 503 and the worker retries cleanup. Upload metadata failures attempt to remove the already stored file. A process crash between file creation and metadata commit can still leave an orphaned file; periodic storage reconciliation is a future improvement.

## Enable AI

Document management and extraction work with `AI_ENABLED=false`. AI endpoints return 503 `AI_DISABLED`; no artificial embeddings or chatbot answers are substituted.

Run Ollama and pull models:

```sh
ollama pull nomic-embed-text
ollama pull llama3.2
```

For local Java, set `AI_ENABLED=true` and `AI_BASE_URL=http://localhost:11434`. For Compose, set `AI_ENABLED=true` in `.env`; its default URL targets the host via `host.docker.internal`. Ollama must listen on an interface reachable from the container; keep it restricted to your trusted local environment. Configure models with `AI_EMBEDDING_MODEL` and `AI_CHAT_MODEL`.

Restart the application after changing configuration. Reprocess existing documents using `/retry-processing` to generate embeddings. Reindex when changing the embedding model. Search only compares vectors from the configured model and matching dimensions; vectors use exact pgvector cosine search without an approximate index in this initial version.

## Configuration

Configuration is read from `src/main/resources/application.yml`, the root `.env` file, and environment variables. Environment variables take precedence over `.env`. Compose supplies database connection and storage settings to the application container.

| Variable | Default / requirement | Purpose |
| --- | --- | --- |
| `DATABASE_PASSWORD` | Required | PostgreSQL password |
| `JWT_SECRET` | Required; at least 32 UTF-8 bytes | JWT signing and validation |
| `DATABASE_NAME` | `docuware` in Compose | Database created by the PostgreSQL container |
| `DATABASE_USER` | `docuware` | PostgreSQL role |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/docuware` locally | Local Java connection override; Compose builds its own container URL |
| `STORAGE_ROOT` | `./data/documents` locally | Local file path; Compose uses `/app/data/documents` |
| `AI_ENABLED` | `false` | Enable embeddings, summaries, Q&A, and semantic search |
| `AI_BASE_URL` | Local application: `http://localhost:11434`; Compose: `http://host.docker.internal:11434` | Ollama endpoint; `.env.example` selects the Compose URL |
| `AI_EMBEDDING_MODEL` | `nomic-embed-text` | Embedding model |
| `AI_CHAT_MODEL` | `llama3.2` | Summary and answer model |
| `POSTGRES_VOLUME_NAME` | `docuware_postgres-data` | Compose database volume override |
| `DOCUMENT_VOLUME_NAME` | `docuware_document-data` | Compose document volume override |

When using IntelliJ or Maven locally, set `AI_BASE_URL=http://localhost:11434` in `.env`. The example file uses the host address intended for the Docker application.

### AI API examples

```sh
curl -X POST http://localhost:8080/api/v1/documents/DOCUMENT_ID/summary \
  -H "Authorization: Bearer $TOKEN"

curl -X POST http://localhost:8080/api/v1/documents/DOCUMENT_ID/ask \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"What are the key points?"}'

curl 'http://localhost:8080/api/v1/search?q=dependency%20injection&limit=5' \
  -H "Authorization: Bearer $TOKEN"
```

Answers include source chunk IDs, document IDs, chunk indices, content excerpts, and similarity scores. A conservative UTF-8 context budget bounds question prompts and handles Unicode without splitting code points. Chunk references are retrieval evidence; generated citation accuracy depends on the model. Prompts instruct the model to use only retrieved text and ignore instructions embedded in uploaded content. This does not guarantee immunity to prompt injection. Summaries process all extracted sections using hierarchical summarization; long documents may require many provider calls. Provider connect/read timeouts are five seconds/two minutes.

## Tests and packaging

```sh
mvn test
mvn verify
mvn package
java -jar target/docuware-0.1.0.jar
```

The regular suite exercises real Spring Security JWTs, registration/login, multipart uploads, owner isolation, pagination, download bytes, deletion, failure/retry states, extraction of real PDF/DOCX fixtures, and a local HTTP stub of Ollama. H2 is used only for core API tests. `PostgresVectorTest` uses pgvector/PostgreSQL through Testcontainers to test Flyway migrations, indexing, owner/model/dimension filtering, RAG, summary caching, and claim recovery. Docker is required for those tests; they are skipped when Docker is unavailable. Live Ollama generation is not part of automated tests.

## Project layout

```text
src/main/java/com/docuware/
  auth/         Accounts and access tokens
  security/     JWT validation and API security
  document/     Metadata, owner-scoped access, document lifecycle
  storage/      File validation and storage abstraction
  processing/   Extraction, chunking, scheduled processing
  ai/           Ollama adapter, vector search, summaries, Q&A
  exception/    Consistent API error responses
src/main/resources/
  static/       Browser frontend and assets
  db/migration/ Flyway SQL migrations
  application.yml
src/test/       API, extraction, AI-provider, and PostgreSQL tests
docs/           Architecture diagrams and diagram-generation utilities
.github/        Maven verification workflow
compose.yml     Application and PostgreSQL services
Dockerfile      Multi-stage application image
.env.example    Sanitized configuration template
```

Controllers handle HTTP, services own business rules, and repositories persist data. See [RUN_APP.md](RUN_APP.md) for the IntelliJ + Docker PostgreSQL + local Ollama workflow.

## Repository hygiene

Commit source code, tests, Flyway migrations, `pom.xml`, Docker configuration, `.env.example`, and selected documentation diagrams. `.gitignore` excludes environment files, uploaded documents, build outputs, dependency caches, logs, IDE settings, Python caches, OS metadata, backups, and local private key / keystore files. `.dockerignore` excludes private files and unnecessary local artifacts from the image build context.

Before your first commit, review the files being staged:

```sh
git status --short
git diff --cached --stat
```

Ignore rules do not remove files that were already tracked. If a private `.env` was tracked, use `git rm --cached .env` to stop tracking it while keeping the local file. Rotate any credentials previously published. Keep SQL migrations and documentation images versioned; they are intentionally not covered by broad `*.sql` or image ignore patterns.

## Upgrading an existing installation

The Maven artifact is `com.docuware:docuware`, Java packages are `com.docuware`, and the entry point is `DocuWareApplication`. Reload Maven in IntelliJ and recreate any old application run configuration. Previously issued tokens must be replaced by signing in again because the JWT issuer changed.

Use your actual project root as the working directory. If you rename the folder to `DocuWare`, stop the IDE/application first and update absolute shortcuts. Stored file paths and document IDs are unchanged by the refactor.

New databases and Compose volumes use `docuware`. **Existing PostgreSQL databases are not renamed automatically.** Before starting Compose against an existing installation, inspect `docker volume ls` and set `POSTGRES_VOLUME_NAME` and `DOCUMENT_VOLUME_NAME` in `.env` to the exact existing volume names. Set `DATABASE_NAME` and `DATABASE_USER` to the existing database and role names. For local Java, also set `DATABASE_URL` to the existing JDBC URL. These overrides preserve your accounts and documents. Back up the database before manually renaming its database or role. Do not remove volumes to perform the rename.

## Current limitations and future work

Future work from the specification: OCR, S3 storage adapter, Redis, Kafka, API rate limits, automatic provider retries/circuit breakers, audit history, collections/sharing, and approximate vector indexes. Before an internet-facing deployment, add TLS, deployment-specific rate limits, malware scanning, secret management, and database/filesystem backup procedures.

Implementation references: [Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/reference/index.html), [springdoc compatibility](https://springdoc.org/v2/), [Ollama API](https://github.com/ollama/ollama/blob/main/docs/api.md), [pgvector](https://github.com/pgvector/pgvector).
