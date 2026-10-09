# Running DocuWare later

Use this setup: **backend in IntelliJ, PostgreSQL in Docker, AI in local Ollama**.

## Start

1. Open Docker Desktop and wait until its engine is running.
2. Open the project directory in IntelliJ. For an existing installation, configure the database and volume overrides described in README before starting Compose. In its terminal, run:

   ```powershell
   docker compose up -d postgres
   ```

3. Open Ollama. Both models are already installed. To check it is available:

   ```powershell
   Invoke-RestMethod http://localhost:11434/api/tags
   ```

4. The project `.env` should contain these AI settings:

   ```env
   AI_ENABLED=true
   AI_BASE_URL=http://localhost:11434
   AI_EMBEDDING_MODEL=nomic-embed-text
   AI_CHAT_MODEL=llama3.2
   ```

   Database password and JWT secret are already configured in `.env`. The backend reads it automatically when the working directory is the project root. Restart the backend after editing it. IntelliJ environment variables override `.env`; remove stale overrides if configuration appears unchanged.

5. Reload Maven, then run `src/main/java/com/docuware/DocuWareApplication.java` using its green Run button. Wait for **Started DocuWareApplication**. Keep the run configuration working directory set to the project directory.
6. Open [DocuWare](http://localhost:8080/) to sign in and manage documents. [Swagger](http://localhost:8080/swagger-ui) provides direct API access. [Health](http://localhost:8080/actuator/health) should show `UP`.

## Test

1. Log in with `POST /api/v1/auth/login`, or register a new account.
2. Copy the `accessToken` value into Swagger's **Authorize** dialog. Paste only the token, without quotation marks or a `Bearer` prefix. Tokens expire after one hour; log in again for a fresh token.
3. Upload a file with `POST /api/v1/documents`. Optional metadata is JSON:

   ```json
   {"title":"Spring Notes","tags":["java"]}
   ```

4. Copy the returned document `id`. Use `GET /api/v1/documents/{id}` until its status is `COMPLETED`.
5. Request a summary with `POST /api/v1/documents/{id}/summary`, or ask with `POST /api/v1/documents/{id}/ask`:

   ```json
   {"question":"What are the main points in this document?"}
   ```

6. Use `GET /api/v1/search` with a query in `q` to test semantic search. For documents uploaded before AI was enabled, call `POST /api/v1/documents/{id}/retry-processing` first.

The first AI request after starting Ollama may take longer while the model loads.

## Stop

1. Click the red Stop button in IntelliJ's Run window.
2. In the project terminal, run:

   ```powershell
   docker compose stop
   ```

3. Quit Ollama from the Windows system tray when you no longer need it. You can then close IntelliJ and Docker Desktop.

Stopping services preserves accounts, database records, and stored files. Local backend uploads live in `data/documents`; Docker API uploads live in a separate Docker volume. Keep the same runtime for existing files, or migrate their storage before switching. Do not use `docker compose down -v` to stop the app: it deletes the database and Docker document volumes.

## Common fixes

- **Missing Spring packages in IntelliJ:** Settings → Build Tools → Maven → Local repository → Override → the `.m2` directory inside your project, then reload/sync the Maven project.
- **Port 8080 is occupied:** run `docker compose stop app`, then start the IntelliJ backend. Run only one backend at a time.
- **Database authentication fails:** confirm PostgreSQL is running and IntelliJ is loading `.env` from the project root. A database created with an older password retains that password even if `.env` changes.
- **Upload returns 415:** refresh Swagger after restarting the backend; its metadata part must use `application/json`.
- **Request returns 401:** authorize with a fresh token without quotes.
- **Processing fails:** check Ollama is running, the models appear in `/api/tags`, and `AI_BASE_URL` is `http://localhost:11434` for IntelliJ; restart and retry processing.

For architecture, full API details, and Docker-only setup, see [README.md](README.md).
